import { assert, assertAlmostEquals, assertEquals } from "jsr:@std/assert@1";
import {
  anthropicCacheUsage,
  anthropicSystemField,
  CACHEABLE_FEATURES,
  cacheMinTokensFor,
  estimateTokens,
  openAiCacheUsage,
  shouldCachePrefix,
} from "./llm_cache.ts";
import { CACHE_READ_MULTIPLIER, CACHE_WRITE_MULTIPLIER, estimateCostUsd } from "./llm.ts";
import { generationLogRow } from "./generation_log.ts";

// The coach's real prefix, measured: system ~1,500 tok + tool catalog ~3,300.
const COACH_PREFIX_TOKENS = 4800;

// --- per-model minimums ------------------------------------------------------

Deno.test("cacheMinTokensFor: the minimum is NOT monotonic across generations", () => {
  // This is the whole reason it is a lookup and not a constant: the newest
  // models need 512, but Opus 4.6 and Haiku 4.5 need 4096. A single constant
  // would either mark uncacheable prefixes or skip cacheable ones.
  assertEquals(cacheMinTokensFor("claude-opus-5"), 512);
  assertEquals(cacheMinTokensFor("claude-opus-4-8"), 1024);
  assertEquals(cacheMinTokensFor("claude-opus-4-7"), 2048);
  assertEquals(cacheMinTokensFor("claude-opus-4-6"), 4096);
  assertEquals(cacheMinTokensFor("claude-haiku-4-5"), 4096);
});

Deno.test("cacheMinTokensFor: opus-5 and opus-4-5 do not collide", () => {
  // "claude-opus-4-5" does not contain the substring "opus-5", but a sloppier
  // pattern would match it and quietly apply the wrong 512 threshold.
  assertEquals(cacheMinTokensFor("claude-opus-4-5"), 4096);
  assertEquals(cacheMinTokensFor("claude-opus-5"), 512);
});

Deno.test("cacheMinTokensFor: an unknown model gets the common threshold, not the smallest", () => {
  // Guessing low would mark prefixes that then never cache, paying nothing but
  // reporting a hit rate of zero that looks like a bug.
  assertEquals(cacheMinTokensFor("some-model-we-have-never-seen"), 1024);
  assertEquals(cacheMinTokensFor(""), 1024);
});

// --- which features cache ----------------------------------------------------

Deno.test("shouldCachePrefix: chat caches, one-shot features do not", () => {
  const model = "claude-opus-4-8";
  assert(shouldCachePrefix("anthropic", model, "chat", COACH_PREFIX_TOKENS).cache);
  // workout/plan/brief run once a day. The prefix has always expired by the
  // next run, so caching them would pay the 1.25x write EVERY time and never
  // read it back: strictly more expensive than not caching.
  for (const feature of ["workout", "plan", "brief", "review", "finalize"]) {
    const d = shouldCachePrefix("anthropic", model, feature, COACH_PREFIX_TOKENS);
    assertEquals(d.cache, false, `${feature} should not cache`);
    assert(d.reason.includes("single-call"));
  }
});

Deno.test("shouldCachePrefix: a missing feature never caches", () => {
  assertEquals(shouldCachePrefix("anthropic", "claude-opus-4-8", undefined, 99999).cache, false);
});

Deno.test("shouldCachePrefix: below the model minimum it stays off", () => {
  // Opus 4.6 needs 4096; the coach prefix clears it, a bare system prompt does not.
  assert(shouldCachePrefix("anthropic", "claude-opus-4-6", "chat", COACH_PREFIX_TOKENS).cache);
  const small = shouldCachePrefix("anthropic", "claude-opus-4-6", "chat", 1500);
  assertEquals(small.cache, false);
  assert(small.reason.includes("4096"));
});

Deno.test("shouldCachePrefix: only the providers that take a marker get one", () => {
  // openai/deepseek/groq/gemini cache long prefixes automatically; sending
  // them an Anthropic-shaped system array would be a malformed request.
  for (const p of ["openai", "deepseek", "groq", "gemini", "custom"] as const) {
    assertEquals(shouldCachePrefix(p, "gpt-5", "chat", COACH_PREFIX_TOKENS).cache, false);
  }
  // OpenRouter forwards cache_control to Anthropic-backed models and ignores
  // it elsewhere, so marking is safe.
  assert(shouldCachePrefix("openrouter", "anthropic/claude-opus-4-8", "chat", COACH_PREFIX_TOKENS).cache);
});

Deno.test("the coach's real prefix clears every model's minimum", () => {
  // If this ever fails, caching has silently switched off for some models and
  // the only visible symptom would be a bigger bill.
  for (const m of ["claude-opus-5", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-haiku-4-5"]) {
    assert(
      COACH_PREFIX_TOKENS >= cacheMinTokensFor(m),
      `${m} needs ${cacheMinTokensFor(m)}, prefix is ${COACH_PREFIX_TOKENS}`,
    );
  }
});

// --- request shaping ---------------------------------------------------------

Deno.test("anthropicSystemField: an uncached call is byte-identical to the old shape", () => {
  // The old code sent a bare string. Not caching must change nothing at all.
  assertEquals(anthropicSystemField("You are a coach.", false), "You are a coach.");
});

Deno.test("anthropicSystemField: caching puts one breakpoint on the system block", () => {
  const field = anthropicSystemField("You are a coach.", true);
  assertEquals(field, [
    { type: "text", text: "You are a coach.", cache_control: { type: "ephemeral" } },
  ]);
});

Deno.test("anthropicSystemField: exactly one breakpoint (the API allows at most four)", () => {
  const blocks = anthropicSystemField("x".repeat(40_000), true) as { cache_control?: unknown }[];
  assertEquals(blocks.filter((b) => b.cache_control).length, 1);
});

Deno.test("estimateTokens: the four-chars-per-token estimate used for the gate", () => {
  assertEquals(estimateTokens("abcd"), 1);
  assertEquals(estimateTokens("x".repeat(4000)), 1000);
  assertEquals(estimateTokens(""), 0);
});

// --- response reading --------------------------------------------------------

Deno.test("anthropicCacheUsage: reads the write and read counters", () => {
  assertEquals(
    anthropicCacheUsage({ input_tokens: 12, cache_creation_input_tokens: 4800, cache_read_input_tokens: 0 }),
    { cacheWriteTokens: 4800, cacheReadTokens: 0 },
  );
  assertEquals(
    anthropicCacheUsage({ input_tokens: 12, cache_creation_input_tokens: 0, cache_read_input_tokens: 4800 }),
    { cacheWriteTokens: 0, cacheReadTokens: 4800 },
  );
});

Deno.test("anthropicCacheUsage: a provider that reports nothing reads as zero, not NaN", () => {
  assertEquals(anthropicCacheUsage(undefined), { cacheWriteTokens: 0, cacheReadTokens: 0 });
  assertEquals(anthropicCacheUsage({}), { cacheWriteTokens: 0, cacheReadTokens: 0 });
  assertEquals(anthropicCacheUsage({ cache_read_input_tokens: "lots" }), {
    cacheWriteTokens: 0,
    cacheReadTokens: 0,
  });
});

Deno.test("openAiCacheUsage: both shapes these providers actually use", () => {
  // OpenAI nests it; DeepSeek reports it flat.
  assertEquals(openAiCacheUsage({ prompt_tokens_details: { cached_tokens: 3000 } }).cacheReadTokens, 3000);
  assertEquals(openAiCacheUsage({ prompt_cache_hit_tokens: 2048 }).cacheReadTokens, 2048);
  assertEquals(openAiCacheUsage({}).cacheReadTokens, 0);
  // Neither exposes a write counter, so it is honestly reported as zero
  // rather than invented.
  assertEquals(openAiCacheUsage({ prompt_tokens_details: { cached_tokens: 3000 } }).cacheWriteTokens, 0);
});

// --- the cost trap -----------------------------------------------------------

Deno.test("estimateCostUsd: cached tokens are priced, not free", () => {
  // THE BUG THIS PREVENTS. Anthropic's input_tokens reports only the UNCACHED
  // remainder, so pricing promptTokens alone would bill a fully-cached call at
  // almost nothing and llm:cost would show a saving that never happened.
  const uncached = estimateCostUsd("anthropic", 5000, 500);
  const cachedRead = estimateCostUsd("anthropic", 200, 500, undefined, undefined, {
    readTokens: 4800,
  });
  assert(cachedRead > 0);
  assert(cachedRead < uncached, "a cache read must be cheaper than full price");

  // And the discount is the real one, not "free".
  const naive = estimateCostUsd("anthropic", 200, 500);
  assert(cachedRead > naive, "ignoring cache_read_tokens under-reports the bill");
});

Deno.test("estimateCostUsd: a write costs more than plain input, a read costs less", () => {
  const rate = estimateCostUsd("anthropic", 1_000_000, 0);
  const write = estimateCostUsd("anthropic", 0, 0, undefined, undefined, { writeTokens: 1_000_000 });
  const read = estimateCostUsd("anthropic", 0, 0, undefined, undefined, { readTokens: 1_000_000 });
  assertAlmostEquals(write, rate * CACHE_WRITE_MULTIPLIER, 1e-9);
  assertAlmostEquals(read, rate * CACHE_READ_MULTIPLIER, 1e-9);
  assert(write > rate && read < rate);
});

Deno.test("estimateCostUsd: no cache argument prices exactly as before", () => {
  // Every existing call site omits the argument; none of them may shift.
  assertEquals(
    estimateCostUsd("anthropic", 1234, 567),
    estimateCostUsd("anthropic", 1234, 567, undefined, undefined, {}),
  );
});

Deno.test("a cached coach turn is cheaper than an uncached one, end to end", () => {
  // The claim the whole feature rests on, priced rather than asserted.
  // 12 steps sharing a 4,800-token prefix, ~300 tokens of turn-specific input
  // and ~200 of output each.
  const STEPS = 12, PREFIX = 4800, PER_STEP_IN = 300, OUT = 200;

  const uncached = Array.from({ length: STEPS }).reduce<number>(
    (sum) => sum + estimateCostUsd("anthropic", PREFIX + PER_STEP_IN, OUT),
    0,
  );
  // Cached: step one writes the prefix, the rest read it.
  let cached = estimateCostUsd("anthropic", PER_STEP_IN, OUT, undefined, undefined, { writeTokens: PREFIX });
  for (let i = 1; i < STEPS; i++) {
    cached += estimateCostUsd("anthropic", PER_STEP_IN, OUT, undefined, undefined, { readTokens: PREFIX });
  }
  assert(cached < uncached, `cached ${cached} should beat uncached ${uncached}`);
  // Worth having: at least a third off the turn.
  assert(cached < uncached * 0.67, `expected a real saving, got ${(cached / uncached).toFixed(2)}x`);
});

// --- the log row -------------------------------------------------------------

Deno.test("generationLogRow: carries the cache columns and prices them in", () => {
  const row = generationLogRow("u1", {
    feature: "chat",
    hosted: false,
    provider: "anthropic",
    model: "claude-opus-4-8",
    promptTokens: 200,
    completionTokens: 500,
    cacheWriteTokens: 0,
    cacheReadTokens: 4800,
  });
  assertEquals(row.cache_read_tokens, 4800);
  assertEquals(row.cache_write_tokens, 0);
  const withCache = row.estimated_cost_usd as number;
  const withoutCache = generationLogRow("u1", {
    feature: "chat",
    hosted: false,
    provider: "anthropic",
    model: "claude-opus-4-8",
    promptTokens: 200,
    completionTokens: 500,
  }).estimated_cost_usd as number;
  assert(withCache > withoutCache, "the cached read must show up in the cost");
});

Deno.test("generationLogRow: a call that ONLY read cache still gets a cost", () => {
  // promptTokens can legitimately be 0 on a fully-cached prefix. The old
  // guard (prompt + completion > 0) would have logged null cost for it.
  const row = generationLogRow("u1", {
    feature: "chat",
    hosted: false,
    provider: "anthropic",
    model: "claude-opus-4-8",
    promptTokens: 0,
    completionTokens: 0,
    cacheReadTokens: 4800,
  });
  assert(typeof row.estimated_cost_usd === "number" && row.estimated_cost_usd > 0);
});

Deno.test("generationLogRow: a failure row still claims no cost", () => {
  const row = generationLogRow("u1", { feature: "chat", hosted: false, error: "boom" });
  assertEquals(row.estimated_cost_usd, null);
  assertEquals(row.cache_read_tokens, null);
});

Deno.test("CACHEABLE_FEATURES is deliberately small", () => {
  // A guard against someone adding a one-shot feature here and making it more
  // expensive. Adding one should require editing this test and saying why.
  assertEquals([...CACHEABLE_FEATURES], ["chat"]);
});
