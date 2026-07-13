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
  // null = no reading for the anchored day (Intervals hasn't synced today's
  // value yet). The UI surfaces this explicitly instead of showing a stale one.
  latest: number | null;
  baseline: number;
  deltaPct: number; // (latest - baseline) / baseline
  // The recent values (oldest→newest, last ~14) so the UI can draw a tiny
  // sparkline showing direction at a glance, not just the single delta badge.
  series: number[];
}

// One reason behind the readiness score — surfaced as a chip ("HRV ↑", "Sleep ↓")
// so the number stops being a black box. tone drives the chip colour.
export interface Driver {
  label: string; // "HRV" | "Resting HR" | "Sleep" | "Soreness" | "Energy"
  dir: "up" | "down" | "flat";
  tone: "good" | "bad" | "neutral";
}

export interface Recovery {
  score: number; // 0..100
  band: "green" | "amber" | "red";
  wellness: number; // 1..5 composite (energy, inverted soreness, sleep quality)
  hrv: Trend | null;
  rhr: Trend | null;
  // hours = null when the anchored day has no sleep yet; avgHours is the personal
  // window average (legitimately not "today") so the UI can still show context.
  sleep: { hours: number | null; avgHours: number | null; score: number | null } | null;
  drivers: Driver[]; // why the score is what it is — for the UI breakdown
  summary: string; // one-line human note for prompts / UI
}

const SLEEP_TARGET_H = 7.5;
const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));
const isNum = (v: unknown): v is number => typeof v === "number" && isFinite(v);
const avg = (vals: number[]) => (vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : 0);

// A measurement series element: a bare number (legacy callers — no date anchoring)
// or a dated point so the recovery can pick TODAY's reading specifically.
export type SeriesPoint = number | { date?: string; value: number };
const valOf = (p: SeriesPoint): number => (typeof p === "number" ? p : p.value);
const dateOf = (p: SeriesPoint): string | undefined => (typeof p === "number" ? undefined : p.date);

/**
 * Latest vs the average of everything before it; null if too little data.
 * When `today` is given, "latest" is TODAY's reading specifically (null if that
 * day has no value) and the baseline excludes it — so a not-yet-synced day reads
 * as missing rather than silently inheriting yesterday's number. Without `today`
 * it keeps the legacy "last point in the window" behaviour (generators/coach).
 */
function trend(series: SeriesPoint[], today?: string): Trend | null {
  const s = series.filter((p) => isNum(valOf(p)));
  if (s.length < 2) return null;
  const spark = s.slice(-14).map(valOf); // recent values for the UI sparkline
  if (!today) {
    const latest = valOf(s[s.length - 1]);
    const baseline = avg(s.slice(0, -1).map(valOf));
    return { latest, baseline, deltaPct: baseline ? (latest - baseline) / baseline : 0, series: spark };
  }
  const todayPoint = s.find((p) => dateOf(p) === today);
  const baseline = avg(s.filter((p) => dateOf(p) !== today).map(valOf));
  if (!todayPoint) return { latest: null, baseline, deltaPct: 0, series: spark };
  const latest = valOf(todayPoint);
  return { latest, baseline, deltaPct: baseline ? (latest - baseline) / baseline : 0, series: spark };
}

/**
 * @param wells   wellness rows, NEWEST FIRST (as queried for the dashboard).
 * @param hrvSeries / rhrSeries  chronological (oldest→newest) measurement series,
 *        already resolved from Health Connect or Intervals by the caller. Pass
 *        dated `{date,value}` points (not bare numbers) to enable `today`.
 * @param today   client LOCAL date (YYYY-MM-DD). When set, HRV/RHR/sleep reflect
 *        that day specifically and report null when it has no reading yet.
 */
export function computeRecovery(
  wells: WellnessRow[],
  hrvSeries: SeriesPoint[],
  rhrSeries: SeriesPoint[],
  today?: string,
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

  const hrv = trend(hrvSeries, today);
  const rhr = trend(rhrSeries, today);

  // Sleep: when anchored on `today`, take that day's row specifically (null hours
  // if it hasn't synced) rather than the most recent night with data — so a
  // missing night reads as missing, not as last night. avgHours stays the window
  // average for context. Without `today`, fall back to the latest night (legacy).
  const sleepMins = wells.map((w) => w.zepp_sleep_minutes).filter(isNum);
  const todayWell = today ? wells.find((w) => w.date === today) : undefined;
  const todayMins = today
    ? (isNum(todayWell?.zepp_sleep_minutes) ? todayWell!.zepp_sleep_minutes! : null)
    : (sleepMins.length ? sleepMins[0] : null);
  const todayScore = today
    ? (isNum(todayWell?.sleep_score) ? todayWell!.sleep_score! : null)
    : (wells.map((w) => w.sleep_score).filter(isNum)[0] ?? null);
  const avgHours = sleepMins.length ? +(avg(sleepMins) / 60).toFixed(1) : null;
  const sleep = (sleepMins.length || todayMins != null)
    ? { hours: todayMins != null ? +(todayMins / 60).toFixed(1) : null, avgHours, score: todayScore }
    : null;

  // Composite. Wellness dominates; HRV up is good, RHR up is bad, short sleep
  // hurts. A missing today reading contributes 0 — the score leans on the
  // subjective 3-day wellness rather than pretending yesterday's value is today's.
  const hrvAdj = hrv && hrv.latest != null ? clamp(hrv.deltaPct * 100, -25, 25) : 0;
  const rhrAdj = rhr && rhr.latest != null ? clamp(-rhr.deltaPct * 100, -15, 15) : 0;
  const sleepAdj = sleep && sleep.hours != null
    ? clamp((sleep.hours - SLEEP_TARGET_H) * 6, -12, 8) // each hour short ≈ -6, capped
    : 0;
  const score = clamp(
    Math.round(((wellnessScore - 1) / 4) * 55 + hrvAdj + rhrAdj + sleepAdj) + 22,
    0,
    100,
  );
  const band = score >= 67 ? "green" : score >= 34 ? "amber" : "red";

  // Driver breakdown — the "why" behind the score, as labelled chips. Objective
  // signals only contribute when today actually synced; subjective soreness/energy
  // come from the 3-day wellness composite and surface only when notable.
  const FLAT = 0.03; // |Δ| under 3% reads as "steady", not a real move
  const drivers: Driver[] = [];
  if (hrv && hrv.latest != null) {
    const dir = hrv.deltaPct > FLAT ? "up" : hrv.deltaPct < -FLAT ? "down" : "flat";
    drivers.push({ label: "HRV", dir, tone: dir === "flat" ? "neutral" : dir === "up" ? "good" : "bad" });
  }
  if (rhr && rhr.latest != null) {
    const dir = rhr.deltaPct > FLAT ? "up" : rhr.deltaPct < -FLAT ? "down" : "flat";
    // Resting HR rising is the bad direction.
    drivers.push({ label: "Resting HR", dir, tone: dir === "flat" ? "neutral" : dir === "up" ? "bad" : "good" });
  }
  if (sleep && sleep.hours != null) {
    const d = sleep.hours - SLEEP_TARGET_H;
    const dir = d > 0.5 ? "up" : d < -0.5 ? "down" : "flat";
    drivers.push({ label: "Sleep", dir, tone: d < -0.75 ? "bad" : d >= -0.25 ? "good" : "neutral" });
  }
  const soreAvg = avg(recent3.map((w) => w.soreness ?? 3));
  if (soreAvg >= 3.5) drivers.push({ label: "Soreness", dir: "up", tone: "bad" });
  const energyAvg = avg(recent3.map((w) => w.energy ?? 3));
  if (energyAvg <= 2.5) drivers.push({ label: "Energy", dir: "down", tone: "bad" });
  else if (energyAvg >= 4) drivers.push({ label: "Energy", dir: "up", tone: "good" });

  // Human one-liner: lead with the strongest signal.
  const parts: string[] = [];
  if (hrv && hrv.latest != null) parts.push(`HRV ${hrv.deltaPct >= 0 ? "+" : ""}${Math.round(hrv.deltaPct * 100)}% vs baseline`);
  if (rhr && rhr.latest != null) parts.push(`resting HR ${rhr.deltaPct > 0 ? "+" : ""}${Math.round(rhr.deltaPct * 100)}%`);
  if (sleep && sleep.hours != null) parts.push(`slept ${sleep.hours}h`);
  const summary = (band === "green" ? "Recovered" : band === "amber" ? "Moderately recovered" : "Under-recovered") +
    (parts.length ? `, ${parts.join(", ")}.` : ".");

  return { score, band, wellness: +wellnessScore.toFixed(2), hrv, rhr, sleep, drivers, summary };
}
