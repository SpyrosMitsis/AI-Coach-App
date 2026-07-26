// Guards the "no em dashes anywhere" product rule: prompts must not model the
// habit (outside the explicit punctuation rule itself), and user-facing quota
// copy must be dash-free. If this fails, someone reintroduced a — or –.

import { assert } from "jsr:@std/assert@1";
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

Deno.test("quota error messages are dash-free", async () => {
  const src = await Deno.readTextFile(new URL("./quota.ts", import.meta.url));
  const strings = src.match(/"[^"\n]*"/g) ?? [];
  for (const s of strings) {
    assert(!/[—–]/.test(s), `quota.ts string contains a dash: ${s}`);
  }
});
