// ============================================================================
// Which models the eval runs. THIS IS THE FILE YOU EDIT.
//
// Add a model:
//   1. add a line to EVAL_MODELS below
//   2. export its key in scripts/dev.local.sh (untracked — this repo is public)
//   3. scripts/dev.sh eval:run
//
// A model with no key is SKIPPED with a warning, never an error, so this file
// can list more models than you currently have keys for.
// ============================================================================

import { PROVIDERS } from "../../supabase/functions/_shared/llm.ts";
import type { LlmProvider } from "../../supabase/functions/_shared/types.ts";

export interface EvalModel {
  /** Label in the report + the --models filter. Keep it stable across runs. */
  id: string;
  /** Routes to the adapter in _shared/llm.ts. */
  provider: LlmProvider;
  /** Override the provider's default model id. Omit to use PROVIDERS[provider].model. */
  model?: string;
  /** Env var holding the plaintext key. */
  envKey: string;
  /**
   * Price per 1M tokens, ONLY when the model isn't priced by llm.ts already.
   * llm.ts prices per provider (+ the Anthropic family per model), so a cheap
   * model on an expensive provider would otherwise be costed wrong.
   */
  inputPer1M?: number;
  outputPer1M?: number;
}

export const EVAL_MODELS: EvalModel[] = [
  { id: "groq/llama-3.3-70b", provider: "groq", envKey: "GROQ_API_KEY" },
  // Renamed from "deepseek/chat" when the deepseek-chat alias was retired
  // (2026-07-24) and the provider default became deepseek-v4-flash. The id is
  // deliberately different so rows in eval_runs/*.jsonl from before the switch
  // don't silently average together with rows from a different model.
  { id: "deepseek/v4-flash", provider: "deepseek", envKey: "DEEPSEEK_API_KEY" },

  // Xiaomi MiMo, via OpenRouter. Prices are per 1M tokens, read from
  // openrouter.ai/api/v1/models — llm.ts prices the openrouter PROVIDER at 0/0
  // (its cost depends entirely on where it routes), so without these overrides
  // the cost column would silently report $0 for every MiMo row.
  //
  // MEASURED (full x3, 2026-07-18, docs/LLM_EVAL_FINDINGS.md): both are
  // reasoning models whose hidden thinking counts against max_tokens — v2.5
  // burned its whole budget and emitted NO JSON on 26% of calls, v2.5-pro on
  // 53% (plus 1-3 min/call). Cheap per token, not per SUCCESSFUL call. Not
  // HOSTED_LLM_MODEL candidates unless reasoning-effort control is added to
  // the openrouter adapter (follow-up).
  { id: "mimo/v2.5", provider: "openrouter", model: "xiaomi/mimo-v2.5", envKey: "OPENROUTER_API_KEY", inputPer1M: 0.14, outputPer1M: 0.28 },
  { id: "mimo/v2.5-pro", provider: "openrouter", model: "xiaomi/mimo-v2.5-pro", envKey: "OPENROUTER_API_KEY", inputPer1M: 0.435, outputPer1M: 0.87 },

  // ---- add yours here ------------------------------------------------------
  // { id: "gemini/2.5-flash", provider: "gemini", model: "gemini-2.5-flash", envKey: "GEMINI_API_KEY" },
  // { id: "openai/gpt-5-mini", provider: "openai", model: "gpt-5-mini", envKey: "OPENAI_API_KEY" },
  // { id: "anthropic/sonnet", provider: "anthropic", model: "claude-sonnet-4-5", envKey: "ANTHROPIC_API_KEY" },
];

/**
 * Which model judges. Advisory only — it never overrides a deterministic check.
 * A judge that also appears in EVAL_MODELS grades its own output; that's flagged
 * per-row as self_judged rather than silently trusted.
 */
export const EVAL_JUDGE = "deepseek/v4-flash";

/**
 * Providers we KNOW honor `seed`, so `deterministic: true` really is
 * reproducible. llm.ts only sends seed on the OpenAI-compatible path; anything
 * else still runs, but its rows are marked reproducible:false rather than
 * pretending.
 *
 * Deliberately excluded even though they take the seed parameter:
 *   - openrouter: an aggregator. It forwards seed only if the backend it routes
 *     to supports it, and it can route the same model id to different backends
 *     between calls. We cannot verify it, so we don't claim it.
 *   - custom: someone's own endpoint. Same argument.
 *
 * "Reproducible" has to mean "we know it is", not "it might be". A false
 * negative just prints a caveat; a false positive means trusting a model
 * comparison that was never valid. Run with --repeats and the notebook's
 * variance chart answers it empirically.
 */
const SEEDED_PROVIDERS = new Set<LlmProvider>(["openai", "deepseek", "groq"]);

export const isReproducible = (m: EvalModel) => SEEDED_PROVIDERS.has(m.provider);

/** The model id actually sent to the provider. */
export const modelIdOf = (m: EvalModel) => m.model ?? PROVIDERS[m.provider].model;

export interface ResolvedModel extends EvalModel {
  apiKey: string;
  resolvedModel: string;
  reproducible: boolean;
}

export interface Resolution {
  usable: ResolvedModel[];
  skipped: { id: string; reason: string }[];
}

/**
 * Pair each configured model with its key. [only] filters by id (--models).
 * Missing keys are reported, not thrown: running with one key configured should
 * work and say so.
 */
export function resolveModels(only?: string[]): Resolution {
  const wanted = only?.length ? EVAL_MODELS.filter((m) => only.includes(m.id)) : EVAL_MODELS;
  const usable: ResolvedModel[] = [];
  const skipped: { id: string; reason: string }[] = [];

  for (const id of only ?? []) {
    if (!EVAL_MODELS.some((m) => m.id === id)) {
      skipped.push({ id, reason: `not in EVAL_MODELS (scripts/eval/models.ts)` });
    }
  }

  for (const m of wanted) {
    const apiKey = Deno.env.get(m.envKey);
    if (!apiKey) {
      skipped.push({ id: m.id, reason: `${m.envKey} is not set` });
      continue;
    }
    const resolvedModel = modelIdOf(m);
    if (!resolvedModel) {
      skipped.push({ id: m.id, reason: `no model id (provider "${m.provider}" has no default)` });
      continue;
    }
    usable.push({ ...m, apiKey, resolvedModel, reproducible: isReproducible(m) });
  }
  return { usable, skipped };
}
