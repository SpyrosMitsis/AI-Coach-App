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
import { anthropicAcceptsTemperature, openAiModernParams, PROVIDERS } from "./llm.ts";
import type { ChatMessage } from "./llm.ts";

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
}

export interface NativeLoopResult {
  text: string;
  toolsUsed: string[];
  // Summed token usage across every API call the loop made (for cost logging).
  promptTokens: number;
  completionTokens: number;
  model: string;
}

export function supportsNativeTools(p: LlmProvider): boolean {
  return p === "anthropic" || p === "openai" || p === "deepseek" || p === "groq" || p === "openrouter";
}

export async function runNativeToolLoop(args: NativeLoopArgs): Promise<NativeLoopResult> {
  if (args.provider === "anthropic") return anthropicLoop(args);
  return openAiCompatibleLoop(args);
}

// ---------------------------------------------------------------------------
// Anthropic Messages API: tool_use content blocks ⇄ tool_result blocks.
// ---------------------------------------------------------------------------
async function anthropicLoop(args: NativeLoopArgs): Promise<NativeLoopResult> {
  const model = args.model ?? PROVIDERS.anthropic.model;
  const toolsUsed: string[] = [];
  let promptTokens = 0;
  let completionTokens = 0;
  // deno-lint-ignore no-explicit-any
  const msgs: any[] = args.messages.map((m) => ({ role: m.role, content: m.content }));

  for (let step = 0; step < (args.maxSteps ?? 6); step++) {
    const res = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-api-key": args.apiKey,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify({
        model,
        max_tokens: 2500,
        ...(anthropicAcceptsTemperature(model) ? { temperature: 0.6 } : {}),
        system: args.systemPrompt,
        messages: msgs,
        tools: args.tools,
      }),
    });
    if (!res.ok) throw new Error(`anthropic HTTP ${res.status}: ${await res.text()}`);
    const data = await res.json();
    promptTokens += data.usage?.input_tokens ?? 0;
    completionTokens += data.usage?.output_tokens ?? 0;
    // deno-lint-ignore no-explicit-any
    const content: any[] = data.content ?? [];
    const toolUses = content.filter((b) => b.type === "tool_use");

    if (data.stop_reason !== "tool_use" || toolUses.length === 0) {
      const text = content.filter((b) => b.type === "text").map((b) => b.text).join("");
      return { text, toolsUsed, promptTokens, completionTokens, model };
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
  return { text: "", toolsUsed, promptTokens, completionTokens, model };
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

async function openAiCompatibleLoop(args: NativeLoopArgs): Promise<NativeLoopResult> {
  const base = OPENAI_BASES[args.provider];
  if (!base) throw new Error(`no native tool support for ${args.provider}`);
  const model = args.model ?? PROVIDERS[args.provider].model;
  const toolsUsed: string[] = [];
  let promptTokens = 0;
  let completionTokens = 0;

  // deno-lint-ignore no-explicit-any
  const msgs: any[] = [
    { role: "system", content: args.systemPrompt },
    ...args.messages.map((m) => ({ role: m.role, content: m.content })),
  ];
  const tools = args.tools.map((t) => ({
    type: "function",
    function: { name: t.name, description: t.description, parameters: t.input_schema },
  }));

  for (let step = 0; step < (args.maxSteps ?? 6); step++) {
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
        ...(openAiModernParams(args.provider, model)
          ? { max_completion_tokens: 2500 }
          : { temperature: 0.6, max_tokens: 2500 }),
      }),
    });
    if (!res.ok) throw new Error(`${args.provider} HTTP ${res.status}: ${await res.text()}`);
    const data = await res.json();
    promptTokens += data.usage?.prompt_tokens ?? 0;
    completionTokens += data.usage?.completion_tokens ?? 0;
    const msg = data.choices?.[0]?.message;
    if (!msg) throw new Error(`${args.provider}: empty completion`);

    const calls = msg.tool_calls ?? [];
    if (!calls.length) return { text: msg.content ?? "", toolsUsed, promptTokens, completionTokens, model };

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
  return { text: "", toolsUsed, promptTokens, completionTokens, model };
}
