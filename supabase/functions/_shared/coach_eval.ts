// ============================================================================
// Coach-quality golden set.
//
// The workout eval (eval_harness.ts) answers "can this model produce a valid
// workout?". This one answers "does this model behave like a decent coach?"
// with deterministic, offline-checkable rubrics:
//   - acts instead of promising ("I'll adjust your week" with no tool = stall)
//   - picks the right tool for action requests, no tool for plain questions
//   - never leaks the JSON tool envelope into prose
//   - follows the house punctuation rule (no em/en dashes)
//
// scoreCoachTurn() is pure (unit-tested); runLive() drives one single-turn
// generation per provider-with-a-key and prints a comparison table, mirroring
// eval_harness.runLive(). Single-turn is deliberate: it measures the first
// decision (answer vs which tool), which is where weak models fail first,
// without needing a database.
// ============================================================================

import { llmGenerate, PROVIDERS } from "./llm.ts";
import type { LlmProvider } from "./types.ts";
import { COACH_SYSTEM_PROMPT } from "./prompt.ts";
import { toolCatalogPrompt } from "./coach_tools.ts";

// --- stall heuristic (shared with coach-chat's anti-stall guard) -------------
// A reply "stalls" when it promises a plan change in the future tense or asks
// the athlete to wait, instead of calling the tool. Two cheap gates keep false
// positives bounded: an explicit stall phrase, OR a future-intent marker
// sitting next to a plan-action verb.
export const STALL_PHRASES =
  /\b(let me (?:review|check|look|take a look|pull up|analyz|see)|give me a (?:moment|sec|minute)|one (?:moment|sec)|hold on|bear with me|i'?ll get (?:back|right back)|i'?ll proceed|i will proceed|i'?ll go ahead)\b/i;
export const FUTURE_INTENT = /\b(i'?ll|i will|i'?m going to|i am going to|let me|going to)\b/i;
export const PLAN_VERB =
  /\b(adjust|updat|chang|modif|revis|re-?plan|plan|schedul|reschedul|mov|shift|push|reduc|lower|cut|increas|build|creat|generat|put together|draft|design|tweak|swap|regenerat|tailor|rework)\w*/i;

export function looksLikeStall(text: string): boolean {
  if (!text) return false;
  if (STALL_PHRASES.test(text)) return true;
  return FUTURE_INTENT.test(text) && PLAN_VERB.test(text);
}

// The JSON tool envelope must never reach the athlete as prose.
export function looksLikeJsonLeak(text: string): boolean {
  const t = text.trim();
  if (/^\{[\s\S]*\}$/.test(t)) return true;
  return /"(tool|arguments|message)"\s*:/.test(t);
}

export function hasForbiddenDashes(text: string): boolean {
  return /[—–]/.test(text);
}

// --- fixtures ----------------------------------------------------------------

export interface CoachFixture {
  name: string;
  ask: string;
  // A write tool that a good coach MUST reach for (single-turn: read tools
  // before the write are also acceptable, so `acceptTools` lists every
  // reasonable first move).
  expectWrite?: string;
  acceptTools?: string[];
  // Pure questions: calling any WRITE tool is wrong (reads are fine).
  forbidWrites?: boolean;
}

export const WRITE_TOOLS = [
  "plan_week",
  "generate_workout",
  "move_workout",
  "set_goal_race",
  "set_rest_day",
  "make_easier",
];

export function buildCoachFixtures(): CoachFixture[] {
  return [
    {
      name: "plan-week",
      ask: "Plan my training week starting Monday.",
      expectWrite: "plan_week",
      acceptTools: ["plan_week", "get_planned_week", "get_fitness", "get_readiness"],
    },
    {
      name: "workout-today",
      ask: "Give me a workout for today, I have 45 minutes and dumbbells.",
      expectWrite: "generate_workout",
      acceptTools: ["generate_workout", "get_readiness", "get_planned_week"],
    },
    {
      name: "move-session",
      ask: "Move tomorrow's run to Saturday please.",
      expectWrite: "move_workout",
      acceptTools: ["move_workout", "get_planned_week"],
    },
    {
      name: "rest-day",
      ask: "I feel wrecked, make today a rest day.",
      expectWrite: "set_rest_day",
      acceptTools: ["set_rest_day", "get_planned_week", "get_readiness"],
    },
    {
      name: "easier-session",
      ask: "Today's intervals look too hard, can you tone them down?",
      expectWrite: "make_easier",
      acceptTools: ["make_easier", "get_planned_week"],
    },
    // Stall bait: phrased so weak models answer "I'll adjust it right away!"
    { name: "stall-bait", ask: "Can you update my week to add a long ride on Sunday?", expectWrite: "plan_week", acceptTools: ["plan_week", "get_planned_week"] },
    // Pure questions: writing anything would be overreach.
    { name: "question-fitness", ask: "How has my fitness been trending lately?", forbidWrites: true },
    { name: "question-sleep", ask: "Does sleep really matter that much for recovery?", forbidWrites: true },
  ];
}

// --- scoring -------------------------------------------------------------------

export interface CoachTurn {
  // Prose the athlete would see ("" when the model called a tool instead).
  reply: string;
  // Tool names invoked this turn, in order.
  tools: string[];
}

export interface CoachScore {
  pass: boolean;
  stalled: boolean;
  jsonLeak: boolean;
  dashes: boolean;
  wrongTool: boolean;
  detail: string;
}

export function scoreCoachTurn(fixture: CoachFixture, turn: CoachTurn): CoachScore {
  const usedWrites = turn.tools.filter((t) => WRITE_TOOLS.includes(t));
  const stalled = turn.tools.length === 0 && looksLikeStall(turn.reply);
  const jsonLeak = looksLikeJsonLeak(turn.reply);
  const dashes = hasForbiddenDashes(turn.reply);

  let wrongTool = false;
  let detail = "ok";
  if (fixture.forbidWrites && usedWrites.length > 0) {
    wrongTool = true;
    detail = `wrote (${usedWrites.join(",")}) on a pure question`;
  } else if (fixture.expectWrite) {
    const accept = fixture.acceptTools ?? [fixture.expectWrite];
    const first = turn.tools[0];
    if (first && !accept.includes(first)) {
      wrongTool = true;
      detail = `first tool ${first}, expected one of ${accept.join("/")}`;
    } else if (!first && !stalled) {
      // Answered in prose without acting and without a detectable stall:
      // acceptable only if it's a genuine clarifying question.
      const asksBack = /\?\s*$/.test(turn.reply.trim());
      if (!asksBack) {
        wrongTool = true;
        detail = "no tool call and no clarifying question";
      }
    }
  }
  if (stalled) detail = "stalled (promised action, no tool)";
  if (jsonLeak) detail = "leaked JSON envelope";

  return { pass: !stalled && !jsonLeak && !dashes && !wrongTool, stalled, jsonLeak, dashes, wrongTool, detail };
}

// --- live comparison (manual, needs provider keys in env) ----------------------

const LIVE_PROTOCOL = `
You can either answer the athlete directly OR call a tool.
Reply with EXACTLY ONE JSON object and nothing else:
  {"tool": "<name>", "arguments": {...}}   to call a tool
  {"message": "<your reply>"}              to answer the athlete
${"" /* the catalog below lists every tool */}
`;

const LIVE_CONTEXT = `ATHLETE CONTEXT (background for YOUR reasoning; never read numbers back as a list):
- Name: Alex; today is 2026-07-13
- Goal: sub-40 10k on 2026-10-04; experience: intermediate
- Available days: Mon, Tue, Thu, Sat, Sun; session length: 60 min
- Equipment: full gym, road bike; injuries: none noted
- Form/freshness: fresh (TSB 6)
- Recovery today: good (78/100), trend steady
- This week: completed 2 of 3 planned sessions so far
- Today's plan: Threshold Intervals (run)
- Last completed sessions (newest first): 2026-07-12 Run 8.0 km · 55 TSS | 2026-07-10 Ride 30.1 km · 60 TSS`;

function parseTurn(raw: string): CoachTurn {
  try {
    const m = raw.match(/\{[\s\S]*\}/);
    if (m) {
      const obj = JSON.parse(m[0]) as { tool?: string; message?: string };
      if (obj.tool) return { reply: "", tools: [obj.tool] };
      if (typeof obj.message === "string") return { reply: obj.message, tools: [] };
    }
  } catch { /* fall through: treat as prose */ }
  return { reply: raw, tools: [] };
}

export async function runLive() {
  const rows: Record<string, unknown>[] = [];
  for (const provider of Object.keys(PROVIDERS) as LlmProvider[]) {
    const key = Deno.env.get(`${provider.toUpperCase()}_API_KEY`);
    if (!key) continue;
    let passed = 0, stalls = 0, wrong = 0;
    const fixtures = buildCoachFixtures();
    for (const f of fixtures) {
      const result = await llmGenerate(provider, {
        systemPrompt: `${COACH_SYSTEM_PROMPT}\n\n${LIVE_CONTEXT}\n${LIVE_PROTOCOL}\n${toolCatalogPrompt()}`,
        prompt: f.ask,
        apiKey: key,
        jsonMode: false,
        temperature: 0,
      });
      const score = scoreCoachTurn(f, parseTurn(result.text ?? ""));
      if (score.pass) passed++;
      if (score.stalled) stalls++;
      if (score.wrongTool) wrong++;
    }
    rows.push({ provider, passPct: Math.round((passed / fixtures.length) * 100), stalls, wrongTool: wrong });
  }
  console.table(rows);
}

if (import.meta.main) await runLive();
