// ============================================================================
// week_planning — pure, pre-generation decision logic for plan-week.
//
// Distinct from plan_checks.ts: that file grades an ALREADY-GENERATED WeekPlan
// against week-level rules (post-hoc). This file computes the inputs plan-week
// hands the model BEFORE generation — which dates are available, what this
// week's periodization target is, and the deterministic pause override — so
// that logic is a named, tested function instead of inline arithmetic in
// plan-week/index.ts's ~350-line planForUser. Every function here takes
// already-fetched data as arguments; none of them touch the DB or network.
// ============================================================================

import type { Workout } from "./types.ts";

const WD = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

export function weekdayOf(iso: string): string {
  return WD[new Date(iso + "T12:00:00").getDay()];
}

// ---------------------------------------------------------------------------
// Day availability — the flag buildWeekPrompt already treats as authoritative
// ("UNAVAILABLE, schedule REST", see prompt.ts). A day is available unless the
// athlete doesn't train that weekday, it already holds a locked session, or it
// falls inside an active training pause (see set_training_pause in
// coach_tools.ts). Any future "does X override the schedule" fact should feed
// THIS flag rather than living only in free-text coach_knowledge prose — see
// the comment above memoryDocsBlock in agent_memory.ts for why.
// ---------------------------------------------------------------------------

export interface WeekDayAvailability {
  date: string;
  weekday: string;
  available: boolean;
}

export function computeDayList(
  dates: string[],
  availableDays: string[],
  lockedDates: ReadonlySet<string>,
  pausedUntil: string | null,
): WeekDayAvailability[] {
  return dates.map((date) => ({
    date,
    weekday: weekdayOf(date),
    available: (availableDays.length === 0 ? true : availableDays.includes(weekdayOf(date))) &&
      !lockedDates.has(date) &&
      !(pausedUntil !== null && date <= pausedUntil),
  }));
}

// ---------------------------------------------------------------------------
// Periodization: opt-in build/deload cadence. Off (onboarding.periodized !==
// true) → the caller never calls this, periodizationBlock stays "" as before.
// ---------------------------------------------------------------------------

export interface PeriodizationResult {
  buildWeeks: number;
  deloadDue: boolean;
  rampTss: number;
  targetTss: number;
  block: string; // prompt text spliced into contextBlocks
}

export function computePeriodization(
  // Most-recent-first `focus` strings from week_plans, already fetched.
  recentWeekFocuses: string[],
  lastWeekTss: number,
  availCeiling: number | null,
  weeklyTssTarget: number,
  deloadAfterWeeks: number,
): PeriodizationResult {
  let buildWeeks = 0;
  for (const focus of recentWeekFocuses) {
    if (/deload|recovery/i.test(focus ?? "")) break;
    buildWeeks++;
  }
  const deloadDue = buildWeeks >= deloadAfterWeeks;
  const rampTss = lastWeekTss > 0 ? Math.round(lastWeekTss * 1.08) : Math.round(weeklyTssTarget);
  const targetTss = availCeiling !== null ? Math.min(rampTss, availCeiling) : rampTss;

  const block = deloadDue
    ? `\n\nPERIODIZATION. DELOAD WEEK: ${buildWeeks} build weeks since the last deload. Make THIS a recovery/deload week, cut total volume ~40% (fewer sets / shorter sessions), keep a little intensity to stay sharp, and set "week_focus" to "Recovery/Deload".`
    : `\n\nPERIODIZATION. BUILD WEEK ${buildWeeks + 1} of ~${deloadAfterWeeks}: progress gently on last week (~${lastWeekTss} TSS), aim for ~${targetTss} TSS via small volume/intensity increases, not a jump. Keep 80/20 and recovery spacing; a deload is due after ${deloadAfterWeeks} build weeks.`;

  return { buildWeeks, deloadDue, rampTss, targetTss, block };
}

// ---------------------------------------------------------------------------
// Pause coercion: the deterministic guarantee on top of the day-list signal
// above. Even if the model ignores an "UNAVAILABLE" date, this forces rest
// server-side before the session reaches reviewWorkout/the DB write.
// ---------------------------------------------------------------------------

export function coerceForPause(
  session: Workout,
  date: string,
  pausedUntil: string | null,
  pauseReason: string | null,
): Workout {
  if (pausedUntil === null || date > pausedUntil || session.type === "rest") return session;
  return {
    type: "rest",
    title: "Training pause",
    duration_minutes: 0,
    tss_estimate: 0,
    rpe_target: 0,
    sections: [],
    coach_note: pauseReason ? `Paused: ${pauseReason}.` : "Training paused.",
  };
}
