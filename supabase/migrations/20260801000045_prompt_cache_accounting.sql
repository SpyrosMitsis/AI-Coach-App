-- Prompt-cache accounting on generation_logs.
--
-- Without these two columns, turning prompt caching on makes the cost table
-- LIE. Anthropic's `input_tokens` counts only the UNCACHED remainder of the
-- prompt, so a cached call reports a small prompt_tokens and the existing cost
-- maths would price the cached portion at zero: `llm:cost` would show a
-- saving several times larger than the real one, and nobody would notice.
--
-- Both are subsets of the prompt, billed at their own rates (a write costs
-- ~1.25x the input rate, a read ~0.1x); estimated_cost_usd now includes them.
-- See _shared/llm_cache.ts for which features cache and why.
--
-- They are also the only way to detect a cache that has silently STOPPED
-- working. A prefix hit is a byte-for-byte prefix match, so interpolating a
-- timestamp into a system prompt or reordering the tool list quietly drops the
-- hit rate to zero, with no error and no behavior change, just a bigger bill.
-- A run of zeroes on a feature that should be caching is that signal.
alter table generation_logs
  add column if not exists cache_write_tokens int,
  add column if not exists cache_read_tokens int;

comment on column generation_logs.cache_write_tokens is
  'Prompt tokens written to the provider prompt cache on this call, billed at ~1.25x the input rate. A SUBSET of the prompt, not additional to prompt_tokens.';
comment on column generation_logs.cache_read_tokens is
  'Prompt tokens served from the provider prompt cache on this call, billed at ~0.1x the input rate. A SUBSET of the prompt, not additional to prompt_tokens.';
