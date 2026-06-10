// delete-workout — delete a planned workout and remove its Intervals.icu
// calendar event (so it also disappears from the connected watch).
//
// POST { workout_id }

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, decryptSecret, getUserId } from "../_shared/supabase.ts";
import { deleteEvent } from "../_shared/intervals.ts";

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const { workout_id } = await req.json().catch(() => ({}));
    if (!workout_id) return json({ error: "workout_id required" }, 400);

    const admin = adminClient();
    const { data: planned } = await admin
      .from("planned_workouts")
      .select("id, intervals_event_id")
      .eq("id", workout_id)
      .eq("user_id", userId)
      .maybeSingle();
    if (!planned) return json({ error: "workout not found" }, 404);

    // Best-effort: remove the watch/calendar event if it was pushed.
    if (planned.intervals_event_id) {
      const { data: profile } = await admin
        .from("user_profiles")
        .select("intervals_athlete_id, intervals_api_key_encrypted")
        .eq("id", userId)
        .single();
      if (profile?.intervals_athlete_id && profile?.intervals_api_key_encrypted) {
        const apiKey = await decryptSecret(admin, profile.intervals_api_key_encrypted);
        if (apiKey) {
          try { await deleteEvent(profile.intervals_athlete_id, apiKey, planned.intervals_event_id); } catch (_e) { /* ignore */ }
        }
      }
    }

    const { error } = await admin
      .from("planned_workouts")
      .delete()
      .eq("id", workout_id)
      .eq("user_id", userId);
    if (error) return json({ error: error.message }, 500);

    return json({ ok: true });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
