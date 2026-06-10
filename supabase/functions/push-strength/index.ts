// push-strength — push a strength workout (routine or logged session) to the
// athlete's Intervals.icu calendar as a WeightTraining event. Intervals.icu then
// syncs planned workouts to the connected watch (e.g. Amazfit via Zepp).
//
// POST {
//   date: "YYYY-MM-DD",
//   name: string,
//   exercises: [{ name, muscle?, sets: [{ reps, weight_kg?, rpe? }] }]
// }

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, decryptSecret, getUserId } from "../_shared/supabase.ts";
import { createEvent } from "../_shared/intervals.ts";
import { type PushExercise, renderStrengthSession } from "../_shared/intervals_workout.ts";

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const body = await req.json().catch(() => ({}));
    const date: string = body.date ?? new Date().toISOString().slice(0, 10);
    const name: string = body.name?.trim() || "Strength Workout";
    const exercises: PushExercise[] = Array.isArray(body.exercises) ? body.exercises : [];
    if (!exercises.length) return json({ error: "no exercises to push" }, 400);

    const admin = adminClient();
    const { data: profile } = await admin
      .from("user_profiles")
      .select("intervals_athlete_id, intervals_api_key_encrypted")
      .eq("id", userId)
      .single();
    if (!profile?.intervals_athlete_id || !profile?.intervals_api_key_encrypted) {
      return json({ error: "Connect Intervals.icu in Settings first." }, 400);
    }

    const apiKey = await decryptSecret(admin, profile.intervals_api_key_encrypted);
    if (!apiKey) return json({ error: "failed to decrypt Intervals key" }, 500);

    const ev = await createEvent(profile.intervals_athlete_id, apiKey, {
      date,
      name,
      description: renderStrengthSession(name, exercises),
      type: "WeightTraining",
    });

    return json({ ok: true, intervals_event_id: ev.id, date });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
