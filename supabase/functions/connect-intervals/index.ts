// connect-intervals — verify Intervals.icu credentials, encrypt + store them
// on the user's profile, and kick off the first 60-day sync.
//
// POST { athleteId, apiKey }

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, encryptSecret, getUserId } from "../_shared/supabase.ts";
import { getAthlete } from "../_shared/intervals.ts";

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const { athleteId, apiKey } = await req.json();
    if (!athleteId || !apiKey) return json({ error: "athleteId and apiKey required" }, 400);

    // Verify by fetching the athlete profile.
    let athlete;
    try {
      athlete = await getAthlete(athleteId, apiKey);
    } catch (e) {
      return json({ error: "verification failed", detail: String(e instanceof Error ? e.message : e) }, 400);
    }

    const admin = adminClient();
    const encrypted = await encryptSecret(admin, apiKey);
    await admin
      .from("user_profiles")
      .update({ intervals_athlete_id: String(athleteId), intervals_api_key_encrypted: encrypted })
      .eq("id", userId);

    // First sync is triggered client-side (with the user's JWT) right after
    // this call returns, so credentials are guaranteed to be persisted first.
    return json({ ok: true, athlete_name: athlete.name, athlete_id: String(athleteId) });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
