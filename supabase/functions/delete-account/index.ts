// delete-account — permanently delete the calling user's account and, via the
// ON DELETE CASCADE chain from auth.users, every row they own (profile, keys,
// workouts, logs, conversations, …). Required by Google Play for any app with
// account creation.
//
// POST {}  — the JWT identifies the account; there is nothing else to send.

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, getUserId } from "../_shared/supabase.ts";
import { logger } from "../_shared/log.ts";

const log = logger("delete-account");

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const admin = adminClient();
    const { error } = await admin.auth.admin.deleteUser(userId);
    if (error) {
      log.error("delete failed", { userId, error: error.message });
      return json({ error: error.message }, 500);
    }

    log.info("account deleted", { userId });
    return json({ ok: true });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
