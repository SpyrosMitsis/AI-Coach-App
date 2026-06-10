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
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";

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

  // Mirror today's sleep into wellness_checkins pre-fill if present.
  const today = new Date().toISOString().slice(0, 10);
  const todayWellness = wellness.find((w) => w.id === today);
  if (todayWellness?.sleepSecs) {
    await admin.from("wellness_checkins").upsert(
      {
        user_id: userId,
        date: today,
        zepp_sleep_minutes: Math.round(todayWellness.sleepSecs / 60),
      },
      { onConflict: "user_id,date", ignoreDuplicates: false },
    );
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
        } catch (e) {
          results.push({ user_id: u.id, error: e instanceof Error ? e.message : String(e) });
        }
      }
      return json({ mode: "all_users", count: results.length, results });
    }

    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);
    const result = await syncUser(admin, userId);
    return json(result);
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
