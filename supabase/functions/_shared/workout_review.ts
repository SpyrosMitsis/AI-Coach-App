// ============================================================================
// Post-generation CONTENT review — the layer that makes the engine trustworthy.
//
// Zod (workout_schema.ts) validates the *shape*. This validates the
// *prescription*: progressive-overload floor, load safety ceiling, weekly volume
// landmarks, 48h muscle recovery, endurance 80/20 / readiness, and an
// independent TSS recompute (the model's self-reported TSS is unreliable and
// feeds next-day ACWR, so we cross-check and replace it when it's way off).
//
// Pure + dependency-light so it's unit-testable without network or an LLM.
// `reviewWorkout` auto-CORRECTS the safe, unambiguous things (load floor/ceiling,
// bad TSS) and FLAGS the judgement calls (volume, recovery, readiness) as
// violations the caller can feed into the self-repair retry.
// ============================================================================

import type { Workout, WorkoutExercise } from "./types.ts";
import { allowedCategories, categoryOfExercise, EXERCISE_CATALOG } from "./exercise_catalog.ts";
import type { NextTarget } from "./progression.ts";

export interface MainLift {
  exercise: string;
  estimated1rm: number;
  lastWeight: number;
  lastReps?: number;
  lastSets?: number;
  // The app's double-progression target for the next session (progression.ts).
  // When set, the review snaps the prescription to it so the plan always says
  // the same thing as the logger's ↗ target.
  target?: NextTarget | null;
}

export interface ReviewContext {
  mainLifts: MainLift[];
  weeklySetsByMuscle: Record<string, number>;
  muscleGroupsLast48h: string[];
  tsb: number;
  daysSinceLastHard: number;
  experience: string;
  // Free-text injuries/constraints (onboarding.injury_history + coach_knowledge)
  // — parsed into structured movement blocks by the safety engine below.
  injuries?: string;
  // Today's recovery/readiness score (0-100). Low readiness + a hard session →
  // the intensity ceiling caps it deterministically (not just a flag).
  readiness?: number;
  // The athlete's equipment tier (onboarding.equipment) — strength lifts whose
  // catalog category isn't available get stripped.
  equipment?: string;
}

export interface ReviewResult {
  violations: string[];
  corrected: Workout;
  // Populated when the independent recompute overrode the model's tss_estimate.
  tssReplaced?: { from: number; to: number };
  // Hard safety blocks (contraindicated movements). These are STRIPPED from
  // `corrected`; if any remain the caller must reject + fall back, never serve.
  unsafe: string[];
}

// --- structured contraindication engine ------------------------------------
// A conservative backstop ON TOP of the free-text coach_knowledge already in the
// prompt: the engine will never SERVE a movement that loads an active injury,
// even if the model ignores the text. Each rule maps an injury pattern to the
// exercise-name patterns it forbids.
interface SafetyRule { when: RegExp; forbid: RegExp; reason: string }
const SAFETY_RULES: SafetyRule[] = [
  {
    when: /lower.?back|low.?back|lumbar|herniat|sciatic|disc|spine|spinal/i,
    forbid: /dead\s?lift|good.?morning|bent.?over\s?row|barbell\s?row|pendlay|sit.?up|jefferson|back\s?extension|hyperextension/i,
    reason: "loads the lumbar spine under flexion",
  },
  {
    when: /knee|patell|acl|mcl|meniscus/i,
    forbid: /pistol|jump\s?squat|box\s?jump|depth\s?jump|plyo|sissy\s?squat|leg\s?extension/i,
    reason: "high or explosive knee-joint stress",
  },
  {
    when: /shoulder|rotator|labrum|ac\s?joint|impinge/i,
    forbid: /behind.?the.?neck|upright\s?row|overhead\s?press|military\s?press|\bdips?\b/i,
    reason: "impingement / unstable overhead shoulder load",
  },
  {
    when: /achilles|calf\s?strain|plantar/i,
    forbid: /jump|plyo|sprint|depth\s?jump|bound/i,
    reason: "explosive ankle/Achilles load",
  },
  {
    when: /wrist|carpal/i,
    forbid: /front\s?squat|barbell\s?curl|\bclean\b|\bsnatch\b/i,
    reason: "wrist extension under load",
  },
];

export function activeSafetyRules(injuries: string): SafetyRule[] {
  if (!injuries || !injuries.trim()) return [];
  return SAFETY_RULES.filter((r) => r.when.test(injuries));
}

// --- helpers (exported for the eval harness) -------------------------------

const norm = (s: string) => s.trim().toLowerCase();

const CATALOG_MUSCLE = new Map(EXERCISE_CATALOG.map((e) => [norm(e.name), e.muscle]));

export function muscleOf(ex: WorkoutExercise): string {
  return (ex.muscle && ex.muscle.trim()) || CATALOG_MUSCLE.get(norm(ex.name)) || "Other";
}

const isWarmupSection = (name: string) => /warm|cool|mobility|activation/i.test(name);

// A working set carries real load/effort (not a warmup, not bodyweight filler).
function workingSets(ex: WorkoutExercise): number {
  return Math.max(0, Math.round(ex.sets || 0));
}

// Highest weekly-volume landmark we allow before flagging junk volume.
const MAX_WEEKLY_SETS = 22;
// Absolute load sanity cap (kg) when we have no 1RM to anchor to — catches
// data-entry disasters (e.g. a "300 kg" curl) without guessing bodyweight.
const ABSOLUTE_LOAD_CAP_KG = 400;

// Zone → TSS-per-minute (TRIMP-style). Calibrated so ~60 min Z2 ≈ 50 TSS and an
// hour at threshold ≈ ~75-100. Used only as an independent cross-check.
const ZONE_TSS_PER_MIN: Record<string, number> = {
  Z1: 0.5,
  Z2: 0.8,
  Z3: 1.2,
  Z4: 1.7,
  Z5: 2.2,
};

function zoneOf(ex: WorkoutExercise): string | null {
  const z = ex.hr_zone || ex.pace_zone;
  if (!z) return null;
  const m = z.match(/z\s*([1-5])/i);
  return m ? `Z${m[1]}` : null;
}

const zoneIsHard = (z: string | null) => z === "Z3" || z === "Z4" || z === "Z5";

/** Is this endurance session a hard/quality effort (Z3+ work or RPE ≥ 7)? */
export function isHardSession(w: Workout): boolean {
  if (w.type !== "run" && w.type !== "ride") return false;
  if (w.rpe_target >= 7) return true;
  return w.sections.some((s) => s.exercises.some((ex) => zoneIsHard(zoneOf(ex))));
}

/** Independent TSS estimate so a bad model self-report can't poison ACWR. */
export function computeTss(w: Workout): number {
  if (w.type === "rest") return 0;

  if (w.type === "run" || w.type === "ride") {
    let tss = 0;
    for (const sec of w.sections) {
      for (const ex of sec.exercises) {
        // Minutes for this piece: explicit section split across its exercises,
        // falling back to the section duration when exercises don't carry time.
        const mins = sec.duration_minutes && sec.exercises.length
          ? sec.duration_minutes / sec.exercises.length
          : 0;
        const z = zoneOf(ex) ?? "Z2";
        tss += mins * (ZONE_TSS_PER_MIN[z] ?? ZONE_TSS_PER_MIN.Z2);
      }
    }
    // Fall back to a duration estimate if sections carried no minutes.
    if (tss === 0 && w.duration_minutes > 0) tss = w.duration_minutes * ZONE_TSS_PER_MIN.Z2;
    return Math.round(tss);
  }

  // Strength: working sets drive load. ~3.3 TSS/working set is a defensible
  // mid-intensity heuristic (≈ 15-20 sets → 50-66 TSS for a typical session).
  let sets = 0;
  for (const sec of w.sections) {
    if (isWarmupSection(sec.name)) continue;
    for (const ex of sec.exercises) sets += workingSets(ex);
  }
  return Math.round(Math.min(120, sets * 3.3));
}

// --- main review -----------------------------------------------------------

export function reviewWorkout(w: Workout, ctx: ReviewContext): ReviewResult {
  const violations: string[] = [];
  const unsafe: string[] = [];
  // Deep-ish clone so corrections don't mutate the caller's object.
  const corrected: Workout = {
    ...w,
    sections: w.sections.map((s) => ({ ...s, exercises: s.exercises.map((e) => ({ ...e })) })),
  };

  // SAFETY FIRST — strip any contraindicated movement, whatever the type. The
  // engine must never serve a movement that loads an active injury.
  const rules = activeSafetyRules(ctx.injuries ?? "");
  if (rules.length) {
    for (const sec of corrected.sections) {
      sec.exercises = sec.exercises.filter((ex) => {
        const hit = rules.find((r) => r.forbid.test(ex.name));
        if (hit) {
          const msg = `${ex.name}: contraindicated, ${hit.reason} (injury on file)`;
          unsafe.push(msg);
          violations.push(msg);
          return false; // remove it
        }
        return true;
      });
    }
    // Drop sections emptied by the safety strip.
    corrected.sections = corrected.sections.filter((s) => s.exercises.length > 0);
  }

  // EQUIPMENT — never serve a strength lift the athlete can't perform with their
  // gear. Only KNOWN catalog lifts are filtered (reworded/unknown names pass), and
  // only when the equipment tier is recognised (else fail open). Flagged too, so
  // the caller's repair pass regenerates an equipment-appropriate session.
  const allowed = allowedCategories(ctx.equipment);
  if (allowed && w.type === "strength") {
    for (const sec of corrected.sections) {
      sec.exercises = sec.exercises.filter((ex) => {
        const cat = categoryOfExercise(ex.name);
        if (cat && !allowed.has(cat)) {
          violations.push(`${ex.name}: needs ${cat}, not in the athlete's equipment (${ctx.equipment}), removed`);
          return false;
        }
        return true;
      });
    }
    corrected.sections = corrected.sections.filter((s) => s.exercises.length > 0);
  }

  if (w.type === "strength") {
    const liftByName = new Map(ctx.mainLifts.map((l) => [norm(l.exercise), l]));
    const prescribedSetsByMuscle: Record<string, number> = {};

    for (const sec of corrected.sections) {
      const warm = isWarmupSection(sec.name);
      for (const ex of sec.exercises) {
        const lift = liftByName.get(norm(ex.name));

        // Progression-engine SNAP: a logged lift's working prescription is the
        // app's double-progression target, verbatim — the athlete sees one
        // number in the plan and the logger. Skipped for warm-up sections and
        // muscles in 48h recovery (deliberate back-off stays allowed there).
        if (lift?.target && !warm && typeof ex.weight_kg === "number" &&
            !ctx.muscleGroupsLast48h.includes(muscleOf(ex))) {
          const t = lift.target;
          const repsNum = Number((ex.reps ?? "").match(/\d+/)?.[0] ?? NaN);
          if (Math.abs(ex.weight_kg - t.weightKg) > 1e-6 || repsNum !== t.reps) {
            violations.push(
              `${ex.name}: prescribed ${ex.weight_kg}kg×${ex.reps} ≠ progression target ${t.weightKg}kg×${t.reps}, snapped to the engine target`,
            );
            ex.weight_kg = t.weightKg;
            ex.reps = String(t.reps);
          }
        }

        // Progressive-overload FLOOR: never program below the athlete's last top
        // set (unless that muscle is in 48h recovery — handled separately).
        if (lift && typeof ex.weight_kg === "number" && lift.lastWeight > 0 &&
            ex.weight_kg < lift.lastWeight - 1e-6 &&
            !ctx.muscleGroupsLast48h.includes(muscleOf(ex))) {
          violations.push(
            `${ex.name}: prescribed ${ex.weight_kg}kg is below last top set ${lift.lastWeight}kg, raised to ${lift.lastWeight}kg`,
          );
          ex.weight_kg = lift.lastWeight;
        }

        // Safety CEILING: clamp implausible loads.
        if (typeof ex.weight_kg === "number") {
          if (lift && lift.estimated1rm > 0 && ex.weight_kg > lift.estimated1rm * 1.5) {
            const cap = Math.round(lift.estimated1rm * 1.5);
            violations.push(`${ex.name}: ${ex.weight_kg}kg exceeds 1.5× est 1RM, clamped to ${cap}kg`);
            ex.weight_kg = cap;
          } else if (ex.weight_kg > ABSOLUTE_LOAD_CAP_KG) {
            violations.push(`${ex.name}: ${ex.weight_kg}kg exceeds the ${ABSOLUTE_LOAD_CAP_KG}kg sanity cap, clamped`);
            ex.weight_kg = ABSOLUTE_LOAD_CAP_KG;
          }
        }

        // 48h recovery: flag hard loading of a recently-trained muscle.
        const muscle = muscleOf(ex);
        if (!warm && ctx.muscleGroupsLast48h.includes(muscle) && (ex.weight_kg ?? 0) > 0) {
          violations.push(`${ex.name}: loads ${muscle}, trained in the last 48h, should be recovering`);
        }

        if (!warm) prescribedSetsByMuscle[muscle] = (prescribedSetsByMuscle[muscle] ?? 0) + workingSets(ex);
      }
    }

    // Weekly volume landmark: prescribed + already-done must not blow past ~22.
    for (const [muscle, prescribed] of Object.entries(prescribedSetsByMuscle)) {
      const total = (ctx.weeklySetsByMuscle[muscle] ?? 0) + prescribed;
      if (total > MAX_WEEKLY_SETS) {
        violations.push(`${muscle}: ${total} weekly sets exceeds the ~${MAX_WEEKLY_SETS}-set landmark (junk volume)`);
      }
    }
  }

  // Endurance readiness: a hard session on a depleted athlete is a red flag.
  if (isHardSession(corrected)) {
    if (ctx.tsb < -20) {
      violations.push(`hard session prescribed while very fatigued (TSB ${ctx.tsb.toFixed(0)}), favor easy/recovery`);
    }
    if (ctx.daysSinceLastHard <= 1) {
      violations.push(`hard session ${ctx.daysSinceLastHard}d after the last hard effort, back-to-back quality`);
    }
  }

  // INTENSITY CEILING — a hard endurance session on a wrecked athlete is CAPPED,
  // not just flagged: when readiness is low or TSB deeply negative, downgrade hard
  // zones to easy and cap effort. Final deterministic safety, even if the model
  // (and the repair pass) ignored the readiness signal. Runs before the TSS
  // cross-check below so the recompute reflects the downgraded zones.
  const wrecked = (typeof ctx.readiness === "number" && ctx.readiness < 35) || ctx.tsb < -20;
  if (wrecked && isHardSession(corrected)) {
    for (const sec of corrected.sections) {
      for (const ex of sec.exercises) {
        if (zoneIsHard(zoneOf(ex))) {
          if (ex.hr_zone) ex.hr_zone = "Z2";
          if (ex.pace_zone) ex.pace_zone = "Z2";
        }
      }
    }
    if (corrected.rpe_target > 5) corrected.rpe_target = 5;
    violations.push(
      `low readiness (${ctx.readiness ?? "?"}/100) / TSB ${ctx.tsb.toFixed(0)}, capped to easy aerobic (Z2, RPE ≤5)`,
    );
  }

  // TSS cross-check — ENDURANCE ONLY. The zone × duration model is a defensible
  // independent estimate for runs/rides, so a wildly wrong self-report (>25% off)
  // gets replaced before it poisons next-day ACWR. Strength TSS is genuinely hard
  // to estimate from sets alone, so we don't override the model there — we only
  // sanity-bound it below.
  let tssReplaced: { from: number; to: number } | undefined;
  if (corrected.type === "run" || corrected.type === "ride") {
    const computed = computeTss(corrected);
    const reported = corrected.tss_estimate;
    if (computed > 0 && Math.abs(reported - computed) / Math.max(computed, 1) > 0.25) {
      violations.push(`tss_estimate ${reported} differs from computed ${computed} by >25%, replaced`);
      corrected.tss_estimate = computed;
      tssReplaced = { from: reported, to: computed };
    }
  } else if (corrected.type === "strength" && corrected.tss_estimate > 150) {
    // A strength session rarely exceeds ~150 TSS; clamp obvious overestimates.
    violations.push(`strength tss_estimate ${corrected.tss_estimate} implausibly high, clamped to 150`);
    tssReplaced = { from: corrected.tss_estimate, to: 150 };
    corrected.tss_estimate = 150;
  }

  return { violations, corrected, tssReplaced, unsafe };
}
