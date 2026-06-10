// Shared athlete-context builders. Both the single-day generator and the weekly
// planner use these so they reason from the same signals (consistency = a more
// mature planner). Each helper is best-effort and returns "" / nulls on failure.

import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { decryptSecret } from "./supabase.ts";
import {
  getAthleteFull,
  getWellness,
  latestWellnessSubjective,
  runHrZones,
  runPaceZones,
} from "./intervals.ts";
import { getWeather } from "./weather.ts";

export type HrZone = { zone: string; min: number; max: number };

export interface ActivityRow {
  type?: string | null;
  date?: string | null;
  distance_m?: number | null;
  tss?: number | null;
  ctl?: number | null;
  atl?: number | null;
}

// Live Intervals.icu physiology: threshold pace, zones, VO2max, subjective flags.
export async function intervalsPhysiology(
  admin: SupabaseClient,
  profile: { intervals_athlete_id?: string | null; intervals_api_key_encrypted?: string | null },
): Promise<{ apiKey: string | null; hrZones: HrZone[] | null; block: string }> {
  let apiKey: string | null = null;
  let hrZones: HrZone[] | null = null;
  let block = "";
  if (!profile.intervals_athlete_id || !profile.intervals_api_key_encrypted) {
    return { apiKey, hrZones, block };
  }
  try {
    apiKey = await decryptSecret(admin, profile.intervals_api_key_encrypted);
    if (!apiKey) return { apiKey, hrZones, block };
    const [athlete, ivWellness] = await Promise.all([
      getAthleteFull(profile.intervals_athlete_id, apiKey),
      getWellness(profile.intervals_athlete_id, apiKey, 14),
    ]);
    const hz = runHrZones(athlete);
    if (hz.length) hrZones = hz.map((z) => ({ zone: z.name, min: z.min, max: z.max }));
    const { thresholdPace, zones: paceZones } = runPaceZones(athlete);
    const subj = latestWellnessSubjective(ivWellness);
    const lines: string[] = [];
    if (thresholdPace !== "—") lines.push(`- Threshold pace: ${thresholdPace}`);
    if (paceZones.length) lines.push(`- Pace zones: ${paceZones.map((z) => `${z.name} ${z.pace}`).join(", ")} (prescribe exact paces, not just zone labels)`);
    if (subj.vo2max) lines.push(`- VO2max: ${subj.vo2max.toFixed(1)}`);
    if (subj.restingHR) lines.push(`- Resting HR: ${subj.restingHR}`);
    if (subj.hrv) lines.push(`- HRV: ${subj.hrv.toFixed(0)}`);
    const flags: string[] = [];
    if ((subj.fatigue ?? 1) >= 3) flags.push("high fatigue");
    if ((subj.soreness ?? 1) >= 3) flags.push("high soreness");
    if ((subj.stress ?? 1) >= 3) flags.push("high stress");
    if ((subj.motivation ?? 1) >= 3) flags.push("low motivation");
    if ((subj.injury ?? 1) >= 2) flags.push("INJURY flagged — avoid aggravating load");
    if (flags.length) lines.push(`- Subjective flags: ${flags.join(", ")} → reduce intensity/volume accordingly`);
    if (lines.length) block = `\n\nMEASURED PHYSIOLOGY (Intervals.icu — use these exact numbers):\n${lines.join("\n")}`;
  } catch (_e) {
    // best-effort
  }
  return { apiKey, hrZones, block };
}

export function memoryBlock(profile: { training_memory?: string | null }): string {
  return profile.training_memory
    ? `\n\nATHLETE MEMORY (long-term notes from past sessions — honor these):\n${profile.training_memory}`
    : "";
}

// Recovery signal (HRV/RHR/sleep trends → 0-100) so generation actually
// down-regulates intensity after a poor night, not just on subjective wellness.
export function recoveryBlock(
  recovery: { score: number; band: string; summary: string } | null,
): string {
  if (!recovery) return "";
  const guide = recovery.band === "red"
    ? "RED: the athlete is under-recovered — cap intensity, prefer easy aerobic/technique or an active-recovery session, and trim volume."
    : recovery.band === "amber"
    ? "AMBER: recovery is moderate — keep quality modest; avoid stacking a second hard day."
    : "GREEN: well recovered — a quality/intensity session is appropriate if the plan calls for it.";
  return `\n\nRECOVERY TODAY: ${recovery.score}/100 (${recovery.band}). ${recovery.summary}\n${guide}`;
}

// Hard constraints captured from the coach conversation (injuries, equipment the
// athlete has/lacks, scheduling, dislikes). These are RULES, not suggestions.
export function knowledgeBlock(profile: { coach_knowledge?: string | null }): string {
  return profile.coach_knowledge && profile.coach_knowledge.trim()
    ? `\n\nATHLETE CONSTRAINTS & PREFERENCES (HARD RULES from the coaching chat — never violate these):\n${profile.coach_knowledge.trim()}\n` +
      `- If an injury is noted, avoid loading/aggravating it and prefer safe alternatives.\n` +
      `- Only prescribe exercises the athlete's available equipment supports.`
    : "";
}

// Planned vs actually-completed over the last 14 days.
export async function adherenceBlock(
  admin: SupabaseClient,
  userId: string,
  since14: string,
  beforeDate: string,
  acts28: ActivityRow[],
): Promise<string> {
  const { data: plannedRecent } = await admin
    .from("planned_workouts")
    .select("date, type, workout_json")
    .eq("user_id", userId)
    .gte("date", since14)
    .lt("date", beforeDate)
    .neq("type", "rest");
  if (!plannedRecent?.length) return "";

  const completedByDate = new Set(acts28.map((a) => a.date));
  const completedTss = acts28.filter((a) => (a.date ?? "") >= since14).reduce((s, a) => s + (a.tss ?? 0), 0);
  let plannedTss = 0;
  let missed = 0;
  const missedList: string[] = [];
  for (const p of plannedRecent) {
    plannedTss += Number((p.workout_json as { tss_estimate?: number })?.tss_estimate ?? 0);
    if (!completedByDate.has(p.date)) {
      missed++;
      missedList.push(`${p.date} ${p.type}`);
    }
  }
  const done = plannedRecent.length - missed;
  const pct = plannedRecent.length ? Math.round((done / plannedRecent.length) * 100) : 0;
  const volPct = plannedTss > 0 ? Math.round((completedTss / plannedTss) * 100) : null;
  return `\n\nADHERENCE (last 14 days — adapt to reality):\n` +
    `- Completed ${done}/${plannedRecent.length} planned sessions (${pct}%)` +
    (volPct != null ? `, ~${volPct}% of planned training load` : "") + ".\n" +
    (missedList.length ? `- Missed: ${missedList.slice(0, 5).join("; ")}. If key sessions were missed, don't cram — rebuild gradually.\n` : "") +
    (volPct != null && volPct < 70 ? "- Under-trained vs plan: hold or modestly reduce; rebuild consistency before progressing.\n" : "") +
    (volPct != null && volPct > 120 ? "- Over-trained vs plan: bias easier to manage fatigue/injury risk.\n" : "");
}

export function goalBlock(
  onboarding: { goal_date?: string },
  weeksToGoal: number | null,
  phase: string,
  acts28: ActivityRow[],
): string {
  if (weeksToGoal == null && !onboarding.goal_date) return "";
  const ctlVals = acts28.filter((a) => a.ctl != null).map((a) => Number(a.ctl));
  const ctlTrend = ctlVals.length >= 2 ? ctlVals[0] - ctlVals[ctlVals.length - 1] : 0;
  const trendWord = ctlTrend > 1 ? "building" : ctlTrend < -1 ? "declining" : "flat";
  return `\n\nGOAL TRACKING:\n` +
    (weeksToGoal != null ? `- ${weeksToGoal} weeks to goal (${onboarding.goal_date}); phase: ${phase}.\n` : "") +
    `- Fitness (CTL) is ${trendWord} (${ctlTrend >= 0 ? "+" : ""}${ctlTrend.toFixed(1)} over 28d).\n` +
    `- Prioritise the work that moves the goal; ${weeksToGoal != null && weeksToGoal <= 2 ? "this is taper — sharpen, don't build." : "progress the limiter without spiking load."}`;
}

export async function weatherBlock(lat: number, lon: number): Promise<string> {
  const wx = await getWeather(lat, lon);
  if (!wx) return "";
  return `\n\nWEATHER (today, at the athlete's location):\n` +
    `- ${wx.tempC.toFixed(0)}°C (feels ${wx.apparentC.toFixed(0)}°C), ${wx.humidity}% RH, wind ${wx.windKmh.toFixed(0)} km/h, rain prob ${wx.precipProbMax}%.\n` +
    `- Guidance: ${wx.summary}.`;
}
