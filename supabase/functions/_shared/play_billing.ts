// ============================================================================
// Google Play subscription verification, shared by verify-purchase (app-
// initiated) and play-rtdn (Pub/Sub push). Both paths call the Android
// Publisher API directly — RTDN bodies are hints, never trusted.
//
// Secrets: GOOGLE_PLAY_SA_JSON (service-account JSON, one line) and
// ANDROID_PACKAGE_NAME (defaults to com.workoutmaker.app).
// ============================================================================

import { logger } from "./log.ts";

const log = logger("play-billing");

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const API = "https://androidpublisher.googleapis.com/androidpublisher/v3";

export function packageName(): string {
  return Deno.env.get("ANDROID_PACKAGE_NAME") || "com.workoutmaker.app";
}

interface ServiceAccount {
  client_email: string;
  private_key: string;
}

export function serviceAccount(): ServiceAccount | null {
  const raw = Deno.env.get("GOOGLE_PLAY_SA_JSON");
  if (!raw) return null;
  try {
    const sa = JSON.parse(raw);
    return sa.client_email && sa.private_key ? sa : null;
  } catch {
    return null;
  }
}

const b64url = (bytes: Uint8Array): string =>
  btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

async function importKey(pem: string): Promise<CryptoKey> {
  const der = atob(pem.replace(/-----[A-Z ]+-----/g, "").replace(/\s/g, ""));
  const buf = Uint8Array.from(der, (c) => c.charCodeAt(0));
  return await crypto.subtle.importKey(
    "pkcs8",
    buf,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

// Mint a short-lived Android Publisher access token from the service account.
export async function googleAccessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const enc = new TextEncoder();
  const header = b64url(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = b64url(enc.encode(JSON.stringify({
    iss: sa.client_email,
    scope: SCOPE,
    aud: TOKEN_URL,
    iat: now,
    exp: now + 3600,
  })));
  const key = await importKey(sa.private_key);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${header}.${claims}`));
  const jwt = `${header}.${claims}.${b64url(new Uint8Array(sig))}`;

  const res = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  if (!res.ok) throw new Error(`google token exchange failed: ${res.status} ${await res.text()}`);
  const data = await res.json();
  return data.access_token as string;
}

// The slice of purchases.subscriptionsv2.get we act on.
export interface SubscriptionV2 {
  subscriptionState?: string;
  acknowledgementState?: string;
  lineItems?: { productId?: string; expiryTime?: string }[];
  externalAccountIdentifiers?: { obfuscatedExternalAccountId?: string };
  testPurchase?: unknown;
}

export async function getSubscription(
  accessToken: string,
  purchaseToken: string,
): Promise<SubscriptionV2> {
  const url = `${API}/applications/${packageName()}/purchases/subscriptionsv2/tokens/${
    encodeURIComponent(purchaseToken)
  }`;
  const res = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` } });
  if (!res.ok) throw new Error(`subscriptionsv2.get failed: ${res.status} ${await res.text()}`);
  return await res.json();
}

// Unacknowledged purchases auto-refund after 3 days — ack immediately.
// (v2 has no ack endpoint; the legacy v3 subscriptions.acknowledge does it.)
export async function acknowledgeSubscription(
  accessToken: string,
  productId: string,
  purchaseToken: string,
): Promise<void> {
  const url = `${API}/applications/${packageName()}/purchases/subscriptions/${
    encodeURIComponent(productId)
  }/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`;
  const res = await fetch(url, {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
    body: "{}",
  });
  // 400 "already acknowledged" is a race with RTDN, not a failure.
  if (!res.ok && res.status !== 400) {
    throw new Error(`acknowledge failed: ${res.status} ${await res.text()}`);
  }
}

export interface PlanDecision {
  plan: "free" | "pro";
  expiresAt: string | null;
  state: string;
  needsAck: boolean;
  productId: string | null;
  obfuscatedAccountId: string | null;
}

// Pure state → plan mapping (unit-tested). CANCELED keeps entitlement until
// expiry (auto-renew off ≠ refunded); ON_HOLD/PAUSED/REVOKED/EXPIRED do not.
export function planFromSubscription(sub: SubscriptionV2, now = new Date()): PlanDecision {
  const state = sub.subscriptionState ?? "SUBSCRIPTION_STATE_UNSPECIFIED";
  const expiryTimes = (sub.lineItems ?? [])
    .map((li) => li.expiryTime)
    .filter((t): t is string => !!t)
    .map((t) => new Date(t).getTime())
    .filter((t) => Number.isFinite(t));
  const expiryMs = expiryTimes.length ? Math.max(...expiryTimes) : null;
  const expiresAt = expiryMs ? new Date(expiryMs).toISOString() : null;

  const entitledStates = new Set([
    "SUBSCRIPTION_STATE_ACTIVE",
    "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
    "SUBSCRIPTION_STATE_CANCELED",
  ]);
  const entitled = entitledStates.has(state) &&
    expiryMs !== null && expiryMs > now.getTime();

  return {
    plan: entitled ? "pro" : "free",
    expiresAt,
    state,
    needsAck: sub.acknowledgementState === "ACKNOWLEDGEMENT_STATE_PENDING",
    productId: sub.lineItems?.[0]?.productId ?? null,
    obfuscatedAccountId: sub.externalAccountIdentifiers?.obfuscatedExternalAccountId ?? null,
  };
}

// Verify a token against the Play API and return the decision, acking when
// needed. Shared by verify-purchase and play-rtdn.
export async function verifyWithPlay(purchaseToken: string): Promise<PlanDecision> {
  const sa = serviceAccount();
  if (!sa) throw new Error("GOOGLE_PLAY_SA_JSON is not configured");
  const token = await googleAccessToken(sa);
  const sub = await getSubscription(token, purchaseToken);
  const decision = planFromSubscription(sub);
  if (decision.needsAck && decision.productId) {
    try {
      await acknowledgeSubscription(token, decision.productId, purchaseToken);
    } catch (e) {
      // Non-fatal here: entitlement stands; RTDN/retry gets another shot
      // inside the 3-day window.
      log.error("acknowledge failed", { err: e instanceof Error ? e.message : String(e) });
    }
  }
  return decision;
}
