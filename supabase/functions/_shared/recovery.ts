// Shared recovery model — turns wellness check-ins + HRV/RHR/sleep series into a
// single 0-100 recovery score AND the trend breakdown behind it. Used by
// daily-summary (dashboard) and the workout generators (so a poor night actually
// down-regulates intensity). Pure + side-effect free so it's testable.

export interface WellnessRow {
  date?: string;
  energy?: number | null;
  soreness?: number | null;
  sleep_score?: number | null; // Intervals.icu device sleep score, 0..100
  zepp_sleep_minutes?: number | null;
  hrv_rmssd?: number | null;
  resting_hr?: number | null;
}

export interface Trend {
  latest: number;
  baseline: number;
  deltaPct: number; // (latest - baseline) / baseline
}

export interface Recovery {
  score: number; // 0..100
  band: "green" | "amber" | "red";
  wellness: number; // 1..5 composite (energy, inverted soreness, sleep quality)
  hrv: Trend | null;
  rhr: Trend | null;
  sleep: { hours: number; avgHours: number | null; score: number | null } | null;
  summary: string; // one-line human note for prompts / UI
}

const SLEEP_TARGET_H = 7.5;
const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));
const isNum = (v: unknown): v is number => typeof v === "number" && isFinite(v);
const avg = (vals: number[]) => (vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : 0);

/** Latest vs the average of everything before it; null if too little data. */
function trend(series: number[]): Trend | null {
  const s = series.filter(isNum);
  if (s.length < 2) return null;
  const latest = s[s.length - 1];
  const baseline = avg(s.slice(0, -1));
  return { latest, baseline, deltaPct: baseline ? (latest - baseline) / baseline : 0 };
}

/**
 * @param wells   wellness rows, NEWEST FIRST (as queried for the dashboard).
 * @param hrvSeries / rhrSeries  chronological (oldest→newest) measurement series,
 *        already resolved from Health Connect or Intervals by the caller.
 */
export function computeRecovery(
  wells: WellnessRow[],
  hrvSeries: number[],
  rhrSeries: number[],
): Recovery {
  const recent3 = wells.slice(0, 3);
  // Sleep on the 1..5 scale comes straight from the device sleep score
  // (0..100 → /20, continuous, e.g. 75 → 3.75); neutral 3 when no data.
  const sleepFive = (w: WellnessRow) =>
    isNum(w.sleep_score) ? clamp(w.sleep_score / 20, 1, 5) : 3;
  const wellnessScore = avg([
    avg(recent3.map((w) => w.energy ?? 3)),
    avg(recent3.map((w) => 6 - (w.soreness ?? 3))), // invert soreness (low = good)
    avg(recent3.map((w) => sleepFive(w))),
  ]) || 3; // 1..5

  const hrv = trend(hrvSeries);
  const rhr = trend(rhrSeries);

  // Sleep duration from the most recent night with data, plus a personal average
  // and the raw device sleep score (0..100) from that latest night.
  const sleepMins = wells.map((w) => w.zepp_sleep_minutes).filter(isNum);
  const sleepScore = wells.map((w) => w.sleep_score).filter(isNum)[0] ?? null;
  const sleep = sleepMins.length
    ? { hours: +(sleepMins[0] / 60).toFixed(1), avgHours: +(avg(sleepMins) / 60).toFixed(1), score: sleepScore }
    : null;

  // Composite. Wellness dominates; HRV up is good, RHR up is bad, short sleep hurts.
  const hrvAdj = hrv ? clamp(hrv.deltaPct * 100, -25, 25) : 0;
  const rhrAdj = rhr ? clamp(-rhr.deltaPct * 100, -15, 15) : 0;
  const sleepAdj = sleep
    ? clamp((sleep.hours - SLEEP_TARGET_H) * 6, -12, 8) // each hour short ≈ -6, capped
    : 0;
  const score = clamp(
    Math.round(((wellnessScore - 1) / 4) * 55 + hrvAdj + rhrAdj + sleepAdj) + 22,
    0,
    100,
  );
  const band = score >= 67 ? "green" : score >= 34 ? "amber" : "red";

  // Human one-liner: lead with the strongest signal.
  const parts: string[] = [];
  if (hrv) parts.push(`HRV ${hrv.deltaPct >= 0 ? "+" : ""}${Math.round(hrv.deltaPct * 100)}% vs baseline`);
  if (rhr) parts.push(`resting HR ${rhr.deltaPct > 0 ? "+" : ""}${Math.round(rhr.deltaPct * 100)}%`);
  if (sleep) parts.push(`slept ${sleep.hours}h`);
  const summary = (band === "green" ? "Recovered" : band === "amber" ? "Moderately recovered" : "Under-recovered") +
    (parts.length ? ` — ${parts.join(", ")}.` : ".");

  return { score, band, wellness: +wellnessScore.toFixed(2), hrv, rhr, sleep, summary };
}
