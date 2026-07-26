// Body-composition trends, read through the athlete's goal.
//
// A single weight reading is nearly meaningless; the signal is the direction
// over weeks, and WHICH direction matters depends on the goal: "Build muscle"
// cares about lean mass rising, "Lose fat" about weight/fat falling, "Body
// recomposition" about both at once. This module turns sparse dated scale
// readings into per-metric slopes (kg or % per week, least squares) plus a
// goal-aware coach-facing summary. Pure: no DB, no dates from the wall clock.

export interface BodyRow {
  date: string; // YYYY-MM-DD
  weight_kg?: number | null;
  body_fat_pct?: number | null;
  lean_mass_kg?: number | null;
}

export type BodyFocus = "muscle" | "fat_loss" | "recomp" | "general";

export interface MetricTrend {
  latest: number;
  latestDate: string;
  /** Change per week over the window; null when too sparse to be honest. */
  slopePerWeek: number | null;
  points: number;
}

export interface BodyTrend {
  focus: BodyFocus;
  weight: MetricTrend | null;
  bodyFat: MetricTrend | null;
  leanMass: MetricTrend | null;
  /** Whether the trend matches the goal; null when no goal or no slope. */
  onTrack: boolean | null;
  /** Plain coach-facing sentence. "" when there is nothing to say. */
  summary: string;
}

const DAY = 86_400_000;
// A slope needs at least this many points spanning at least this many days,
// or day-to-day water-weight noise masquerades as a trend.
const MIN_POINTS = 3;
const MIN_SPAN_DAYS = 14;
const WINDOW_DAYS = 90;

/** Map the athlete's strength goals to what the trend should watch. */
export function bodyFocus(strengthGoals: string[]): BodyFocus {
  const goals = strengthGoals.map((g) => g.toLowerCase());
  const muscle = goals.some((g) => g.includes("build muscle"));
  const fat = goals.some((g) => g.includes("lose fat"));
  if (goals.some((g) => g.includes("recomposition")) || (muscle && fat)) return "recomp";
  if (muscle) return "muscle";
  if (fat) return "fat_loss";
  return "general";
}

function parseMs(date: string): number | null {
  const t = new Date(date + "T12:00:00Z").getTime();
  return Number.isFinite(t) ? t : null;
}

// Least-squares slope in units/week over dated points (already bounds-checked).
function metricTrend(points: { date: string; value: number }[]): MetricTrend | null {
  const dated = points
    .map((p) => ({ ...p, ms: parseMs(p.date) }))
    .filter((p): p is { date: string; value: number; ms: number } => p.ms !== null)
    .sort((a, b) => a.ms - b.ms);
  if (!dated.length) return null;
  const last = dated[dated.length - 1];
  const base: MetricTrend = {
    latest: last.value,
    latestDate: last.date,
    slopePerWeek: null,
    points: dated.length,
  };
  const spanDays = (last.ms - dated[0].ms) / DAY;
  if (dated.length < MIN_POINTS || spanDays < MIN_SPAN_DAYS) return base;

  const xs = dated.map((p) => (p.ms - dated[0].ms) / (7 * DAY)); // weeks
  const ys = dated.map((p) => p.value);
  const n = xs.length;
  const mx = xs.reduce((s, v) => s + v, 0) / n;
  const my = ys.reduce((s, v) => s + v, 0) / n;
  let num = 0;
  let den = 0;
  for (let i = 0; i < n; i++) {
    num += (xs[i] - mx) * (ys[i] - my);
    den += (xs[i] - mx) ** 2;
  }
  if (den === 0) return base;
  return { ...base, slopePerWeek: Math.round((num / den) * 100) / 100 };
}

const inRange = (v: unknown, lo: number, hi: number): v is number =>
  typeof v === "number" && Number.isFinite(v) && v >= lo && v <= hi;

function directionWord(slope: number, unit: string): string {
  if (Math.abs(slope) < 0.05) return "steady";
  const dir = slope > 0 ? "up" : "down";
  return `${dir} ${Math.abs(slope).toFixed(1)} ${unit} per week`;
}

/**
 * Compute goal-aware body trends from dated wellness rows. [today] bounds the
 * window (client local date, per house rules); rows outside the last
 * WINDOW_DAYS days or with out-of-range values are ignored. Lean mass falls
 * back to weight x (1 - fat/100) on days with both readings.
 */
export function computeBodyTrend(
  rows: BodyRow[],
  strengthGoals: string[],
  today: string,
): BodyTrend | null {
  const todayMs = parseMs(today);
  const since = todayMs === null ? null : todayMs - WINDOW_DAYS * DAY;
  const windowed = rows.filter((r) => {
    const ms = parseMs(r.date);
    return ms !== null && (since === null || ms >= since) && (todayMs === null || ms <= todayMs);
  });

  const weightPts = windowed
    .filter((r) => inRange(r.weight_kg, 30, 250))
    .map((r) => ({ date: r.date, value: r.weight_kg as number }));
  const fatPts = windowed
    .filter((r) => inRange(r.body_fat_pct, 3, 60))
    .map((r) => ({ date: r.date, value: r.body_fat_pct as number }));
  const leanPts = windowed
    .map((r) => {
      if (inRange(r.lean_mass_kg, 20, 150)) return { date: r.date, value: r.lean_mass_kg as number };
      // Derived fallback: most BIA scales write weight + fat only.
      if (inRange(r.weight_kg, 30, 250) && inRange(r.body_fat_pct, 3, 60)) {
        return { date: r.date, value: Math.round(r.weight_kg! * (1 - r.body_fat_pct! / 100) * 10) / 10 };
      }
      return null;
    })
    .filter((p): p is { date: string; value: number } => p !== null);

  const weight = metricTrend(weightPts);
  const bodyFat = metricTrend(fatPts);
  const leanMass = metricTrend(leanPts);
  if (!weight && !bodyFat && !leanMass) return null;

  const focus = bodyFocus(strengthGoals);

  const leanSlope = leanMass?.slopePerWeek ?? null;
  const weightSlope = weight?.slopePerWeek ?? null;
  const fatSlope = bodyFat?.slopePerWeek ?? null;
  const onTrack = ((): boolean | null => {
    switch (focus) {
      case "muscle":
        return leanSlope === null ? null : leanSlope > 0;
      case "fat_loss":
        if (weightSlope === null && fatSlope === null) return null;
        return (weightSlope ?? 0) < 0 || (fatSlope ?? 0) < 0;
      case "recomp":
        if (leanSlope === null || fatSlope === null) return null;
        return leanSlope >= 0 && fatSlope <= 0;
      case "general":
        return null;
    }
  })();

  const bits: string[] = [];
  if (weight) {
    bits.push(
      `weight ${weight.latest.toFixed(1)} kg` +
        (weightSlope !== null ? `, ${directionWord(weightSlope, "kg")}` : ""),
    );
  }
  if (bodyFat) {
    bits.push(
      `body fat ${bodyFat.latest.toFixed(1)}%` +
        (fatSlope !== null ? `, ${directionWord(fatSlope, "points")}` : ""),
    );
  }
  if (leanMass) {
    bits.push(
      `lean mass ${leanMass.latest.toFixed(1)} kg` +
        (leanSlope !== null ? `, ${directionWord(leanSlope, "kg")}` : ""),
    );
  }
  const goalWord = focus === "muscle"
    ? "building muscle"
    : focus === "fat_loss"
    ? "losing fat"
    : focus === "recomp"
    ? "recomposition"
    : "";
  const verdict = onTrack === null || !goalWord
    ? ""
    : onTrack
    ? ` The trend matches their goal of ${goalWord}.`
    : ` The trend is NOT matching their goal of ${goalWord} yet.`;
  const summary = bits.length ? `Body trend: ${bits.join("; ")}.${verdict}` : "";

  return { focus, weight, bodyFat, leanMass, onTrack, summary };
}
