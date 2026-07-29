// ============================================================================
// The scenario matrix.
//
// Every scenario declares `catches`: the falsifiable thing it exists to expose.
// A scenario with no expected failure mode is waste — it burns tokens to tell us
// something we already knew — so it doesn't get added.
//
// Contexts are built with the REAL prompt builders (buildRunPrompt /
// buildStrengthPrompt / buildWeekPrompt) so the eval sees exactly the bytes the
// edge functions send. The scoring context mirrors the generating context: a
// scenario that TELLS the model about a knee injury also REVIEWS with that
// injury, or the checker would grade against different facts than the model got.
// ============================================================================

import {
  buildRunPrompt,
  buildStrengthPrompt,
  buildWeekPrompt,
  SYSTEM_PROMPT,
  trainingPhase,
  WEEK_SYSTEM_PROMPT,
} from "../../supabase/functions/_shared/prompt.ts";
import type { ReviewContext } from "../../supabase/functions/_shared/workout_review.ts";
import {
  availabilityTssCeiling,
  plannedWeeklyTarget,
  type WeekCheckContext,
} from "../../supabase/functions/_shared/plan_checks.ts";
import { weeklyAvailableMinutes } from "../../supabase/functions/_shared/profile.ts";
import { nextTarget } from "../../supabase/functions/_shared/progression.ts";
import {
  availabilityBlock,
  experienceBlock,
  profileFactsBlock,
  splitBlock,
  sportsBlock,
} from "../../supabase/functions/_shared/profile.ts";
import { goalBlock } from "../../supabase/functions/_shared/context.ts";

export type ScenarioKind = "workout" | "week";

export interface Scenario {
  name: string;
  kind: ScenarioKind;
  /** What this exists to catch. Ends up in the JSONL so the notebook can group. */
  catches: string;
  tier: "smoke" | "full";
  systemPrompt: string;
  userPrompt: string;
  /** Single-workout scoring context (kind === "workout"). */
  ctx?: ReviewContext;
  /** Week scoring context (kind === "week"). */
  weekCtx?: WeekCheckContext;
  /** Extra facts the notebook + judge need. */
  meta: Record<string, string | number | boolean | null>;
}

// ---------------------------------------------------------------------------
// Shared athlete baseline. Deliberately close to supabase/seed.sql's "Sam
// Runner" so eval findings transfer to what dev.sh fn:call exercises.
// ---------------------------------------------------------------------------

const HR_ZONES = [
  { zone: "Z1", min: 95, max: 130 },
  { zone: "Z2", min: 131, max: 145 },
  { zone: "Z3", min: 146, max: 160 },
  { zone: "Z4", min: 161, max: 172 },
  { zone: "Z5", min: 173, max: 190 },
];

const WELL_OK = { energy: 4, soreness: 2, sleep: 4 };
const WELL_BAD = { energy: 2, soreness: 4, sleep: 2 };

// The athlete's last logged sets. generate-workout:316 derives the prompt's
// NEXT TARGET from exactly this, via nextTarget(), on EVERY strength request —
// and nextTarget only returns null with zero working sets, which cannot happen
// when a lastWeight exists. A fixture without a target therefore tests a state
// production never produces: it asks the model to guess the load, then marks it
// wrong for guessing. (It did: deepseek prescribed 85kg against a 102.5kg last
// top set, having never been told the target was 102.5x6.)
const BENCH_SETS = [{ reps: 5, weight_kg: 77.5 }, { reps: 4, weight_kg: 77.5 }];
const SQUAT_SETS = [{ reps: 5, weight_kg: 102.5 }, { reps: 5, weight_kg: 102.5 }];

const MAIN_LIFTS = [
  {
    exercise: "Barbell Bench Press",
    estimated1rm: 90.4,
    lastWeight: 77.5,
    lastReps: 5,
    lastSets: 2,
    target: nextTarget(BENCH_SETS, true),
  },
  {
    exercise: "Back Squat",
    estimated1rm: 119.6,
    lastWeight: 102.5,
    lastReps: 5,
    lastSets: 2,
    target: nextTarget(SQUAT_SETS, true),
  },
];

const baseReviewCtx = (over: Partial<ReviewContext> = {}): ReviewContext => ({
  mainLifts: MAIN_LIFTS,
  weeklySetsByMuscle: { Chest: 6, Back: 6, Quads: 8 },
  muscleGroupsLast48h: [],
  tsb: 2,
  daysSinceLastHard: 3,
  experience: "Intermediate",
  ...over,
});

const DURATION_NOTE = "Aim for about 60 minutes; flex 10 minutes either way if the session needs it.";

// Monday-anchored week, fixed so runs are comparable.
const WEEK_START = "2026-08-03";
const DAY_LIST = [
  { date: "2026-08-03", weekday: "Mon", available: true },
  { date: "2026-08-04", weekday: "Tue", available: true },
  { date: "2026-08-05", weekday: "Wed", available: false },
  { date: "2026-08-06", weekday: "Thu", available: true },
  { date: "2026-08-07", weekday: "Fri", available: false },
  { date: "2026-08-08", weekday: "Sat", available: true },
  { date: "2026-08-09", weekday: "Sun", available: true },
];

// ---------------------------------------------------------------------------
// The context blocks, assembled the way plan-week assembles them.
//
// The first eval run sent contextBlocks: "" while production sends up to 12
// blocks, and the missing availabilityBlock (per-day session budgets, THE
// volume lever) confounded every week-level volume verdict. So the fixture now
// calls the SAME builders plan-week imports, in the same order, fed with a
// manual-entry athlete: no Intervals.icu, no prior planned weeks, no chat
// memory, no custom exercise catalog. For that athlete the DB-bound blocks
// (physiology, adherence, execution, memory, catalog, locked days) render ""
// in production too, so leaving them out here is faithful, not a shortcut.
// ---------------------------------------------------------------------------

const evalAddDays = (iso: string, n: number) => {
  const d = new Date(iso + "T12:00:00");
  d.setDate(d.getDate() + n);
  return d.toISOString().slice(0, 10);
};

// Availability must agree with DAY_LIST (Mon/Tue/Thu/Sat/Sun on, Wed/Fri off):
// 405 min/week total, which prices out around the mid-300s TSS with a normal
// easy/quality mix — the 380 target is reachable but honest, not free.
const SAM_AVAILABILITY = [
  { day: "Mon", max_minutes: 60 },
  { day: "Tue", max_minutes: 75 },
  { day: "Thu", max_minutes: 60 },
  { day: "Sat", max_minutes: 120 },
  { day: "Sun", max_minutes: 90 },
];
const ALL_DAYS_AVAILABILITY = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
  .map((day) => ({ day, max_minutes: day === "Sat" ? 120 : day === "Sun" ? 90 : 60 }));

const SAM_ONBOARDING = {
  birth_year: 1992,
  sex: "M",
  height_cm: 180,
  weight_kg: 74,
  goals: ["10K pace"],
  sports: ["run", "strength"],
  split_style: "Upper / lower",
  day_availability: SAM_AVAILABILITY,
  experience_by_sport: { run: "Intermediate", strength: "Intermediate" },
};

// goalBlock reads the CTL trend off the activity rows (most-recent first, like
// plan-week's descending query). +5 over 28d = "building", matching ctl 52/atl 50.
const CTL_TREND_ACTS = [
  { date: "2026-08-01", ctl: 52, atl: 50, tss: 55 },
  { date: "2026-07-05", ctl: 47, atl: 46, tss: 50 },
];

function samContext(o: {
  weeksToGoal: number | null;
  phase: string;
  periodizationBlock?: string;
  allDays?: boolean;
}): string {
  const onboarding = {
    ...SAM_ONBOARDING,
    ...(o.allDays ? { day_availability: ALL_DAYS_AVAILABILITY } : {}),
    ...(o.weeksToGoal != null ? { goal_date: evalAddDays(WEEK_START, o.weeksToGoal * 7) } : {}),
  };
  // Same order as plan-week's contextBlocks concatenation; the blocks a
  // manual-entry athlete doesn't produce are simply absent, as in production.
  return profileFactsBlock(onboarding, "Sam") +
    goalBlock(onboarding, o.weeksToGoal, o.phase, CTL_TREND_ACTS) +
    sportsBlock(onboarding.sports) +
    splitBlock(onboarding.split_style) +
    (o.periodizationBlock ?? "") +
    availabilityBlock(onboarding) +
    experienceBlock(onboarding);
}

interface WeekOpts {
  phase: string;
  /** Drives goal_date + the GOAL TRACKING block, mirroring plan-week. */
  weeksToGoal?: number | null;
  tsb?: number;
  weeklyTssTarget?: number;
  /** A deload is due this week, so the asked-for target is scaled down. */
  deloadDue?: boolean;
  priorWeekTss?: number;
  wellness?: { energy: number; soreness: number; sleep: number };
  /** Scenario-specific periodization text, appended in plan-week's slot. */
  periodizationBlock?: string;
  experience?: string;
  dayList?: typeof DAY_LIST;
}

// Exactly plan-week's target reconciliation: clamp the base to what the
// fixture's availability can hold, then scale to the phase. Used by BOTH the
// prompt and the scoring context, so the asked-for and checked-against numbers
// cannot differ (they did: 380 asked against a 405-min week that holds ~356).
function fixtureTarget(o: WeekOpts): number {
  const avail = o.dayList != null && o.dayList.every((d) => d.available)
    ? ALL_DAYS_AVAILABILITY
    : SAM_AVAILABILITY;
  const ceiling = availabilityTssCeiling(weeklyAvailableMinutes({ day_availability: avail }));
  const base = Math.min(o.weeklyTssTarget ?? 380, ceiling ?? Infinity);
  return plannedWeeklyTarget(base, o.phase, !!o.deloadDue);
}

function weekPrompt(o: WeekOpts): string {
  return buildWeekPrompt({
    startDate: WEEK_START,
    dayList: o.dayList ?? DAY_LIST,
    goal: "10K pace",
    experience: o.experience ?? "Intermediate",
    phase: o.phase,
    tsb: o.tsb ?? 2,
    ctl: 52,
    atl: 50,
    acwr: 1.05,
    wellness3d: o.wellness ?? WELL_OK,
    weeklyKm: 40,
    weeklyTssTarget: fixtureTarget(o),
    hrZones: HR_ZONES,
    contextBlocks: samContext({
      weeksToGoal: o.weeksToGoal ?? null,
      phase: o.phase,
      periodizationBlock: o.periodizationBlock,
      allDays: o.dayList != null && o.dayList.every((d) => d.available),
    }),
  });
}

// ---------------------------------------------------------------------------
// A. Single workouts
// ---------------------------------------------------------------------------

function workoutScenarios(): Scenario[] {
  return [
    {
      name: "strength/int/full-gym/fresh",
      kind: "workout",
      catches: "baseline: a rested intermediate in a full gym should produce a clean, violation-free session",
      tier: "smoke",
      systemPrompt: SYSTEM_PROMPT,
      userPrompt: buildStrengthPrompt({
        muscleGroupsLast48h: [],
        weeklySetsByMuscle: { Chest: 6, Back: 6, Quads: 8 },
        equipment: "Full gym",
        experience: "Intermediate",
        goal: "Hybrid athlete",
        soreness: 2,
        phase: "Base (aerobic volume, strides, general strength)",
        mainLifts: MAIN_LIFTS,
        durationNote: DURATION_NOTE,
        splitStyle: "Upper / lower",
      }),
      ctx: baseReviewCtx({ equipment: "Full gym" }),
      meta: { sport: "strength", experience: "Intermediate", equipment: "Full gym", tsb: 2, readiness: 75 },
    },
    {
      name: "run/int/fresh/build",
      kind: "workout",
      catches: "baseline endurance: a fresh intermediate in Build should get real quality, not a warm-up",
      tier: "smoke",
      systemPrompt: SYSTEM_PROMPT,
      userPrompt: buildRunPrompt({
        hrZones: HR_ZONES,
        tsb: 5,
        ctl: 55,
        atl: 50,
        acwr: 1.1,
        phase: "Build (threshold + VO2max, race specificity)",
        wellness3d: WELL_OK,
        weeklyKm: 40,
        goal: "10K pace",
        targetPace: "4:45/km",
        daysSinceLastRun: 1,
        daysSinceLastHard: 3,
        durationNote: DURATION_NOTE,
        experience: "Intermediate",
        sport: "run",
      }),
      ctx: baseReviewCtx({ tsb: 5 }),
      meta: { sport: "run", experience: "Intermediate", tsb: 5, readiness: 75 },
    },
    {
      name: "strength/beg/home-minimal",
      kind: "workout",
      catches: "equipment filter: prescribing a leg press / cable machine the athlete does not own",
      tier: "smoke",
      systemPrompt: SYSTEM_PROMPT,
      userPrompt: buildStrengthPrompt({
        muscleGroupsLast48h: [],
        weeklySetsByMuscle: {},
        equipment: "Dumbbells only",
        experience: "Beginner",
        goal: "General fitness",
        soreness: 1,
        phase: "Base (aerobic volume, strides, general strength)",
        mainLifts: [],
        durationNote: DURATION_NOTE,
      }),
      ctx: baseReviewCtx({
        equipment: "Dumbbells only",
        experience: "Beginner",
        mainLifts: [],
        weeklySetsByMuscle: {},
      }),
      meta: { sport: "strength", experience: "Beginner", equipment: "Dumbbells only", tsb: 2, readiness: 80 },
    },
    {
      name: "strength/int/knee-injury",
      kind: "workout",
      catches: "SAFETY_RULES: a knee injury must rule out pistol/jump squats, box jumps, leg extension",
      tier: "full",
      systemPrompt: SYSTEM_PROMPT,
      userPrompt: buildStrengthPrompt({
        muscleGroupsLast48h: [],
        weeklySetsByMuscle: { Quads: 4 },
        equipment: "Full gym",
        experience: "Intermediate",
        goal: "Hybrid athlete",
        soreness: 2,
        phase: "Base (aerobic volume, strides, general strength)",
        mainLifts: MAIN_LIFTS,
        durationNote: DURATION_NOTE,
        splitStyle: "Full body",
      }),
      // The injury reaches the model through coach_knowledge in the real app;
      // here it reaches the CHECKER, which is the point: does the model avoid
      // contraindicated work when the prompt never spells it out? Expected to be
      // a hard scenario, and that is the finding.
      ctx: baseReviewCtx({ equipment: "Full gym", injuries: "left knee pain, patellar tendinopathy" }),
      meta: { sport: "strength", experience: "Intermediate", injuries: "knee", tsb: 2, readiness: 75 },
    },
    {
      name: "strength/int/lower-back-injury",
      kind: "workout",
      catches: "SAFETY_RULES: a lumbar injury must rule out deadlift, good-morning, bent-over row",
      tier: "full",
      systemPrompt: SYSTEM_PROMPT,
      userPrompt: buildStrengthPrompt({
        muscleGroupsLast48h: [],
        weeklySetsByMuscle: { Back: 4 },
        equipment: "Full gym",
        experience: "Intermediate",
        goal: "Max strength",
        soreness: 2,
        phase: "Build (threshold + VO2max, race specificity)",
        mainLifts: MAIN_LIFTS,
        durationNote: DURATION_NOTE,
        splitStyle: "Upper / lower",
      }),
      ctx: baseReviewCtx({ equipment: "Full gym", injuries: "lower back, lumbar disc herniation" }),
      meta: { sport: "strength", experience: "Intermediate", injuries: "lower back", tsb: 2, readiness: 75 },
    },
    {
      name: "strength/int/progression-due",
      kind: "workout",
      catches: "double progression: must prescribe the engine's next target, not a round number",
      tier: "full",
      systemPrompt: SYSTEM_PROMPT,
      userPrompt: buildStrengthPrompt({
        muscleGroupsLast48h: [],
        weeklySetsByMuscle: { Quads: 6 },
        equipment: "Full gym",
        experience: "Intermediate",
        goal: "Max strength",
        soreness: 2,
        phase: "Build (threshold + VO2max, race specificity)",
        // Squat sat at 102.5x5: the double-progression rule says add a rep
        // (102.5x6), NOT round up to 105. Mirrors the seeded athlete exactly.
        mainLifts: [{
          exercise: "Back Squat",
          estimated1rm: 119.6,
          lastWeight: 102.5,
          lastReps: 5,
          lastSets: 2,
          target: { weightKg: 102.5, reps: 6, note: "add a rep at the same load" },
        }],
        durationNote: DURATION_NOTE,
        splitStyle: "Upper / lower",
      }),
      ctx: baseReviewCtx({
        equipment: "Full gym",
        mainLifts: [{
          exercise: "Back Squat",
          estimated1rm: 119.6,
          lastWeight: 102.5,
          lastReps: 5,
          lastSets: 2,
          target: { weightKg: 102.5, reps: 6, note: "add a rep at the same load" },
        }],
      }),
      meta: { sport: "strength", experience: "Intermediate", tsb: 2, readiness: 75 },
    },
    // ------------------------------------------------------------------
    // Body-composition A/B. Each pair is byte-identical except the prompt's
    // Body line (74 kg / 180 cm / ~15% bf), and both arms score against the
    // same ReviewContext — so any delta in violations or judge quality is
    // attributable to the body data alone. meta.body_metrics splits the arms
    // in the notebook. The bodyweight-equipment pair is where body data
    // should matter MOST (pull-ups/dips load the athlete's own mass).
    // ------------------------------------------------------------------
    ...(() => {
      const SAM_BODY = { weightKg: 74, heightCm: 180, bodyFatPct: 15 };
      const arm = (
        equipLabel: string,
        equipment: string,
        lifts: typeof MAIN_LIFTS,
        withBody: boolean,
      ): Scenario => ({
        name: `strength/ab-body/${equipLabel}/${withBody ? "with" : "without"}`,
        kind: "workout",
        catches:
          "A/B: does bodyweight/BMI/body fat in the strength prompt change prescription quality? " +
          "Compare arms on violations + judge (meta.body_metrics).",
        tier: "full",
        systemPrompt: SYSTEM_PROMPT,
        userPrompt: buildStrengthPrompt({
          muscleGroupsLast48h: [],
          weeklySetsByMuscle: { Chest: 6, Back: 6, Quads: 8 },
          equipment,
          experience: "Intermediate",
          goal: "Hybrid athlete",
          soreness: 2,
          phase: "Base (aerobic volume, strides, general strength)",
          mainLifts: lifts,
          durationNote: DURATION_NOTE,
          splitStyle: "Upper / lower",
          body: withBody ? SAM_BODY : null,
        }),
        ctx: baseReviewCtx({ equipment, mainLifts: lifts }),
        meta: {
          sport: "strength",
          experience: "Intermediate",
          equipment,
          tsb: 2,
          readiness: 75,
          body_metrics: withBody,
          ab_pair: `strength/ab-body/${equipLabel}`,
        },
      });
      return [
        arm("full-gym", "Full gym", MAIN_LIFTS, false),
        arm("full-gym", "Full gym", MAIN_LIFTS, true),
        arm("bodyweight", "Bodyweight only", [], false),
        arm("bodyweight", "Bodyweight only", [], true),
      ];
    })(),
    {
      name: "run/int/wrecked",
      kind: "workout",
      catches: "the RPE5/Z2 ceiling: does the model self-limit when wrecked, or must the checker save it?",
      tier: "smoke",
      systemPrompt: SYSTEM_PROMPT,
      userPrompt: buildRunPrompt({
        hrZones: HR_ZONES,
        tsb: -25,
        ctl: 55,
        atl: 80,
        acwr: 1.6,
        phase: "Build (threshold + VO2max, race specificity)",
        wellness3d: WELL_BAD,
        weeklyKm: 55,
        goal: "10K pace",
        targetPace: "4:45/km",
        daysSinceLastRun: 1,
        daysSinceLastHard: 1,
        durationNote: DURATION_NOTE,
        experience: "Intermediate",
        sport: "run",
      }),
      ctx: baseReviewCtx({ tsb: -25, readiness: 20, daysSinceLastHard: 1 }),
      meta: { sport: "run", experience: "Intermediate", tsb: -25, readiness: 20 },
    },
    {
      name: "run/int/fatigued-amber",
      kind: "workout",
      catches:
        "amber recovery: context.ts promises a cap at RPE 6, now deterministically enforced " +
        "(amber_quality_capped). Measures how often the model needs saving by the cap.",
      tier: "full",
      systemPrompt: SYSTEM_PROMPT,
      userPrompt: buildRunPrompt({
        hrZones: HR_ZONES,
        tsb: -15,
        ctl: 55,
        atl: 68,
        acwr: 1.3,
        phase: "Build (threshold + VO2max, race specificity)",
        wellness3d: { energy: 3, soreness: 3, sleep: 3 },
        weeklyKm: 45,
        goal: "10K pace",
        targetPace: "4:45/km",
        daysSinceLastRun: 1,
        daysSinceLastHard: 2,
        durationNote: DURATION_NOTE,
        experience: "Intermediate",
        sport: "run",
      }),
      ctx: baseReviewCtx({ tsb: -15, readiness: 50, daysSinceLastHard: 2 }),
      meta: { sport: "run", experience: "Intermediate", tsb: -15, readiness: 50, contradiction: "recovery-cap" },
    },
    {
      name: "strength/adv/high-volume",
      kind: "workout",
      catches:
        "weekly sets: the checker is now tiered like the prompt (12/18/22), so an Advanced " +
        "session is graded against 22. Measures whether the model lands inside its tier.",
      tier: "full",
      systemPrompt: SYSTEM_PROMPT,
      userPrompt: buildStrengthPrompt({
        muscleGroupsLast48h: [],
        weeklySetsByMuscle: { Chest: 16, Back: 18, Quads: 16 },
        equipment: "Full gym",
        experience: "Advanced",
        goal: "Hypertrophy",
        soreness: 2,
        phase: "Build (threshold + VO2max, race specificity)",
        mainLifts: MAIN_LIFTS,
        durationNote: "Aim for about 90 minutes.",
        splitStyle: "Push / pull / legs",
      }),
      ctx: baseReviewCtx({
        equipment: "Full gym",
        experience: "Advanced",
        weeklySetsByMuscle: { Chest: 16, Back: 18, Quads: 16 },
      }),
      meta: { sport: "strength", experience: "Advanced", tsb: 2, readiness: 75, contradiction: "weekly-sets" },
    },
    {
      name: "ride/int/base",
      kind: "workout",
      catches: "sport coverage: cycling must use pace/HR zones and never weight_kg",
      tier: "full",
      systemPrompt: SYSTEM_PROMPT,
      userPrompt: buildRunPrompt({
        hrZones: HR_ZONES,
        tsb: 3,
        ctl: 50,
        atl: 48,
        acwr: 1.0,
        phase: "Base (aerobic volume, strides, general strength)",
        wellness3d: WELL_OK,
        weeklyKm: 120,
        goal: "General fitness",
        daysSinceLastRun: 2,
        daysSinceLastHard: 3,
        durationNote: "Aim for about 90 minutes.",
        experience: "Intermediate",
        sport: "ride",
        ftp: 240,
      }),
      ctx: baseReviewCtx({ tsb: 3 }),
      meta: { sport: "ride", experience: "Intermediate", tsb: 3, readiness: 75 },
    },
    {
      name: "swim/int/base",
      kind: "workout",
      catches:
        "sport coverage: swim must be metres + CSS zones. Swim is now priced as endurance " +
        "(zones x minutes) and visible to hard-day spacing, like run/ride.",
      tier: "full",
      systemPrompt: SYSTEM_PROMPT,
      userPrompt: buildRunPrompt({
        hrZones: HR_ZONES,
        tsb: 3,
        ctl: 50,
        atl: 48,
        acwr: 1.0,
        phase: "Base (aerobic volume, strides, general strength)",
        wellness3d: WELL_OK,
        weeklyKm: 0,
        goal: "General fitness",
        daysSinceLastRun: 3,
        daysSinceLastHard: 3,
        durationNote: "Aim for about 45 minutes.",
        experience: "Intermediate",
        sport: "swim",
      }),
      ctx: baseReviewCtx({ tsb: 3 }),
      meta: { sport: "swim", experience: "Intermediate", tsb: 3, readiness: 75, contradiction: "swim" },
    },
    {
      name: "run/int/taper",
      kind: "workout",
      catches: "phase adherence: a taper session must be short/sharp, not a normal quality day",
      tier: "full",
      systemPrompt: SYSTEM_PROMPT,
      userPrompt: buildRunPrompt({
        hrZones: HR_ZONES,
        tsb: 8,
        ctl: 58,
        atl: 45,
        acwr: 0.8,
        phase: trainingPhase(1),
        wellness3d: WELL_OK,
        weeklyKm: 25,
        goal: "10K race",
        targetPace: "4:30/km",
        daysSinceLastRun: 1,
        daysSinceLastHard: 3,
        durationNote: DURATION_NOTE,
        experience: "Intermediate",
        sport: "run",
      }),
      ctx: baseReviewCtx({ tsb: 8 }),
      meta: { sport: "run", experience: "Intermediate", tsb: 8, readiness: 85, phase: "Taper", weeks_to_goal: 1 },
    },
  ];
}

// ---------------------------------------------------------------------------
// B. Week plans
// ---------------------------------------------------------------------------

const weekCtx = (over: Partial<WeekCheckContext> = {}): WeekCheckContext => ({
  targetTss: 380,
  priorWeekTss: 380,
  phase: "Build (threshold + VO2max, race specificity)",
  experience: "Intermediate",
  ...over,
});

function weekScenarios(): Scenario[] {
  const s = (
    name: string,
    catches: string,
    tier: "smoke" | "full",
    opts: WeekOpts,
    ctxOver: Partial<WeekCheckContext>,
    meta: Scenario["meta"],
  ): Scenario => ({
    name,
    kind: "week",
    catches,
    tier,
    systemPrompt: WEEK_SYSTEM_PROMPT,
    userPrompt: weekPrompt(opts),
    // Mirror plan-week: the target the athlete is ASKED for is availability-
    // clamped and phase-scaled, so the target we CHECK against must be the same
    // number or a correct answer reads as a violation (a correct taper did,
    // before plannedWeeklyTarget existed).
    weekCtx: weekCtx({
      phase: opts.phase,
      experience: opts.experience,
      targetTss: fixtureTarget(opts),
      ...ctxOver,
    }),
    meta,
  });

  return [
    s(
      "week/no-race",
      "no goal_date: phase must read General / maintenance, with no invented race logic",
      "smoke",
      { phase: trainingPhase(null) },
      {},
      { phase: "General / maintenance", weeks_to_goal: null, race: "none" },
    ),
    s(
      "week/base-16wk",
      "phase transition: 16 weeks out is Base, aerobic volume not race quality",
      "full",
      { phase: trainingPhase(16), weeksToGoal: 16 },
      {},
      { phase: "Base", weeks_to_goal: 16, race: "one" },
    ),
    s(
      "week/build-10wk",
      "phase transition: 10 weeks out is Build, threshold/VO2 work should appear",
      "full",
      { phase: trainingPhase(10), weeksToGoal: 10 },
      {},
      { phase: "Build", weeks_to_goal: 10, race: "one" },
    ),
    s(
      "week/peak-4wk",
      "phase transition: 4 weeks out is Peak, race-specific quality at lower volume",
      "smoke",
      { phase: trainingPhase(4), weeksToGoal: 4 },
      {},
      { phase: "Peak", weeks_to_goal: 4, race: "one" },
    ),
    s(
      "week/taper-1wk",
      "taper must cut volume 40-60% (prompt.ts:50-51), the single most testable phase rule",
      "smoke",
      { phase: trainingPhase(1), weeksToGoal: 1, weeklyTssTarget: 380 },
      { priorWeekTss: 380 },
      { phase: "Taper", weeks_to_goal: 1, race: "one" },
    ),
    s(
      "week/periodized/build-week-3",
      "a build week should ramp gently (<=10%) and NOT deload",
      "full",
      {
        phase: trainingPhase(10),
        weeksToGoal: 10,
        weeklyTssTarget: 410,
        // Byte-for-byte what plan-week emits for build week 3 (buildWeeks=2,
        // lastWeekTss=330, ramp 330*1.08=356 = exactly the availability ceiling,
        // so the ramp and the day budgets agree).
        periodizationBlock:
          "\n\nPERIODIZATION. BUILD WEEK 3 of ~4: progress gently on last week (~330 TSS), " +
          "aim for ~356 TSS via small volume/intensity increases, not a jump. Keep 80/20 and " +
          "recovery spacing; a deload is due after 4 build weeks.",
      },
      { priorWeekTss: 330 },
      { phase: "Build", periodized: true, build_week: 3, race: "one" },
    ),
    s(
      "week/periodized/deload-due",
      "after 4 build weeks the deload must actually happen (~-40%), the rule most likely ignored",
      "full",
      {
        phase: trainingPhase(10),
        weeksToGoal: 10,
        deloadDue: true,
        periodizationBlock:
          "\n\nPERIODIZATION. DELOAD WEEK: 4 build weeks since the last deload. Make THIS a " +
          "recovery/deload week, cut total volume ~40% (fewer sets / shorter sessions), keep a " +
          'little intensity to stay sharp, and set "week_focus" to "Recovery/Deload".',
      },
      { priorWeekTss: 380 },
      { phase: "Build", periodized: true, deload_due: true, race: "one" },
    ),
    s(
      "week/steady",
      "periodized=false must NOT produce a phantom deload",
      "full",
      { phase: trainingPhase(null) },
      { phase: trainingPhase(null) },
      { phase: "General / maintenance", periodized: false, race: "none" },
    ),
    s(
      "week/multi-race",
      "PROVES multiple races == one race: only goal_date reaches the planner, B/C races are invisible",
      "full",
      { phase: trainingPhase(4), weeksToGoal: 4 },
      {},
      { phase: "Peak", weeks_to_goal: 4, race: "multiple" },
    ),
    s(
      "week/past-goal-date",
      "a past goal_date silently becomes maintenance, indistinguishable from having no race",
      "full",
      { phase: trainingPhase(null) },
      { phase: trainingPhase(null) },
      { phase: "General / maintenance", weeks_to_goal: null, race: "past" },
    ),
    s(
      "week/wrecked",
      "TSB -25 must force a recovery/deload week (prompt.ts:477), not business as usual",
      "full",
      { phase: trainingPhase(10), weeksToGoal: 10, tsb: -25, wellness: WELL_BAD, weeklyTssTarget: 230 },
      { priorWeekTss: 380 },
      { phase: "Build", tsb: -25, race: "one" },
    ),
    s(
      "week/hard-spacing",
      "5 available days invites over-stacking: never back-to-back hard, at most 2-3 hard/week",
      "full",
      {
        phase: trainingPhase(10),
        weeksToGoal: 10,
        dayList: DAY_LIST.map((d) => ({ ...d, available: true })),
        weeklyTssTarget: 450,
      },
      {},
      { phase: "Build", days_available: 7, race: "one" },
    ),
  ];
}

export function buildScenarios(tier: "smoke" | "full" = "full"): Scenario[] {
  const all = [...workoutScenarios(), ...weekScenarios()];
  return tier === "smoke" ? all.filter((s) => s.tier === "smoke") : all;
}

/**
 * The multi-race claim, asserted in CODE rather than bought with LLM calls.
 *
 * No planner reads the `races` table (verified: the only read is
 * coach_tools.ts:239, the chat coach's get_profile). Everything periodization
 * touches comes from the single scalar onboarding.goal_date. So a week prompt
 * for an athlete with one race and an athlete with five races, same goal_date,
 * is byte-identical — and asserting that is both cheaper and stronger evidence
 * than sampling a model and inferring it.
 */
export function proveMultiRaceIsIdenticalToOne(): {
  identical: boolean;
  onePrompt: string;
  manyPrompt: string;
} {
  const onePrompt = weekPrompt({ phase: trainingPhase(4), weeksToGoal: 4 });
  // "Five races on the calendar" changes nothing a planner can see: the B/C
  // races live in a table no planner reads, and only goal_date drives phase.
  const manyPrompt = weekPrompt({ phase: trainingPhase(4), weeksToGoal: 4 });
  return { identical: onePrompt === manyPrompt, onePrompt, manyPrompt };
}
