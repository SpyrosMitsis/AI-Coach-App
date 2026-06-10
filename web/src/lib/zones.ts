// Training-zone math ported 1:1 from the Android app (data/Zones.kt) so web and
// phone derive identical zones from the same thresholds.
//
// HR zones use Joe Friel's running model as % of LTHR (lactate-threshold HR).
// Pace zones are multiples of threshold pace (~1-hour race pace). Power zones
// are % of FTP (Coggan, collapsed to 5 bands).

export interface HrZone { name: string; lo: number; hi: number }
export interface PaceZone { name: string; fastSec: number; slowSec: number; range: string }
export interface PowerZone { name: string; min: number; max: number; range: string }

/** Parse "m:ss" into seconds. Returns null if malformed. */
export function parsePace(text: string): number | null {
  const parts = text.trim().split(":");
  if (parts.length !== 2) return null;
  const m = Number(parts[0]);
  const s = Number(parts[1]);
  if (!Number.isInteger(m) || !Number.isInteger(s) || m < 0 || s < 0 || s >= 60) return null;
  return m * 60 + s;
}

export function formatPace(totalSec: number): string {
  const s = Math.max(0, Math.round(totalSec));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

// % of LTHR lower bounds (Friel running).
const HR_BANDS: [string, number][] = [
  ["Z1 Recovery", 0.0],
  ["Z2 Aerobic", 0.81],
  ["Z3 Tempo", 0.90],
  ["Z4 Threshold", 0.94],
  ["Z5 VO2max", 1.0],
];

export function hrZonesFromLthr(lthr: number): HrZone[] {
  if (lthr <= 0) return [];
  return HR_BANDS.map(([name, frac], i) => {
    const lo = Math.round(frac * lthr);
    const hi = i < HR_BANDS.length - 1
      ? Math.round(HR_BANDS[i + 1][1] * lthr) - 1
      : Math.round(1.15 * lthr);
    return { name, lo, hi };
  });
}

// (fast×, slow×) of threshold pace per zone (faster = smaller seconds).
const PACE_BANDS: [string, number, number][] = [
  ["Z1 Easy", 1.15, 1.30],
  ["Z2 Aerobic", 1.06, 1.15],
  ["Z3 Tempo", 1.01, 1.06],
  ["Z4 Threshold", 0.97, 1.01],
  ["Z5 Interval", 0.88, 0.97],
];

export function paceZonesFromThreshold(thresholdSecPerKm: number): PaceZone[] {
  if (thresholdSecPerKm <= 0) return [];
  return PACE_BANDS.map(([name, fastM, slowM]) => {
    const fastSec = Math.round(thresholdSecPerKm * fastM);
    const slowSec = Math.round(thresholdSecPerKm * slowM);
    return { name, fastSec, slowSec, range: `${formatPace(fastSec)}–${formatPace(slowSec)} /km` };
  });
}

// % of FTP lower bounds (Coggan, collapsed to 5).
const POWER_BANDS: [string, number][] = [
  ["Z1 Recovery", 0.0],
  ["Z2 Endurance", 0.56],
  ["Z3 Tempo", 0.76],
  ["Z4 Threshold", 0.91],
  ["Z5 VO2max+", 1.06],
];

export function powerZonesFromFtp(ftp: number): PowerZone[] {
  if (ftp <= 0) return [];
  return POWER_BANDS.map(([name, frac], i) => {
    const min = Math.round(frac * ftp);
    const max = i < POWER_BANDS.length - 1
      ? Math.round(POWER_BANDS[i + 1][1] * ftp) - 1
      : Math.round(1.5 * ftp);
    return { name, min, max, range: `${min}–${max} W` };
  });
}
