// move-workout — reschedule a planned workout to another date AND move its
// Intervals.icu calendar event (so the watch schedule follows the app).
//
// POST { workout_id, new_date: "YYYY-MM-DD" }
//
// Replaces the old client-side date UPDATE, which left the Intervals event
// (and therefore the watch) on the original day.

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, decryptSecret, getUserId } from "../_shared/supabase.ts";
import { updateEvent } from "../_shared/intervals.ts";

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const { workout_id, new_date } = await req.json().catch(() => ({}));
    if (!workout_id) return json({ error: "workout_id required" }, 400);
    if (typeof new_date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(new_date)) {
      return json({ error: "new_date must be YYYY-MM-DD" }, 400);
    }

    const admin = adminClient();
    const { data: planned } = await admin
      .from("planned_workouts")
      .select("id, date, intervals_event_id")
      .eq("id", workout_id)
      .eq("user_id", userId)
      .maybeSingle();
    if (!planned) return json({ error: "workout not found" }, 404);

    const { error } = await admin
      .from("planned_workouts")
      .update({ date: new_date })
      .eq("id", workout_id)
      .eq("user_id", userId);
    if (error) return json({ error: error.message }, 500);

    // Best-effort: move the watch/calendar event with it.
    let eventMoved = false;
    let eventError: string | null = null;
    if (planned.intervals_event_id) {
      const { data: profile } = await admin
        .from("user_profiles")
        .select("intervals_athlete_id, intervals_api_key_encrypted")
        .eq("id", userId)
        .single();
      if (profile?.intervals_athlete_id && profile?.intervals_api_key_encrypted) {
        const apiKey = await decryptSecret(admin, profile.intervals_api_key_encrypted);
        if (apiKey) {
          try {
            await updateEvent(profile.intervals_athlete_id, apiKey, planned.intervals_event_id, {
              start_date_local: `${new_date}T00:00:00`,
            });
            eventMoved = true;
          } catch (e) {
            eventError = e instanceof Error ? e.message : String(e);
          }
        }
      }
    }

    return json({ ok: true, old_date: planned.date, new_date, event_moved: eventMoved, event_error: eventError });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
