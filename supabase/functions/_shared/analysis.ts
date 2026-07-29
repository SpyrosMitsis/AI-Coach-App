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
  cadence: (number | null)[]; // spm/rpm (null when not recorded)
  power: (number | null)[]; // watts (null when not recorded)
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
  // Whether the split landed in the planned target band (null = no plan/band).
  in_band?: boolean | null;
}

// --- small utils -------------------------------------------------------------

export function parsePaceToSec(p: string | null | undefined): number | null {
  if (!p) return null;
  const m = String(p).trim().match(/^(\d{1,2}):(\d{2})/);
  if (!m) return null;
  return Number(m[1]) * 60 + Number(m[2]);
}

// Swims are paced per 100 m (CSS-anchored), everything else per km.
export type PaceUnit = "/km" | "/100m";

export function fmtPace(sec: number, unit: PaceUnit = "/km"): string {
  const m = Math.floor(sec / 60);
  const s = Math.round(sec % 60);
  return `${m}:${s.toString().padStart(2, "0")}${unit}`;
}

export function isSwimType(type: string | null | undefined): boolean {
  return /swim/i.test(type ?? "");
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

// %CSS speed per swim zone. Swimming compresses the speed range (drag grows
// with the square of speed), so zones sit much closer to CSS than run zones.
const SWIM_ZONE_SPEED_PCT: Record<number, { lo: number; hi: number }> = {
  1: { lo: 65, hi: 80 },
  2: { lo: 80, hi: 88 },
  3: { lo: 88, hi: 96 },
  4: { lo: 96, hi: 103 },
  5: { lo: 103, hi: 112 },
};

// thresholdSec: the athlete's ~1h race pace in sec per unit distance (per km
// for run/ride, per 100 m for swim, matching `swim`). Returns [fasterBound,
// slowerBound] in the same unit for the zone span, or null without a threshold.
export function paceBandForZones(
  zones: number[],
  thresholdSec: number | null,
  swim = false,
): { lo: number; hi: number } | null {
  if (!zones.length || !thresholdSec) return null;
  const table = swim ? SWIM_ZONE_SPEED_PCT : ZONE_SPEED_PCT;
  const minZ = Math.min(...zones);
  const maxZ = Math.max(...zones);
  const fastPct = table[maxZ].hi; // fastest speed → lowest sec
  const slowPct = table[minZ].lo;
  return {
    lo: Math.round(thresholdSec / (fastPct / 100)),
    hi: Math.round(thresholdSec / (slowPct / 100)),
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
  cadence?: (number | null)[];
  power?: (number | null)[];
}

// Downsample to ≤ maxPoints, converting velocity (m/s) to pace: sec/km for
// land sports, sec/100m for swims (which also lowers the "not moving" cutoff,
// since a comfortable swim pace can sit below a walking cutoff in m/s).
export function buildSeries(s: RawStreams, maxPoints = 120, swim = false): AnalysisSeries {
  const n = s.time.length;
  if (!n) return { t: [], pace: [], hr: [], cadence: [], power: [] };
  const minV = swim ? 0.25 : 0.5;
  const paceMeters = swim ? 100 : 1000;
  const stride = Math.max(1, Math.ceil(n / maxPoints));
  const t: number[] = [];
  const pace: (number | null)[] = [];
  const hr: (number | null)[] = [];
  const cadence: (number | null)[] = [];
  const power: (number | null)[] = [];
  for (let i = 0; i < n; i += stride) {
    // Average the window for a smoother line than point-sampling.
    let vSum = 0, vN = 0, hSum = 0, hN = 0, cSum = 0, cN = 0, pSum = 0, pN = 0;
    for (let j = i; j < Math.min(i + stride, n); j++) {
      const v = s.velocity[j];
      if (typeof v === "number" && v > minV) { vSum += v; vN++; }
      const h = s.hr[j];
      if (typeof h === "number" && h > 0) { hSum += h; hN++; }
      const c = s.cadence?.[j];
      if (typeof c === "number" && c > 0) { cSum += c; cN++; }
      const p = s.power?.[j];
      if (typeof p === "number" && p > 0) { pSum += p; pN++; }
    }
    t.push(s.time[i]);
    pace.push(vN ? Math.round(paceMeters / (vSum / vN)) : null);
    hr.push(hN ? Math.round(hSum / hN) : null);
    cadence.push(cN ? Math.round(cSum / cN) : null);
    power.push(pN ? Math.round(pSum / pN) : null);
  }
  return { t, pace, hr, cadence, power };
}

// Splits every [splitMeters] (1 km for land sports, 100 m for swims). `km` is
// the cumulative distance in km at the end of the split, so 100 m swim splits
// read 0.1, 0.2, ... and the client renders "100 m", "200 m".
export function buildSplits(s: RawStreams, splitMeters = 1000): AnalysisSplit[] {
  const out: AnalysisSplit[] = [];
  let next = splitMeters;
  let lastT = 0;
  let hrSum = 0, hrN = 0;
  for (let i = 0; i < s.time.length; i++) {
    const d = s.distance[i];
    const h = s.hr[i];
    if (typeof h === "number" && h > 0) { hrSum += h; hrN++; }
    if (typeof d !== "number" || d < next) continue;
    out.push({
      km: next / 1000,
      sec: Math.round(s.time[i] - lastT),
      avg_hr: hrN ? Math.round(hrSum / hrN) : null,
    });
    lastT = s.time[i];
    next += splitMeters;
    hrSum = 0;
    hrN = 0;
  }
  return out;
}

// --- session-shape insights -----------------------------------------------------
//
// Deterministic observations about HOW the session unfolded, computed from the
// splits/series and handed to the feedback LLM as facts. This is what keeps the
// feedback specific: the model gets "second half 3.2% slower, HR drifted +7"
// instead of having to eyeball a splits list.
export function pacingInsights(splits: AnalysisSplit[], series: AnalysisSeries | null): string[] {
  const out: string[] = [];

  if (splits.length >= 4) {
    const half = Math.floor(splits.length / 2);
    const avg = (xs: AnalysisSplit[]) => xs.reduce((s, x) => s + x.sec, 0) / xs.length;
    const first = avg(splits.slice(0, half));
    const second = avg(splits.slice(splits.length - half));
    const pct = ((second - first) / first) * 100;
    if (Math.abs(pct) >= 1) {
      out.push(
        pct < 0
          ? `negative split, second half ${Math.abs(pct).toFixed(1)}% faster than the first`
          : `positive split, second half ${pct.toFixed(1)}% slower than the first`,
      );
    } else {
      out.push("even split, halves within 1% of each other");
    }

    // Pacing evenness: spread of split times around their mean.
    const secs = splits.map((s) => s.sec);
    const mean = secs.reduce((a, b) => a + b, 0) / secs.length;
    const sd = Math.sqrt(secs.reduce((a, b) => a + (b - mean) ** 2, 0) / secs.length);
    const cv = (sd / mean) * 100;
    out.push(
      cv <= 3
        ? `very even pacing (splits vary ${cv.toFixed(1)}%)`
        : cv <= 6
        ? `moderately even pacing (splits vary ${cv.toFixed(1)}%)`
        : `ragged pacing (splits vary ${cv.toFixed(1)}%)`,
    );
  }

  // Cardiac drift: HR in the second half vs the first, moving samples only.
  const hrs = (series?.hr ?? []).filter((h): h is number => typeof h === "number");
  if (hrs.length >= 20) {
    const half = Math.floor(hrs.length / 2);
    const avg = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / xs.length;
    const drift = avg(hrs.slice(half)) - avg(hrs.slice(0, half));
    if (Math.abs(drift) >= 2) {
      out.push(`heart rate ${drift > 0 ? "drifted up" : "came down"} ${Math.abs(Math.round(drift))} bpm from first half to second`);
    }
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

// Mark each split in/out of the planned target band, using the same ±3% grace as
// paceInBandScore. Prefers the pace band (split.sec IS the per-km pace); falls back
// to the HR band on split.avg_hr. Mutates splits in place and returns the on-target
// count. With no band, leaves in_band null and returns 0.
export function markSplitsInBand(
  splits: AnalysisSplit[],
  paceBand: { lo: number; hi: number } | null,
  hrBand: { lo: number; hi: number } | null,
): number {
  let onTarget = 0;
  for (const s of splits) {
    let inBand: boolean | null = null;
    if (paceBand) {
      inBand = s.sec >= paceBand.lo * 0.97 && s.sec <= paceBand.hi * 1.03;
    } else if (hrBand && s.avg_hr != null) {
      inBand = s.avg_hr >= hrBand.lo && s.avg_hr <= hrBand.hi;
    }
    s.in_band = inBand;
    if (inBand === true) onTarget++;
  }
  return onTarget;
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
