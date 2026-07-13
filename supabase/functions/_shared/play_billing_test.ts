import { assertEquals } from "jsr:@std/assert@1";
import { planFromSubscription, type SubscriptionV2 } from "./play_billing.ts";

const NOW = new Date("2026-07-12T12:00:00Z");
const FUTURE = "2026-08-12T12:00:00Z";
const PAST = "2026-06-12T12:00:00Z";

const sub = (state: string, expiry: string | null, extra: Partial<SubscriptionV2> = {}): SubscriptionV2 => ({
  subscriptionState: state,
  lineItems: expiry ? [{ productId: "pro", expiryTime: expiry }] : [],
  ...extra,
});

Deno.test("play: active within expiry → pro", () => {
  const d = planFromSubscription(sub("SUBSCRIPTION_STATE_ACTIVE", FUTURE), NOW);
  assertEquals(d.plan, "pro");
  assertEquals(d.expiresAt, new Date(FUTURE).toISOString());
  assertEquals(d.productId, "pro");
});

Deno.test("play: canceled keeps entitlement until expiry (auto-renew off, not refunded)", () => {
  assertEquals(planFromSubscription(sub("SUBSCRIPTION_STATE_CANCELED", FUTURE), NOW).plan, "pro");
  assertEquals(planFromSubscription(sub("SUBSCRIPTION_STATE_CANCELED", PAST), NOW).plan, "free");
});

Deno.test("play: grace period stays pro; hold/paused/revoked/expired do not", () => {
  assertEquals(planFromSubscription(sub("SUBSCRIPTION_STATE_IN_GRACE_PERIOD", FUTURE), NOW).plan, "pro");
  for (const s of ["ON_HOLD", "PAUSED", "REVOKED", "EXPIRED"]) {
    assertEquals(planFromSubscription(sub(`SUBSCRIPTION_STATE_${s}`, FUTURE), NOW).plan, "free");
  }
});

Deno.test("play: active but past expiry → free (stale state loses to the clock)", () => {
  assertEquals(planFromSubscription(sub("SUBSCRIPTION_STATE_ACTIVE", PAST), NOW).plan, "free");
});

Deno.test("play: no line items / missing expiry → free, null expiry", () => {
  const d = planFromSubscription(sub("SUBSCRIPTION_STATE_ACTIVE", null), NOW);
  assertEquals(d.plan, "free");
  assertEquals(d.expiresAt, null);
  assertEquals(d.productId, null);
});

Deno.test("play: latest expiry wins across line items", () => {
  const d = planFromSubscription({
    subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
    lineItems: [
      { productId: "pro", expiryTime: PAST },
      { productId: "pro", expiryTime: FUTURE },
    ],
  }, NOW);
  assertEquals(d.plan, "pro");
  assertEquals(d.expiresAt, new Date(FUTURE).toISOString());
});

Deno.test("play: pending ack + obfuscated account id surface for the callers", () => {
  const d = planFromSubscription(sub("SUBSCRIPTION_STATE_ACTIVE", FUTURE, {
    acknowledgementState: "ACKNOWLEDGEMENT_STATE_PENDING",
    externalAccountIdentifiers: { obfuscatedExternalAccountId: "user-123" },
  }), NOW);
  assertEquals(d.needsAck, true);
  assertEquals(d.obfuscatedAccountId, "user-123");
});
