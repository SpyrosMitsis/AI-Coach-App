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

// ---------------------------------------------------------------------------
// Streaming steps.
//
// Every step streams (the caller cannot know which one produces the final
// answer), so the loop must reassemble streamed responses into exactly the
// shape the non-streaming path produces. The load-bearing assertion is COST
// PARITY: streamed usage must equal the 22/13 the non-streamed tests above
// pin, or the same turn would be billed differently by transport.
// ---------------------------------------------------------------------------

/** Serve a sequence of SSE bodies, one per call. */
function withSequencedSse<T>(bodies: string[], fn: () => Promise<T>): Promise<T> {
  const orig = globalThis.fetch;
  let i = 0;
  globalThis.fetch = (() => {
    const body = bodies[Math.min(i, bodies.length - 1)];
    i++;
    return Promise.resolve(
      new Response(body, { status: 200, headers: { "Content-Type": "text/event-stream" } }),
    );
  }) as typeof fetch;
  return fn().finally(() => { globalThis.fetch = orig; });
}

const sse = (...events: string[]) => events.map((e) => `data: ${e}`).join("\n\n") + "\n\n";

Deno.test("anthropic streamed: text preamble then a tool call, then a streamed answer", async () => {
  const deltas: string[] = [];
  let toolStarts = 0;
  const out = await withSequencedSse(
    [
      // Step 1: narrate, then call the tool. Tool args arrive as fragments.
      sse(
        `{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}`,
        `{"type":"content_block_start","index":0,"content_block":{"type":"text"}}`,
        `{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me check."}}`,
        `{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1","name":"get_fitness"}}`,
        `{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"days\\":"}}`,
        `{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"28}"}}`,
        `{"type":"content_block_stop","index":1}`,
        `{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}`,
      ),
      // Step 2: the real answer.
      sse(
        `{"type":"message_start","message":{"usage":{"input_tokens":12,"output_tokens":0}}}`,
        `{"type":"content_block_start","index":0,"content_block":{"type":"text"}}`,
        `{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"You're carrying "}}`,
        `{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"a little fatigue."}}`,
        `{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":8}}`,
      ),
    ],
    () =>
      runNativeToolLoop({
        provider: "anthropic",
        apiKey: "sk-test",
        systemPrompt: "sys",
        messages: [{ role: "user", content: "how's my fitness?" }],
        tools: TOOLS,
        exec: () => Promise.resolve("ctl 40, atl 50"),
        stream: true,
        onDelta: (t) => deltas.push(t),
        onToolStart: () => toolStarts++,
      }),
  );
  assertEquals(out.text, "You're carrying a little fatigue.");
  assertEquals(out.toolsUsed, ["get_fitness"]);
  // The preamble WAS streamed; discarding it is coach_stream.ts's job, and it
  // needs the onToolStart signal to know to.
  assertEquals(deltas, ["Let me check.", "You're carrying ", "a little fatigue."]);
  assertEquals(toolStarts, 1);
  // COST PARITY with the non-streamed anthropic test above.
  assertEquals(out.promptTokens, 22);
  assertEquals(out.completionTokens, 13);
});

Deno.test("anthropic streamed: fragmented tool arguments reassemble into real JSON", async () => {
  const seen: Record<string, unknown>[] = [];
  await withSequencedSse(
    [
      sse(
        `{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"get_fitness"}}`,
        `{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"week_st"}}`,
        `{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"art\\":\\"2026-07-20\\"}"}}`,
        `{"type":"content_block_stop","index":0}`,
        `{"type":"message_delta","delta":{"stop_reason":"tool_use"}}`,
      ),
      sse(
        `{"type":"content_block_start","index":0,"content_block":{"type":"text"}}`,
        `{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"done"}}`,
        `{"type":"message_delta","delta":{"stop_reason":"end_turn"}}`,
      ),
    ],
    () =>
      runNativeToolLoop({
        provider: "anthropic",
        apiKey: "k",
        systemPrompt: "s",
        messages: [{ role: "user", content: "q" }],
        tools: TOOLS,
        exec: (_n, a) => { seen.push(a); return Promise.resolve("ok"); },
        stream: true,
      }),
  );
  // Neither fragment is valid JSON alone.
  assertEquals(seen, [{ week_start: "2026-07-20" }]);
});

Deno.test("openai streamed: tool_call fragments reassemble, usage rides a late chunk", async () => {
  const deltas: string[] = [];
  let toolStarts = 0;
  const seen: Record<string, unknown>[] = [];
  const out = await withSequencedSse(
    [
      sse(
        `{"choices":[{"delta":{"content":"One sec."}}]}`,
        `{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"get_fitness","arguments":"{\\"da"}}]}}]}`,
        `{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ys\\":14}"}}]}}]}`,
        `{"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}`,
        "[DONE]",
      ),
      sse(
        `{"choices":[{"delta":{"content":"You're fresh."}}],"usage":{"prompt_tokens":12,"completion_tokens":8}}`,
        "[DONE]",
      ),
    ],
    () =>
      runNativeToolLoop({
        provider: "deepseek",
        apiKey: "k",
        systemPrompt: "s",
        messages: [{ role: "user", content: "q" }],
        tools: TOOLS,
        exec: (_n, a) => { seen.push(a); return Promise.resolve("ok"); },
        stream: true,
        onDelta: (t) => deltas.push(t),
        onToolStart: () => toolStarts++,
      }),
  );
  assertEquals(out.text, "You're fresh.");
  assertEquals(out.toolsUsed, ["get_fitness"]);
  // Arguments only parse once both fragments are concatenated.
  assertEquals(seen, [{ days: 14 }]);
  assertEquals(deltas, ["One sec.", "You're fresh."]);
  assertEquals(toolStarts, 1);
  // COST PARITY with the non-streamed openai test above.
  assertEquals(out.promptTokens, 22);
  assertEquals(out.completionTokens, 13);
});

Deno.test("streamed steps ask for usage, except on custom endpoints", async () => {
  let body: Record<string, unknown> = {};
  const orig = globalThis.fetch;
  globalThis.fetch = ((_u: string, init?: RequestInit) => {
    body = JSON.parse(String(init?.body ?? "{}"));
    return Promise.resolve(
      new Response(sse(`{"choices":[{"delta":{"content":"hi"}}]}`, "[DONE]"), { status: 200 }),
    );
  }) as unknown as typeof fetch;
  try {
    await runNativeToolLoop({
      provider: "deepseek", apiKey: "k", systemPrompt: "s",
      messages: [{ role: "user", content: "q" }], tools: TOOLS,
      exec: () => Promise.resolve("ok"), stream: true,
    });
    assertEquals(body.stream, true);
    assertEquals(body.stream_options, { include_usage: true });
    // The v4 thinking guard must survive on the streamed path too.
    assertEquals(body.thinking, { type: "disabled" });
  } finally {
    globalThis.fetch = orig;
  }
});

Deno.test("a non-streamed loop still sends no stream flag (the default path is untouched)", async () => {
  let body: Record<string, unknown> = {};
  const orig = globalThis.fetch;
  globalThis.fetch = ((_u: string, init?: RequestInit) => {
    body = JSON.parse(String(init?.body ?? "{}"));
    return Promise.resolve(
      new Response(JSON.stringify({ choices: [{ message: { content: "hi" } }] }), { status: 200 }),
    );
  }) as unknown as typeof fetch;
  try {
    await runNativeToolLoop({
      provider: "deepseek", apiKey: "k", systemPrompt: "s",
      messages: [{ role: "user", content: "q" }], tools: TOOLS,
      exec: () => Promise.resolve("ok"),
    });
    assertEquals(body.stream, undefined);
    assertEquals(body.stream_options, undefined);
  } finally {
    globalThis.fetch = orig;
  }
});
