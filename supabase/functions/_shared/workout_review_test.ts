// Eval harness for the content-review layer — turns "is the engine good?" from a
// vibe into invariants. Run: `deno test supabase/functions/_shared/workout_review_test.ts`
import { assert, assertEquals } from "jsr:@std/assert@1";
import { validateWorkout } from "./workout_schema.ts";
import { computeTss, isHardSession, type ReviewContext, reviewWorkout } from "./workout_review.ts";

const EMPTY_CTX: ReviewContext = {
  mainLifts: [],
  weeklySetsByMuscle: {},
  muscleGroupsLast48h: [],
  tsb: 0,
  daysSinceLastHard: 99,
  experience: "Intermediate",
};

// Build a validated Workout from a partial spec (mirrors the real pipeline:
// zod-coerce first, then review).
function mk(over: Record<string, unknown>) {
  const v = validateWorkout({
    type: "strength",
    title: "Session",
    duration_minutes: 60,
    tss_estimate: 55,
    rpe_target: 7,
    coach_note: "",
    sections: [],
    ...over,
  });
  assert(v.ok, `fixture failed to validate: ${v.error}`);
  return v.workout!;
}

const strengthSection = (exercises: Record<string, unknown>[]) => ({
  name: "Main Set",
  duration_minutes: 45,
  exercises,
});

// --- progressive-overload floor --------------------------------------------
Deno.test("strength load below last top set is raised + flagged", () => {
  const w = mk({
    sections: [strengthSection([
      { name: "Back Squat", sets: 4, reps: "5", weight_kg: 80, muscle: "Quads", notes: "" },
    ])],
  });
  const r = reviewWorkout(w, {
    ...EMPTY_CTX,
    mainLifts: [{ exercise: "Back Squat", estimated1rm: 130, lastWeight: 100, lastReps: 5, lastSets: 4 }],
  });
  assertEquals(r.corrected.sections[0].exercises[0].weight_kg, 100);
  assert(r.violations.some((v) => v.includes("below last top set")));
});

// --- safety ceiling (vs 1RM) -----------------------------------------------
Deno.test("strength load above 1.5x est 1RM is clamped", () => {
  const w = mk({
    sections: [strengthSection([
      { name: "Bench Press", sets: 3, reps: "5", weight_kg: 300, muscle: "Chest", notes: "" },
    ])],
  });
  const r = reviewWorkout(w, {
    ...EMPTY_CTX,
    mainLifts: [{ exercise: "Bench Press", estimated1rm: 100, lastWeight: 90 }],
  });
  assertEquals(r.corrected.sections[0].exercises[0].weight_kg, 150); // 1.5 * 100
  assert(r.violations.some((v) => v.includes("exceeds 1.5")));
});

// --- absolute sanity cap (no 1RM available) --------------------------------
Deno.test("absurd load with no 1RM is capped at the absolute limit", () => {
  const w = mk({
    sections: [strengthSection([
      { name: "Dumbbell Curl", sets: 3, reps: "10", weight_kg: 999, muscle: "Biceps", notes: "" },
    ])],
  });
  const r = reviewWorkout(w, EMPTY_CTX);
  assertEquals(r.corrected.sections[0].exercises[0].weight_kg, 400);
  assert(r.violations.some((v) => v.includes("sanity cap")));
});

// --- weekly volume landmark ------------------------------------------------
Deno.test("blowing past the weekly volume landmark is flagged", () => {
  const w = mk({
    sections: [strengthSection([
      { name: "Back Squat", sets: 8, reps: "8", weight_kg: 100, muscle: "Quads", notes: "" },
    ])],
  });
  const r = reviewWorkout(w, { ...EMPTY_CTX, weeklySetsByMuscle: { Quads: 18 } }); // 18 + 8 = 26
  assert(r.violations.some((v) => v.includes("Quads") && v.includes("landmark")));
});

// --- 48h muscle recovery ---------------------------------------------------
Deno.test("hard-loading a muscle trained in the last 48h is flagged", () => {
  const w = mk({
    sections: [strengthSection([
      { name: "Back Squat", sets: 4, reps: "5", weight_kg: 100, muscle: "Quads", notes: "" },
    ])],
  });
  const r = reviewWorkout(w, { ...EMPTY_CTX, muscleGroupsLast48h: ["Quads"] });
  assert(r.violations.some((v) => v.includes("last 48h")));
});

// --- endurance readiness ---------------------------------------------------
Deno.test("hard run while very fatigued (TSB < -20) is flagged", () => {
  const w = mk({
    type: "run",
    rpe_target: 9,
    sections: [{
      name: "Main Set",
      duration_minutes: 40,
      exercises: [{ name: "VO2 intervals", sets: 5, reps: "3 min", hr_zone: "Z5", notes: "" }],
    }],
  });
  assert(isHardSession(w));
  const r = reviewWorkout(w, { ...EMPTY_CTX, tsb: -25 });
  assert(r.violations.some((v) => v.includes("very fatigued")));
});

Deno.test("back-to-back hard days are flagged", () => {
  const w = mk({
    type: "run",
    rpe_target: 8,
    sections: [{
      name: "Main Set",
      duration_minutes: 30,
      exercises: [{ name: "Threshold", sets: 1, reps: "20 min", hr_zone: "Z4", notes: "" }],
    }],
  });
  const r = reviewWorkout(w, { ...EMPTY_CTX, daysSinceLastHard: 1 });
  assert(r.violations.some((v) => v.includes("back-to-back")));
});

// --- TSS cross-check --------------------------------------------------------
Deno.test("a wildly wrong tss_estimate is replaced with the computed value", () => {
  const w = mk({
    type: "run",
    tss_estimate: 300, // absurd for 30 min easy
    rpe_target: 3,
    sections: [{
      name: "Main Set",
      duration_minutes: 30,
      exercises: [{ name: "Easy jog", sets: 1, reps: "30 min", hr_zone: "Z2", notes: "" }],
    }],
  });
  const r = reviewWorkout(w, EMPTY_CTX);
  assert(r.tssReplaced, "expected a TSS replacement");
  assertEquals(r.corrected.tss_estimate, computeTss(w));
  assert(r.corrected.tss_estimate < 60);
});

Deno.test("computeTss: ~60 min Z2 lands in a sane endurance range", () => {
  const w = mk({
    type: "run",
    sections: [{
      name: "Main Set",
      duration_minutes: 60,
      exercises: [{ name: "Z2", sets: 1, reps: "60 min", hr_zone: "Z2", notes: "" }],
    }],
  });
  const tss = computeTss(w);
  assert(tss >= 40 && tss <= 60, `Z2 hour TSS out of range: ${tss}`);
});

// --- clean workout: no false positives -------------------------------------
Deno.test("a sound progressive-overload session produces no violations", () => {
  const w = mk({
    tss_estimate: 50,
    sections: [strengthSection([
      { name: "Back Squat", sets: 4, reps: "5", weight_kg: 102.5, muscle: "Quads", notes: "RIR 2" },
    ])],
  });
  const r = reviewWorkout(w, {
    ...EMPTY_CTX,
    mainLifts: [{ exercise: "Back Squat", estimated1rm: 130, lastWeight: 100 }],
    weeklySetsByMuscle: { Quads: 8 },
  });
  assertEquals(r.violations, []);
});

Deno.test("rest day reviews clean with zero TSS", () => {
  const w = mk({ type: "rest", sections: [], tss_estimate: 0, rpe_target: 0 });
  const r = reviewWorkout(w, EMPTY_CTX);
  assertEquals(r.violations, []);
  assertEquals(computeTss(w), 0);
});

// --- contraindication safety engine ----------------------------------------
Deno.test("a contraindicated movement is stripped + marked unsafe", () => {
  const w = mk({
    sections: [strengthSection([
      { name: "Deadlift", sets: 3, reps: "5", weight_kg: 140, muscle: "Back", notes: "" },
      { name: "Leg Press", sets: 3, reps: "10", weight_kg: 200, muscle: "Quads", notes: "" },
    ])],
  });
  const r = reviewWorkout(w, { ...EMPTY_CTX, injuries: "Lower back disc herniation — be careful" });
  assert(r.unsafe.some((v) => v.includes("Deadlift")));
  const names = r.corrected.sections.flatMap((s) => s.exercises.map((e) => e.name));
  assert(!names.includes("Deadlift"), "deadlift should be removed");
  assert(names.includes("Leg Press"), "leg press should remain");
});

Deno.test("no injury on file leaves the movement in place", () => {
  const w = mk({
    sections: [strengthSection([
      { name: "Deadlift", sets: 3, reps: "5", weight_kg: 140, muscle: "Back", notes: "" },
    ])],
  });
  const r = reviewWorkout(w, EMPTY_CTX);
  assertEquals(r.unsafe, []);
  assertEquals(r.corrected.sections[0].exercises[0].name, "Deadlift");
});

// --- determinism / idempotency ---------------------------------------------
Deno.test("review is deterministic and idempotent (re-review is a no-op)", () => {
  const w = mk({
    sections: [strengthSection([
      { name: "Back Squat", sets: 4, reps: "5", weight_kg: 80, muscle: "Quads", notes: "" },
    ])],
  });
  const ctx = {
    ...EMPTY_CTX,
    mainLifts: [{ exercise: "Back Squat", estimated1rm: 130, lastWeight: 100 }],
  };
  const first = reviewWorkout(w, ctx);
  const second = reviewWorkout(first.corrected, ctx); // re-reviewing the fixed output
  assertEquals(second.violations, []); // nothing left to fix
  assertEquals(first.corrected.sections[0].exercises[0].weight_kg, 100);
  assertEquals(second.corrected.sections[0].exercises[0].weight_kg, 100);
});

// --- equipment hard-filter -------------------------------------------------
Deno.test("strength lift needing unavailable equipment is stripped + flagged", () => {
  const w = mk({
    sections: [strengthSection([
      { name: "Barbell Bench Press", sets: 4, reps: "5", weight_kg: 60, muscle: "Chest", notes: "" },
      { name: "Push-Up", sets: 3, reps: "12", muscle: "Chest", notes: "" },
    ])],
  });
  const r = reviewWorkout(w, { ...EMPTY_CTX, equipment: "Bodyweight" });
  const names = r.corrected.sections.flatMap((s) => s.exercises.map((e) => e.name));
  assert(!names.includes("Barbell Bench Press"), "barbell lift should be removed");
  assert(names.includes("Push-Up"), "bodyweight lift should remain");
  assert(r.violations.some((v) => /Barbell Bench Press/.test(v)));
});

// --- intensity ceiling on low readiness ------------------------------------
Deno.test("hard endurance session on a wrecked athlete is capped to easy", () => {
  const w = validateWorkout({
    type: "run", title: "Intervals", duration_minutes: 50, tss_estimate: 90, rpe_target: 9,
    coach_note: "", sections: [
      { name: "Main Set", duration_minutes: 30, exercises: [{ name: "VO2 reps", sets: 5, reps: "3min", pace_zone: "Z5" }] },
    ],
  }).workout!;
  assert(isHardSession(w));
  const r = reviewWorkout(w, { ...EMPTY_CTX, tsb: -25, readiness: 20 });
  assert(r.corrected.rpe_target <= 5, "rpe should be capped");
  const zones = r.corrected.sections.flatMap((s) => s.exercises.map((e) => e.pace_zone));
  assert(zones.every((z) => z !== "Z5" && z !== "Z4"), "hard zones should be downgraded");
  assert(r.violations.some((v) => /readiness|TSB/i.test(v)));
});
