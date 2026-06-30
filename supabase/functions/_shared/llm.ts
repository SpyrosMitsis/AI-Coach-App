// ============================================================================
// Multi-LLM provider abstraction layer.
//
// One entry point — `llmGenerate` — normalizes request + response across all
// five providers and returns a uniform `LlmResult`. `llmGenerateWithFallback`
// walks the user's configured fallback chain on failure.
//
// Every provider receives the SAME system + user prompt. Provider-specific
// quirks (JSON mode, message shape, header names) are isolated to the adapter
// functions below.
// ============================================================================

import type { LlmProvider, LlmResult } from "./types.ts";
import { logger } from "./log.ts";

const log = logger("llm");

export interface ProviderSpec {
  label: string;
  model: string;
  // USD per 1M tokens.
  inputPer1M: number;
  outputPer1M: number;
  getFreeKeyUrl: string;
}

export const PROVIDERS: Record<LlmProvider, ProviderSpec> = {
  anthropic: {
    label: "Anthropic",
    model: "claude-opus-4-8",
    inputPer1M: 5.0,
    outputPer1M: 25.0,
    getFreeKeyUrl: "https://console.anthropic.com/settings/keys",
  },
  deepseek: {
    label: "DeepSeek",
    model: "deepseek-chat",
    inputPer1M: 0.28,
    outputPer1M: 0.42,
    getFreeKeyUrl: "https://platform.deepseek.com/api_keys",
  },
  openai: {
    label: "OpenAI",
    model: "gpt-5-mini",
    inputPer1M: 0.25,
    outputPer1M: 2.0,
    getFreeKeyUrl: "https://platform.openai.com/api-keys",
  },
  gemini: {
    label: "Google Gemini",
    model: "gemini-2.5-flash",
    inputPer1M: 0.3,
    outputPer1M: 2.5,
    getFreeKeyUrl: "https://aistudio.google.com/app/apikey",
  },
  groq: {
    label: "Groq",
    model: "llama-3.3-70b-versatile",
    inputPer1M: 0.59,
    outputPer1M: 0.79,
    getFreeKeyUrl: "https://console.groq.com/keys",
  },
  openrouter: {
    // OpenAI-compatible aggregator at a fixed endpoint. Default model is the
    // auto-router; the user can override with any OpenRouter model id. Pricing
    // varies per underlying model, so cost shows ~$0 unless a price override is
    // set (customPriceFromProfile also covers openrouter).
    label: "OpenRouter",
    model: "openrouter/auto",
    inputPer1M: 0,
    outputPer1M: 0,
    getFreeKeyUrl: "https://openrouter.ai/keys",
  },
  custom: {
    // User-supplied OpenAI-compatible endpoint. No fixed model (the user types
    // it) and no pricing (self-hosted/unknown → cost shows ~$0).
    label: "Custom (OpenAI-compatible)",
    model: "",
    inputPer1M: 0,
    outputPer1M: 0,
    getFreeKeyUrl: "",
  },
};

// Per-1M-token prices to use instead of the provider's defaults — set for the
// custom (BYO) provider, which has no fixed pricing.
export interface PriceOverride {
  inputPer1M: number;
  outputPer1M: number;
}

export function estimateCostUsd(
  provider: LlmProvider,
  promptTokens: number,
  completionTokens: number,
  override?: PriceOverride,
): number {
  const p = PROVIDERS[provider];
  const inputPer1M = override?.inputPer1M ?? p.inputPer1M;
  const outputPer1M = override?.outputPer1M ?? p.outputPer1M;
  return (
    (promptTokens / 1_000_000) * inputPer1M +
    (completionTokens / 1_000_000) * outputPer1M
  );
}

// Build a price override from the user's profile for the providers with no
// fixed pricing (custom BYO endpoint + OpenRouter, whose cost depends on the
// chosen model), so their cost isn't hardcoded $0. Returns undefined for the
// built-in providers (known pricing) or when the user hasn't entered prices.
export function customPriceFromProfile(
  provider: LlmProvider | string,
  profile:
    | { llm_custom_input_per_1m?: number | null; llm_custom_output_per_1m?: number | null }
    | null
    | undefined,
): PriceOverride | undefined {
  if ((provider !== "custom" && provider !== "openrouter") || !profile) return undefined;
  const inp = profile.llm_custom_input_per_1m;
  const out = profile.llm_custom_output_per_1m;
  if (typeof inp !== "number" && typeof out !== "number") return undefined;
  return { inputPer1M: inp ?? 0, outputPer1M: out ?? 0 };
}

// Rough token estimate when a provider doesn't return usage (~4 chars/token).
function estTokens(s: string): number {
  return Math.ceil(s.length / 4);
}

export interface ChatMessage {
  role: "user" | "assistant";
  content: string;
}

interface GenArgs {
  // Either a single prompt OR a multi-turn message history.
  prompt?: string;
  messages?: ChatMessage[];
  systemPrompt: string;
  apiKey: string;
  model?: string;
  // JSON-object response mode. Defaults to true (workout generation); pass
  // false for free-form coach chat.
  jsonMode?: boolean;
  // OpenAI-compatible base URL for the "custom" provider (e.g.
  // http://host:11434/v1). Ignored by the built-in providers.
  baseUrl?: string;
  // Sampling temperature. Defaults to 0.6 (conversational). Structured workout
  // generation passes a lower value for less variance in the numbers (TSS/load).
  // Ignored by models that reject the parameter (Opus 4.7+, gpt-5/o-series).
  temperature?: number;
  // Reproducible mode for regression/eval runs: forces temperature 0 and passes
  // a fixed `seed` where the provider supports it (OpenAI-compatible).
  deterministic?: boolean;
  seed?: number;
}

// Resolve the effective temperature for a call (deterministic → 0, else 0.6).
function tempOf(args: GenArgs): number {
  if (args.deterministic) return 0;
  return typeof args.temperature === "number" ? args.temperature : 0.6;
}

function turns(args: GenArgs): ChatMessage[] {
  return args.messages ?? [{ role: "user", content: args.prompt ?? "" }];
}
function promptText(args: GenArgs): string {
  return args.systemPrompt + turns(args).map((m) => m.content).join("\n");
}

// ---------------------------------------------------------------------------
// OpenAI-compatible adapter — covers OpenAI, DeepSeek, and Groq, which all
// speak the /chat/completions schema. Only base URL + JSON-mode flag differ.
// ---------------------------------------------------------------------------
// Newer OpenAI models (gpt-5 family, o-series) require `max_completion_tokens`
// instead of `max_tokens` and reject custom temperature values.
export function openAiModernParams(provider: LlmProvider, model: string): boolean {
  return provider === "openai" && /^(gpt-5|o\d|chatgpt)/i.test(model);
}

// Optional ranking/attribution headers OpenRouter uses for its app leaderboard.
// Purely informational — calls work without them; ignored by every other base.
function openRouterHeaders(provider: LlmProvider): Record<string, string> {
  return provider === "openrouter"
    ? { "HTTP-Referer": "https://github.com/workout-maker", "X-Title": "Workout Maker" }
    : {};
}

async function openAiCompatible(
  provider: LlmProvider,
  baseUrl: string,
  args: GenArgs,
): Promise<LlmResult> {
  const model = args.model ?? PROVIDERS[provider].model;
  const body: Record<string, unknown> = {
    model,
    messages: [
      { role: "system", content: args.systemPrompt },
      ...turns(args),
    ],
  };
  if (openAiModernParams(provider, model)) {
    body.max_completion_tokens = 2500;
  } else {
    body.temperature = tempOf(args);
    body.max_tokens = 2500;
  }
  // Reproducible sampling where supported (OpenAI/DeepSeek/Groq honor `seed`).
  if (args.deterministic && typeof args.seed === "number") body.seed = args.seed;
  if (args.jsonMode !== false) body.response_format = { type: "json_object" };

  const res = await fetch(`${baseUrl}/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${args.apiKey}`,
      ...openRouterHeaders(provider),
    },
    body: JSON.stringify(body),
  });

  if (!res.ok) {
    throw new Error(`${provider} HTTP ${res.status}: ${await res.text()}`);
  }
  const data = await res.json();
  const text: string = data.choices?.[0]?.message?.content ?? "";
  return {
    text,
    promptTokens: data.usage?.prompt_tokens ?? estTokens(promptText(args)),
    completionTokens: data.usage?.completion_tokens ?? estTokens(text),
    provider,
    model,
  };
}

// ---------------------------------------------------------------------------
// Anthropic Messages API
// ---------------------------------------------------------------------------

// Opus 4.7+ and Fable removed sampling parameters — sending `temperature`
// returns a 400 on those models, so only include it where still accepted.
export function anthropicAcceptsTemperature(model: string): boolean {
  return !/opus-4-[78]|fable/i.test(model);
}

async function anthropic(args: GenArgs): Promise<LlmResult> {
  const model = args.model ?? PROVIDERS.anthropic.model;
  const body: Record<string, unknown> = {
    model,
    max_tokens: 2500,
    system: args.systemPrompt,
    messages: turns(args),
  };
  if (anthropicAcceptsTemperature(model)) body.temperature = tempOf(args);
  const res = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-api-key": args.apiKey,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    throw new Error(`anthropic HTTP ${res.status}: ${await res.text()}`);
  }
  const data = await res.json();
  const text: string =
    (data.content ?? []).filter((b: { type: string }) => b.type === "text")
      .map((b: { text: string }) => b.text).join("") ?? "";
  return {
    text,
    promptTokens: data.usage?.input_tokens ?? estTokens(promptText(args)),
    completionTokens: data.usage?.output_tokens ?? estTokens(text),
    provider: "anthropic",
    model,
  };
}

// ---------------------------------------------------------------------------
// Google Gemini generateContent API
// ---------------------------------------------------------------------------
async function gemini(args: GenArgs): Promise<LlmResult> {
  const model = args.model ?? PROVIDERS.gemini.model;
  const url =
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${args.apiKey}`;
  const generationConfig: Record<string, unknown> = {
    temperature: tempOf(args),
    maxOutputTokens: 2500,
  };
  if (args.jsonMode !== false) generationConfig.responseMimeType = "application/json";
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: args.systemPrompt }] },
      contents: turns(args).map((m) => ({
        role: m.role === "assistant" ? "model" : "user",
        parts: [{ text: m.content }],
      })),
      generationConfig,
    }),
  });
  if (!res.ok) {
    throw new Error(`gemini HTTP ${res.status}: ${await res.text()}`);
  }
  const data = await res.json();
  const text: string =
    data.candidates?.[0]?.content?.parts?.map((p: { text: string }) => p.text).join("") ?? "";
  return {
    text,
    promptTokens: data.usageMetadata?.promptTokenCount ?? estTokens(promptText(args)),
    completionTokens: data.usageMetadata?.candidatesTokenCount ?? estTokens(text),
    provider: "gemini",
    model,
  };
}

// ---------------------------------------------------------------------------
// Streaming. Each provider family is parsed from its SSE format; onToken fires
// per text delta. Returns the assembled full text. Used by the coach chat.
// ---------------------------------------------------------------------------
async function* sseLines(res: Response): AsyncGenerator<string> {
  const reader = res.body!.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    const parts = buf.split("\n");
    buf = parts.pop() ?? "";
    for (const line of parts) {
      const t = line.trim();
      if (t.startsWith("data:")) yield t.slice(5).trim();
    }
  }
}

export async function llmStream(
  provider: LlmProvider,
  args: GenArgs,
  onToken: (t: string) => void,
): Promise<string> {
  const model = args.model ?? PROVIDERS[provider].model;
  let full = "";

  if (provider === "openai" || provider === "deepseek" || provider === "groq" || provider === "openrouter" || provider === "custom") {
    if (provider === "custom" && !args.baseUrl) throw new Error("custom provider: base URL not configured");
    const base = provider === "openai" ? "https://api.openai.com/v1"
      : provider === "deepseek" ? "https://api.deepseek.com/v1"
      : provider === "openrouter" ? "https://openrouter.ai/api/v1"
      : provider === "custom" ? args.baseUrl!
      : "https://api.groq.com/openai/v1";
    const body: Record<string, unknown> = {
      model,
      messages: [{ role: "system", content: args.systemPrompt }, ...turns(args)],
      stream: true,
    };
    if (openAiModernParams(provider, model)) {
      body.max_completion_tokens = 2500;
    } else {
      body.temperature = 0.6;
      body.max_tokens = 2500;
    }
    const res = await fetch(`${base}/chat/completions`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${args.apiKey}`, ...openRouterHeaders(provider) },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error(`${provider} HTTP ${res.status}: ${await res.text()}`);
    for await (const data of sseLines(res)) {
      if (data === "[DONE]") break;
      try {
        const tok = JSON.parse(data).choices?.[0]?.delta?.content ?? "";
        if (tok) { full += tok; onToken(tok); }
      } catch { /* ignore keep-alives */ }
    }
    return full;
  }

  if (provider === "anthropic") {
    const streamBody: Record<string, unknown> = {
      model, max_tokens: 2500, stream: true,
      system: args.systemPrompt, messages: turns(args),
    };
    if (anthropicAcceptsTemperature(model)) streamBody.temperature = 0.6;
    const res = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-api-key": args.apiKey, "anthropic-version": "2023-06-01" },
      body: JSON.stringify(streamBody),
    });
    if (!res.ok) throw new Error(`anthropic HTTP ${res.status}: ${await res.text()}`);
    for await (const data of sseLines(res)) {
      try {
        const ev = JSON.parse(data);
        if (ev.type === "content_block_delta") {
          const tok = ev.delta?.text ?? "";
          if (tok) { full += tok; onToken(tok); }
        }
      } catch { /* ignore */ }
    }
    return full;
  }

  // gemini
  const url =
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:streamGenerateContent?alt=sse&key=${args.apiKey}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: args.systemPrompt }] },
      contents: turns(args).map((m) => ({ role: m.role === "assistant" ? "model" : "user", parts: [{ text: m.content }] })),
      generationConfig: { temperature: 0.6, maxOutputTokens: 2500 },
    }),
  });
  if (!res.ok) throw new Error(`gemini HTTP ${res.status}: ${await res.text()}`);
  for await (const data of sseLines(res)) {
    try {
      const tok = JSON.parse(data).candidates?.[0]?.content?.parts?.map((p: { text: string }) => p.text).join("") ?? "";
      if (tok) { full += tok; onToken(tok); }
    } catch { /* ignore */ }
  }
  return full;
}

export async function llmGenerate(
  provider: LlmProvider,
  args: GenArgs,
): Promise<LlmResult> {
  const dispatch = (): Promise<LlmResult> => {
    switch (provider) {
      case "openai":
        return openAiCompatible("openai", "https://api.openai.com/v1", args);
      case "deepseek":
        return openAiCompatible("deepseek", "https://api.deepseek.com/v1", args);
      case "groq":
        return openAiCompatible("groq", "https://api.groq.com/openai/v1", args);
      case "openrouter":
        return openAiCompatible("openrouter", "https://openrouter.ai/api/v1", args);
      case "anthropic":
        return anthropic(args);
      case "gemini":
        return gemini(args);
      case "custom":
        if (!args.baseUrl) throw new Error("custom provider: base URL not configured");
        if (!args.model) throw new Error("custom provider: model id not configured");
        return openAiCompatible("custom", args.baseUrl, args);
      default:
        throw new Error(`unknown provider: ${provider}`);
    }
  };
  // One structured line per call: provider, model, latency, token usage (or the
  // error body on failure) — this is the LLM path's only observability surface.
  return log.time("generate", dispatch(), (r) => ({
    provider,
    model: r.model || args.model,
    promptTokens: r.promptTokens,
    completionTokens: r.completionTokens,
  }));
}

export interface FallbackKeyResolver {
  (provider: LlmProvider): Promise<string | null>;
}

export interface FallbackOutcome extends LlmResult {
  attempts: { provider: LlmProvider; error?: string }[];
}

// Per-provider model override (the user's choice from the dynamic model
// selector); undefined → the provider's default model.
export interface ModelResolver {
  (provider: LlmProvider): string | undefined;
}

// Per-provider base URL — only meaningful for the "custom" provider, which has
// no fixed endpoint. undefined → not configured.
export interface BaseUrlResolver {
  (provider: LlmProvider): Promise<string | null>;
}

// Walk [active, ...fallback] in order, skipping providers with no key, until
// one succeeds. Throws with the full attempt log if all fail.
export async function llmGenerateWithFallback(
  chain: LlmProvider[],
  args: Omit<GenArgs, "apiKey">,
  resolveKey: FallbackKeyResolver,
  resolveModel?: ModelResolver,
  resolveBaseUrl?: BaseUrlResolver,
): Promise<FallbackOutcome> {
  const attempts: { provider: LlmProvider; error?: string }[] = [];
  const seen = new Set<LlmProvider>();

  for (const provider of chain) {
    if (seen.has(provider)) continue;
    seen.add(provider);

    const apiKey = await resolveKey(provider);
    if (!apiKey) {
      attempts.push({ provider, error: "no api key configured" });
      continue;
    }
    try {
      const model = args.model ?? resolveModel?.(provider);
      const baseUrl = args.baseUrl ??
        (provider === "custom" ? (await resolveBaseUrl?.(provider)) ?? undefined : undefined);
      const result = await llmGenerate(provider, { ...args, apiKey, model, baseUrl });
      return { ...result, attempts };
    } catch (e) {
      attempts.push({ provider, error: String(e instanceof Error ? e.message : e) });
    }
  }
  throw new Error(
    `all providers in fallback chain failed: ${JSON.stringify(attempts)}`,
  );
}

// Tolerant extraction of the JSON object from a model reply (handles ```json
// fences and leading/trailing prose that some providers emit).
export function extractJson<T = unknown>(text: string): T {
  let t = text.trim();
  const fence = t.match(/```(?:json)?\s*([\s\S]*?)```/i);
  if (fence) t = fence[1].trim();
  const start = t.indexOf("{");
  const end = t.lastIndexOf("}");
  if (start === -1 || end === -1 || end < start) {
    throw new Error("no JSON object found in model output");
  }
  return JSON.parse(t.slice(start, end + 1)) as T;
}
