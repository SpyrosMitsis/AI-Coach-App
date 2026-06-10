// daily-summary — compute the dashboard payload: readiness score (0-100),
// today's planned workout, TSB sparkline (14d), and weekly load vs target.
//
// Readiness = composite of HRV delta, resting-HR delta, and 3-day avg wellness.
//
// GET or POST (no body required).

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, getUserId } from "../_shared/supabase.ts";
import { computeRecovery } from "../_shared/recovery.ts";

const DAY = 86_400_000;

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const admin = adminClient();
    const today = new Date().toISOString().slice(0, 10);
    const since14 = new Date(Date.now() - 14 * DAY).toISOString().slice(0, 10);
    const since7 = new Date(Date.now() - 7 * DAY).toISOString().slice(0, 10);

    const [{ data: profile }, { data: wellness }, { data: activities }, { data: planned }] =
      await Promise.all([
        admin.from("user_profiles").select("onboarding, active_llm_provider").eq("id", userId).single(),
        admin.from("wellness_checkins").select("date, energy, soreness, sleep_quality, zepp_sleep_minutes")
          .eq("user_id", userId).gte("date", since14).order("date", { ascending: false }),
        admin.from("completed_activities").select("date, distance_m, tss, ctl, atl, data_json")
          .eq("user_id", userId).gte("date", since14).order("date", { ascending: true }),
        admin.from("planned_workouts").select("*").eq("user_id", userId).eq("date", today).order("created_at", { ascending: false }).limit(1),
      ]);

    const wells = wellness ?? [];
    const acts = activities ?? [];

    // --- recovery: HRV/RHR/sleep trends → 0-100 score (shared module) --------
    // Prefer device Health Connect values on wellness_checkins; fall back to
    // Intervals.icu wellness mirrored in completed_activities.data_json. Queried
    // best-effort so this works whether or not migration 8 has been applied yet.
    const isNum = (v: unknown): v is number => typeof v === "number";
    let hcHrv: number[] = [];
    let hcRhr: number[] = [];
    {
      const { data, error } = await admin.from("wellness_checkins")
        .select("date, hrv_rmssd, resting_hr")
        .eq("user_id", userId).gte("date", since14).order("date", { ascending: true });
      if (!error && data) {
        hcHrv = data.map((w) => (w as { hrv_rmssd?: number }).hrv_rmssd).filter(isNum);
        hcRhr = data.map((w) => (w as { resting_hr?: number }).resting_hr).filter(isNum);
      }
    }
    const ivHrv = acts.map((a) => (a.data_json as { hrv?: number })?.hrv).filter(isNum);
    const ivRhr = acts.map((a) => (a.data_json as { restingHR?: number })?.restingHR).filter(isNum);
    const hrvSeries = hcHrv.length >= 2 ? hcHrv : ivHrv;
    const rhrSeries = hcRhr.length >= 2 ? hcRhr : ivRhr;

    const recovery = computeRecovery(wells, hrvSeries, rhrSeries);
    const readiness = recovery.score;
    const band = recovery.band;

    // VO2 max (from Zepp via Health Connect): current value + change over ~90d.
    // Best-effort — works whether or not the vo2max column migration is applied.
    let vo2max: { value: number; change: number | null } | null = null;
    try {
      const since90 = new Date(Date.now() - 90 * DAY).toISOString().slice(0, 10);
      const { data, error } = await admin.from("wellness_checkins")
        .select("date, vo2max").eq("user_id", userId).gte("date", since90).order("date", { ascending: true });
      if (!error && data) {
        const series = data.map((r) => (r as { vo2max?: number }).vo2max).filter(isNum);
        if (series.length) {
          const value = series[series.length - 1];
          const change = series.length >= 2 ? +(value - series[0]).toFixed(1) : null;
          vo2max = { value: +value.toFixed(1), change };
        }
      }
    } catch { /* column not migrated yet */ }

    // --- TSB sparkline (14d) -------------------------------------------------
    const tsbSparkline = acts
      .filter((a) => a.ctl != null && a.atl != null)
      .map((a) => ({ date: a.date, tsb: (a.ctl ?? 0) - (a.atl ?? 0), ctl: a.ctl, atl: a.atl }));

    // --- weekly load vs target ----------------------------------------------
    const weeklyTss = acts.filter((a) => a.date >= since7).reduce((s, a) => s + (a.tss ?? 0), 0);
    const targetWeeklyTss = (profile?.onboarding as { weekly_tss_target?: number })?.weekly_tss_target ?? 350;

    // --- goal tracking ------------------------------------------------------
    const onboard = (profile?.onboarding ?? {}) as { goal?: string; goal_date?: string };
    let goal = null as null | {
      goal: string; goal_date: string | null; weeks_to_goal: number | null;
      phase: string; ctl_trend: number; on_track: string;
    };
    if (onboard.goal || onboard.goal_date) {
      let weeksToGoal: number | null = null;
      if (onboard.goal_date) {
        const wk = (new Date(onboard.goal_date).getTime() - Date.now()) / (7 * 86_400_000);
        weeksToGoal = wk >= 0 ? Math.round(wk) : null;
      }
      const phase = weeksToGoal == null ? "General / maintenance"
        : weeksToGoal <= 2 ? "Taper" : weeksToGoal <= 6 ? "Peak" : weeksToGoal <= 14 ? "Build" : "Base";
      const ctlVals = acts.filter((a) => a.ctl != null).map((a) => Number(a.ctl));
      const ctlTrend = ctlVals.length >= 2 ? Math.round((ctlVals[0] - ctlVals[ctlVals.length - 1]) * 10) / 10 : 0;
      const onTrack = ctlTrend > 1 ? "Fitness building — on track"
        : ctlTrend < -1 ? (phase === "Taper" ? "Tapering as planned" : "Fitness slipping — rebuild consistency")
        : "Holding steady";
      goal = { goal: onboard.goal ?? "Goal", goal_date: onboard.goal_date ?? null, weeks_to_goal: weeksToGoal, phase, ctl_trend: ctlTrend, on_track: onTrack };
    }

    return json({
      date: today,
      readiness: {
        score: readiness,
        band,
        components: {
          wellness: recovery.wellness,
          hrvDelta: recovery.hrv?.deltaPct ?? 0,
          rhrDelta: recovery.rhr?.deltaPct ?? 0,
        },
      },
      recovery,
      vo2max,
      today_workout: planned?.[0] ?? null,
      tsb_sparkline: tsbSparkline,
      weekly_load: { tss: Math.round(weeklyTss), target: targetWeeklyTss },
      active_llm_provider: profile?.active_llm_provider ?? "groq",
      goal,
    });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
