// refresh-memory — maintain a short, rolling "athlete memory" the coach carries
// into every generation. Called fire-and-forget by the app after a workout is
// generated or feedback is submitted, so it never blocks the user.
//
// Reads the old memory + recent feedback + recent sessions, asks a cheap LLM to
// fold them into <=120 words of durable notes, and saves it on the profile.

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, getUserId } from "../_shared/supabase.ts";
import { llmGenerateWithFallback } from "../_shared/llm.ts";
import { llmAccess } from "../_shared/llm_keys.ts";

const SYSTEM = `You maintain a running coach's private notes about ONE athlete.
Fold new evidence into the existing notes. Keep DURABLE patterns (how they
respond to volume/intensity, recurring niggles, scheduling preferences,
motivation, what works) and drop stale specifics. Output ONLY the updated notes
as plain prose, <=120 words, no preamble.`;

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);
    const admin = adminClient();

    const [{ data: profile }, { data: feedback }, { data: planned }] = await Promise.all([
      admin.from("user_profiles").select("training_memory, active_llm_provider, llm_fallback_chain, onboarding").eq("id", userId).single(),
      admin.from("workout_feedback").select("date, difficulty, actual_rpe, completed, notes").eq("user_id", userId).order("date", { ascending: false }).limit(8),
      admin.from("planned_workouts").select("date, type, workout_json").eq("user_id", userId).order("date", { ascending: false }).limit(6),
    ]);
    if (!profile) return json({ error: "no profile" }, 404);

    const fbLines = (feedback ?? []).map((f) =>
      `- ${f.date}: ${f.completed === false ? "skipped; " : ""}${f.difficulty ?? "?"}${f.actual_rpe ? `, RPE ${f.actual_rpe}` : ""}${f.notes ? ` ("${f.notes}")` : ""}`
    ).join("\n") || "(none)";
    const wkLines = (planned ?? []).map((p) =>
      `- ${p.date} ${p.type}: ${(p.workout_json as { title?: string })?.title ?? ""}`
    ).join("\n") || "(none)";

    const prompt = `EXISTING NOTES:\n${profile.training_memory ?? "(none yet)"}\n\n` +
      `GOAL: ${(profile.onboarding as { goal?: string })?.goal ?? "general fitness"}\n\n` +
      `RECENT FEEDBACK:\n${fbLines}\n\nRECENT SESSIONS:\n${wkLines}\n\n` +
      `Return the updated notes only.`;

    const { chain, resolveKey, resolveModel } = llmAccess(admin, userId, profile);

    const outcome = await llmGenerateWithFallback(chain, { prompt, systemPrompt: SYSTEM }, resolveKey, resolveModel);
    const memory = outcome.text.trim().slice(0, 1200);
    await admin.from("user_profiles").update({ training_memory: memory }).eq("id", userId);

    return json({ ok: true, memory });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 200);
  }
});
