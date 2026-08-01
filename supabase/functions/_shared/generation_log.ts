// ============================================================================
// generation_logs — the one place a row gets built and written.
//
// Every LLM call in this project is supposed to land here. It is not just
// diagnostics: hosted_spend() (migration 35/36) SUMs this table, and
// _shared/quota.ts gates the operator's hosted key on that sum. So a call that
// isn't logged here is a call that is both invisible in the cost report AND
// invisible to the quota that is supposed to stop the bill running away.
//
// Before this module the insert was hand-rolled in six places, each with its own
// idea of which columns to fill; agent_memory.ts, which fires up to two extra
// hosted calls per coach turn, filled none of them.
// ============================================================================

import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { customPriceFromProfile, estimateCostUsd } from "./llm.ts";
import { logger } from "./log.ts";
import type { LlmProvider, LlmResult } from "./types.ts";

const log = logger("generation_log");

// The `feature` column is free text (no CHECK constraint), but these are the
// values the cost report groups by. Add here rather than inline at a call site.
export type GenerationFeature =
  | "chat"
  | "finalize"
  | "workout"
  | "plan"
  | "brief"
  | "week_review"
  | "analyze"
  | "memory";

// Just enough of user_profiles to price a BYO/OpenRouter call.
export interface PricingProfile {
  llm_custom_input_per_1m?: number | null;
  llm_custom_output_per_1m?: number | null;
}

export interface GenerationLogEntry {
  feature: GenerationFeature;
  /** True when the call ran on the operator's key, so quota must count it. */
  hosted: boolean;
  provider?: string | null;
  model?: string | null;
  promptTokens?: number;
  completionTokens?: number;
  cacheWriteTokens?: number;
  cacheReadTokens?: number;
  /** user_profiles row, for custom/OpenRouter pricing. */
  profile?: PricingProfile | Record<string, unknown> | null;
  systemPrompt?: string | null;
  userPrompt?: string | null;
  rawResponse?: string | null;
  toolsUsed?: string[] | null;
  parsedOk?: boolean;
  error?: string | null;
  workoutId?: string | null;
}

/**
 * Build the row. Pure, so the column mapping and the cost maths are testable
 * without a database.
 */
export function generationLogRow(
  userId: string,
  e: GenerationLogEntry,
): Record<string, unknown> {
  const promptTokens = e.promptTokens ?? 0;
  const completionTokens = e.completionTokens ?? 0;
  const cacheWriteTokens = e.cacheWriteTokens ?? 0;
  const cacheReadTokens = e.cacheReadTokens ?? 0;
  // A failure row (no provider reached, no tokens) has no cost to estimate;
  // leave it null rather than claiming $0 was spent.
  const cost = e.provider && promptTokens + completionTokens + cacheReadTokens > 0
    ? estimateCostUsd(
      e.provider as LlmProvider,
      promptTokens,
      completionTokens,
      customPriceFromProfile(e.provider, e.profile),
      e.model ?? undefined,
      { writeTokens: cacheWriteTokens, readTokens: cacheReadTokens },
    )
    : null;

  return {
    user_id: userId,
    feature: e.feature,
    hosted: e.hosted,
    provider: e.provider ?? null,
    model: e.model ?? null,
    prompt_tokens: e.promptTokens ?? null,
    completion_tokens: e.completionTokens ?? null,
    // Subsets of the prompt billed at the cache rates, not extra tokens.
    // A run of zeroes on a feature that should be caching is the signal that
    // something invalidated the prefix (see _shared/llm_cache.ts).
    cache_write_tokens: e.cacheWriteTokens ?? null,
    cache_read_tokens: e.cacheReadTokens ?? null,
    estimated_cost_usd: cost,
    system_prompt: e.systemPrompt ?? null,
    user_prompt: e.userPrompt ?? null,
    raw_response: e.rawResponse ?? null,
    tools_used: e.toolsUsed?.length ? e.toolsUsed : null,
    parsed_ok: e.parsedOk ?? true,
    error: e.error ?? null,
    workout_id: e.workoutId ?? null,
  };
}

/**
 * Write the row. Never throws: a cost row failing to insert must not turn a
 * working reply into an error. Failures are logged so they aren't silent.
 */
export async function logGeneration(
  admin: SupabaseClient,
  userId: string,
  e: GenerationLogEntry,
): Promise<void> {
  try {
    const { error } = await admin.from("generation_logs").insert(generationLogRow(userId, e));
    if (error) log.warn("insert failed", { feature: e.feature, error: error.message });
  } catch (err) {
    log.warn("insert threw", { feature: e.feature, err: String(err) });
  }
}

/** Convenience for the common case: a successful llmGenerate* outcome. */
export function logLlmResult(
  admin: SupabaseClient,
  userId: string,
  feature: GenerationFeature,
  hosted: boolean,
  out: LlmResult,
  profile?: PricingProfile | Record<string, unknown> | null,
  extra: Partial<GenerationLogEntry> = {},
): Promise<void> {
  return logGeneration(admin, userId, {
    feature,
    hosted,
    provider: out.provider,
    model: out.model,
    promptTokens: out.promptTokens,
    completionTokens: out.completionTokens,
    cacheWriteTokens: out.cacheWriteTokens,
    cacheReadTokens: out.cacheReadTokens,
    profile,
    ...extra,
  });
}
