// sync-intervals — pull + cache activities and CTL/ATL/TSB from Intervals.icu.
//
// Two modes:
//   - Client call (Authorization: user JWT)       → sync that one user.
//   - Cron call  (Authorization: service_role; body { all_users: true }) →
//     iterate every user with Intervals credentials.

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, decryptSecret, getUserId } from "../_shared/supabase.ts";
import {
  getActivities,
  getWellness,
  latestFitness,
} from "../_shared/intervals.ts";
import { autoAnalyzeRecent } from "../_shared/analyze_core.ts";
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";

// Keep the worker alive for post-response work (auto-analysis) without
// delaying the sync response.
declare const EdgeRuntime: { waitUntil?: (p: Promise<unknown>) => void } | undefined;
function waitUntil(p: Promise<unknown>) {
  try {
    if (typeof EdgeRuntime !== "undefined" && EdgeRuntime?.waitUntil) {
      EdgeRuntime.waitUntil(p);
      return;
    }
  } catch (_e) { /* fall through */ }
  p.catch(() => {});
}

interface SyncResult {
  user_id: string;
  activities_synced: number;
  ctl: number;
  atl: number;
  tsb: number;
}

async function syncUser(admin: SupabaseClient, userId: string): Promise<SyncResult> {
  const { data: profile, error } = await admin
    .from("user_profiles")
    .select("intervals_athlete_id, intervals_api_key_encrypted")
    .eq("id", userId)
    .single();
  if (error || !profile?.intervals_athlete_id || !profile?.intervals_api_key_encrypted) {
    throw new Error("user has no Intervals.icu credentials");
  }

  const apiKey = await decryptSecret(admin, profile.intervals_api_key_encrypted);
  if (!apiKey) throw new Error("failed to decrypt Intervals key");
  const athleteId = profile.intervals_athlete_id;

  const [activities, wellness] = await Promise.all([
    getActivities(athleteId, apiKey, 60),
    getWellness(athleteId, apiKey, 60),
  ]);

  const rows = activities.map((a) => ({
    user_id: userId,
    intervals_id: a.id,
    type: a.type ?? null,
    date: a.start_date_local?.slice(0, 10) ?? null,
    duration_seconds: a.moving_time ?? null,
    distance_m: a.distance ?? null,
    avg_hr: a.average_heartrate ? Math.round(a.average_heartrate) : null,
    tss: a.icu_training_load ?? null,
    ctl: a.icu_ctl ?? null,
    atl: a.icu_atl ?? null,
    data_json: a,
  }));

  if (rows.length) {
    const { error: upErr } = await admin
      .from("completed_activities")
      .upsert(rows, { onConflict: "user_id,intervals_id" });
    if (upErr) throw new Error(`cache upsert: ${upErr.message}`);
  }

  // Mirror Intervals' OBJECTIVE daily metrics into wellness_checkins so the
  // dashboard + recovery model read everything from one table. We only write
  // objective columns, so the user's subjective energy/soreness check-in is
  // left untouched (PostgREST upsert updates only the columns present per row).
  // The 1..5 sleep_quality the recovery composite expects is derived from the
  // device sleep score (0..100) when Intervals exposes one; otherwise it falls
  // back to sleep DURATION (always present, and it varies night to night), so
  // sleep_quality can never go stale on a manual value the user once entered.
  const clamp5 = (v: number) => Math.max(1, Math.min(5, Math.round(v)));
  const qualityFor = (w: { sleepScore?: number; sleepSecs?: number }) =>
    typeof w.sleepScore === "number" ? clamp5(w.sleepScore / 20)
      : typeof w.sleepSecs === "number" ? clamp5(w.sleepSecs / 3600 - 3) // <4h→1 .. 8h+→5
      : undefined;
  const wellnessRows = wellness
    .map((w) => {
      const row: Record<string, unknown> = { user_id: userId, date: w.id };
      let has = false;
      if (typeof w.sleepSecs === "number") { row.zepp_sleep_minutes = Math.round(w.sleepSecs / 60); has = true; }
      const q = qualityFor(w);
      if (q !== undefined) { row.sleep_quality = q; has = true; }
      if (typeof w.hrv === "number") { row.hrv_rmssd = w.hrv; has = true; }
      if (typeof w.restingHR === "number") { row.resting_hr = Math.round(w.restingHR); has = true; }
      if (typeof w.vo2max === "number") { row.vo2max = w.vo2max; has = true; }
      return has ? row : null;
    })
    .filter((r): r is Record<string, unknown> => r !== null);
  if (wellnessRows.length) {
    const { error: wErr } = await admin
      .from("wellness_checkins")
      .upsert(wellnessRows, { onConflict: "user_id,date", ignoreDuplicates: false });
    if (wErr) throw new Error(`wellness mirror: ${wErr.message}`);
  }

  const fitness = latestFitness(wellness);
  return { user_id: userId, activities_synced: rows.length, ...fitness };
}

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const admin = adminClient();
    const body = await req.json().catch(() => ({}));

    if (body?.all_users) {
      // Cron path. Best-effort per user; one failure doesn't abort the batch.
      const { data: users } = await admin
        .from("user_profiles")
        .select("id")
        .not("intervals_athlete_id", "is", null);
      const results = [];
      for (const u of users ?? []) {
        try {
          results.push(await syncUser(admin, u.id));
          // Auto-analyze the freshly synced sessions (best-effort, capped).
          waitUntil(autoAnalyzeRecent(admin, u.id));
        } catch (e) {
          results.push({ user_id: u.id, error: e instanceof Error ? e.message : String(e) });
        }
      }
      return json({ mode: "all_users", count: results.length, results });
    }

    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);
    const result = await syncUser(admin, userId);
    // Analyze today's/yesterday's matched run/ride in the background so the
    // execution score + AI feedback are ready when the activity is opened.
    waitUntil(autoAnalyzeRecent(admin, userId));
    return json(result);
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
