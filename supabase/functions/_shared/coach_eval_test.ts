import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  buildCoachFixtures,
  hasForbiddenDashes,
  looksLikeJsonLeak,
  looksLikeStall,
  scoreCoachTurn,
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
