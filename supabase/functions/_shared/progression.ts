// Server-side mirror of the Android double-progression engine
// (android .../strength/StrengthProgression.kt, Progression.suggest with the
// DOUBLE rule). The generator prescribes THE SAME next-session target the
// in-app "↗ target" shows, so the plan and the logger never disagree.
//
// Rep windows and load increments must stay in lockstep with the app:
// compound 5-8 reps / +2.5 kg, isolation 8-12 reps / +1.25 kg
// (StrengthRepository.progressionFor).

export interface LoggedSet {
  weight_kg?: number | null;
  reps?: number | null;
}

export interface NextTarget {
  weightKg: number;
  reps: number;
  note: string;
}

const fmt = (n: number) => (Number.isInteger(n) ? String(n) : n.toFixed(2).replace(/0+$/, "").replace(/\.$/, ""));

/**
 * Double progression from the last session's working sets (strength_logs rows
 * already exclude warm-ups): add a rep at the top weight until every top-weight
 * set hits the range ceiling, then add load and reset to the range floor.
 */
export function nextTarget(sets: LoggedSet[], compound: boolean): NextTarget | null {
  const work = (sets ?? []).filter((s) => Number(s?.reps ?? 0) > 0);
  if (!work.length) return null;
  const [lo, hi] = compound ? [5, 8] : [8, 12];
  const inc = compound ? 2.5 : 1.25;
  const top = Math.max(...work.map((s) => Number(s.weight_kg ?? 0)));
  const topSets = work.filter((s) => Number(s.weight_kg ?? 0) >= top - 1e-6);
  const hitTop = topSets.every((s) => Number(s.reps) >= hi);
  if (hitTop) {
    return {
      weightKg: top + inc,
      reps: lo,
      note: `hit ${hi} reps everywhere → +${fmt(inc)}kg, reset to ${lo}`,
    };
  }
  const target = Math.min(hi, Math.max(lo, Math.max(...topSets.map((s) => Number(s.reps))) + 1));
  return { weightKg: top, reps: target, note: `add a rep toward ${hi}` };
}
