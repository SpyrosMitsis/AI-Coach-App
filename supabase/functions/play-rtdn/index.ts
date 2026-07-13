// play-rtdn — Google Play Real-Time Developer Notifications, delivered as a
// Pub/Sub push. Deployed with --no-verify-jwt; the shared secret in the push
// endpoint URL (?secret=…, env PLAY_RTDN_SECRET) is the auth.
//
// The body is only a HINT: we re-verify every token against the Play API and
// act on that. Unknown tokens and malformed messages return 200 so Pub/Sub
// doesn't redeliver forever; only a bad secret gets a 401.

import { json } from "../_shared/cors.ts";
import { adminClient } from "../_shared/supabase.ts";
import { serviceAccount, verifyWithPlay } from "../_shared/play_billing.ts";
import { logger } from "../_shared/log.ts";

const log = logger("play-rtdn");

interface DeveloperNotification {
  subscriptionNotification?: { notificationType?: number; purchaseToken?: string };
  voidedPurchaseNotification?: { purchaseToken?: string };
  testNotification?: unknown;
}

Deno.serve(async (req) => {
  const secret = Deno.env.get("PLAY_RTDN_SECRET");
  if (!secret || new URL(req.url).searchParams.get("secret") !== secret) {
    return json({ error: "unauthorized" }, 401);
  }

  try {
    const envelope = await req.json().catch(() => null);
    const data = envelope?.message?.data;
    if (typeof data !== "string") {
      log.error("malformed pub/sub envelope");
      return json({ ok: true, ignored: "malformed" });
    }
    const note: DeveloperNotification = JSON.parse(atob(data));

    if (note.testNotification) {
      log.info("test notification received");
      return json({ ok: true, test: true });
    }

    const purchaseToken = note.subscriptionNotification?.purchaseToken ??
      note.voidedPurchaseNotification?.purchaseToken;
    const notificationType = note.subscriptionNotification?.notificationType ?? null;
    if (!purchaseToken) return json({ ok: true, ignored: "no token" });
    if (!serviceAccount()) {
      log.error("GOOGLE_PLAY_SA_JSON missing, cannot verify RTDN");
      return json({ ok: true, ignored: "unconfigured" });
    }

    const admin = adminClient();
    const { data: holder } = await admin
      .from("user_profiles")
      .select("id")
      .eq("play_purchase_token", purchaseToken)
      .maybeSingle();
    if (!holder) {
      // Purchase not linked yet (RTDN can beat verify-purchase) — the app's
      // verify call will bind it moments later.
      log.info("token not linked to a user yet", { notificationType });
      await admin.from("billing_events").insert({
        source: "rtdn",
        event_type: String(notificationType ?? "unknown"),
        purchase_token: purchaseToken,
        outcome: "ignored",
      });
      return json({ ok: true, ignored: "unknown token" });
    }

    const decision = await verifyWithPlay(purchaseToken);
    const { error: updErr } = await admin
      .from("user_profiles")
      .update({ plan: decision.plan, plan_expires_at: decision.expiresAt })
      .eq("id", holder.id);

    await admin.from("billing_events").insert({
      user_id: holder.id,
      source: "rtdn",
      event_type: `${notificationType ?? "?"}:${decision.state}`,
      purchase_token: purchaseToken,
      outcome: updErr ? `error: ${updErr.message}` : decision.plan,
    });
    if (updErr) throw new Error(updErr.message);

    log.info("plan updated", { userId: holder.id, plan: decision.plan, notificationType });
    return json({ ok: true, plan: decision.plan });
  } catch (e) {
    log.error("failed", { err: e instanceof Error ? e.message : String(e) });
    // 500 → Pub/Sub retries with backoff, which is what we want for transient
    // Play API / DB failures.
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
