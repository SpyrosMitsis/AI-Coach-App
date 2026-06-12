// connect-intervals — verify Intervals.icu credentials, encrypt + store them
// on the user's profile, and kick off the first 60-day sync.
//
// POST { athleteId, apiKey }

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, encryptSecret, getUserId } from "../_shared/supabase.ts";
import { getAthlete } from "../_shared/intervals.ts";
import { maskKey } from "../_shared/mask.ts";

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
    const update = { intervals_athlete_id: String(athleteId), intervals_api_key_encrypted: encrypted };
    // A masked hint lets Settings show which key is saved (migration 27;
    // fall back to a hint-less update until it's applied).
    const { error: upErr } = await admin
      .from("user_profiles")
      .update({ ...update, intervals_api_key_hint: maskKey(apiKey) })
      .eq("id", userId);
    if (upErr) await admin.from("user_profiles").update(update).eq("id", userId);

    // First sync is triggered client-side (with the user's JWT) right after
    // this call returns, so credentials are guaranteed to be persisted first.
    return json({ ok: true, athlete_name: athlete.name, athlete_id: String(athleteId) });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
