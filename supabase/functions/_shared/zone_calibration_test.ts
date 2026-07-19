import { assert, assertEquals } from "jsr:@std/assert@1";
import { computeTss, isHardSession, ZONE_TSS_PER_MIN, zoneOf } from "./workout_review.ts";
import { validateWorkout } from "./workout_schema.ts";
import type { WorkoutExercise } from "./types.ts";

// ---------------------------------------------------------------------------
// The zone scale, pinned.
//
// ZONE_TSS_PER_MIN is the ruler every load verdict is measured with: computeTss
// feeds the tss_replaced violation (which OVERWRITES the model's estimate), the
// weekly totals plan_checks grades, and the numbers the athlete is shown. A
// silent edit here moves every one of those at once, so the numbers and their
// derivation are asserted rather than trusted.
// ---------------------------------------------------------------------------

const ex = (zone: string | null): WorkoutExercise => ({
  name: "Effort",
  sets: 1,
  reps: "1",
  weight_kg: null,
  pace_zone: zone,
  hr_zone: zone,
  rest_seconds: null,
  notes: "",
});

/** Standard TSS: hours x IF^2 x 100. Inverted, a rate implies an intensity factor. */
const impliedIf = (tssPerMin: number) => Math.sqrt(tssPerMin * 60 / 100);

// Coggan zone bands, % of FTP.
const BAND: Record<string, [number, number]> = {
  Z1: [0, 55],
  Z2: [56, 75],
  Z3: [76, 90],
  Z4: [91, 105],
  Z5: [106, 120],
};

Deno.test("zone scale: every rate implies an IF inside its own Coggan band", () => {
  // Not a taste test: a rate whose implied IF falls outside its band is not a
  // calibration choice, it's a typo.
  for (const [zone, rate] of Object.entries(ZONE_TSS_PER_MIN)) {
    const IF = impliedIf(rate) * 100;
    const [lo, hi] = BAND[zone];
    assert(IF >= lo && IF <= hi, `${zone}: rate ${rate} implies IF ${IF.toFixed(0)}%, outside ${lo}-${hi}%`);
  }
});

Deno.test("zone scale: the calibration is upper-band, deliberately", () => {
  // This is the property zoneOf's range handling depends on: a prescribed "Z2
  // run" means the TOP of Z2, so a "Z1-Z2" run resolves to Z2. If someone
  // re-centres these rates mid-band, that argument dies and this fails first.
  for (const [zone, rate] of Object.entries(ZONE_TSS_PER_MIN)) {
    const [lo, hi] = BAND[zone];
    const pos = (impliedIf(rate) * 100 - lo) / (hi - lo);
    assert(pos > 0.5, `${zone} sits at ${(pos * 100).toFixed(0)}% of its band, no longer upper-band`);
  }
});

Deno.test("zone scale: the documented anchor holds (60 min Z2 ~= 50 TSS)", () => {
  assertEquals(Math.round(60 * ZONE_TSS_PER_MIN.Z2), 48);
});

Deno.test("zone scale: rates rise monotonically with zone", () => {
  const rates = ["Z1", "Z2", "Z3", "Z4", "Z5"].map((z) => ZONE_TSS_PER_MIN[z]);
  for (let i = 1; i < rates.length; i++) {
    assert(rates[i] > rates[i - 1], `Z${i + 1} is not harder than Z${i}`);
  }
});

// ---------------------------------------------------------------------------
// zoneOf — range notation
// ---------------------------------------------------------------------------

Deno.test("zoneOf: a single zone is itself", () => {
  for (const z of ["Z1", "Z2", "Z3", "Z4", "Z5"]) assertEquals(zoneOf(ex(z)), z);
});

Deno.test("zoneOf: a range resolves to its highest zone", () => {
  // THE REGRESSION. These are verbatim the strings models write; the old
  // first-match parse priced each at its floor.
  assertEquals(zoneOf(ex("Z1-Z2")), "Z2");
  assertEquals(zoneOf(ex("Z3-Z4")), "Z4");
  assertEquals(zoneOf(ex("Z4-Z5")), "Z5");
  assertEquals(zoneOf(ex("Z2-Z3")), "Z3");
});

Deno.test("zoneOf: an easy run in the Z1-Z2 band is priced as Z2, not Z1", () => {
  // 45 min easy: 36 TSS, not the 23 it used to be. The engine then stops
  // "correcting" the model's own closer estimate down to the wrong number.
  const v = validateWorkout({
    type: "run",
    title: "Easy Run",
    duration_minutes: 45,
    tss_estimate: 40,
    rpe_target: 4,
    coach_note: "",
    sections: [{ name: "Main Set", duration_minutes: 45, exercises: [ex("Z1-Z2")] }],
  });
  assert(v.ok);
  assertEquals(computeTss(v.workout!), 36);
});

Deno.test("zoneOf: a Z2-Z3 tempo counts as a hard session", () => {
  // The unsafe direction of the old bug: "Z2-Z3" read as Z2, so a tempo was
  // invisible to hard-day spacing and could be stacked on another quality day.
  const v = validateWorkout({
    type: "run",
    title: "Tempo",
    duration_minutes: 40,
    tss_estimate: 55,
    rpe_target: 6, // below the RPE>=7 shortcut, so the zone must carry it
    coach_note: "",
    sections: [{ name: "Main Set", duration_minutes: 40, exercises: [ex("Z2-Z3")] }],
  });
  assert(v.ok);
  assert(isHardSession(v.workout!), "a Z2-Z3 tempo must register as hard");
});

Deno.test("zoneOf: tolerates the shapes models actually write", () => {
  assertEquals(zoneOf(ex("z2")), "Z2");
  assertEquals(zoneOf(ex("Z 2")), "Z2");
  assertEquals(zoneOf(ex("Z1-Z2 (95-145 bpm)")), "Z2", "bare band numbers must not be read as zones");
  assertEquals(zoneOf(ex("5x Z4")), "Z4", "a rep count must not be read as a zone");
  assertEquals(zoneOf(ex("Z4-Z5 x 6")), "Z5");
});

Deno.test("zoneOf: no zone is null, not a guess", () => {
  assertEquals(zoneOf(ex(null)), null);
  assertEquals(zoneOf(ex("")), null);
  assertEquals(zoneOf(ex("easy")), null);
  // computeTss's own fallback (Z2) is a separate, deliberate decision.
});

Deno.test("zoneOf: prefers hr_zone over pace_zone, as before", () => {
  const both: WorkoutExercise = { ...ex("Z2"), hr_zone: "Z4", pace_zone: "Z1" };
  assertEquals(zoneOf(both), "Z4");
});
