// ============================================================================
// Native tool-calling loop for the agentic coach.
//
// Anthropic and the OpenAI-compatible providers (OpenAI/DeepSeek/Groq) have
// first-class tool-use APIs that are far more reliable than asking the model
// to emit a JSON action protocol. This module runs the full agentic loop
// (model → tool calls → observations → … → final text) against those APIs.
// Gemini (and any provider error) falls back to the JSON protocol in
// coach-chat, so every configured provider still works.
// ============================================================================

import type { LlmProvider } from "./types.ts";
import {
  anthropicAcceptsTemperature,
  deepseekBodyExtras,
  maxTokensOf,
  openAiModernParams,
  PROVIDERS,
  sseLines,
  streamAbort,
} from "./llm.ts";
import type { ChatMessage } from "./llm.ts";
import {
  anthropicCacheUsage,
  anthropicSystemField,
  estimateTokens,
  openAiCacheUsage,
  shouldCachePrefix,
} from "./llm_cache.ts";

// Per-step deadline — one hung tool-call step shouldn't run to the platform
// wall-clock and stall the whole agentic loop.
const STEP_TIMEOUT_MS = 60_000;

export interface NativeToolDef {
  name: string;
  description: string;
  input_schema: Record<string, unknown>;
}

export interface NativeLoopArgs {
  provider: LlmProvider;
  apiKey: string;
  model?: string;
  systemPrompt: string;
  messages: ChatMessage[];
  tools: NativeToolDef[];
  exec: (name: string, args: Record<string, unknown>) => Promise<string>;
  maxSteps?: number;
  // Same contract as GenArgs in llm.ts: resolved through maxTokensOf(), so the
  // feature budget and hosted ceiling apply here too. This loop is the coach's
  // hot path, which is exactly why its budget stays tight.
  feature?: string;
  maxTokens?: number;
  hosted?: boolean;
  // Stream each step's text as it arrives. The caller cannot know in advance
  // which step produces the final answer (that is what coach_stream.ts's
  // hold-back is for), so EVERY step streams and the caller decides what to
  // show. Usage accounting is unchanged: it still sums across steps, so the
  // caller's single end-of-turn logGeneration stays correct.
  stream?: boolean;
  onDelta?: (t: string) => void;
  // Fired as soon as a step commits to calling a tool, so a narrated preamble
  // ("Let me check your recent runs...") can be discarded before it is shown.
  onToolStart?: () => void;
}

export interface NativeLoopResult {
  text: string;
  toolsUsed: string[];
  // Summed token usage across every API call the loop made (for cost logging).
  promptTokens: number;
  completionTokens: number;
  model: string;
  // How many provider calls this loop actually made. The caller spends these
  // against a per-turn budget, so a turn's total LLM calls stays bounded.
  steps: number;
  // Prompt-cache totals across the loop's calls, a subset of promptTokens.
  // The loop is where caching pays off, so this is where it gets measured.
  cacheWriteTokens?: number;
  cacheReadTokens?: number;
}

export function supportsNativeTools(p: LlmProvider): boolean {
  return p === "anthropic" || p === "openai" || p === "deepseek" || p === "groq" || p === "openrouter";
}

export async function runNativeToolLoop(args: NativeLoopArgs): Promise<NativeLoopResult> {
  if (args.provider === "anthropic") return anthropicLoop(args);
  return openAiCompatibleLoop(args);
}

// One step's outcome, normalized so the loop below reads the same whether the
// step was streamed or not. Streaming changes HOW the response arrives, never
// what the loop does with it.
interface AnthropicStep {
  // deno-lint-ignore no-explicit-any
  content: any[];
  stopReason: string;
  inputTokens: number;
  outputTokens: number;
  cacheWriteTokens: number;
  cacheReadTokens: number;
}

/**
 * Reassemble a streamed Anthropic response into the same content-block array
 * the non-streaming API returns.
 *
 * The fiddly part is tool arguments: they arrive as `input_json_delta`
 * fragments that are only valid JSON once concatenated, so each block's
 * partial_json is accumulated by index and parsed at content_block_stop.
 */
async function anthropicStreamStep(
  res: Response,
  args: NativeLoopArgs,
  reset: () => void,
): Promise<AnthropicStep> {
  // deno-lint-ignore no-explicit-any
  const blocks: any[] = [];
  const partials = new Map<number, string>();
  let stopReason = "end_turn";
  let inputTokens = 0;
  let cacheWriteTokens = 0;
  let cacheReadTokens = 0;
  let outputTokens = 0;
  let toolAnnounced = false;

  for await (const data of sseLines(res, reset)) {
    if (!data || data === "[DONE]") continue;
    let ev: Record<string, unknown>;
    try {
      ev = JSON.parse(data);
    } catch {
      continue;
    }
    // deno-lint-ignore no-explicit-any
    const e = ev as any;
    switch (e.type) {
      case "message_start": {
        inputTokens = e.message?.usage?.input_tokens ?? 0;
        outputTokens = e.message?.usage?.output_tokens ?? 0;
        // Cache counters arrive with the opening usage block, not the delta.
        const cu = anthropicCacheUsage(e.message?.usage);
        cacheWriteTokens = cu.cacheWriteTokens;
        cacheReadTokens = cu.cacheReadTokens;
        break;
      }
      case "content_block_start": {
        const b = e.content_block ?? {};
        blocks[e.index] = b.type === "tool_use"
          ? { type: "tool_use", id: b.id, name: b.name, input: {} }
          : { type: "text", text: "" };
        if (b.type === "tool_use") {
          partials.set(e.index, "");
          // Tell the caller now, not at the end: anything narrated before this
          // was a preamble to a tool call and should not reach the athlete.
          if (!toolAnnounced) {
            toolAnnounced = true;
            args.onToolStart?.();
          }
        }
        break;
      }
      case "content_block_delta": {
        const d = e.delta ?? {};
        if (d.type === "text_delta" && d.text) {
          const blk = blocks[e.index] ??= { type: "text", text: "" };
          blk.text += d.text;
          args.onDelta?.(d.text);
        } else if (d.type === "input_json_delta") {
          partials.set(e.index, (partials.get(e.index) ?? "") + (d.partial_json ?? ""));
        }
        break;
      }
      case "content_block_stop": {
        const raw = partials.get(e.index);
        if (raw !== undefined && blocks[e.index]) {
          try {
            blocks[e.index].input = raw.trim() ? JSON.parse(raw) : {};
          } catch {
            blocks[e.index].input = {};
          }
        }
        break;
      }
      case "message_delta":
        if (e.delta?.stop_reason) stopReason = e.delta.stop_reason;
        // Cumulative, so last write wins rather than summing.
        if (e.usage?.output_tokens != null) outputTokens = e.usage.output_tokens;
        break;
    }
  }
  return {
    content: blocks.filter(Boolean), stopReason, inputTokens, outputTokens,
    cacheWriteTokens, cacheReadTokens,
  };
}

// ---------------------------------------------------------------------------
// Anthropic Messages API: tool_use content blocks ⇄ tool_result blocks.
// ---------------------------------------------------------------------------
async function anthropicLoop(args: NativeLoopArgs): Promise<NativeLoopResult> {
  const model = args.model ?? PROVIDERS.anthropic.model;
  const toolsUsed: string[] = [];
  let promptTokens = 0;
  let completionTokens = 0;
  let cacheWriteTokens = 0;
  let cacheReadTokens = 0;
  // deno-lint-ignore no-explicit-any
  const msgs: any[] = args.messages.map((m) => ({ role: m.role, content: m.content }));

  // THE REASON PROMPT CACHING EXISTS IN THIS REPO. Every iteration of the loop
  // below resends `system` and `tools` unchanged, and there can be up to
  // maxSteps of them for ONE athlete message. On this app's coach that prefix
  // measures ~4,800 tokens (tools ~3,300 + system ~1,500), so an uncached turn
  // pays full input price for it six to twelve times over.
  //
  // The prefix is computed ONCE, out here, precisely because it must be
  // byte-identical on every request in the turn: a cache read is a prefix
  // match, and rebuilding the string per step would risk a difference that
  // silently costs the hit. Tools render before system, so the single
  // breakpoint on the system block covers both.
  const prefixTokens = estimateTokens(args.systemPrompt) +
    estimateTokens(JSON.stringify(args.tools));
  const { cache } = shouldCachePrefix("anthropic", model, args.feature, prefixTokens);
  const systemField = anthropicSystemField(args.systemPrompt, cache);

  let steps = 0;
  for (let step = 0; step < (args.maxSteps ?? 6); step++) {
    steps++;
    // A streamed step must not use the flat per-step deadline: a long healthy
    // answer would be cut off mid-sentence. Abort on silence instead.
    const abort = args.stream ? streamAbort() : null;
    const res = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": args.apiKey,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify({
        model,
        max_tokens: maxTokensOf(args),
        ...(anthropicAcceptsTemperature(model) ? { temperature: 0.6 } : {}),
        system: systemField,
        messages: msgs,
        tools: args.tools,
        ...(args.stream ? { stream: true } : {}),
      }),
      signal: abort ? abort.signal : AbortSignal.timeout(STEP_TIMEOUT_MS),
    });
    if (!res.ok) {
      abort?.done();
      throw new Error(`anthropic HTTP ${res.status}: ${await res.text()}`);
    }

    // deno-lint-ignore no-explicit-any
    let content: any[];
    let stopReason: string;
    if (abort) {
      try {
        const s = await anthropicStreamStep(res, args, abort.reset);
        content = s.content;
        stopReason = s.stopReason;
        promptTokens += s.inputTokens;
        completionTokens += s.outputTokens;
        cacheWriteTokens += s.cacheWriteTokens;
        cacheReadTokens += s.cacheReadTokens;
      } finally {
        abort.done();
      }
    } else {
      const data = await res.json();
      promptTokens += data.usage?.input_tokens ?? 0;
      completionTokens += data.usage?.output_tokens ?? 0;
      const cu = anthropicCacheUsage(data.usage);
      cacheWriteTokens += cu.cacheWriteTokens;
      cacheReadTokens += cu.cacheReadTokens;
      content = data.content ?? [];
      stopReason = data.stop_reason;
    }

    const toolUses = content.filter((b) => b.type === "tool_use");

    if (stopReason !== "tool_use" || toolUses.length === 0) {
      const text = content.filter((b) => b.type === "text").map((b) => b.text).join("");
      return {
        text, toolsUsed, promptTokens, completionTokens, model, steps,
        cacheWriteTokens, cacheReadTokens,
      };
    }

    msgs.push({ role: "assistant", content });
    const results = [];
    for (const tu of toolUses) {
      toolsUsed.push(tu.name);
      const obs = await args.exec(tu.name, (tu.input ?? {}) as Record<string, unknown>);
      results.push({ type: "tool_result", tool_use_id: tu.id, content: obs });
    }
    msgs.push({ role: "user", content: results });
  }
  return {
    text: "", toolsUsed, promptTokens, completionTokens, model, steps,
    cacheWriteTokens: 0, cacheReadTokens,
  };
}

// ---------------------------------------------------------------------------
// OpenAI-compatible /chat/completions: tool_calls ⇄ role:"tool" messages.
// Covers OpenAI, DeepSeek, and Groq.
// ---------------------------------------------------------------------------
const OPENAI_BASES: Partial<Record<LlmProvider, string>> = {
  openai: "https://api.openai.com/v1",
  deepseek: "https://api.deepseek.com/v1",
  groq: "https://api.groq.com/openai/v1",
  openrouter: "https://openrouter.ai/api/v1",
};

interface OpenAiStep {
  // deno-lint-ignore no-explicit-any
  message: any;
  promptTokens: number;
  completionTokens: number;
}

/**
 * Reassemble a streamed OpenAI-compatible response into the same `message`
 * object the non-streaming API returns.
 *
 * tool_calls arrive as fragments keyed by `index`: the id and function name
 * usually land on the first fragment and `arguments` accumulates as a string
 * that only parses once complete. Usage rides a late chunk (on DeepSeek, the
 * last CONTENT chunk, which still has choices), so read it off every chunk.
 */
async function openAiStreamStep(
  res: Response,
  args: NativeLoopArgs,
  reset: () => void,
): Promise<OpenAiStep> {
  let content = "";
  let promptTokens = 0;
  let completionTokens = 0;
  let toolAnnounced = false;
  const calls = new Map<number, { id: string; name: string; arguments: string }>();

  for await (const data of sseLines(res, reset)) {
    if (data === "[DONE]") break;
    // deno-lint-ignore no-explicit-any
    let chunk: any;
    try {
      chunk = JSON.parse(data);
    } catch {
      continue;
    }
    const u = chunk.usage ?? chunk.x_groq?.usage;
    if (u) {
      promptTokens = u.prompt_tokens ?? promptTokens;
      completionTokens = u.completion_tokens ?? completionTokens;
    }
    const delta = chunk.choices?.[0]?.delta;
    if (!delta) continue;
    if (delta.content) {
      content += delta.content;
      args.onDelta?.(delta.content);
    }
    for (const tc of delta.tool_calls ?? []) {
      const i = tc.index ?? 0;
      const cur = calls.get(i) ?? { id: "", name: "", arguments: "" };
      if (tc.id) cur.id = tc.id;
      if (tc.function?.name) cur.name = tc.function.name;
      if (tc.function?.arguments) cur.arguments += tc.function.arguments;
      calls.set(i, cur);
      if (!toolAnnounced) {
        toolAnnounced = true;
        args.onToolStart?.();
      }
    }
  }

  const tool_calls = [...calls.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([, c]) => ({
      id: c.id,
      type: "function",
      function: { name: c.name, arguments: c.arguments },
    }));

  return {
    message: { role: "assistant", content, ...(tool_calls.length ? { tool_calls } : {}) },
    promptTokens,
    completionTokens,
  };
}

async function openAiCompatibleLoop(args: NativeLoopArgs): Promise<NativeLoopResult> {
  const base = OPENAI_BASES[args.provider];
  if (!base) throw new Error(`no native tool support for ${args.provider}`);
  const model = args.model ?? PROVIDERS[args.provider].model;
  const toolsUsed: string[] = [];
  let promptTokens = 0;
  let completionTokens = 0;
  // These providers cache long prefixes themselves; nothing is sent to ask for
  // it, this only reads back what they report so llm:cost can show it.
  let cacheReadTokens = 0;

  // deno-lint-ignore no-explicit-any
  const msgs: any[] = [
    { role: "system", content: args.systemPrompt },
    ...args.messages.map((m) => ({ role: m.role, content: m.content })),
  ];
  const tools = args.tools.map((t) => ({
    type: "function",
    function: { name: t.name, description: t.description, parameters: t.input_schema },
  }));

  let steps = 0;
  for (let step = 0; step < (args.maxSteps ?? 6); step++) {
    steps++;
    const abort = args.stream ? streamAbort() : null;
    const res = await fetch(`${base}/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${args.apiKey}`,
        ...(args.provider === "openrouter"
          ? { "HTTP-Referer": "https://github.com/workout-maker", "X-Title": "Workout Maker" }
          : {}),
      },
      body: JSON.stringify({
        model,
        messages: msgs,
        tools,
        ...(args.stream
          ? { stream: true, ...(args.provider === "custom" ? {} : { stream_options: { include_usage: true } }) }
          : {}),
        ...deepseekBodyExtras(args.provider, model),
        ...(openAiModernParams(args.provider, model)
          ? { max_completion_tokens: maxTokensOf(args) }
          : { temperature: 0.6, max_tokens: maxTokensOf(args) }),
      }),
      signal: abort ? abort.signal : AbortSignal.timeout(STEP_TIMEOUT_MS),
    });
    if (!res.ok) {
      abort?.done();
      throw new Error(`${args.provider} HTTP ${res.status}: ${await res.text()}`);
    }

    // deno-lint-ignore no-explicit-any
    let msg: any;
    if (abort) {
      try {
        const s = await openAiStreamStep(res, args, abort.reset);
        msg = s.message;
        promptTokens += s.promptTokens;
        completionTokens += s.completionTokens;
      } finally {
        abort.done();
      }
    } else {
      const data = await res.json();
      promptTokens += data.usage?.prompt_tokens ?? 0;
      cacheReadTokens += openAiCacheUsage(data.usage).cacheReadTokens;
      completionTokens += data.usage?.completion_tokens ?? 0;
      msg = data.choices?.[0]?.message;
    }
    if (!msg) throw new Error(`${args.provider}: empty completion`);

    const calls = msg.tool_calls ?? [];
    if (!calls.length) {
      return {
        text: msg.content ?? "", toolsUsed, promptTokens, completionTokens, model, steps,
        cacheWriteTokens: 0, cacheReadTokens,
      };
    }

    msgs.push(msg);
    for (const call of calls) {
      const name = call.function?.name ?? "";
      toolsUsed.push(name);
      let parsed: Record<string, unknown> = {};
      try { parsed = JSON.parse(call.function?.arguments || "{}"); } catch { /* bad args → {} */ }
      const obs = await args.exec(name, parsed);
      msgs.push({ role: "tool", tool_call_id: call.id, content: obs });
    }
  }
  return { text: "", toolsUsed, promptTokens, completionTokens, model, steps };
}
