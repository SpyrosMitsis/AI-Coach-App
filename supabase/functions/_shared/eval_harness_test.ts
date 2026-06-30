import { assert } from "jsr:@std/assert@1";
import { buildFixtures, scoreRaw } from "./eval_harness.ts";

const fixtures = buildFixtures();

Deno.test("fixtures build non-empty prompts", () => {
  assert(fixtures.length >= 2);
  for (const fx of fixtures) {
    assert(fx.userPrompt.length > 50, `${fx.name} prompt too short`);
    assert(fx.systemPrompt.length > 50);
  }
});

Deno.test("scoreRaw grades a valid endurance workout", () => {
  const raw = JSON.stringify({
    type: "run",
    title: "Tempo",
    duration_minutes: 50,
    tss_estimate: 55,
    rpe_target: 6,
    coach_note: "controlled tempo",
    sections: [
      { name: "Warmup", duration_minutes: 10, exercises: [{ name: "Easy jog", sets: 1, reps: "10min", pace_zone: "Z2" }] },
      { name: "Main Set", duration_minutes: 30, exercises: [{ name: "Tempo", sets: 1, reps: "30min", pace_zone: "Z3" }] },
    ],
  });
  const runFx = fixtures.find((f) => f.name.startsWith("run"))!;
  const s = scoreRaw(raw, runFx);
  assert(s.valid);
  assert(s.tss > 0);
});

Deno.test("scoreRaw flags unparseable output", () => {
  const s = scoreRaw("not json at all", fixtures[0]);
  assert(!s.valid);
  assert(s.parseError);
});
