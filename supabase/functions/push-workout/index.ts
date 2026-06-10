// push-workout — push an already-planned workout to the Intervals.icu calendar.
//
// POST { workout_id }

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, decryptSecret, getUserId } from "../_shared/supabase.ts";
import { createEvent } from "../_shared/intervals.ts";
import { renderIntervalsWorkout } from "../_shared/intervals_workout.ts";
import type { Workout } from "../_shared/types.ts";

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const { workout_id } = await req.json();
    if (!workout_id) return json({ error: "workout_id required" }, 400);

    const admin = adminClient();
    const { data: planned } = await admin
      .from("planned_workouts")
      .select("*")
      .eq("id", workout_id)
      .eq("user_id", userId)
      .single();
    if (!planned) return json({ error: "workout not found" }, 404);

    const { data: profile } = await admin
      .from("user_profiles")
      .select("intervals_athlete_id, intervals_api_key_encrypted")
      .eq("id", userId)
      .single();
    if (!profile?.intervals_athlete_id || !profile?.intervals_api_key_encrypted) {
      return json({ error: "no Intervals.icu credentials" }, 400);
    }

    const apiKey = await decryptSecret(admin, profile.intervals_api_key_encrypted);
    if (!apiKey) return json({ error: "failed to decrypt key" }, 500);

    const w = planned.workout_json as Workout;
    const ev = await createEvent(profile.intervals_athlete_id, apiKey, {
      date: planned.date,
      name: w.title,
      description: renderIntervalsWorkout(w),
      type: w.type === "run" ? "Run" : "Workout",
    });

    await admin
      .from("planned_workouts")
      .update({ intervals_event_id: ev.id, pushed_at: new Date().toISOString() })
      .eq("id", workout_id);

    return json({ ok: true, intervals_event_id: ev.id });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
