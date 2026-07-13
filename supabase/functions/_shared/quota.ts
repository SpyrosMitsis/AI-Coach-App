// ============================================================================
// Hosted-AI spend quotas. Every hosted generation burns the owner's money, so
// the gate fails CLOSED: if spend can't be read (pre-migration DB, RPC error),
// hosted access is denied rather than unmetered.
//
// Caps are operator-set env: HOSTED_USER_DAILY_USD, HOSTED_USER_MONTHLY_USD,
// HOSTED_GLOBAL_MONTHLY_USD (all USD) and HOSTED_USER_HOURLY_CALLS (count).
// Sizing rule for ~€5/mo Pro: user-month must stay well under net revenue
// (~$4.60 after Play's cut); global scales as ~10 + 2.5 x subscribers. The
// hard spend cap on the provider account is still the true kill switch
// (see docs/PLAY_RELEASE.md).
// ============================================================================

import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { logger } from "./log.ts";

const log = logger("quota");

export class QuotaError extends Error {
  status = 429;
  scope: "user-hour" | "user-day" | "user-month" | "global-month" | "unavailable";
  constructor(scope: QuotaError["scope"], message: string) {
    super(message);
    this.scope = scope;
  }
}

export interface QuotaCaps {
  userHourlyCalls: number;
  userDaily: number;
  userMonthly: number;
  globalMonthly: number;
}

function envNum(name: string, fallback: number): number {
  const n = Number(Deno.env.get(name));
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

export function quotaCaps(): QuotaCaps {
  return {
    userHourlyCalls: envNum("HOSTED_USER_HOURLY_CALLS", 30),
    userDaily: envNum("HOSTED_USER_DAILY_USD", 0.25),
    userMonthly: envNum("HOSTED_USER_MONTHLY_USD", 2),
    globalMonthly: envNum("HOSTED_GLOBAL_MONTHLY_USD", 25),
  };
}

interface Spend {
  user_day: number;
  user_month: number;
  global_month: number;
  // Added by migration 36; older DBs return rows without it -> fail closed.
  user_hour_calls?: number;
}

// Throws QuotaError(429) when the next hosted call must not run.
export async function assertHostedQuota(
  admin: SupabaseClient,
  userId: string,
  caps: QuotaCaps = quotaCaps(),
): Promise<void> {
  const { data, error } = await admin.rpc("hosted_spend", { p_user: userId });
  if (error) {
    log.error("hosted_spend rpc failed, failing closed", { error: error.message });
    throw new QuotaError("unavailable", "Hosted AI is temporarily unavailable.");
  }
  // `returns table` comes back as a single-row array.
  const row = (Array.isArray(data) ? data[0] : data) as Spend | undefined;
  if (!row || typeof row.user_hour_calls !== "number") {
    // Pre-migration-36 schema: no rate column means no rate limit, so deny.
    throw new QuotaError("unavailable", "Hosted AI is temporarily unavailable.");
  }
  if (row.global_month >= caps.globalMonthly) {
    log.error("global hosted cap reached", { spend: row.global_month, cap: caps.globalMonthly });
    throw new QuotaError("global-month", "Hosted AI is at capacity this month. Try again next month, or add your own key in Settings.");
  }
  if (row.user_month >= caps.userMonthly) {
    throw new QuotaError("user-month", "You've reached this month's hosted AI allowance. It resets next month, or add your own key in Settings.");
  }
  if (row.user_day >= caps.userDaily) {
    throw new QuotaError("user-day", "You've reached today's hosted AI allowance. It resets tomorrow, or add your own key in Settings.");
  }
  if (row.user_hour_calls >= caps.userHourlyCalls) {
    throw new QuotaError("user-hour", "You're going fast. Hosted AI is briefly rate limited, try again in a little while or add your own key in Settings.");
  }
}
