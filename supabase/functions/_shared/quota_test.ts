import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { assertHostedQuota, QuotaError } from "./quota.ts";

const CAPS = { userHourlyCalls: 30, userDaily: 0.25, userMonthly: 2, globalMonthly: 25 };

type SpendRow = {
  user_day: number;
  user_month: number;
  global_month: number;
  user_hour_calls?: number;
};

// Minimal admin stub: only .rpc("hosted_spend") is exercised.
function adminWithSpend(spend: SpendRow | null, error?: string) {
  return {
    rpc: (_fn: string, _args: unknown) =>
      Promise.resolve(error ? { data: null, error: { message: error } } : { data: spend ? [spend] : [], error: null }),
  } as unknown as SupabaseClient;
}

const ok = (over: Partial<SpendRow> = {}): SpendRow => ({
  user_day: 0.01,
  user_month: 0.5,
  global_month: 5,
  user_hour_calls: 3,
  ...over,
});

async function scopeOf(p: Promise<void>): Promise<string | null> {
  try {
    await p;
    return null;
  } catch (e) {
    return e instanceof QuotaError ? e.scope : `unexpected: ${e}`;
  }
}

Deno.test("quota: under all caps passes", async () => {
  assertEquals(await scopeOf(assertHostedQuota(adminWithSpend(ok()), "u1", CAPS)), null);
});

Deno.test("quota: each cap trips its scope, most global first", async () => {
  assertEquals(
    await scopeOf(assertHostedQuota(adminWithSpend(ok({ global_month: 25 })), "u1", CAPS)),
    "global-month",
  );
  assertEquals(
    await scopeOf(assertHostedQuota(adminWithSpend(ok({ user_month: 2 })), "u1", CAPS)),
    "user-month",
  );
  assertEquals(
    await scopeOf(assertHostedQuota(adminWithSpend(ok({ user_day: 0.25 })), "u1", CAPS)),
    "user-day",
  );
  assertEquals(
    await scopeOf(assertHostedQuota(adminWithSpend(ok({ user_hour_calls: 30 })), "u1", CAPS)),
    "user-hour",
  );
});

Deno.test("quota: hourly burst limit trips even with spend to spare", async () => {
  assertEquals(
    await scopeOf(assertHostedQuota(adminWithSpend(ok({ user_hour_calls: 31 })), "u1", CAPS)),
    "user-hour",
  );
});

Deno.test("quota: fails CLOSED when spend is unreadable", async () => {
  assertEquals(
    await scopeOf(assertHostedQuota(adminWithSpend(null, "function hosted_spend does not exist"), "u1", CAPS)),
    "unavailable",
  );
  // RPC succeeded but returned nothing — still closed.
  assertEquals(await scopeOf(assertHostedQuota(adminWithSpend(null), "u1", CAPS)), "unavailable");
});

Deno.test("quota: pre-migration-36 row (no user_hour_calls) fails closed", async () => {
  const legacy = { user_day: 0, user_month: 0, global_month: 0 };
  assertEquals(
    await scopeOf(assertHostedQuota(adminWithSpend(legacy), "u1", CAPS)),
    "unavailable",
  );
});

Deno.test("quota: QuotaError carries a 429 for errorStatus()", async () => {
  const err = await assertRejects(
    () => assertHostedQuota(adminWithSpend(ok({ user_day: 9 })), "u1", CAPS),
    QuotaError,
  );
  assertEquals(err.status, 429);
});
