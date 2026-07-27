// Guards the "no em dashes anywhere" product rule at both ends: prompts must
// not model the habit (outside the explicit punctuation rule itself), user
// facing quota copy must be dash-free, and — the half this file was missing —
// what the MODEL sends back must be scrubbed before anyone sees it.
//
// The gap was not theoretical. This file only ever checked prompts, the scrub
// was hand-applied at three call sites, coach-brief was not one of them, and an
// em dash sat in the readiness note on the Home screen. The reply-side tests
// below pin the enforcement point where it can't be forgotten again.

import { assert, assertEquals } from "jsr:@std/assert@1";
import { llmGenerate } from "./llm.ts";
import { stripDashes } from "./dashes.ts";
import {
  BRIEF_SYSTEM,
  CHAT_COACHING_DIGEST,
  COACH_REPLY_SHAPE,
  COACH_SYSTEM_PROMPT,
  COACH_VOICE_RULE,
  PUNCTUATION_RULE,
  SYSTEM_PROMPT,
  WEEK_REVIEW_SYSTEM,
  WEEK_SYSTEM_PROMPT,
} from "./prompt.ts";
import { DEFAULT_SOUL } from "./agent_memory.ts";

// The style rule itself names the characters; that's the one allowed mention.
function withoutRuleMentions(text: string): string {
  return text
    .replaceAll(PUNCTUATION_RULE, "")
    .replace(/[Nn]ever use em dashes \(—\) or en\s*dashes? \(–\)/g, "");
}

const PROMPTS: Record<string, string> = {
  SYSTEM_PROMPT,
  WEEK_SYSTEM_PROMPT,
  COACH_SYSTEM_PROMPT,
  BRIEF_SYSTEM,
  WEEK_REVIEW_SYSTEM,
  DEFAULT_SOUL,
  // Chat's constituent blocks, checked individually as well as via the
  // assembled COACH_SYSTEM_PROMPT, so a dash in one is attributed to it.
  CHAT_COACHING_DIGEST,
  COACH_VOICE_RULE,
  COACH_REPLY_SHAPE,
};

Deno.test("prompts carry the punctuation rule where users see the output", () => {
  for (const name of ["SYSTEM_PROMPT", "WEEK_SYSTEM_PROMPT", "COACH_SYSTEM_PROMPT", "BRIEF_SYSTEM", "WEEK_REVIEW_SYSTEM"]) {
    assert(PROMPTS[name].includes(PUNCTUATION_RULE), `${name} is missing PUNCTUATION_RULE`);
  }
});

Deno.test("prompts do not model the em-dash habit themselves", () => {
  for (const [name, text] of Object.entries(PROMPTS)) {
    const cleaned = withoutRuleMentions(text);
    assert(!/[—–]/.test(cleaned), `${name} contains an em/en dash outside the punctuation rule`);
  }
});

Deno.test("DEFAULT_SOUL does not model the em-dash habit", () => {
  assert(!/[—–]/.test(DEFAULT_SOUL), "DEFAULT_SOUL contains an em/en dash");
});

// ---------------------------------------------------------------------------
// The reply side: llmGenerate is THE enforcement point
// ---------------------------------------------------------------------------

function openAiResponse(text: string): Response {
  return new Response(
    JSON.stringify({
      choices: [{ message: { content: text } }],
      usage: { prompt_tokens: 10, completion_tokens: 5 },
    }),
    { status: 200, headers: { "Content-Type": "application/json" } },
  );
}

function withFetchStub<T>(text: string, fn: () => Promise<T>): Promise<T> {
  const orig = globalThis.fetch;
  globalThis.fetch = (() => Promise.resolve(openAiResponse(text))) as typeof fetch;
  return fn().finally(() => { globalThis.fetch = orig; });
}

Deno.test("llmGenerate scrubs dashes out of every completion", async () => {
  const dashed = "Readiness is steady — nothing alarming. Aim for 5—8 reps.";
  const out = await withFetchStub(
    dashed,
    () => llmGenerate("openai", { prompt: "hi", systemPrompt: "sys", apiKey: "sk-test" }),
  );
  assert(!/[—–]/.test(out.text), `llmGenerate returned a dash: ${out.text}`);
  assertEquals(out.text, "Readiness is steady, nothing alarming. Aim for 5-8 reps.");
});

Deno.test("llmGenerate keeps the model's own words in raw, for the eval to score", async () => {
  // Without this the eval's dash checker is green by construction: it would be
  // scoring text this very module already cleaned, not what the model wrote.
  const dashed = "Easy week — back off.";
  const out = await withFetchStub(
    dashed,
    () => llmGenerate("openai", { prompt: "hi", systemPrompt: "sys", apiKey: "sk-test" }),
  );
  assertEquals(out.raw, dashed);
  assertEquals(out.text, stripDashes(dashed));
});

Deno.test("the scrub leaves JSON features parseable", () => {
  // Every JSON-producing feature (workout, plan) now goes through the same
  // scrub, so it must only ever emit hyphens and commas, never a quote or brace.
  const json = '{"title":"Tempo — 40 min","notes":"hold 4—5 RPE","reps":"5 – 8"}';
  const cleaned = stripDashes(json);
  assert(!/[—–]/.test(cleaned));
  const parsed = JSON.parse(cleaned) as Record<string, string>;
  assertEquals(parsed.title, "Tempo, 40 min");
  assertEquals(parsed.reps, "5-8");
});

Deno.test("quota error messages are dash-free", async () => {
  const src = await Deno.readTextFile(new URL("./quota.ts", import.meta.url));
  const strings = src.match(/"[^"\n]*"/g) ?? [];
  for (const s of strings) {
    assert(!/[—–]/.test(s), `quota.ts string contains a dash: ${s}`);
  }
});
