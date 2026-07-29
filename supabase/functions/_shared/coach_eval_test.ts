import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  buildCoachFixtures,
  callBudget,
  claimsCompletedAction,
  cleanReply,
  hasForbiddenDashes,
  looksLikeJsonLeak,
  looksLikeStall,
  scoreCoachTurn,
  shouldUpdateKnowledge,
  stripDashes,
  talksWithoutActing,
  type CoachFixture,
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

// ---------------------------------------------------------------------------
// stripDashes: the no-em-dash house rule, enforced on OUTPUT.
//
// PUNCTUATION_RULE has been in every prompt for a long time and models still
// ignore it. Measured against deepseek-v4-flash on real coach questions: 1 in 5
// replies carried an em dash on the old chat prompt, 3 in 5 on the newer,
// shorter one. no_dashes_test.ts only ever checked the prompts.
// ---------------------------------------------------------------------------

Deno.test("stripDashes: a spaced parenthetical dash becomes a comma", () => {
  assertEquals(
    stripDashes("You're carrying fatigue — nothing alarming."),
    "You're carrying fatigue, nothing alarming.",
  );
  assertEquals(stripDashes("Keep it easy – save the hard work."), "Keep it easy, save the hard work.");
});

Deno.test("stripDashes: numeric ranges become a plain hyphen, per the rule", () => {
  assertEquals(stripDashes("5—8 reps"), "5-8 reps");
  assertEquals(stripDashes("20 – 30 min"), "20-30 min");
  assertEquals(stripDashes("Z1—Z2"), "Z1-Z2");
});

Deno.test("stripDashes: no double punctuation is left behind", () => {
  assertEquals(stripDashes("Nice work. — Keep it up."), "Nice work. Keep it up.");
  assert(!/,\s*,/.test(stripDashes("a, — b")));
});

Deno.test("stripDashes: text without dashes is returned untouched", () => {
  const clean = "You're fresh today, so let's push on Thursday. Keep 5-8 reps in the tank.";
  assertEquals(stripDashes(clean), clean);
});

Deno.test("stripDashes: plain hyphens and bullet lists survive", () => {
  const t = "Here's the week:\n- Mon easy run\n- Tue strength\nKeep 5-8 reps.";
  assertEquals(stripDashes(t), t);
});

Deno.test("stripDashes: markdown table pipes and separators are untouched", () => {
  const t = "| Day | What |\n|---|---|\n| Mon | Easy run |";
  assertEquals(stripDashes(t), t);
});

Deno.test("cleanReply applies the dash rule, including inside a JSON envelope", () => {
  assertEquals(cleanReply("You're fine — really."), "You're fine, really.");
  assertEquals(
    cleanReply(`{"action":"final","message":"Good week — keep going."}`),
    "Good week, keep going.",
  );
});

// ---------------------------------------------------------------------------
// claimsCompletedAction: the past-tense half of "talks but never does it".
//
// looksLikeStall only ever caught promises, so a reply that skipped straight to
// "I moved tomorrow's run to Saturday" without calling a tool passed every
// check. Measured on deepseek-v4-flash it is the MORE common failure, and the
// worse one: the athlete is told their plan changed when it did not.
// ---------------------------------------------------------------------------

Deno.test("a past-tense claim with no tool call is caught", () => {
  // All three are verbatim from a live run.
  assert(claimsCompletedAction("I moved tomorrow's run to Saturday so your legs are fresh."));
  assert(claimsCompletedAction("Already done, I've cleared today's threshold intervals."));
  assert(claimsCompletedAction("Done, your threshold intervals are now on Saturday the 1st."));
  assert(claimsCompletedAction("Week's locked in. I planned Monday as threshold intervals."));
});

Deno.test("honest reports of READS are not mistaken for changes", () => {
  // The false positive that would matter: reads are most of what the coach
  // does, and nudging after every one would double the cost of the feature.
  assert(!claimsCompletedAction("I've looked at your profile and recent training."));
  assert(!claimsCompletedAction("I checked your week and your readiness is good."));
  assert(!claimsCompletedAction("Your fitness is trending up, CTL is 42."));
  assert(!claimsCompletedAction("Sleep is the single most powerful recovery tool you have."));
});

Deno.test("talksWithoutActing covers promises and false reports alike", () => {
  assert(talksWithoutActing("I'll adjust your plan shortly."));      // future
  assert(talksWithoutActing("I moved your long run to Sunday."));    // past
  assert(!talksWithoutActing("Your long run is on Sunday this week."));
});

Deno.test("scoreCoachTurn treats a false past-tense report as a stall", () => {
  const f: CoachFixture = { name: "x", ask: "move it", expectWrite: "move_workout" };
  const s = scoreCoachTurn(f, { reply: "I moved tomorrow's run to Saturday.", tools: [] });
  assert(s.stalled, "a claim with no tool call must not pass");
  assert(!s.pass);
});

Deno.test("cleanReply unwraps a nested envelope, not just the outer one", () => {
  // Observed live on a re-plan turn: the athlete was shown
  // {"action":"final","message":"You're right, four strength sessions..."}
  // because cleanReply unwrapped exactly one layer of two.
  const inner = JSON.stringify({ action: "final", message: "Here is your week." });
  const doubled = JSON.stringify({ action: "final", message: inner });
  assertEquals(cleanReply(doubled), "Here is your week.");
  assert(!looksLikeJsonLeak(cleanReply(doubled)));
});

Deno.test("cleanReply still leaves ordinary prose and JSON-ish text alone", () => {
  assertEquals(cleanReply("Easy run today, keep it conversational."), "Easy run today, keep it conversational.");
  // A reply that merely MENTIONS braces must not be eaten.
  assertEquals(cleanReply("Use {reps} as the placeholder."), "Use {reps} as the placeholder.");
  // Single-layer unwrap is unchanged.
  assertEquals(cleanReply('{"action":"final","message":"Done."}'), "Done.");
});

Deno.test("cleanReply terminates on a deeply nested envelope", () => {
  let t = "the actual reply";
  for (let i = 0; i < 12; i++) t = JSON.stringify({ action: "final", message: t });
  const out = cleanReply(t);
  // Bounded, so it will not fully unwrap 12 layers, but it must RETURN.
  assertEquals(typeof out, "string");
});
