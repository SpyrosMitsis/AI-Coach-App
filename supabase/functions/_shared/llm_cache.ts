// ============================================================================
// llm_cache — provider prompt caching, decided in one place.
//
// WHY. The agentic coach makes up to MAX_LLM_CALLS_PER_TURN (12) provider calls
// for ONE athlete message, and every one of them resends a byte-identical
// prefix: the system prompt plus the tool catalog. Measured on this repo that
// prefix is ~4,800 tokens (tools ~3,300, system ~1,500), so a single turn can
// pay full input price for ~57,000 tokens of text that never changed. Cached,
// the same turn writes the prefix once and reads it eleven times.
//
// That matters beyond the bill. CLAUDE.md's own note says the biggest lever on
// coach quality is the MODEL, and that strong models need a Settings opt-in
// because of cost. Making the expensive part of a turn cheap is what makes the
// better model affordable, which is the actual quality win.
//
// WHAT EACH PROVIDER NEEDS. Only Anthropic takes an explicit marker:
//
//   anthropic  cache_control on the last block of the stable prefix. Render
//              order is tools -> system -> messages, so ONE breakpoint on the
//              system block covers the tool definitions too.
//   openai,    automatic. Nothing to send; the provider caches long prefixes
//   deepseek,  on its own and reports what it did in `usage`. We only read
//   groq,      that back, so `llm:cost` can show it.
//   gemini
//   openrouter passes cache_control through to Anthropic-backed models, and
//              ignores it otherwise, so it is safe to send unconditionally.
//
// Everything here is pure so the thresholds can be tested rather than guessed.
// ============================================================================

import type { LlmProvider } from "./types.ts";

// ---------------------------------------------------------------------------
// Minimum cacheable prefix, per model.
//
// Below this the provider silently declines to cache: no error, no write, no
// read, just a `cache_creation_input_tokens` of 0. It is NOT monotonic across
// generations (512 on the newest models, 4096 on Opus 4.6 and Haiku 4.5), so
// it has to be a lookup rather than a single constant. Ordered most specific
// first; "opus-4-5" and "opus-5" are different strings and do not collide.
// ---------------------------------------------------------------------------

const CACHE_MIN_TOKENS: { when: RegExp; min: number }[] = [
  { when: /opus-4-6|opus-4-5|haiku-4-5/i, min: 4096 },
  { when: /opus-4-7|mythos-preview|haiku-3-5/i, min: 2048 },
  { when: /opus-5|fable-5|mythos-5/i, min: 512 },
];

// Everything else (Opus 4.8, Sonnet 5, Sonnet 4.6, Sonnet 4.5, Opus 4.1 ...),
// and the safe assumption for a model string we do not recognise: a model we
// have never heard of is more likely to want the common threshold than the
// smallest one, and guessing low would mean marking prefixes that never cache.
const CACHE_MIN_DEFAULT = 1024;

export function cacheMinTokensFor(model: string): number {
  return CACHE_MIN_TOKENS.find((r) => r.when.test(model ?? ""))?.min ?? CACHE_MIN_DEFAULT;
}

/** Same rough estimator llm.ts uses for unreported usage. Chars over four. */
export function estimateTokens(s: string): number {
  return Math.ceil((s ?? "").length / 4);
}

// ---------------------------------------------------------------------------
// WHICH FEATURES. Caching is not free: a write costs ~1.25x normal input and a
// read ~0.1x, so a prefix has to be reused at least twice inside the 5-minute
// window to break even. That makes this a per-FEATURE decision, not a global
// switch, and the answer is different for the two shapes of work this app does:
//
//   chat   one athlete message fans out to up to 12 provider calls, seconds
//          apart, all sharing one prefix. Break-even lands on call two and
//          every call after it is close to free. This is the whole win.
//   workout/plan/brief/review
//          one call, maybe a second repair pass, then nothing for a day. The
//          prefix has always expired by the next run, so caching them would
//          pay the 1.25x write premium every single time and never read it
//          back. Enabling it "for consistency" would make those features
//          MORE expensive, so they are deliberately left off.
//
// If a one-shot feature ever starts looping, add it here and nowhere else.
// ---------------------------------------------------------------------------

export const CACHEABLE_FEATURES = new Set(["chat"]);

export interface CacheDecision {
  /** Attach cache_control to the system block. */
  cache: boolean;
  /** Why, for the log line. Empty when caching. */
  reason: string;
}

/**
 * Should this call mark its stable prefix as cacheable?
 *
 * `prefixTokens` must count everything that renders BEFORE the breakpoint,
 * which for Anthropic means the tool definitions as well as the system prompt.
 * Passing only the system prompt would under-count by ~3,300 tokens on the
 * coach path and wrongly skip caching on the models with a 4,096 minimum.
 */
export function shouldCachePrefix(
  provider: LlmProvider,
  model: string,
  feature: string | undefined,
  prefixTokens: number,
): CacheDecision {
  if (provider !== "anthropic" && provider !== "openrouter") {
    return { cache: false, reason: "provider caches automatically" };
  }
  if (!feature || !CACHEABLE_FEATURES.has(feature)) {
    return { cache: false, reason: `feature ${feature ?? "unset"} is single-call` };
  }
  const min = cacheMinTokensFor(model);
  if (prefixTokens < min) {
    return { cache: false, reason: `prefix ${prefixTokens} tok below ${min} minimum` };
  }
  return { cache: true, reason: "" };
}

// ---------------------------------------------------------------------------
// Request shaping
// ---------------------------------------------------------------------------

export interface AnthropicTextBlock {
  type: "text";
  text: string;
  cache_control?: { type: "ephemeral" };
}

/**
 * The `system` field for an Anthropic request.
 *
 * A plain string when not caching (exactly what this code sent before, so an
 * uncached call is byte-identical to the old behavior), and a one-block array
 * carrying the breakpoint when caching. The breakpoint goes on the LAST block
 * of the stable prefix; with one system block that is simply this block, and
 * because tools render ahead of system it covers the tool catalog too.
 */
export function anthropicSystemField(
  systemPrompt: string,
  cache: boolean,
): string | AnthropicTextBlock[] {
  if (!cache) return systemPrompt;
  return [{ type: "text", text: systemPrompt, cache_control: { type: "ephemeral" } }];
}

// ---------------------------------------------------------------------------
// Response reading
//
// Reported so `scripts/dev.sh llm:cost` can show whether caching is actually
// happening. A cache that silently stops working (someone interpolates a
// timestamp into the system prompt, someone reorders the tool list) looks
// exactly like a cache that was never enabled, and the only way to tell is to
// watch these two numbers.
// ---------------------------------------------------------------------------

export interface CacheUsage {
  /** Tokens written to the cache this call, billed at ~1.25x. */
  cacheWriteTokens: number;
  /** Tokens served from the cache this call, billed at ~0.1x. */
  cacheReadTokens: number;
}

const num = (v: unknown): number => (typeof v === "number" && isFinite(v) ? v : 0);

/** Anthropic usage block: explicit write/read counters. */
export function anthropicCacheUsage(usage: unknown): CacheUsage {
  const u = (usage ?? {}) as Record<string, unknown>;
  return {
    cacheWriteTokens: num(u.cache_creation_input_tokens),
    cacheReadTokens: num(u.cache_read_input_tokens),
  };
}

/**
 * OpenAI-compatible usage block. These providers cache automatically and each
 * reports it differently: OpenAI (and OpenRouter's OpenAI-shaped passthrough)
 * nest a `prompt_tokens_details.cached_tokens`, DeepSeek reports flat
 * `prompt_cache_hit_tokens`. Neither exposes a write counter, so only reads
 * are known; a provider that reports nothing simply reads as zero.
 */
export function openAiCacheUsage(usage: unknown): CacheUsage {
  const u = (usage ?? {}) as Record<string, unknown>;
  const details = (u.prompt_tokens_details ?? {}) as Record<string, unknown>;
  return {
    cacheWriteTokens: 0,
    cacheReadTokens: num(details.cached_tokens) || num(u.prompt_cache_hit_tokens),
  };
}
