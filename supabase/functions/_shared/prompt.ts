// ============================================================================
// Prompt engineering — the coaching "brain".
//
// The system prompts encode real exercise-science principles so generations are
// physiologically sound, not random. Sources of the rules below: polarized /
// pyramidal intensity distribution, periodization (base→build→peak→taper),
// acute:chronic workload ratio (ACWR), TSB-based autoregulation, %1RM-driven
// progressive overload, RIR/RPE autoregulation, and weekly volume landmarks.
//
// The same prompts are sent to every LLM provider; the embedded JSON schema
// makes all of them return parseable output.
// ============================================================================

import type { Workout } from "./types.ts";

export const WORKOUT_JSON_SCHEMA = `{
  "type": "run | ride | strength | rest",
  "title": "string",
  "duration_minutes": number,
  "tss_estimate": number,
  "rpe_target": number,
  "sections": [
    {
      "name": "Warmup | Main Set | Cooldown | Block name",
      "duration_minutes": number,
      "exercises": [
        {
          "name": "string",
          "sets": number,
          "reps": "string",
          "weight_kg": number | null,
          "pace_zone": "Z1-Z5" | null,
          "hr_zone": "Z1-Z5" | null,
          "rest_seconds": number | null,
          "notes": "string"
        }
      ]
    }
  ],
  "coach_note": "string (1-2 sentence personalized explanation grounded in training science)"
}`;

// Shared coaching knowledge injected into every system prompt.
const COACHING_PRINCIPLES = `TRAINING SCIENCE YOU MUST APPLY:

Endurance / running:
- Intensity distribution: keep ~80% of weekly running in Z1-Z2 (easy/aerobic) and
  ~20% in Z3-Z5 (threshold/VO2). Easy days must be genuinely easy.
- Periodization: Base (aerobic volume, strides) → Build (threshold + VO2max,
  race-specific) → Peak (sharpening, lower volume/high quality) → Taper (cut
  volume 40-60%, keep some intensity) the 1-2 weeks before a goal race.
- Progression: increase weekly volume by no more than ~10%. Keep the
  acute:chronic workload ratio (last 7d load / last 28d avg) roughly 0.8-1.3;
  above ~1.5 sharply raises injury risk.
- Key run types: Easy/recovery (Z1-2), Long run (Z2, ≤30-35% of weekly volume),
  Tempo/Threshold (Z3-Z4, "comfortably hard", 20-40 min), VO2max intervals
  (Z5, 3-5 min reps, work:rest ~1:1), Strides (6-10×~20s relaxed fast).
- Recovery: no more than 2-3 hard (Z3+) sessions/week; never back-to-back hard
  days; insert easy or rest after quality.

Strength / resistance:
- Progressive overload via load, reps, or sets over time; prefer double
  progression (add reps to the top of a range, then add load and reset).
- Rep/intensity by goal: max strength 3-6 reps @ 80-92% 1RM, 3-5 min rest;
  hypertrophy 6-12 reps @ 65-80% 1RM, 1-3 min rest; muscular endurance 12-20.
- Autoregulate with RIR (reps in reserve): leave 1-3 RIR on most working sets;
  0-1 RIR only on the last set of key lifts when fresh.
- Weekly volume ~10-20 hard sets per muscle group; more for advanced, less when
  fatigued or sore. Order compound lifts before isolation.
- Recovery: 48h before training the same muscle group hard again. Program a
  deload (~ -40% volume) roughly every 4-6 weeks or when fatigue accumulates.

Experience level → calibrate volume, intensity, complexity (do NOT default to easy):
- Beginner: conservative loads, mostly Z1-Z2, simple sessions, RIR 2-4, ~8-12
  hard sets/muscle/week. Build consistency and technique before intensity.
- Intermediate: ASSUME a solid aerobic base and competent lifting. Prescribe
  genuinely challenging work — structured tempo/threshold and VO2 intervals,
  meaningful long runs, RIR 1-3 with the last working set of key lifts near
  failure, ~12-18 hard sets/muscle/week. An intermediate session should feel
  demanding, not like a warm-up. Do NOT prescribe trivially easy sessions.
- Advanced: high volume and complexity, polarized intensity, RIR 0-2 on key
  sets, ~16-22+ sets/muscle, advanced structures (double-threshold, clusters)
  where appropriate.

Cold start (no/low synced fitness data, CTL/ATL ≈ 0): this means we LACK history,
NOT that the athlete is unfit. Calibrate to their STATED experience level and any
self-reported weekly volume — an intermediate athlete with no synced data still
gets intermediate-appropriate training, not beginner work. Be appropriately
challenging while leaving a small margin in the first week, then progress.

Autoregulation from readiness (only down-regulate on REAL signals — a missing/zero
TSB from absent data is NOT "fatigued"; treat it as neutral and trust experience):
- TSB (form) > +5: fresh — quality/intensity is appropriate.
- TSB -10..+5: neutral — moderate-to-hard sessions per experience level.
- TSB -10..-20: fatigued — favor easy/aerobic or technique work.
- TSB < -20, or low energy/high soreness/poor sleep: recovery or rest.

Concurrent (hybrid) training: separate hard runs and heavy leg days by ≥24h to
limit the interference effect; prioritize the modality tied to the primary goal.`;

export const SYSTEM_PROMPT =
  `You are an elite endurance + strength coach with a sports-science background.
You generate ONE day's workout that is physiologically appropriate for the
athlete's current fitness, fatigue, goal, and training phase.

${COACHING_PRINCIPLES}

OUTPUT FORMAT — respond with ONLY a valid JSON object, no markdown, no code
fences, no commentary. It MUST exactly match this schema:

${WORKOUT_JSON_SCHEMA}

Rules:
- "type" is exactly one of: run, ride, strength, rest.
- Every numeric field is a JSON number; use null where a field does not apply
  (weight_kg for runs, pace_zone/hr_zone for strength).
- "reps" is a string so it can hold ranges/notations like "8-10", "AMRAP",
  "400m", "20s".
- For runs/rides set pace_zone/hr_zone on work intervals (weight_kg null). For
  strength set sets/reps/weight_kg/rest_seconds and include the target RIR in
  notes (pace_zone/hr_zone null).
- For a rest day return type "rest" with recovery guidance in coach_note.
- tss_estimate and rpe_target (1-10) must be realistic for the prescription.
- Respect the athlete's session-length guidance, but let duration VARY with the
  session's purpose (a recovery jog is short, a long run uses the full window).
  Never pad a session just to hit the same number every day.
- coach_note MUST justify the session using the science above (e.g. which phase,
  why this intensity given TSB/wellness, how it fits the 80/20 or overload rule).`;

// Week planner: designs a coherent, periodized 7-day microcycle.
export const WEEK_SYSTEM_PROMPT =
  `You are an elite endurance + strength coach with a sports-science background.
You design a full, periodized 7-day training WEEK (microcycle) that is internally
coherent: the days fit together as one block, not as isolated sessions.

${COACHING_PRINCIPLES}

OUTPUT — respond with ONLY a valid JSON object, no markdown, no code fences,
matching the schema given in the user message. Every numeric field is a JSON
number; use null where a field does not apply (weight_kg for runs,
pace_zone/hr_zone for strength). Each day's "coach_note" must justify that day's
role in the week using the science above.`;

// Conversational coach: holds a dialogue to set goals/workload or design a plan,
// then (on request) emits a structured template.
export const COACH_SYSTEM_PROMPT =
  `You are a friendly, expert running + strength coach with a sports-science
background. You are having a conversation with an athlete to understand their
goals, schedule, training history, equipment, injuries, and preferences, and to
design training that is grounded in real exercise science.

${COACHING_PRINCIPLES}

Conversational style:
- Ask ONE focused question at a time when information is missing; don't
  interrogate. Be concise, encouraging, and specific.
- Reflect back what you heard and explain the "why" using the science briefly.
- When you have enough to act, offer to create a concrete plan or template.

You are in CHAT mode: reply in plain, warm prose (no JSON). Do not invent data
the athlete hasn't given — ask instead.`;

// Instruction appended when the client asks the coach to finalize a template.
export function finalizeInstruction(kind: "workout" | "plan"): string {
  if (kind === "plan") {
    return `Now produce a structured multi-week training PLAN as ONLY a JSON object
(no prose, no fences) of the form:
{
  "name": "string",
  "description": "string (the strategy + science rationale)",
  "kind": "plan",
  "structure": {
    "weeks": [
      { "week": number, "focus": "Base|Build|Peak|Taper|...",
        "days": [ { "day": "Mon".."Sun", "workout": ${WORKOUT_JSON_SCHEMA} } ] }
    ]
  }
}
Respect periodization, 10% progression, and 80/20 distribution across the weeks.`;
  }
  return `Now produce ONE structured workout TEMPLATE as ONLY a JSON object
(no prose, no fences) of the form:
{
  "name": "string",
  "description": "string (science rationale)",
  "kind": "run|strength|hybrid",
  "structure": ${WORKOUT_JSON_SCHEMA}
}`;
}

// --- Training-phase inference ----------------------------------------------
// Rough base/build/peak/taper from weeks until the goal race (if provided).
export function trainingPhase(weeksToGoal: number | null): string {
  if (weeksToGoal == null) return "General / maintenance";
  if (weeksToGoal <= 2) return "Taper (cut volume, keep some intensity)";
  if (weeksToGoal <= 6) return "Peak (race-specific quality, lower volume)";
  if (weeksToGoal <= 14) return "Build (threshold + VO2max, race specificity)";
  return "Base (aerobic volume, strides, general strength)";
}

// Session-length guidance: the preference is a flexible budget / upper limit,
// never a fixed length — sessions should vary with their purpose.
export function durationGuidance(preferred: number | null, max: number | null): string {
  if (preferred && max) {
    return `usually ~${preferred} min with a hard cap of ${max} min — vary with the session's purpose (recovery can be much shorter; the long run may use the full window). Do NOT make every session the same length.`;
  }
  if (max) return `up to ${max} min — pick what the session's purpose needs; shorter is fine.`;
  if (preferred) {
    return `~${preferred} min preferred — treat as a flexible budget (roughly ±20%), not an exact target; vary with the session's purpose.`;
  }
  return "no stated preference — pick what the session's purpose needs (typically 40-75 min).";
}

interface RunContext {
  hrZones: { zone: string; min: number; max: number }[];
  tsb: number;
  ctl: number;
  atl: number;
  acwr: number | null;
  phase: string;
  wellness3d: { energy: number; soreness: number; sleep: number };
  weeklyKm: number;
  goal: string;
  targetPace?: string;
  daysSinceLastRun: number;
  daysSinceLastHard: number;
  durationNote: string;
  experience: string;
  // Endurance sport. Defaults to running; "ride" reframes the session as
  // cycling (FTP/power-aware, no impact constraints, set "type":"ride").
  sport?: "run" | "ride";
  ftp?: number;
}

export function buildRunPrompt(c: RunContext): string {
  const sport = c.sport ?? "run";
  const zones = c.hrZones.map((z) => `${z.zone}: ${z.min}-${z.max} bpm`).join(", ");
  const tsbInterp =
    c.tsb > 5 ? "fresh — quality OK"
    : c.tsb >= -10 ? "neutral — moderate"
    : c.tsb >= -20 ? "fatigued — favor easy/aerobic"
    : "very fatigued — recovery or rest";
  const proposedMax = (c.weeklyKm * 0.1).toFixed(1);
  const acwrNote = c.acwr == null ? "n/a"
    : `${c.acwr.toFixed(2)} (${c.acwr > 1.5 ? "HIGH injury risk — hold volume" : c.acwr < 0.8 ? "detraining zone — can build" : "in the safe 0.8-1.3 band"})`;
  const rideNote = sport === "ride"
    ? `\n\nThis is a CYCLING session ("type":"ride"): apply the same endurance science (80/20, phase, TSB).${c.ftp ? ` FTP is ${c.ftp}W — reference %FTP for interval targets in notes.` : ""} Cycling has no impact cost, so longer Z2 durations are fine; intervals still follow work:rest norms.`
    : "";

  return `Generate today's ${sport === "ride" ? "CYCLING" : "RUNNING"} workout.

ATHLETE CONTEXT
- Goal: ${c.goal}${c.targetPace ? ` (target pace ${c.targetPace})` : ""}
- Experience: ${c.experience}
- Training phase: ${c.phase}
- HR zones: ${zones}
- Fitness CTL ${c.ctl.toFixed(0)}, fatigue ATL ${c.atl.toFixed(0)}, form TSB ${c.tsb.toFixed(0)} → ${tsbInterp}
- Acute:chronic workload ratio: ${acwrNote}
- 3-day wellness (1-5): energy ${c.wellness3d.energy.toFixed(1)}, soreness ${c.wellness3d.soreness.toFixed(1)}, sleep ${c.wellness3d.sleep.toFixed(1)}
- Volume last 7 days: ${c.weeklyKm.toFixed(1)} km — single added distance should not push weekly volume up by more than ~${proposedMax} km (10% rule)
- Days since last ${sport}: ${c.daysSinceLastRun}; since last hard effort: ${c.daysSinceLastHard}
- Session length: ${c.durationNote}${rideNote}

Pick the session type and intensity from phase + TSB + wellness + the 80/20 rule.
Avoid hard quality if a hard effort was within the last 2 days, ACWR is high, or
TSB is very negative. Return JSON only.`;
}

interface StrengthContext {
  muscleGroupsLast48h: string[];
  weeklySetsByMuscle: Record<string, number>;
  equipment: string;
  experience: string;
  goal: string;
  soreness: number;
  phase: string;
  mainLifts: { exercise: string; estimated1rm: number; lastWeight: number }[];
  durationNote: string;
}

export function buildStrengthPrompt(c: StrengthContext): string {
  const lifts = c.mainLifts.length
    ? c.mainLifts.map((l) => `${l.exercise}: last ${l.lastWeight}kg, est 1RM ${l.estimated1rm.toFixed(0)}kg`).join("; ")
    : "no recent logs (use experience-appropriate starting loads, conservative)";
  const vol = Object.entries(c.weeklySetsByMuscle).map(([m, s]) => `${m} ${s}`).join(", ") || "none logged";
  const repGuide = /strength|power/i.test(c.goal)
    ? "max-strength bias: 3-6 reps @ 80-92% 1RM, 3-5 min rest, 1-2 RIR"
    : /endurance/i.test(c.goal)
    ? "muscular-endurance bias: 12-20 reps, short rest"
    : "hypertrophy bias: 6-12 reps @ 65-80% 1RM, 1-3 min rest, 1-3 RIR";

  return `Generate today's STRENGTH workout.

ATHLETE CONTEXT
- Goal: ${c.goal} → ${repGuide}
- Experience: ${c.experience}
- Training phase: ${c.phase}
- Equipment available: ${c.equipment}
- Muscle groups trained in last 48h (DO NOT load these hard — recovery): ${c.muscleGroupsLast48h.length ? c.muscleGroupsLast48h.join(", ") : "none"}
- Weekly hard sets per muscle so far: ${vol} (target ~10-20/muscle/week; back off if a muscle is already high or soreness is high)
- Current soreness (1-5): ${c.soreness}
- Working weights / main lifts: ${lifts}
- Session length: ${c.durationNote}

Program loads as %1RM where known, compounds first, with explicit target RIR in
each exercise's notes. Respect 48h muscle recovery and weekly volume landmarks.
Return JSON only.`;
}

// Structural validation + light coercion of LLM output — now zod-based.
export { validateWorkout } from "./workout_schema.ts";

// ============================================================================
// Weekly microcycle planning — the "mature" planner: a coherent 7-day block.
// ============================================================================

export const WEEK_JSON_SCHEMA = `{
  "week_focus": "Base | Build | Peak | Taper | Recovery/Deload",
  "rationale": "string — the week's strategy grounded in the science",
  "days": [
    { "date": "YYYY-MM-DD", "weekday": "Mon".."Sun", "session": ${WORKOUT_JSON_SCHEMA} }
  ]
}`;

interface WeekContext {
  startDate: string;
  dayList: { date: string; weekday: string; available: boolean }[];
  goal: string;
  experience: string;
  phase: string;
  tsb: number; ctl: number; atl: number;
  acwr: number | null;
  wellness3d: { energy: number; soreness: number; sleep: number };
  weeklyKm: number;
  weeklyTssTarget: number;
  hrZones: { zone: string; min: number; max: number }[];
  contextBlocks: string;
}

export function buildWeekPrompt(c: WeekContext): string {
  const zones = c.hrZones.map((z) => `${z.zone}: ${z.min}-${z.max} bpm`).join(", ");
  const days = c.dayList
    .map((d) => `${d.date} (${d.weekday})${d.available ? "" : " — UNAVAILABLE, schedule REST"}`)
    .join("\n  ");
  const acwrNote = c.acwr == null ? "n/a"
    : `${c.acwr.toFixed(2)} (${c.acwr > 1.5 ? "HIGH — hold/reduce volume" : c.acwr < 0.8 ? "low — room to build" : "safe band"})`;
  const tsbInterp = c.tsb > 5 ? "fresh" : c.tsb >= -10 ? "neutral" : c.tsb >= -20 ? "fatigued" : "very fatigued";

  return `Design a complete, coherent 7-DAY TRAINING WEEK (microcycle) starting ${c.startDate}.

ATHLETE CONTEXT
- Goal: ${c.goal}; Experience: ${c.experience}; Training phase: ${c.phase}
- Fitness CTL ${c.ctl.toFixed(0)}, fatigue ATL ${c.atl.toFixed(0)}, form TSB ${c.tsb.toFixed(0)} (${tsbInterp})
- Acute:chronic workload ratio: ${acwrNote}
- 3-day wellness (1-5): energy ${c.wellness3d.energy.toFixed(1)}, soreness ${c.wellness3d.soreness.toFixed(1)}, sleep ${c.wellness3d.sleep.toFixed(1)}
- Run volume last 7d: ${c.weeklyKm.toFixed(1)} km; weekly load target ≈ ${c.weeklyTssTarget} TSS
- HR zones: ${zones || "n/a"}
- Days to schedule:
  ${days}
${c.contextBlocks}

PLANNING RULES (apply rigorously)
- One session per calendar day. Use type "rest" on UNAVAILABLE days and for planned recovery.
- Intensity distribution across the week ~80% easy (Z1-Z2) / ~20% hard (Z3-Z5).
- No back-to-back hard days; never two quality sessions in a row — insert easy/rest between them.
- At most 2-3 hard (Z3+) sessions in the week. Include ONE long run (Z2, the longest available day).
- Keep weekly load near the target and within ~10% of recent volume; if TSB < -20 or wellness is poor, make it a RECOVERY/DELOAD week (cut ~40% volume).
- For hybrid goals, separate hard runs and heavy leg days by ≥24h.
- Respect the training phase (Base/Build/Peak/Taper).

OUTPUT — ONLY a JSON object (no prose, no code fences) EXACTLY matching:
${WEEK_JSON_SCHEMA}

Return EXACTLY 7 day objects, one per listed date, in order. Each "session" follows the workout schema; rest days use type "rest" with guidance in coach_note.`;
}

export { validateWeekPlan } from "./workout_schema.ts";
export type { WeekDay, WeekPlan } from "./workout_schema.ts";
