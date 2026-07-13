// verify-purchase — the app posts a Play Billing purchase token right after
// the purchase flow (and on resume for unacknowledged purchases). We verify
// against the Android Publisher API (never trust the client), acknowledge
// within Google's 3-day window, and flip the profile's plan columns — the
// only writer of those columns besides play-rtdn.
//
// POST { purchase_token: string }
// → { plan: "free"|"pro", expires_at: string|null }

import { errorStatus, handleOptions, json } from "../_shared/cors.ts";
import { adminClient, getUserId } from "../_shared/supabase.ts";
import { serviceAccount, verifyWithPlay } from "../_shared/play_billing.ts";
import { logger } from "../_shared/log.ts";

const log = logger("verify-purchase");

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);
    if (!serviceAccount()) return json({ error: "billing is not configured on this server" }, 501);

    const body = await req.json().catch(() => ({}));
    const purchaseToken = typeof body.purchase_token === "string" ? body.purchase_token.trim() : "";
    if (!purchaseToken) return json({ error: "purchase_token required" }, 400);

    const admin = adminClient();

    // One token, one account: block a token already bound to someone else
    // (replaying a friend's receipt).
    const { data: holder } = await admin
      .from("user_profiles")
      .select("id")
      .eq("play_purchase_token", purchaseToken)
      .neq("id", userId)
      .maybeSingle();
    if (holder) {
      log.error("token already bound to another account", { userId });
      return json({ error: "this purchase is linked to a different account" }, 409);
    }

    const decision = await verifyWithPlay(purchaseToken);

    // The purchase flow tags the purchase with the buyer's user id
    // (obfuscatedExternalAccountId); if Google echoes a different one, the
    // token belongs to someone else's account.
    if (decision.obfuscatedAccountId && decision.obfuscatedAccountId !== userId) {
      log.error("obfuscated account mismatch", { userId });
      return json({ error: "this purchase is linked to a different account" }, 409);
    }

    const { error: updErr } = await admin
      .from("user_profiles")
      .update({
        plan: decision.plan,
        plan_expires_at: decision.expiresAt,
        play_purchase_token: purchaseToken,
      })
      .eq("id", userId);
    if (updErr) throw new Error(`profile update failed: ${updErr.message}`);

    await admin.from("billing_events").insert({
      user_id: userId,
      source: "verify-purchase",
      event_type: decision.state,
      purchase_token: purchaseToken,
      outcome: decision.plan,
    });

    log.info("verified", { userId, plan: decision.plan, state: decision.state });
    return json({ plan: decision.plan, expires_at: decision.expiresAt });
  } catch (e) {
    log.error("failed", { err: e instanceof Error ? e.message : String(e) });
    return json({ error: e instanceof Error ? e.message : String(e) }, errorStatus(e));
  }
});
