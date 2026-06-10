// Helpers for past Intervals.icu activities — mirrors the Android
// CompletedActivity accessors so web and phone read the same fields.
import type { CompletedActivity } from "@shared/types";

export function fmtPaceSec(sec: number): string {
  const s = Math.max(0, Math.round(sec));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

export const isManual = (a: CompletedActivity) => a.intervals_id.startsWith("manual:");
export const distanceKm = (a: CompletedActivity) => (a.distance_m != null ? a.distance_m / 1000 : null);
export const durationMin = (a: CompletedActivity) => (a.duration_seconds != null ? Math.round(a.duration_seconds / 60) : null);

export function paceSecPerKm(a: CompletedActivity): number | null {
  if (!a.distance_m || a.distance_m < 100 || !a.duration_seconds) return null;
  return Math.round(a.duration_seconds / (a.distance_m / 1000));
}

function num(a: CompletedActivity, key: string): number | null {
  const v = a.data_json?.[key];
  return typeof v === "number" ? v : typeof v === "string" && v !== "" && !isNaN(+v) ? +v : null;
}
function str(a: CompletedActivity, key: string): string | null {
  const v = a.data_json?.[key];
  return typeof v === "string" && v ? v : null;
}

export const displayName = (a: CompletedActivity) => str(a, "name") ?? a.type ?? "Activity";
export const avgPower = (a: CompletedActivity) => num(a, "icu_average_watts") ?? num(a, "average_watts");
export const maxHr = (a: CompletedActivity) => num(a, "max_heartrate");
export const elevationGain = (a: CompletedActivity) => num(a, "total_elevation_gain") ?? num(a, "icu_elevation_gain");
export const calories = (a: CompletedActivity) => num(a, "calories");
export const avgCadence = (a: CompletedActivity) => num(a, "average_cadence");

/** Compact metadata chips for an activity row. */
export function activityMeta(a: CompletedActivity): string[] {
  const out: string[] = [];
  const km = distanceKm(a);
  if (km && km > 0) out.push(`${km.toFixed(1)} km`);
  const min = durationMin(a);
  if (min && min > 0) out.push(`${min} min`);
  const pace = paceSecPerKm(a);
  if (pace) out.push(`${fmtPaceSec(pace)} /km`);
  if (a.avg_hr) out.push(`♥ ${a.avg_hr}`);
  if (a.tss && a.tss > 0) out.push(`TSS ${Math.round(a.tss)}`);
  return out;
}

/** Does a completed activity's type satisfy a planned session's type? */
export function looksLike(plannedType: string, actualType: string | null): boolean {
  const a = (actualType ?? "").toLowerCase();
  switch (plannedType.toLowerCase()) {
    case "run": return a.includes("run") || a.includes("walk");
    case "strength": return a.includes("weight") || a.includes("strength") || a.includes("workout") || a.includes("gym");
    case "ride": case "bike": return a.includes("ride") || a.includes("bike") || a.includes("cycl");
    case "rest": return false;
    default: return a.length > 0;
  }
}
