import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  durationToken,
  normZone,
  renderIntervalsWorkout,
  renderStrengthSession,
} from "./intervals_workout.ts";
import type { Workout } from "./types.ts";

Deno.test("normZone accepts the messy forms LLMs emit", () => {
  assertEquals(normZone("Z4"), "Z4");
  assertEquals(normZone("Zone 4"), "Z4");
  assertEquals(normZone("4"), "Z4");
  assertEquals(normZone("z2 - easy"), "Z2");
  assertEquals(normZone(""), null);
  assertEquals(normZone(null), null);
});

Deno.test("durationToken parses time and distance variants", () => {
  assertEquals(durationToken("10 min"), "10m");
  assertEquals(durationToken("30s"), "30s");
  assertEquals(durationToken("1h"), "1h");
  assertEquals(durationToken("1km"), "1km");
  assertEquals(durationToken("800m"), "0.8km");   // bare metres ≥100
  assertEquals(durationToken("5m"), "5m");        // bare m <100 ⇒ minutes
  assertEquals(durationToken("5:00"), "300s");    // clock
  assertEquals(durationToken("12"), "12m");       // bare number ⇒ minutes
  assertEquals(durationToken("8-10"), null);      // strength rep range: no duration
});

Deno.test("run renders Intervals step syntax with repeats and recoveries", () => {
  const w: Workout = {
    type: "run", title: "VO2", duration_minutes: 45, tss_estimate: 70, rpe_target: 8,
    coach_note: "Hit the reps.",
    sections: [
      {
        name: "Warmup", duration_minutes: 10,
        exercises: [{ name: "Jog", sets: 1, reps: "10 min", weight_kg: null, pace_zone: null, hr_zone: "Z1", rest_seconds: null, notes: "" }],
      },
      {
        name: "Main Set", duration_minutes: 24,
        exercises: [{ name: "Reps", sets: 4, reps: "3 min", weight_kg: null, pace_zone: "Z5", hr_zone: null, rest_seconds: 180, notes: "" }],
      },
    ],
  };
  const text = renderIntervalsWorkout(w);
  assert(text.includes("Warmup\n- 10m Z1 HR"));
  assert(text.includes("4x\n- 3m Z5 Pace\n- 3m Z1 Pace"));
  assert(text.endsWith("Hit the reps."));
});

Deno.test("strength renders readable list with load/rest/RIR", () => {
  const w: Workout = {
    type: "strength", title: "Lower", duration_minutes: 60, tss_estimate: 50, rpe_target: 8,
    coach_note: "",
    sections: [{
      name: "Main", duration_minutes: 40,
      exercises: [{ name: "Back Squat", sets: 4, reps: "5", weight_kg: 100, pace_zone: null, hr_zone: null, rest_seconds: 180, notes: "2 RIR" }],
    }],
  };
  const text = renderIntervalsWorkout(w);
  assert(text.includes("- Back Squat: 4×5 @ 100kg rest 3m 2 RIR"));
});

Deno.test("renderStrengthSession collapses identical sets", () => {
  const text = renderStrengthSession("Push Day", [
    { name: "Bench", sets: [{ reps: 5, weight_kg: 80 }, { reps: 5, weight_kg: 80 }, { reps: 5, weight_kg: 80 }] },
    { name: "OHP", sets: [{ reps: 8, weight_kg: 40 }, { reps: 6, weight_kg: 42.5 }] },
  ]);
  assert(text.includes("- Bench: 3×(5@80kg)"));
  assert(text.includes("- OHP: 8@40kg, 6@42.5kg"));
});
