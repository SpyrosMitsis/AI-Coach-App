// ============================================================================
// Post-workout execution analysis — the pure, testable core.
//
// Compares what the athlete actually did (Intervals.icu streams + summary)
// against the planned session, Garmin-style: an execution score built from
// duration / load / intensity adherence, a downsampled pace+HR series with
// the target pace band for charting, and per-km splits.
// ============================================================================

import type { Workout } from "./types.ts";

export interface AnalysisComponent {
  name: string;
  score: number; // 0-100
  detail: string;
}

export interface AnalysisSeries {
  t: number[]; // seconds since start
  pace: (number | null)[]; // sec/km (null when not moving)
  hr: (number | null)[];
}

export interface AnalysisTarget {
  pace_lo: number | null; // sec/km — faster bound of the planned band
  pace_hi: number | null; // sec/km — slower bound
  hr_lo: number | null;
  hr_hi: number | null;
  zones: string; // e.g. "Z2" or "Z3-Z4"
}

export interface AnalysisSplit {
  km: number;
  sec: number;
  avg_hr: number | null;
}

// --- small utils -------------------------------------------------------------

export function parsePaceToSec(p: string | null | undefined): number | null {
  if (!p) return null;
  const m = String(p).trim().match(/^(\d{1,2}):(\d{2})/);
  if (!m) return null;
  return Number(m[1]) * 60 + Number(m[2]);
}

export function fmtPace(sec: number): string {
  const m = Math.floor(sec / 60);
  const s = Math.round(sec % 60);
  return `${m}:${s.toString().padStart(2, "0")}/km`;
}

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

// --- planned-session introspection --------------------------------------------

// Every pace/HR zone number referenced by the planned workout's WORK intervals.
// Warmup/cooldown zones are ignored when there is a main set, so the target
// band reflects the work the session was actually about.
export function plannedZones(w: Workout, kind: "pace_zone" | "hr_zone"): number[] {
  const pull = (sectionFilter: (name: string) => boolean): number[] => {
    const out: number[] = [];
    for (const s of w.sections ?? []) {
      if (!sectionFilter(s.name ?? "")) continue;
      for (const ex of s.exercises ?? []) {
        const z = kind === "pace_zone" ? ex.pace_zone : ex.hr_zone;
        if (typeof z !== "string") continue;
        for (const m of z.matchAll(/Z?(\d)/gi)) out.push(Number(m[1]));
      }
    }
    return out.filter((n) => n >= 1 && n <= 5);
  };
  const main = pull((n) => !/warm|cool/i.test(n));
  return main.length ? main : pull(() => true);
}

// % of threshold SPEED per zone (typical 5-zone running model). The pace band
// for a zone span is derived from the athlete's threshold pace.
const ZONE_SPEED_PCT: Record<number, { lo: number; hi: number }> = {
  1: { lo: 55, hi: 78 },
  2: { lo: 78, hi: 88 },
  3: { lo: 88, hi: 95 },
  4: { lo: 95, hi: 102 },
  5: { lo: 102, hi: 115 },
};

// thresholdSecPerKm: athlete's ~1h race pace. Returns [fasterBound, slowerBound]
// in sec/km for the zone span, or null without a threshold.
export function paceBandForZones(
  zones: number[],
  thresholdSecPerKm: number | null,
): { lo: number; hi: number } | null {
  if (!zones.length || !thresholdSecPerKm) return null;
  const minZ = Math.min(...zones);
  const maxZ = Math.max(...zones);
  const fastPct = ZONE_SPEED_PCT[maxZ].hi; // fastest speed → lowest sec/km
  const slowPct = ZONE_SPEED_PCT[minZ].lo;
  return {
    lo: Math.round(thresholdSecPerKm / (fastPct / 100)),
    hi: Math.round(thresholdSecPerKm / (slowPct / 100)),
  };
}

export function hrBandForZones(
  zones: number[],
  hrZones: { zone: string; min: number; max: number }[],
): { lo: number; hi: number } | null {
  if (!zones.length || !hrZones.length) return null;
  const byNum = (n: number) =>
    hrZones.find((z) => z.zone.replace(/\D/g, "") === String(n)) ?? null;
  const lo = byNum(Math.min(...zones));
  const hi = byNum(Math.max(...zones));
  if (!lo || !hi) return null;
  return { lo: lo.min, hi: hi.max };
}

// --- streams → series / splits -------------------------------------------------

export interface RawStreams {
  time: number[];
  velocity: (number | null)[];
  hr: (number | null)[];
  distance: (number | null)[];
}

// Downsample to ≤ maxPoints, converting velocity (m/s) to pace (sec/km).
// Velocities under ~0.5 m/s (standing) become null gaps instead of absurd paces.
export function buildSeries(s: RawStreams, maxPoints = 120): AnalysisSeries {
  const n = s.time.length;
  if (!n) return { t: [], pace: [], hr: [] };
  const stride = Math.max(1, Math.ceil(n / maxPoints));
  const t: number[] = [];
  const pace: (number | null)[] = [];
  const hr: (number | null)[] = [];
  for (let i = 0; i < n; i += stride) {
    // Average the window for a smoother line than point-sampling.
    let vSum = 0, vN = 0, hSum = 0, hN = 0;
    for (let j = i; j < Math.min(i + stride, n); j++) {
      const v = s.velocity[j];
      if (typeof v === "number" && v > 0.5) { vSum += v; vN++; }
      const h = s.hr[j];
      if (typeof h === "number" && h > 0) { hSum += h; hN++; }
    }
    t.push(s.time[i]);
    pace.push(vN ? Math.round(1000 / (vSum / vN)) : null);
    hr.push(hN ? Math.round(hSum / hN) : null);
  }
  return { t, pace, hr };
}

export function buildSplits(s: RawStreams): AnalysisSplit[] {
  const out: AnalysisSplit[] = [];
  let nextKm = 1000;
  let lastT = 0;
  let hrSum = 0, hrN = 0;
  for (let i = 0; i < s.time.length; i++) {
    const d = s.distance[i];
    const h = s.hr[i];
    if (typeof h === "number" && h > 0) { hrSum += h; hrN++; }
    if (typeof d !== "number" || d < nextKm) continue;
    out.push({
      km: nextKm / 1000,
      sec: Math.round(s.time[i] - lastT),
      avg_hr: hrN ? Math.round(hrSum / hrN) : null,
    });
    lastT = s.time[i];
    nextKm += 1000;
    hrSum = 0;
    hrN = 0;
  }
  return out;
}

// --- scoring -------------------------------------------------------------------

// Adherence of an actual value to its planned value: 100 at exact, ~75 at 10%
// off, 0 at 40%+ off.
export function adherenceScore(actual: number, planned: number): number {
  if (planned <= 0) return 0;
  return Math.round(clamp(100 - Math.abs(1 - actual / planned) * 250, 0, 100));
}

// Fraction of moving samples inside the target pace band (with a small grace
// margin) → 0-100. A run that's ~80% in-band is excellent in practice.
export function paceInBandScore(
  pace: (number | null)[],
  band: { lo: number; hi: number },
): { score: number; frac: number } {
  const lo = band.lo * 0.97;
  const hi = band.hi * 1.03;
  let inBand = 0, total = 0;
  for (const p of pace) {
    if (p == null) continue;
    total++;
    if (p >= lo && p <= hi) inBand++;
  }
  if (!total) return { score: 0, frac: 0 };
  const frac = inBand / total;
  return { score: Math.round(clamp(frac * 125, 0, 100)), frac };
}

export function scoreLabel(score: number): string {
  if (score >= 90) return "Executed to plan";
  if (score >= 75) return "Solid execution";
  if (score >= 55) return "Drifted from plan";
  return "Off plan";
}

// Weighted combination of whichever components exist. The component that
// captures the session's purpose counts double: Intensity for endurance,
// Coverage (planned exercises completed) for strength.
export function combineScore(components: AnalysisComponent[]): number | null {
  if (!components.length) return null;
  let sum = 0, wsum = 0;
  for (const c of components) {
    const w = c.name === "Intensity" || c.name === "Coverage" ? 2 : 1;
    sum += c.score * w;
    wsum += w;
  }
  return Math.round(sum / wsum);
}
