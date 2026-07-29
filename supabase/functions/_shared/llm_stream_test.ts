import { assert, assertEquals } from "jsr:@std/assert@1";
import { llmStream } from "./llm.ts";
import type { LlmProvider } from "./types.ts";

// ---------------------------------------------------------------------------
// llmStream usage capture.
//
// WHY THIS FILE EXISTS. Every LLM call must land a generation_logs row;
// hosted_spend() SUMs those rows and quota.ts fails CLOSED on the total. Before
// this, llmStream returned a bare string and captured no usage at all, which is
// why docs/LLM_COSTS.md carried the warning that streamed spend would "go
// dark". The tests that matter most here are the ones asserting a stream still
// reports non-zero tokens even when the provider tells us nothing.
// ---------------------------------------------------------------------------

/** Serve one SSE body, and capture the request the code under test sent. */
function withSse<T>(
  sse: string,
  fn: () => Promise<T>,
): Promise<{ result: T; body: Record<string, unknown> }> {
  const orig = globalThis.fetch;
  let sent: Record<string, unknown> = {};
  globalThis.fetch = ((_url: string, init?: RequestInit) => {
    sent = JSON.parse(String(init?.body ?? "{}"));
    return Promise.resolve(
      new Response(sse, { status: 200, headers: { "Content-Type": "text/event-stream" } }),
    );
  }) as unknown as typeof fetch;
  return fn()
    .then((result) => ({ result, body: sent }))
    .finally(() => {
      globalThis.fetch = orig;
    });
}

const ARGS = { prompt: "hi", systemPrompt: "sys", apiKey: "k" };

const openAiSse = (usageChunk: string | null) =>
  [
    `data: {"choices":[{"delta":{"content":"Hello"}}]}`,
    `data: {"choices":[{"delta":{"content":" there"}}]}`,
    ...(usageChunk ? [`data: ${usageChunk}`] : []),
    "data: [DONE]",
    "",
  ].join("\n\n");

Deno.test("openai-compatible: deltas concatenate and provider usage wins", async () => {
  const tokens: string[] = [];
  const { result } = await withSse(
    // Usage rides the LAST CONTENT chunk on DeepSeek (verified live), not a
    // separate empty-choices chunk. Shaped that way on purpose.
    openAiSse(`{"choices":[{"delta":{"content":"!"}}],"usage":{"prompt_tokens":22,"completion_tokens":13}}`),
    () => llmStream("deepseek", ARGS, (t) => tokens.push(t)),
  );
  assertEquals(result.text, "Hello there!");
  assertEquals(tokens, ["Hello", " there", "!"]);
  assertEquals(result.promptTokens, 22);
  assertEquals(result.completionTokens, 13);
  assertEquals(result.usageEstimated, false);
  assertEquals(result.provider, "deepseek");
});

// THE COST-BLINDNESS REGRESSION. A provider that returns no usage must still
// produce a non-zero, clearly-flagged estimate. A zero here is spend the cap
// cannot see.
Deno.test("a stream with no usage at all still reports non-zero estimated tokens", async () => {
  const { result } = await withSse(openAiSse(null), () => llmStream("deepseek", ARGS, () => {}));
  assertEquals(result.text, "Hello there");
  assert(result.promptTokens > 0, "prompt tokens must never be reported as zero");
  assert(result.completionTokens > 0, "completion tokens must never be reported as zero");
  assertEquals(result.usageEstimated, true);
});

Deno.test("groq usage under x_groq is read when the standard field is absent", async () => {
  const { result } = await withSse(
    [
      `data: {"choices":[{"delta":{"content":"hi"}}]}`,
      `data: {"choices":[],"x_groq":{"usage":{"prompt_tokens":7,"completion_tokens":3}}}`,
      "data: [DONE]",
      "",
    ].join("\n\n"),
    () => llmStream("groq", ARGS, () => {}),
  );
  assertEquals(result.promptTokens, 7);
  assertEquals(result.completionTokens, 3);
  assertEquals(result.usageEstimated, false);
});

Deno.test("include_usage is requested for known providers but never for custom", async () => {
  for (const p of ["openai", "deepseek", "groq", "openrouter"] as LlmProvider[]) {
    const { body } = await withSse(openAiSse(null), () => llmStream(p, ARGS, () => {}));
    assertEquals(
      body.stream_options,
      { include_usage: true },
      `${p} should ask for streamed usage`,
    );
  }
  // An unknown body key can 400 a strict self-hosted endpoint.
  const { body } = await withSse(
    openAiSse(null),
    () => llmStream("custom", { ...ARGS, baseUrl: "http://localhost:11434/v1" }, () => {}),
  );
  assertEquals(body.stream_options, undefined);
});

Deno.test("streaming honours tempOf, not a hardcoded 0.6", async () => {
  const { body } = await withSse(
    openAiSse(null),
    () => llmStream("deepseek", { ...ARGS, deterministic: true, seed: 5 }, () => {}),
  );
  assertEquals(body.temperature, 0);
  assertEquals(body.seed, 5);

  const { body: warm } = await withSse(
    openAiSse(null),
    () => llmStream("deepseek", { ...ARGS, temperature: 0.9 }, () => {}),
  );
  assertEquals(warm.temperature, 0.9);
});

Deno.test("deepseek streams also disable thinking mode", async () => {
  const { body } = await withSse(openAiSse(null), () => llmStream("deepseek", ARGS, () => {}));
  assertEquals(body.thinking, { type: "disabled" });
});

Deno.test("anthropic: input tokens once, cumulative output tokens last-wins", async () => {
  const { result } = await withSse(
    [
      `data: {"type":"message_start","message":{"usage":{"input_tokens":40,"output_tokens":1}}}`,
      `data: {"type":"content_block_delta","delta":{"text":"Nice "}}`,
      `data: {"type":"content_block_delta","delta":{"text":"work"}}`,
      // Cumulative, so the LAST value is the total, not the sum of these two.
      `data: {"type":"message_delta","usage":{"output_tokens":5}}`,
      `data: {"type":"message_delta","usage":{"output_tokens":9}}`,
      "",
    ].join("\n\n"),
    () => llmStream("anthropic", ARGS, () => {}),
  );
  assertEquals(result.text, "Nice work");
  assertEquals(result.promptTokens, 40);
  assertEquals(result.completionTokens, 9);
  assertEquals(result.usageEstimated, false);
});

Deno.test("gemini: the last usageMetadata wins", async () => {
  const { result } = await withSse(
    [
      `data: {"candidates":[{"content":{"parts":[{"text":"a"}]}}],"usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":1}}`,
      `data: {"candidates":[{"content":{"parts":[{"text":"b"}]}}],"usageMetadata":{"promptTokenCount":11,"candidatesTokenCount":2}}`,
      "",
    ].join("\n\n"),
    () => llmStream("gemini", ARGS, () => {}),
  );
  assertEquals(result.text, "ab");
  assertEquals(result.promptTokens, 11);
  assertEquals(result.completionTokens, 2);
});

Deno.test("a malformed chunk is skipped, not fatal", async () => {
  const { result } = await withSse(
    [
      `data: {"choices":[{"delta":{"content":"ok"}}]}`,
      `data: {not json`,
      `data: {"choices":[{"delta":{"content":"!"}}],"usage":{"prompt_tokens":1,"completion_tokens":2}}`,
      "data: [DONE]",
      "",
    ].join("\n\n"),
    () => llmStream("deepseek", ARGS, () => {}),
  );
  assertEquals(result.text, "ok!");
  assertEquals(result.completionTokens, 2);
});

Deno.test("an HTTP error surfaces instead of returning empty text", async () => {
  const orig = globalThis.fetch;
  globalThis.fetch = (() =>
    Promise.resolve(new Response("nope", { status: 429 }))) as unknown as typeof fetch;
  try {
    let threw = false;
    await llmStream("deepseek", ARGS, () => {}).catch(() => {
      threw = true;
    });
    assert(threw, "a 429 must reject, not resolve with an empty reply");
  } finally {
    globalThis.fetch = orig;
  }
});
