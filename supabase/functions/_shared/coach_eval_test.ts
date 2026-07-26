import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  buildCoachFixtures,
  callBudget,
  cleanReply,
  hasForbiddenDashes,
  looksLikeJsonLeak,
  looksLikeStall,
  scoreCoachTurn,
  shouldUpdateKnowledge,
} from "./coach_eval.ts";

Deno.test("coach eval: fixtures build and cover both action and question cases", () => {
  const f = buildCoachFixtures();
  assert(f.length >= 8);
  assert(f.some((x) => x.expectWrite));
  assert(f.some((x) => x.forbidWrites));
});

Deno.test("coach eval: stall detection catches promises, not reports", () => {
  assert(looksLikeStall("I'll adjust your week right away, give me a moment."));
  assert(looksLikeStall("Let me review your plan and update Sunday."));
  assert(!looksLikeStall("Done. I moved your long run to Saturday."));
  assert(!looksLikeStall("Your fitness is trending up nicely."));
});

Deno.test("coach eval: json leak + dash checks", () => {
  assert(looksLikeJsonLeak('{"tool": "plan_week", "arguments": {}}'));
  assert(looksLikeJsonLeak('Sure! {"message": "hello"}'));
  assert(!looksLikeJsonLeak("A plain coaching reply about pacing."));
  assert(hasForbiddenDashes("Recovery is key — sleep more."));
  assert(!hasForbiddenDashes("Recovery is key, sleep more. Aim for 5-8 reps."));
});

Deno.test("coach eval: right tool passes, wrong tool fails", () => {
  const plan = buildCoachFixtures().find((f) => f.name === "plan-week")!;
  assert(scoreCoachTurn(plan, { reply: "", tools: ["plan_week"] }).pass);
  // Reading first is an acceptable first move.
  assert(scoreCoachTurn(plan, { reply: "", tools: ["get_planned_week"] }).pass);
  const wrong = scoreCoachTurn(plan, { reply: "", tools: ["set_goal_race"] });
  assertEquals(wrong.pass, false);
  assert(wrong.wrongTool);
});

Deno.test("coach eval: stalling on an action request fails", () => {
  const bait = buildCoachFixtures().find((f) => f.name === "stall-bait")!;
  const s = scoreCoachTurn(bait, {
    reply: "Great idea! I'll update your week and add a long ride on Sunday.",
    tools: [],
  });
  assertEquals(s.pass, false);
  assert(s.stalled);
});

Deno.test("coach eval: clarifying question on an action request is acceptable", () => {
  const move = buildCoachFixtures().find((f) => f.name === "move-session")!;
  const s = scoreCoachTurn(move, {
    reply: "You have two runs tomorrow. Which one should move to Saturday?",
    tools: [],
  });
  assert(s.pass);
});

Deno.test("coach eval: writing on a pure question fails", () => {
  const q = buildCoachFixtures().find((f) => f.name === "question-fitness")!;
  assert(scoreCoachTurn(q, { reply: "Trending up.", tools: ["get_fitness"] }).pass);
  const s = scoreCoachTurn(q, { reply: "", tools: ["plan_week"] });
  assertEquals(s.pass, false);
  assert(s.wrongTool);
});

// --- callBudget ----------------------------------------------------------------
Deno.test("callBudget: tracks spend and exhaustion across a shared budget", () => {
  const b = callBudget(3);
  assertEquals(b.used(), 0);
  assertEquals(b.remaining(), 3);
  assert(!b.exhausted());
  b.spend();
  b.spend(2);
  assertEquals(b.used(), 3);
  assertEquals(b.remaining(), 0);
  assert(b.exhausted());
});

Deno.test("callBudget: remaining never goes negative on overspend", () => {
  const b = callBudget(2);
  b.spend(5);
  assertEquals(b.remaining(), 0);
  assert(b.exhausted());
});

// --- cleanReply ------------------------------------------------------------------
Deno.test("cleanReply: passes plain prose through unchanged", () => {
  assertEquals(cleanReply("Done, I moved your run to Saturday."), "Done, I moved your run to Saturday.");
});

Deno.test("cleanReply: unwraps a fenced JSON envelope", () => {
  assertEquals(
    cleanReply('```json\n{"action":"final","message":"Your week is set."}\n```'),
    "Your week is set.",
  );
});

Deno.test("cleanReply: unwraps a raw (unfenced) JSON envelope", () => {
  assertEquals(cleanReply('{"message": "All set for tomorrow."}'), "All set for tomorrow.");
  assertEquals(cleanReply('{"reply": "Sounds good."}'), "Sounds good.");
  assertEquals(cleanReply('{"final": "Noted."}'), "Noted.");
});

Deno.test("cleanReply: malformed JSON-looking text falls back to itself", () => {
  const t = '{not actually json';
  assertEquals(cleanReply(t), t);
});

// --- shouldUpdateKnowledge -------------------------------------------------------
Deno.test("shouldUpdateKnowledge: a hint keyword triggers it", () => {
  assert(shouldUpdateKnowledge("my left knee has been sore all week", 1));
  assert(shouldUpdateKnowledge("I'm traveling for work next week", 1));
});

Deno.test("shouldUpdateKnowledge: a first-person declarative without a hint keyword still triggers it", () => {
  assert(shouldUpdateKnowledge("I only train twice a week these days", 1));
});

Deno.test("shouldUpdateKnowledge: periodic safety net fires every 4th user turn", () => {
  assert(!shouldUpdateKnowledge("How's my fitness looking?", 1));
  assert(!shouldUpdateKnowledge("How's my fitness looking?", 3));
  assert(shouldUpdateKnowledge("How's my fitness looking?", 4));
  assert(shouldUpdateKnowledge("How's my fitness looking?", 8));
});

Deno.test("shouldUpdateKnowledge: an unrelated question on an off-cadence turn does not trigger it", () => {
  assert(!shouldUpdateKnowledge("What's a good cadence for easy runs?", 2));
});
