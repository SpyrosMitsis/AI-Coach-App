// ============================================================================
// Agent memory — the coach's long-term "documents" + smart context compression.
//
// Hermes-style triad, all kept on user_profiles so every edge function reasons
// from the same state:
//   coach_knowledge  → user.md   — durable facts/constraints (chat-maintained)
//   training_memory  → memory.md — rolling episodic notes (refresh-memory)
//   coach_soul       → soul.md   — the coach's identity/voice + its slowly
//                                  evolving relationship with this athlete
//
// Plus compression helpers: compressThread (pure — pick the active window and
// report what to archive) and summarizeDropped (fold archived turns into a
// running summary so long threads stay coherent without linear token growth).
// ============================================================================

import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";
import type { LlmProvider } from "./types.ts";
import { type ChatMessage, llmGenerateWithFallback } from "./llm.ts";
import { logger } from "./log.ts";

const log = logger("agent_memory");

// Provider access bundle — exactly the shape llmAccess() returns, so callers
// can pass it straight through.
export interface LlmBundle {
  chain: LlmProvider[];
  resolveKey: (p: LlmProvider) => Promise<string | null>;
  resolveModel?: (p: LlmProvider) => string | undefined;
  resolveBaseUrl?: (p: LlmProvider) => Promise<string | null>;
}

export interface AgentMemory {
  user: string; // coach_knowledge
  memory: string; // training_memory
  soul: string; // coach_soul (falls back to DEFAULT_SOUL)
  soulUpdatedAt: string | null;
}

// Seed identity for soul.md. The science/persona base also lives in
// COACH_SYSTEM_PROMPT; this is the *relationship-bearing* layer that deepens
// over time. The "Relationship & history" section is what grows.
export const DEFAULT_SOUL =
  `# Coach soul

## Identity & voice
I'm your endurance + strength coach: warm but direct, grounded in real
sports-science, and biased toward action. I drive the plan rather than waiting to
be asked, I explain the "why" briefly, and I keep a calm, encouraging tone even
when I'm pushing you. I talk like a human, not a dashboard — I interpret your
numbers into plain language instead of reciting stats at you, and I only quote a
figure when it's something you can act on.

## How I coach
- Honor the science: 80/20 intensity, periodization, progressive overload,
  autoregulate from real readiness signals.
- Meet you where you are — adapt to your constraints, equipment, and life.
- Celebrate the wins, name the patterns, never shame a missed session.

## Relationship & history
(Nothing yet — this fills in as we train together.)`;

// Map an already-fetched profile row → AgentMemory (no DB round-trip).
export function memoryFromProfile(
  p:
    | {
      coach_knowledge?: string | null;
      training_memory?: string | null;
      coach_soul?: string | null;
      coach_soul_updated_at?: string | null;
    }
    | null
    | undefined,
): AgentMemory {
  const soul = (p?.coach_soul ?? "").trim();
  return {
    user: (p?.coach_knowledge ?? "").trim(),
    memory: (p?.training_memory ?? "").trim(),
    soul: soul || DEFAULT_SOUL,
    soulUpdatedAt: p?.coach_soul_updated_at ?? null,
  };
}

// Fetch the three docs for a user in one select.
export async function loadAgentMemory(
  admin: SupabaseClient,
  userId: string,
): Promise<AgentMemory> {
  const { data } = await admin
    .from("user_profiles")
    .select("coach_knowledge, training_memory, coach_soul, coach_soul_updated_at")
    .eq("id", userId)
    .single();
  return memoryFromProfile(data);
}

// Assemble the three docs into one system-prompt block. Soul leads (it frames
// who the coach is), then the hard rules (user), then the rolling memory. Empty
// docs are omitted. Preserves the wording of the old knowledge/memory blocks so
// generation behavior stays consistent, and adds the soul layer.
export function memoryDocsBlock(mem: AgentMemory): string {
  const parts: string[] = [];
  if (mem.soul.trim()) {
    parts.push(
      `COACH IDENTITY & RELATIONSHIP (your "soul" — this is WHO YOU ARE to this ` +
        `athlete; stay in this voice and draw on the shared history):\n${mem.soul.trim()}`,
    );
  }
  if (mem.user.trim()) {
    parts.push(
      `ATHLETE CONSTRAINTS & PREFERENCES (HARD RULES — never violate these):\n${mem.user.trim()}\n` +
        `- If an injury is noted, avoid loading/aggravating it and prefer safe alternatives.\n` +
        `- Only prescribe exercises the athlete's available equipment supports.`,
    );
  }
  if (mem.memory.trim()) {
    parts.push(
      `ATHLETE MEMORY (long-term notes from past sessions — honor these):\n${mem.memory.trim()}`,
    );
  }
  return parts.length ? "\n\n" + parts.join("\n\n") : "";
}

// ---------------------------------------------------------------------------
// Document maintainers. All output prose (jsonMode:false) and are best-effort —
// callers run them fire-and-forget so they never block a user response.
// ---------------------------------------------------------------------------

// user.md — durable constraints/preferences distilled from the coaching chat.
export async function updateUserDoc(
  admin: SupabaseClient,
  userId: string,
  existing: string,
  recent: ChatMessage[],
  bundle: LlmBundle,
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
    const out = await llmGenerateWithFallback(
      bundle.chain,
      { prompt, systemPrompt: "You extract and maintain durable athlete facts. Output only a bullet list.", jsonMode: false },
      bundle.resolveKey,
      bundle.resolveModel,
      bundle.resolveBaseUrl,
    );
    const next = out.text.trim();
    // Relaxed gate: previously required a bullet char, which silently dropped a
    // valid single fact stated without a dash ("Only trains twice a week"). Now
    // we accept any non-empty, length-bounded, changed text and only reject
    // explicit no-content replies — logging every outcome so drops aren't blind.
    const noContent = !next || /^\(?\s*(empty|none|no (durable|new|changes?|updates?)|n\/a)\b/i.test(next);
    if (noContent) {
      log.debug("user_doc_skip", { reason: "no-content" });
    } else if (next.length > 2000) {
      log.warn("user_doc_skip", { reason: "too-long", len: next.length });
    } else if (next === existing.trim()) {
      log.debug("user_doc_skip", { reason: "unchanged" });
    } else {
      await admin.from("user_profiles").update({ coach_knowledge: next }).eq("id", userId);
      log.info("user_doc_saved", { len: next.length });
    }
  } catch (e) {
    log.warn("user_doc_failed", { err: e instanceof Error ? e.message : String(e) });
  }
}

const MEMORY_SYSTEM = `You maintain a running coach's private notes about ONE athlete.
Fold new evidence into the existing notes. Keep DURABLE patterns (how they
respond to volume/intensity, recurring niggles, scheduling preferences,
motivation, what works) and drop stale specifics. Output ONLY the updated notes
as plain prose, <=120 words, no preamble.`;

// memory.md — rolling episodic notes. `evidence` is the caller-assembled recent
// feedback/sessions/strength block. Returns the saved notes.
export async function updateMemoryDoc(
  admin: SupabaseClient,
  userId: string,
  existing: string,
  evidence: string,
  bundle: LlmBundle,
): Promise<string> {
  const prompt = `EXISTING NOTES:\n${existing || "(none yet)"}\n\n${evidence}\n\nReturn the updated notes only.`;
  const out = await llmGenerateWithFallback(
    bundle.chain,
    { prompt, systemPrompt: MEMORY_SYSTEM, jsonMode: false },
    bundle.resolveKey,
    bundle.resolveModel,
    bundle.resolveBaseUrl,
  );
  const memory = out.text.trim().slice(0, 1200);
  await admin.from("user_profiles").update({ training_memory: memory }).eq("id", userId);
  return memory;
}

const SOUL_SYSTEM =
  `You maintain the SOUL document of an AI endurance + strength coach — its
identity, voice, and its relationship with ONE athlete. This document changes
RARELY and SLOWLY. Preserve the coach's established voice and philosophy almost
verbatim. Only the "Relationship & history" section may grow: add at most 1-2
durable, human observations about the bond/arc with this athlete — milestones
reached, how they like to be coached, recurring rapport — written in the coach's
first-person voice. NEVER put training numbers, plans, or transient data here
(those live in other notes). Keep the whole document under ~200 words. Output
ONLY the updated soul markdown, no preamble.`;

// soul.md — the coach's identity, deepened conservatively. Time-gated: skips if
// it was evolved within `minHours` (unless still on the seed, which it personalizes).
export async function updateSoulDoc(
  admin: SupabaseClient,
  userId: string,
  mem: AgentMemory,
  evidence: string,
  bundle: LlmBundle,
  minHours = 72,
): Promise<void> {
  const onSeed = mem.soul.trim() === DEFAULT_SOUL.trim();
  if (!onSeed && mem.soulUpdatedAt) {
    const ageH = (Date.now() - new Date(mem.soulUpdatedAt).getTime()) / 3_600_000;
    if (Number.isFinite(ageH) && ageH < minHours) return; // too soon — keep it stable
  }
  const prompt =
    `CURRENT SOUL:\n${mem.soul}\n\n` +
    `RECENT EVIDENCE about this athlete and how training is going (use only to deepen the Relationship section):\n${evidence}\n\n` +
    `Return the updated soul markdown only.`;
  try {
    const out = await llmGenerateWithFallback(
      bundle.chain,
      { prompt, systemPrompt: SOUL_SYSTEM, jsonMode: false },
      bundle.resolveKey,
      bundle.resolveModel,
      bundle.resolveBaseUrl,
    );
    const next = out.text.trim().slice(0, 2000);
    if (next.length > 40) {
      await admin
        .from("user_profiles")
        .update({ coach_soul: next, coach_soul_updated_at: new Date().toISOString() })
        .eq("id", userId);
    }
  } catch (_e) {
    // best-effort — never block on soul maintenance
  }
}

// ---------------------------------------------------------------------------
// Context compression.
// ---------------------------------------------------------------------------

// Pick the active window the model should see and report what to archive. Keeps
// the opening message (it anchors the thread's purpose) plus the most recent
// turns within a turn/char budget; everything else is `dropped` (to be folded
// into the running summary). Pure + deterministic.
export function compressThread(
  msgs: ChatMessage[],
  maxTurns = 24,
  maxChars = 24_000,
): { kept: ChatMessage[]; dropped: ChatMessage[] } {
  const n = msgs.length;
  if (n <= 1) return { kept: [...msgs], dropped: [] };

  // Anchor (index 0) + the most recent (maxTurns - 1) turns.
  const recentCount = Math.min(n - 1, maxTurns - 1);
  const startKeep = n - recentCount; // first index of the recent window
  const keptIdx = [0];
  for (let i = startKeep; i < n; i++) keptIdx.push(i);

  // Char budget: drop oldest turns *after* the anchor until under budget.
  let total = keptIdx.reduce((s, i) => s + msgs[i].content.length, 0);
  while (keptIdx.length > 2 && total > maxChars) {
    const removed = keptIdx.splice(1, 1)[0];
    total -= msgs[removed].content.length;
  }

  const keptSet = new Set(keptIdx);
  const kept = keptIdx.map((i) => msgs[i]);
  const dropped = msgs.filter((_, i) => !keptSet.has(i));
  return { kept, dropped };
}

// Fold archived turns into a fresh running summary. Regenerates from the canonical
// `dropped` set each time (no double-counting), capped to the most recent turns
// within a char budget. Returns null when there's too little to summarize.
export async function summarizeDropped(
  dropped: ChatMessage[],
  bundle: LlmBundle,
  maxChars = 12_000,
): Promise<string | null> {
  if (dropped.length < 4) return null;
  const picked: ChatMessage[] = [];
  let total = 0;
  for (let i = dropped.length - 1; i >= 0; i--) {
    total += dropped[i].content.length;
    if (total > maxChars && picked.length) break;
    picked.unshift(dropped[i]);
  }
  const transcript = picked
    .map((m) => `${m.role === "user" ? "Athlete" : "Coach"}: ${m.content}`)
    .join("\n");
  const prompt =
    `EARLIER COACHING-CHAT TURNS (now archived out of the live context window):\n${transcript}\n\n` +
    `Write a tight running summary the coach can rely on to stay coherent across the rest of the thread: durable decisions made, the athlete's stated goals/numbers/intent, plans agreed, and any open threads. Drop pleasantries. Output ONLY the summary, <=180 words.`;
  try {
    const out = await llmGenerateWithFallback(
      bundle.chain,
      { prompt, systemPrompt: "You compress a coaching conversation into a durable running summary.", jsonMode: false },
      bundle.resolveKey,
      bundle.resolveModel,
      bundle.resolveBaseUrl,
    );
    const t = out.text.trim();
    return t ? t.slice(0, 1500) : null;
  } catch (_e) {
    return null;
  }
}
