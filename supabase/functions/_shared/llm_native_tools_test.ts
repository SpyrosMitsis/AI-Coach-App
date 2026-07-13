import { assert, assertEquals } from "jsr:@std/assert@1";
import { type NativeToolDef, runNativeToolLoop, supportsNativeTools } from "./llm_native_tools.ts";
import type { LlmProvider } from "./types.ts";

// A fetch stub that returns a different canned Response per call, in sequence.
function withSequencedFetch<T>(responses: unknown[], fn: () => Promise<T>): Promise<T> {
  const orig = globalThis.fetch;
  let i = 0;
  globalThis.fetch = (() => {
    const body = responses[Math.min(i, responses.length - 1)];
    i++;
    return Promise.resolve(
      new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } }),
    );
  }) as typeof fetch;
  return fn().finally(() => { globalThis.fetch = orig; });
}

const TOOLS: NativeToolDef[] = [
  { name: "get_fitness", description: "fitness", input_schema: { type: "object", properties: {} } },
];

Deno.test("supportsNativeTools: anthropic + openai-compatible yes, gemini/custom no", () => {
  for (const p of ["anthropic", "openai", "deepseek", "groq", "openrouter"]) {
    assert(supportsNativeTools(p as LlmProvider), `${p} should support native tools`);
  }
  assert(!supportsNativeTools("gemini" as LlmProvider));
  assert(!supportsNativeTools("custom" as LlmProvider));
});

Deno.test("anthropic loop: runs a tool, feeds the observation back, returns final text", async () => {
  const calls: { name: string; args: Record<string, unknown> }[] = [];
  const out = await withSequencedFetch(
    [
      // turn 1: model calls the tool
      {
        stop_reason: "tool_use",
        content: [{ type: "tool_use", id: "t1", name: "get_fitness", input: { days: 28 } }],
        usage: { input_tokens: 10, output_tokens: 5 },
      },
      // turn 2: model answers
      {
        stop_reason: "end_turn",
        content: [{ type: "text", text: "You're carrying a little fatigue." }],
        usage: { input_tokens: 12, output_tokens: 8 },
      },
    ],
    () =>
      runNativeToolLoop({
        provider: "anthropic",
        apiKey: "sk-test",
        systemPrompt: "sys",
        messages: [{ role: "user", content: "how's my fitness?" }],
        tools: TOOLS,
        exec: (name, args) => { calls.push({ name, args }); return Promise.resolve("ctl 40, atl 50"); },
      }),
  );
  assertEquals(out.text, "You're carrying a little fatigue.");
  assertEquals(out.toolsUsed, ["get_fitness"]);
  assertEquals(calls, [{ name: "get_fitness", args: { days: 28 } }]);
  // Token usage is summed across both API calls.
  assertEquals(out.promptTokens, 22);
  assertEquals(out.completionTokens, 13);
});

Deno.test("openai loop: tool_calls then final content", async () => {
  const calls: string[] = [];
  const out = await withSequencedFetch(
    [
      {
        choices: [{ message: { tool_calls: [{ id: "c1", function: { name: "get_fitness", arguments: "{}" } }] } }],
        usage: { prompt_tokens: 10, completion_tokens: 5 },
      },
      { choices: [{ message: { content: "Fresh and ready." } }], usage: { prompt_tokens: 4, completion_tokens: 3 } },
    ],
    () =>
      runNativeToolLoop({
        provider: "openai",
        apiKey: "sk-test",
        systemPrompt: "sys",
        messages: [{ role: "user", content: "fitness?" }],
        tools: TOOLS,
        exec: (name) => { calls.push(name); return Promise.resolve("ctl 40"); },
      }),
  );
  assertEquals(out.text, "Fresh and ready.");
  assertEquals(calls, ["get_fitness"]);
});

Deno.test("openai loop: malformed tool arguments degrade to {} instead of throwing", async () => {
  let seen: Record<string, unknown> | null = null;
  await withSequencedFetch(
    [
      {
        choices: [{ message: { tool_calls: [{ id: "c1", function: { name: "get_fitness", arguments: "{not json" } }] } }],
        usage: {},
      },
      { choices: [{ message: { content: "ok" } }], usage: {} },
    ],
    () =>
      runNativeToolLoop({
        provider: "openai",
        apiKey: "sk-test",
        systemPrompt: "sys",
        messages: [{ role: "user", content: "x" }],
        tools: TOOLS,
        exec: (_n, args) => { seen = args; return Promise.resolve("obs"); },
      }),
  );
  assertEquals(seen, {});
});

Deno.test("maxSteps exhaustion returns empty text (the JSON-fallback trigger)", async () => {
  // Model keeps calling the tool forever; with maxSteps:2 the loop gives up and
  // returns "" — exactly the case coach-chat must not re-execute write tools on.
  const out = await withSequencedFetch(
    [{
      stop_reason: "tool_use",
      content: [{ type: "tool_use", id: "t1", name: "get_fitness", input: {} }],
      usage: { input_tokens: 1, output_tokens: 1 },
    }],
    () =>
      runNativeToolLoop({
        provider: "anthropic",
        apiKey: "sk-test",
        systemPrompt: "sys",
        messages: [{ role: "user", content: "loop" }],
        tools: TOOLS,
        exec: () => Promise.resolve("ctl 40"),
        maxSteps: 2,
      }),
  );
  assertEquals(out.text, "");
  assertEquals(out.toolsUsed.length, 2);
});
