import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  checkDeload,
  checkHardSpacing,
  checkLongRun,
  checkPolarization,
  checkRamp,
  checkSetLandmarks,
  checkTaper,
  checkTssTarget,
  checkWeek,
  DELOAD_CUT,
  DELOAD_EVERY_WEEKS,
  MAX_HARD_SESSIONS_PER_WEEK,
  MAX_WEEKLY_RAMP,
  MIN_EASY_TIME_FRACTION,
  availabilityTssCeiling,
  plannedWeeklyTarget,
  SETS_BY_EXPERIENCE,
  TAPER_CUT,
  weekMetrics,
} from "./plan_checks.ts";
import { classifyViolation } from "./eval_harness.ts";
import { validateWeekPlan } from "./workout_schema.ts";
import type { WeekPlan } from "./workout_schema.ts";

// ---------------------------------------------------------------------------
// Fixtures — mirror the real pipeline (coerce first, then check), same shape as
// workout_review_test.ts's mk().
// ---------------------------------------------------------------------------

// A zoned endurance session. `zones` is one entry per exercise; the section's
// minutes are split evenly across them, exactly as computeTss attributes time.
function endurance(
  type: "run" | "ride" | "swim",
  mins: number,
  zones: string[],
  rpe = 4,
): Record<string, unknown> {
  return {
    type,
    title: `${type} session`,
    duration_minutes: mins,
    tss_estimate: 50,
    rpe_target: rpe,
    coach_note: "",
    sections: [{
      name: "Main Set",
      duration_minutes: mins,
      exercises: zones.map((z) => ({
        name: "Effort",
        sets: 1,
        reps: "1",
        weight_kg: null,
        pace_zone: z,
        hr_zone: z,
        rest_seconds: null,
        notes: "",
      })),
    }],
  };
}

function strength(exercises: { name: string; sets: number }[]): Record<string, unknown> {
  return {
    type: "strength",
    title: "Lift",
    duration_minutes: 60,
    tss_estimate: 55,
    rpe_target: 7,
    coach_note: "",
    sections: [{
      name: "Main Set",
      duration_minutes: 50,
      exercises: exercises.map((e) => ({
        name: e.name,
        sets: e.sets,
        reps: "8",
        weight_kg: 60,
        pace_zone: null,
        hr_zone: null,
        rest_seconds: 120,
        notes: "",
      })),
    }],
  };
}

const rest = () => ({
  type: "rest",
  title: "Rest",
  duration_minutes: 0,
  tss_estimate: 0,
  rpe_target: 0,
  coach_note: "Rest",
  sections: [],
});

const DATES = ["2026-07-13", "2026-07-14", "2026-07-15", "2026-07-16", "2026-07-17", "2026-07-18", "2026-07-19"];
const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

/** Build a validated 7-day WeekPlan from session specs. */
function week(sessions: Record<string, unknown>[], focus = "Build"): WeekPlan {
  const v = validateWeekPlan({
    week_focus: focus,
    rationale: "test",
    days: sessions.map((s, i) => ({ date: DATES[i], weekday: WEEKDAYS[i], session: s })),
  });
  assert(v.ok, `fixture failed to validate: ${v.error}`);
  return v.plan!;
}

const CTX = { targetTss: 300, priorWeekTss: 300, phase: "Build", experience: "Intermediate" };

// ---------------------------------------------------------------------------
// weekMetrics
// ---------------------------------------------------------------------------

Deno.test("weekMetrics: counts sessions, rest days and sports", () => {
  const m = weekMetrics(week([
    endurance("run", 60, ["Z2"]),
    rest(),
    endurance("ride", 60, ["Z2"]),
    rest(),
    strength([{ name: "Back Squat", sets: 4 }]),
    rest(),
    rest(),
  ]));
  assertEquals(m.sessions, 3);
  assertEquals(m.restDays, 4);
  assertEquals(m.sessionsBySport, { run: 1, ride: 1, strength: 1 });
});

Deno.test("weekMetrics: splits a section's minutes evenly across its zones", () => {
  // 60min section, one Z2 + one Z4 exercise -> 30min each, mirroring computeTss.
  const m = weekMetrics(week([endurance("run", 60, ["Z2", "Z4"]), rest(), rest(), rest(), rest(), rest(), rest()]));
  assertEquals(m.easyMinutes, 30);
  assertEquals(m.hardMinutes, 30);
  assertEquals(m.easyFraction, 0.5);
});

Deno.test("weekMetrics: a week with no zoned endurance has a null easy fraction", () => {
  // Not 0, and not 1: "no opinion". A pure strength week must not read as
  // failing an intensity-distribution rule that is about running.
  const m = weekMetrics(week([
    strength([{ name: "Back Squat", sets: 4 }]),
    rest(), rest(), rest(), rest(), rest(), rest(),
  ]));
  assertEquals(m.easyFraction, null);
});

// ---------------------------------------------------------------------------
// checkHardSpacing — prompt.ts:58, :476
// ---------------------------------------------------------------------------

Deno.test("checkHardSpacing: back-to-back hard days are a violation", () => {
  const r = checkHardSpacing(week([
    endurance("run", 60, ["Z4"], 8), // hard
    endurance("run", 60, ["Z4"], 8), // hard, back-to-back
    rest(), rest(), rest(), rest(), rest(),
  ]));
  assert(!r.ok);
  assertEquals(r.detail.back_to_back, 1);
  assert(r.violations[0].includes("2026-07-14"), "names the second day");
});

Deno.test("checkHardSpacing: a rest day between hard days is fine", () => {
  const r = checkHardSpacing(week([
    endurance("run", 60, ["Z4"], 8),
    rest(),
    endurance("run", 60, ["Z4"], 8),
    rest(), rest(), rest(), rest(),
  ]));
  assert(r.ok);
  assertEquals(r.detail.hard_sessions, 2);
});

Deno.test("checkHardSpacing: an easy day between hard days is fine", () => {
  const r = checkHardSpacing(week([
    endurance("run", 60, ["Z4"], 8),
    endurance("run", 45, ["Z2"], 4),
    endurance("run", 60, ["Z4"], 8),
    rest(), rest(), rest(), rest(),
  ]));
  assert(r.ok);
});

Deno.test("checkHardSpacing: more than the ceiling of hard sessions is a violation", () => {
  const hard = () => endurance("run", 40, ["Z4"], 8);
  const easy = () => endurance("run", 40, ["Z2"], 3);
  const r = checkHardSpacing(week([hard(), easy(), hard(), easy(), hard(), easy(), hard()]));
  assertEquals(r.detail.hard_sessions, 4);
  assert(!r.ok);
  assert(r.violations.some((v) => v.includes(String(MAX_HARD_SESSIONS_PER_WEEK))));
});

Deno.test("checkHardSpacing: a hard swim counts as a hard session", () => {
  // THE REGRESSION (formerly KNOWN_CONTRADICTIONS "swim"): isHardSession was
  // run/ride only, so a Z4 CSS swim slipped past hard-day spacing entirely and
  // could be stacked next to a hard run.
  const r = checkHardSpacing(week([
    endurance("swim", 45, ["Z4"], 8),
    endurance("run", 40, ["Z4"], 8),
    rest(), rest(), rest(), rest(), rest(),
  ]));
  assertEquals(r.detail.hard_sessions, 2, "the Z4 swim is a hard session like any other");
  assert(!r.ok, "swim-then-run back-to-back quality must now flag");
});

// ---------------------------------------------------------------------------
// checkPolarization — prompt.ts:47, :475
// ---------------------------------------------------------------------------

Deno.test("checkPolarization: a polarized week passes", () => {
  // 4x60 Z2 + 1x60 Z4 -> 240 easy / 60 hard = 80%.
  const r = checkPolarization(week([
    endurance("run", 60, ["Z2"]),
    endurance("run", 60, ["Z2"]),
    endurance("run", 60, ["Z4"], 8),
    endurance("run", 60, ["Z2"]),
    endurance("run", 60, ["Z2"]),
    rest(), rest(),
  ]));
  assert(r.ok);
  assertEquals(r.detail.easy_fraction, 0.8);
});

Deno.test("checkPolarization: a 50/50 week is a violation", () => {
  const r = checkPolarization(week([
    endurance("run", 60, ["Z2"]),
    endurance("run", 60, ["Z4"], 8),
    rest(), rest(), rest(), rest(), rest(),
  ]));
  assert(!r.ok);
  assertEquals(r.detail.easy_fraction, 0.5);
  assert(r.violations[0].includes("80/20"));
});

Deno.test("checkPolarization: a pure strength week has no opinion", () => {
  const r = checkPolarization(week([
    strength([{ name: "Back Squat", sets: 4 }]),
    rest(), rest(), rest(), rest(), rest(), rest(),
  ]));
  assert(r.ok, "the 80/20 rule is about endurance; strength must not fail it");
  assertEquals(r.detail.easy_fraction, null);
});

Deno.test("checkPolarization: the floor is the band, not the 80% target", () => {
  // 75/25 is off-target but inside the band: the rule is written "~80%".
  const r = checkPolarization(week([
    endurance("run", 60, ["Z2", "Z2", "Z2", "Z4"]), // 45 easy / 15 hard
    rest(), rest(), rest(), rest(), rest(), rest(),
  ]));
  assertEquals(r.detail.easy_fraction, 0.75);
  assert(r.ok);
  assert(0.75 >= MIN_EASY_TIME_FRACTION);
});

// ---------------------------------------------------------------------------
// checkTssTarget / checkRamp — prompt.ts:477, :52
// ---------------------------------------------------------------------------

Deno.test("checkTssTarget: load near the target passes", () => {
  const w = week([endurance("run", 100, ["Z2"]), rest(), rest(), rest(), rest(), rest(), rest()]);
  const actual = weekMetrics(w).totalTss;
  const r = checkTssTarget(w, actual);
  assert(r.ok);
  assertEquals(r.detail.delta_pct, 0);
});

Deno.test("checkTssTarget: load far from the target is a violation", () => {
  const w = week([endurance("run", 100, ["Z2"]), rest(), rest(), rest(), rest(), rest(), rest()]);
  const actual = weekMetrics(w).totalTss;
  const r = checkTssTarget(w, actual * 2);
  assert(!r.ok);
  assert((r.detail.delta_pct as number) < -0.4);
});

Deno.test("checkTssTarget: no target means no opinion", () => {
  const r = checkTssTarget(week([rest(), rest(), rest(), rest(), rest(), rest(), rest()]), 0);
  assert(r.ok);
  assertEquals(r.detail.target_tss, null);
});

Deno.test("checkRamp: a big jump on last week is a violation", () => {
  const w = week([endurance("run", 100, ["Z2"]), rest(), rest(), rest(), rest(), rest(), rest()]);
  const actual = weekMetrics(w).totalTss;
  const r = checkRamp(w, Math.round(actual / 2)); // +100%
  assert(!r.ok);
  assert((r.detail.ramp_pct as number) > MAX_WEEKLY_RAMP);
});

Deno.test("checkRamp: cutting volume is never a ramp violation", () => {
  const w = week([endurance("run", 30, ["Z2"]), rest(), rest(), rest(), rest(), rest(), rest()]);
  const r = checkRamp(w, 500);
  assert(r.ok, "the rule caps increases, not decreases");
});

Deno.test("checkRamp: a cold start has no prior volume to ramp from", () => {
  // prompt.ts:90-94 is explicit that absent history is not a fatigue signal;
  // it must not read as a violation either.
  const w = week([endurance("run", 100, ["Z2"]), rest(), rest(), rest(), rest(), rest(), rest()]);
  const r = checkRamp(w, 0);
  assert(r.ok);
  assertEquals(r.detail.ramp_pct, null);
});

// ---------------------------------------------------------------------------
// checkTaper — prompt.ts:50-51
// ---------------------------------------------------------------------------

Deno.test("checkTaper: only asserts during a taper phase", () => {
  const w = week([endurance("run", 100, ["Z2"]), rest(), rest(), rest(), rest(), rest(), rest()]);
  const r = checkTaper(w, 500, "Build (threshold + VO2max, race specificity)");
  assert(r.ok);
  assertEquals(r.detail.asserted, 0, "no taper claim outside taper");
});

Deno.test("checkTaper: a taper that doesn't cut volume is a violation", () => {
  const w = week([endurance("run", 100, ["Z2"]), rest(), rest(), rest(), rest(), rest(), rest()]);
  const actual = weekMetrics(w).totalTss;
  const r = checkTaper(w, actual, "Taper (cut volume, keep some intensity)");
  assert(!r.ok);
  assertEquals(r.detail.cut_pct, 0);
});

Deno.test("checkTaper: cutting too much is also a violation", () => {
  // "Taper" is not "stop training" — keep some intensity (prompt.ts:51).
  const w = week([endurance("run", 10, ["Z2"]), rest(), rest(), rest(), rest(), rest(), rest()]);
  const r = checkTaper(w, 500, "Taper (cut volume, keep some intensity)");
  assert(!r.ok);
  assert((r.detail.cut_pct as number) > 0.7);
});

Deno.test("checkTaper: a ~50% cut is right", () => {
  const w = week([endurance("run", 100, ["Z2"]), rest(), rest(), rest(), rest(), rest(), rest()]);
  const actual = weekMetrics(w).totalTss;
  const r = checkTaper(w, actual * 2, "Taper (cut volume, keep some intensity)");
  assert(r.ok);
  assertEquals(r.detail.cut_pct, 0.5);
});

// ---------------------------------------------------------------------------
// checkSetLandmarks — prompt.ts:79-88 (the experience-tiered rule)
// ---------------------------------------------------------------------------

Deno.test("checkSetLandmarks: over the tier ceiling is a violation", () => {
  // 20 sets of squats: fine for Advanced (16-22), over the line for Beginner (8-12).
  const w = week([
    strength([{ name: "Back Squat", sets: 20 }]),
    rest(), rest(), rest(), rest(), rest(), rest(),
  ]);
  const beginner = checkSetLandmarks(w, "Beginner");
  const advanced = checkSetLandmarks(w, "Advanced");
  assert(!beginner.ok, "20 sets is over the Beginner landmark");
  assert(advanced.ok, "but inside the Advanced one");
  assertEquals(beginner.detail.max_sets, 20);
});

Deno.test("checkSetLandmarks: this is the tier the flat runtime check misses", () => {
  // KNOWN_CONTRADICTIONS #1 made concrete: 20 sets for a Beginner breaks the
  // prompt but passes workout_review's flat MAX_WEEKLY_SETS=22.
  assert(SETS_BY_EXPERIENCE.Beginner[1] < 22);
  const w = week([
    strength([{ name: "Back Squat", sets: 20 }]),
    rest(), rest(), rest(), rest(), rest(), rest(),
  ]);
  assert(!checkSetLandmarks(w, "Beginner").ok);
});

Deno.test("checkSetLandmarks: under-shooting one muscle is not a violation", () => {
  const w = week([
    strength([{ name: "Back Squat", sets: 2 }]),
    rest(), rest(), rest(), rest(), rest(), rest(),
  ]);
  assert(checkSetLandmarks(w, "Intermediate").ok, "one light week is rotation, not a rule break");
});

Deno.test("checkSetLandmarks: an unknown experience string has no opinion", () => {
  const w = week([
    strength([{ name: "Back Squat", sets: 40 }]),
    rest(), rest(), rest(), rest(), rest(), rest(),
  ]);
  assert(checkSetLandmarks(w, "Elite").ok, "invent no landmark we don't have");
});

// ---------------------------------------------------------------------------
// checkDeload — prompt.ts:76
// ---------------------------------------------------------------------------

Deno.test("checkDeload: a build/deload cadence passes", () => {
  // 4 build weeks then a -40% cut, twice.
  const r = checkDeload([300, 324, 350, 378, 227, 300, 324, 350, 378, 227]);
  assert(r.ok);
  assertEquals(r.detail.deloads, 2);
  assertEquals(r.detail.first_deload_week, 5);
});

Deno.test("checkDeload: never deloading is a violation", () => {
  const r = checkDeload([300, 310, 320, 330, 340, 350, 360, 370]);
  assert(!r.ok);
  assertEquals(r.detail.deloads, 0);
  assert(r.violations[0].includes(String(DELOAD_EVERY_WEEKS.max)));
});

Deno.test("checkDeload: build weeks already served count toward the cadence", () => {
  // 4 build weeks already behind us + 3 more with no deload = 7 > 6.
  const r = checkDeload([300, 310, 320], 4);
  assert(!r.ok);
  assertEquals(r.detail.build_weeks_in, 4);
});

Deno.test("checkDeload: a shallow dip is not a deload", () => {
  const r = checkDeload([300, 300, 300, 300, 290, 300, 300, 300], 0);
  assertEquals(r.detail.deloads, 0, "-3% is noise, not a deload");
  assert(!r.ok);
});

// ---------------------------------------------------------------------------
// checkWeek — the composite the eval calls
// ---------------------------------------------------------------------------

Deno.test("checkWeek: a sane week passes every check", () => {
  const w = week([
    endurance("run", 60, ["Z2"]),
    strength([{ name: "Back Squat", sets: 4 }]),
    endurance("run", 45, ["Z4"], 8),
    rest(),
    endurance("run", 60, ["Z2"]),
    endurance("run", 90, ["Z2"]),
    rest(),
  ]);
  const m = weekMetrics(w);
  const r = checkWeek(w, { ...CTX, targetTss: m.totalTss, priorWeekTss: m.totalTss });
  assertEquals(r.violations, []);
  assert(r.ok);
});

Deno.test("checkWeek: violations concatenate across checks", () => {
  // Back-to-back hard AND unpolarized AND a big ramp.
  const w = week([
    endurance("run", 60, ["Z4"], 8),
    endurance("run", 60, ["Z4"], 8),
    rest(), rest(), rest(), rest(), rest(),
  ]);
  const r = checkWeek(w, { ...CTX, targetTss: 300, priorWeekTss: 50 });
  assert(!r.ok);
  assert(r.violations.length >= 3, `expected several, got ${JSON.stringify(r.violations)}`);
  assert(!r.checks.hard_spacing.ok);
  assert(!r.checks.polarization.ok);
  assert(!r.checks.ramp.ok);
});

Deno.test("checkWeek: every check reports detail even when it passes", () => {
  // The eval plots distributions, not just pass/fail, so detail must always be
  // populated — a check that only speaks up on failure gives us no baseline.
  const w = week([endurance("run", 60, ["Z2"]), rest(), rest(), rest(), rest(), rest(), rest()]);
  const r = checkWeek(w, CTX);
  for (const [name, c] of Object.entries(r.checks)) {
    assert(Object.keys(c.detail).length > 0, `${name} reported no detail`);
  }
});

// ---------------------------------------------------------------------------
// Classification tripwire
//
// VIOLATION_TEMPLATES in eval_harness.ts is hand-copied prose, so a test that
// only matches those samples proves nothing about drift — the sample and the
// regex would rot together. This runs the REAL checkers over weeks built to trip
// them and asserts nothing lands in "unknown". Reword a violation here and this
// fails, which is the whole point.
// ---------------------------------------------------------------------------

Deno.test("plan_checks: every violation this module emits is classifiable", () => {
  const emitted: string[] = [];

  // back-to-back hard + too many hard + unpolarized
  const stacked = week([
    endurance("run", 60, ["Z4"], 8),
    endurance("run", 60, ["Z4"], 8),
    endurance("run", 60, ["Z4"], 8),
    endurance("run", 60, ["Z4"], 8),
    rest(), rest(), rest(),
  ]);
  emitted.push(...checkHardSpacing(stacked).violations);
  emitted.push(...checkPolarization(stacked).violations);
  // off target + too steep a ramp
  emitted.push(...checkTssTarget(stacked, 100).violations);
  emitted.push(...checkRamp(stacked, 50).violations);
  // taper that didn't taper
  emitted.push(...checkTaper(stacked, 100, "Taper (cut volume, keep some intensity)").violations);
  // over the experience landmark
  emitted.push(...checkSetLandmarks(
    week([strength([{ name: "Back Squat", sets: 20 }]), rest(), rest(), rest(), rest(), rest(), rest()]),
    "Beginner",
  ).violations);
  // no deload in sight
  emitted.push(...checkDeload([300, 310, 320, 330, 340, 350, 360, 370]).violations);

  assert(emitted.length >= 7, `expected every check to fire, got ${emitted.length}`);
  for (const v of emitted) {
    assertEquals(classifyViolation(v), classifyViolation(v)); // stable
    assert(
      classifyViolation(v) !== "unknown",
      `unclassified violation (add a pattern in eval_harness.ts): ${v}`,
    );
    assert(classifyViolation(v).startsWith("week_"), `week violation misclassified: ${v}`);
  }
});

Deno.test("plan_checks: every exported threshold is a real number", () => {
  // These are the single source of truth shared with any future runtime check.
  // A typo'd NaN would silently make a check always-pass.
  assert(Number.isFinite(MAX_HARD_SESSIONS_PER_WEEK) && MAX_HARD_SESSIONS_PER_WEEK > 0);
  assert(MIN_EASY_TIME_FRACTION > 0 && MIN_EASY_TIME_FRACTION < 1);
  assert(MAX_WEEKLY_RAMP > 0 && MAX_WEEKLY_RAMP < 1);
  assert(DELOAD_EVERY_WEEKS.min < DELOAD_EVERY_WEEKS.max);
  for (const [tier, [lo, hi]] of Object.entries(SETS_BY_EXPERIENCE)) {
    assert(lo < hi, `${tier} landmark is inverted`);
  }
});

// ---------------------------------------------------------------------------
// plannedWeeklyTarget — the fix for the prompt asking two things at once
// ---------------------------------------------------------------------------

Deno.test("plannedWeeklyTarget: a normal week asks for the athlete's target", () => {
  for (const phase of [
    "General / maintenance",
    "Base (aerobic volume, strides, general strength)",
    "Build (threshold + VO2max, race specificity)",
  ]) {
    assertEquals(plannedWeeklyTarget(380, phase), 380, phase);
  }
});

Deno.test("plannedWeeklyTarget: a taper week asks for a tapered target", () => {
  // The bug this exists for: buildWeekPrompt used to say "cut volume 40-60%" and
  // "keep weekly load near 380 TSS" in the same prompt. 190 is inside the band
  // the phase demands, so the two instructions finally agree.
  const t = plannedWeeklyTarget(380, "Taper (cut volume, keep some intensity)");
  assertEquals(t, 190);
  const cut = (380 - t) / 380;
  assert(cut >= TAPER_CUT.min && cut <= TAPER_CUT.max, `taper target cut ${cut} is outside its own band`);
});

Deno.test("plannedWeeklyTarget: a deload week asks for a deloaded target", () => {
  const t = plannedWeeklyTarget(380, "Build (threshold + VO2max, race specificity)", true);
  assertEquals(t, 228);
  const cut = (380 - t) / 380;
  assert(cut >= DELOAD_CUT.min && cut <= DELOAD_CUT.max, `deload target cut ${cut} is outside its own band`);
});

Deno.test("plannedWeeklyTarget: taper beats a coinciding deload", () => {
  // Race week is race week; the deeper cut wins.
  assertEquals(plannedWeeklyTarget(380, "Taper (cut volume, keep some intensity)", true), 190);
});

Deno.test("plannedWeeklyTarget: Peak is deliberately not scaled", () => {
  // prompt.ts:257 calls Peak "lower volume/high quality" but names no number.
  // Inventing one here would be a rule with no source. Documented gap, not an
  // oversight — if this ever changes, the prompt must change first.
  assertEquals(plannedWeeklyTarget(380, "Peak (race-specific quality, lower volume)"), 380);
});

Deno.test("plannedWeeklyTarget: no target stays no target", () => {
  assertEquals(plannedWeeklyTarget(0, "Taper (cut volume, keep some intensity)"), 0);
});

Deno.test("plan_checks: a correct taper passes BOTH taper and tss_target", () => {
  // The regression. deepseek tapered correctly (-43%) and the old unscaled
  // target made checkTssTarget call it a violation. Now the target is the
  // phase's target, so doing the right thing scores as the right thing — with
  // no phase special-casing inside the checker.
  const phase = "Taper (cut volume, keep some intensity)";
  const base = 380;
  const target = plannedWeeklyTarget(base, phase);

  // ~190 TSS of easy running: 5 x 45min Z2 (0.8 TSS/min) = 180.
  const w = week([
    endurance("run", 45, ["Z2"]),
    endurance("run", 45, ["Z2"]),
    endurance("run", 45, ["Z2"]),
    rest(),
    endurance("run", 45, ["Z2"]),
    endurance("run", 45, ["Z2"]),
    rest(),
  ]);
  const r = checkWeek(w, { targetTss: target, priorWeekTss: base, phase, experience: "Intermediate" });
  assert(r.checks.taper.ok, `taper: ${r.checks.taper.violations}`);
  assert(r.checks.tss_target.ok, `tss_target: ${r.checks.tss_target.violations}`);
});

// ---------------------------------------------------------------------------
// availabilityTssCeiling — the target cannot exceed the athlete's own week
// ---------------------------------------------------------------------------

Deno.test("availabilityTssCeiling: prices the week at the polarized mix", () => {
  // Sam's fixture week: 405 min. 405 x 0.88 = 356 — the number the 380 target
  // silently exceeded while the same prompt said "size each day to its budget".
  assertEquals(availabilityTssCeiling(405), 356);
});

Deno.test("availabilityTssCeiling: no day budgets means no ceiling, not zero", () => {
  // Legacy profiles have no day_availability; clamping their target to 0 would
  // zero out every plan. null = "don't clamp".
  assertEquals(availabilityTssCeiling(0), null);
});

Deno.test("a generous week leaves the target alone", () => {
  // 10h of availability holds any sane target; the clamp must be invisible.
  const ceiling = availabilityTssCeiling(600)!;
  assert(ceiling > 380, `600 min should hold 380 (ceiling ${ceiling})`);
});

// ---------------------------------------------------------------------------
// checkLongRun — prompt.ts:57 "Long run (Z2, ≤30-35% of weekly volume)"
// ---------------------------------------------------------------------------

Deno.test("checkLongRun: an oversized long run flags", () => {
  // 120 of 240 weekly run minutes = 50%, well past the ~35% landmark.
  const r = checkLongRun(week([
    endurance("run", 120, ["Z2"]),
    endurance("run", 60, ["Z2"]),
    endurance("run", 60, ["Z2"]),
    rest(), rest(), rest(), rest(),
  ]));
  assert(!r.ok);
  assertEquals(r.detail.long_run_fraction, 0.5);
});

Deno.test("checkLongRun: a proportionate long run passes", () => {
  // 90 of 250 = 36%, inside the tilde band (flag line is 0.40).
  const r = checkLongRun(week([
    endurance("run", 90, ["Z2"]),
    endurance("run", 80, ["Z2"]),
    endurance("run", 80, ["Z2"]),
    rest(), rest(), rest(), rest(),
  ]));
  assert(r.ok);
});

Deno.test("checkLongRun: fewer than 3 runs is no opinion, not a violation", () => {
  // Two runs a week: each is naturally ~half the volume. The marathon-training
  // landmark doesn't apply, so the check must abstain.
  const r = checkLongRun(week([
    endurance("run", 120, ["Z2"]),
    endurance("run", 40, ["Z2"]),
    rest(), rest(), rest(), rest(), rest(),
  ]));
  assert(r.ok);
  assertEquals(r.detail.long_run_fraction, null);
});
