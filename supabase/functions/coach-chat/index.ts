// coach-chat — conversational sport-science coach.
//
// POST {
//   messages: [{ role: 'user'|'assistant', content }],
//   mode: 'chat' | 'finalize',
//   finalizeKind?: 'workout' | 'plan',
//   conversationId?: uuid,         // persist the thread
//   purpose?: 'setup'|'plan'|'workout',
//   save?: boolean                 // on finalize, store a workout_template
// }
//
// chat     → returns { reply } (free-form prose) and saves the thread.
// finalize → returns { template } (structured JSON) and optionally saves it.

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, decryptSecret, getUserId } from "../_shared/supabase.ts";
import {
  type ChatMessage,
  estimateCostUsd,
  extractJson,
  llmGenerateWithFallback,
  llmStream,
} from "../_shared/llm.ts";
import { corsHeaders } from "../_shared/cors.ts";
import { COACH_SYSTEM_PROMPT, finalizeInstruction, trainingPhase } from "../_shared/prompt.ts";
import type { LlmProvider } from "../_shared/types.ts";
import { executeTool, toolCatalogPrompt } from "../_shared/coach_tools.ts";

// Tool-use protocol appended to the coach system prompt for chat mode. The
// coach reads real data and takes real actions through a JSON tool channel —
// provider-agnostic so it works on every LLM the user might configure.
const TOOL_PROTOCOL = `

YOU ARE AN AGENTIC COACH WITH TOOLS. You can read the athlete's real training data and act on their plan. Don't invent numbers — read them.

TOOLS:
${toolCatalogPrompt()}

RESPONSE FORMAT — output ONLY one JSON object, nothing else:
• Use a tool:  {"action":"tool","tool":"<name>","args":{ ... }}
• Reply to athlete:  {"action":"final","message":"<concise, specific reply>"}

RULES:
- Before giving training advice or a plan, READ the relevant data first (get_fitness, get_planned_week, get_recent_activities, get_strength_summary, get_profile). Ground every claim in what you read.
- Take ACTIONS (plan_week, generate_workout, set_goal_race) ONLY after the athlete clearly agrees, then confirm what you did and why in your final message.
- Call remember when the athlete shares a durable preference, constraint, or injury.
- Be efficient: a few targeted reads, then answer. You have at most 6 tool calls per turn.
- Final messages are warm but concise — reference the actual numbers, and give one clear next step.`;

const DAY = 86_400_000;

// Durable facts worth remembering tend to mention these. We only spend a token
// budget on knowledge-extraction when the latest user turn plausibly carries one.
const KNOWLEDGE_HINTS =
  /\b(injur|hurt|pain|sore|tendin|strain|sprain|knee|shoulder|back|hip|ankle|wrist|elbow|equipment|dumbbell|barbell|kettlebell|machine|rack|gym|home|treadmill|don'?t have|no access|only have|prefer|hate|dislike|avoid|can'?t|cannot|unable|allerg|vegan|schedule|mornings?|evenings?|nights?|work|travel|busy|recover)/i;

// Maintain user_profiles.coach_knowledge from the conversation. Best-effort:
// returns the new knowledge text (or null if unchanged / on failure).
async function updateKnowledge(
  admin: ReturnType<typeof adminClient>,
  userId: string,
  existing: string,
  recent: ChatMessage[],
  chain: LlmProvider[],
  resolveKey: (p: LlmProvider) => Promise<string | null>,
): Promise<void> {
  const transcript = recent
    .slice(-6)
    .map((m) => `${m.role === "user" ? "Athlete" : "Coach"}: ${m.content}`)
    .join("\n");
  const prompt =
    `You maintain an athlete's durable COACHING KNOWLEDGE — a short bullet list of facts a coach must always honor: injuries/limitations, equipment they have or lack, scheduling constraints, exercise preferences and dislikes, dietary/other constraints.\n\n` +
    `EXISTING KNOWLEDGE:\n${existing.trim() || "(empty)"}\n\n` +
    `RECENT CONVERSATION:\n${transcript}\n\n` +
    `Return the UPDATED knowledge as a concise markdown bullet list (max ~12 bullets). Merge new durable facts, drop anything the athlete has retracted, keep it terse. If nothing durable changed, return the existing list unchanged. Output ONLY the bullet list, no preamble.`;
  try {
    const out = await llmGenerateWithFallback(chain, { prompt, systemPrompt: "You extract and maintain durable athlete facts. Output only a bullet list." }, resolveKey);
    const next = out.text.trim();
    // Sanity: keep only plausible bullet output, cap length.
    if (next && next.length <= 2000 && /[-*•]/.test(next) && next !== existing.trim()) {
      await admin.from("user_profiles").update({ coach_knowledge: next }).eq("id", userId);
    }
  } catch (_e) {
    // best-effort — never block the chat on this
  }
}

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const admin = adminClient();
    const body = await req.json().catch(() => ({}));
    const messages: ChatMessage[] = Array.isArray(body.messages) ? body.messages : [];
    const mode: string = body.mode ?? "chat";
    if (!messages.length) return json({ error: "messages required" }, 400);

    // --- athlete context so the coach grounds advice in real data ----------
    const { data: profile } = await admin
      .from("user_profiles")
      .select("display_name, onboarding, active_llm_provider, llm_fallback_chain, coach_knowledge")
      .eq("id", userId)
      .single();
    const onboarding = (profile?.onboarding ?? {}) as Record<string, unknown>;
    const existingKnowledge = (profile?.coach_knowledge ?? "") as string;

    const since28 = new Date(Date.now() - 28 * DAY).toISOString().slice(0, 10);
    const { data: acts } = await admin
      .from("completed_activities")
      .select("type, date, distance_m, tss, ctl, atl")
      .eq("user_id", userId).gte("date", since28).order("date", { ascending: false });
    const a = acts ?? [];
    const fitnessRow = a.find((r) => r.ctl != null);
    const ctl = fitnessRow?.ctl ?? 0;
    const atl = fitnessRow?.atl ?? 0;
    const weeklyKm = a.filter((r) => (r.type ?? "").toLowerCase().includes("run"))
      .reduce((s, r) => s + (r.distance_m ?? 0) / 1000, 0) / 4;

    let weeksToGoal: number | null = null;
    if (onboarding.goal_date) {
      const d = (new Date(String(onboarding.goal_date)).getTime() - Date.now()) / (7 * DAY);
      weeksToGoal = d >= 0 ? Math.round(d) : null;
    }

    const context =
      `ATHLETE CONTEXT (use it, don't restate it verbatim):
- Name: ${profile?.display_name ?? "athlete"}
- Goal: ${onboarding.goal ?? "not set"}; experience: ${onboarding.experience ?? "unknown"}
- Available days: ${(onboarding.days as string[] | undefined)?.join(", ") ?? "unknown"}; session length: ${onboarding.session_duration ?? "?"} min
- Equipment: ${onboarding.equipment ?? "unknown"}; injuries: ${onboarding.injury_history ?? "none noted"}
- Fitness CTL ${ctl.toFixed(0)}, fatigue ATL ${atl.toFixed(0)}, form TSB ${(ctl - atl).toFixed(0)}
- ~${weeklyKm.toFixed(0)} km/week recently; training phase: ${trainingPhase(weeksToGoal)}` +
      (existingKnowledge.trim()
        ? `\n\nKNOWN CONSTRAINTS & PREFERENCES (already on file — honor these, ask before changing them):\n${existingKnowledge.trim()}`
        : "");

    const systemPrompt = `${COACH_SYSTEM_PROMPT}\n\n${context}`;

    // --- resolve provider keys (fallback chain) ----------------------------
    const chain: LlmProvider[] = [
      profile?.active_llm_provider,
      ...((profile?.llm_fallback_chain as LlmProvider[]) ?? []),
    ].filter(Boolean) as LlmProvider[];

    const keyCache = new Map<string, string | null>();
    const resolveKey = async (provider: LlmProvider): Promise<string | null> => {
      if (keyCache.has(provider)) return keyCache.get(provider)!;
      const { data } = await admin.from("llm_api_keys")
        .select("api_key_encrypted").eq("user_id", userId).eq("provider", provider).maybeSingle();
      const key = data?.api_key_encrypted ? await decryptSecret(admin, data.api_key_encrypted) : null;
      keyCache.set(provider, key);
      return key;
    };

    // --- build the turn list -----------------------------------------------
    const turns: ChatMessage[] = [...messages];
    if (mode === "finalize") {
      const kind = body.finalizeKind === "plan" ? "plan" : "workout";
      turns.push({ role: "user", content: finalizeInstruction(kind) });
    }

    // --- streaming chat (SSE) ----------------------------------------------
    if (mode === "chat" && body.stream) {
      // Pick the first provider in the chain that has a key.
      let streamProvider: LlmProvider | null = null;
      let streamKey: string | null = null;
      for (const p of chain) {
        const k = await resolveKey(p);
        if (k) { streamProvider = p; streamKey = k; break; }
      }
      if (!streamProvider || !streamKey) return json({ error: "no LLM key configured" }, 400);

      const provider = streamProvider, apiKey = streamKey;
      const encoder = new TextEncoder();
      const stream = new ReadableStream({
        async start(controller) {
          const send = (obj: unknown) => controller.enqueue(encoder.encode(`data: ${JSON.stringify(obj)}\n\n`));
          let fullText = "";
          try {
            fullText = await llmStream(provider, { messages: turns, systemPrompt, jsonMode: false, apiKey }, (tok) => {
              send({ token: tok });
            });
          } catch (e) {
            send({ error: String(e instanceof Error ? e.message : e) });
          }
          // Persist the thread after the stream completes.
          const fullThread = [...messages, { role: "assistant", content: fullText }];
          let convId: string | null = body.conversationId ?? null;
          try {
            if (convId) {
              await admin.from("coach_conversations").update({ messages: fullThread }).eq("id", convId).eq("user_id", userId);
            } else {
              const { data: conv } = await admin.from("coach_conversations").insert({
                user_id: userId, title: messages[0]?.content?.slice(0, 60) ?? "Coaching chat",
                messages: fullThread, purpose: body.purpose ?? "setup",
              }).select("id").single();
              convId = conv?.id ?? null;
            }
          } catch { /* best effort */ }
          const lastUser = [...messages].reverse().find((m) => m.role === "user")?.content ?? "";
          if (KNOWLEDGE_HINTS.test(lastUser)) {
            await updateKnowledge(admin, userId, existingKnowledge, fullThread as ChatMessage[], chain, resolveKey);
          }
          send({ done: true, conversation_id: convId, provider });
          controller.close();
        },
      });
      return new Response(stream, {
        headers: { ...corsHeaders, "Content-Type": "text/event-stream", "Cache-Control": "no-cache" },
      });
    }

    // --- agentic chat: tool-use loop over the athlete's real data ----------
    if (mode === "chat") {
      const auth = req.headers.get("Authorization") ?? "";
      const chatSystem = `${COACH_SYSTEM_PROMPT}\n\n${context}${TOOL_PROTOCOL}`;
      const work: ChatMessage[] = [...messages];
      const toolsUsed: string[] = [];
      let provider: LlmProvider | string = chain[0] ?? "";
      let replyText = "";

      for (let step = 0; step < 6; step++) {
        const step_out = await llmGenerateWithFallback(
          chain, { messages: work, systemPrompt: chatSystem, jsonMode: true }, resolveKey,
        );
        provider = step_out.provider;
        let parsed: Record<string, unknown> | null = null;
        try { parsed = extractJson<Record<string, unknown>>(step_out.text); } catch { parsed = null; }

        // Model replied (or returned non-JSON prose) → that's the answer.
        if (!parsed || parsed.action === "final" || typeof parsed.message === "string") {
          replyText = (parsed?.message as string) ?? (parsed?.reply as string) ?? step_out.text;
          break;
        }
        // Tool call → run it and feed back the observation.
        if (parsed.action === "tool" && typeof parsed.tool === "string") {
          const obs = await executeTool(admin, userId, auth, parsed.tool, (parsed.args ?? {}) as Record<string, unknown>);
          toolsUsed.push(parsed.tool);
          work.push({ role: "assistant", content: JSON.stringify(parsed) });
          work.push({ role: "user", content: `OBSERVATION from ${parsed.tool}: ${obs}` });
          continue;
        }
        // Unknown shape — surface whatever text came back.
        replyText = step_out.text;
        break;
      }
      if (!replyText.trim()) replyText = "I gathered your data but need a bit more to act — what would you like me to do?";

      // Persist the thread.
      const fullThread = [...messages, { role: "assistant", content: replyText }];
      let convId: string | null = body.conversationId ?? null;
      try {
        if (convId) {
          await admin.from("coach_conversations").update({ messages: fullThread }).eq("id", convId).eq("user_id", userId);
        } else {
          const { data: conv } = await admin.from("coach_conversations").insert({
            user_id: userId, title: messages[0]?.content?.slice(0, 60) ?? "Coaching chat",
            messages: fullThread, purpose: body.purpose ?? "setup",
          }).select("id").single();
          convId = conv?.id ?? null;
        }
      } catch { /* best effort */ }

      return json({ reply: replyText, conversation_id: convId, provider, tools_used: toolsUsed });
    }

    const outcome = await llmGenerateWithFallback(
      chain,
      { messages: turns, systemPrompt, jsonMode: mode === "finalize" },
      resolveKey,
    );
    const cost = estimateCostUsd(outcome.provider, outcome.promptTokens, outcome.completionTokens);

    // --- persist the conversation thread -----------------------------------
    const fullThread = [...messages, { role: "assistant", content: outcome.text }];
    let conversationId: string | null = body.conversationId ?? null;
    if (conversationId) {
      await admin.from("coach_conversations")
        .update({ messages: fullThread })
        .eq("id", conversationId).eq("user_id", userId);
    } else {
      const { data: conv } = await admin.from("coach_conversations")
        .insert({
          user_id: userId,
          title: messages[0]?.content?.slice(0, 60) ?? "Coaching chat",
          messages: fullThread,
          purpose: body.purpose ?? "plan",
        })
        .select("id").single();
      conversationId = conv?.id ?? null;
    }

    if (mode !== "finalize") {
      // Maintain durable knowledge when the latest turn plausibly carries a fact.
      const lastUser = [...messages].reverse().find((m) => m.role === "user")?.content ?? "";
      if (KNOWLEDGE_HINTS.test(lastUser)) {
        await updateKnowledge(admin, userId, existingKnowledge, fullThread as ChatMessage[], chain, resolveKey);
      }
      return json({ reply: outcome.text, conversation_id: conversationId, provider: outcome.provider, estimated_cost_usd: cost });
    }

    // --- finalize: parse + (optionally) save the template ------------------
    let template;
    try {
      template = extractJson<Record<string, unknown>>(outcome.text);
    } catch (e) {
      return json({ error: "could not parse template", detail: String(e), raw: outcome.text }, 422);
    }

    let templateId: string | null = null;
    if (body.save) {
      const { data: saved } = await admin.from("workout_templates").insert({
        user_id: userId,
        name: String(template.name ?? "Coaching template"),
        description: String(template.description ?? ""),
        kind: String(template.kind ?? "workout"),
        structure: template.structure ?? template,
        source_conversation_id: conversationId,
      }).select("id").single();
      templateId = saved?.id ?? null;
    }

    return json({
      template,
      template_id: templateId,
      conversation_id: conversationId,
      provider: outcome.provider,
      estimated_cost_usd: cost,
    });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
