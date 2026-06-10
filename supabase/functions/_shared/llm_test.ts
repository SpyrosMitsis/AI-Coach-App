import { assert, assertEquals, assertRejects } from "jsr:@std/assert@1";
import { extractJson, llmGenerateWithFallback } from "./llm.ts";
import type { LlmProvider } from "./types.ts";

// ---------------------------------------------------------------------------
// extractJson
// ---------------------------------------------------------------------------

Deno.test("extractJson: plain object", () => {
  assertEquals(extractJson(`{"a":1}`), { a: 1 });
});

Deno.test("extractJson: ```json fence", () => {
  assertEquals(extractJson("```json\n{\"a\":1}\n```"), { a: 1 });
});

Deno.test("extractJson: bare ``` fence", () => {
  assertEquals(extractJson("```\n{\"a\":1}\n```"), { a: 1 });
});

Deno.test("extractJson: leading/trailing prose", () => {
  assertEquals(extractJson(`Sure! Here is your workout:\n{"a":{"b":2}}\nEnjoy!`), { a: { b: 2 } });
});

Deno.test("extractJson: no JSON throws", () => {
  let threw = false;
  try { extractJson("no json here"); } catch { threw = true; }
  assert(threw);
});

// ---------------------------------------------------------------------------
// llmGenerateWithFallback — stub fetch so no network is touched.
// ---------------------------------------------------------------------------

function openAiResponse(text: string): Response {
  return new Response(JSON.stringify({
    choices: [{ message: { content: text } }],
    usage: { prompt_tokens: 10, completion_tokens: 5 },
  }), { status: 200, headers: { "Content-Type": "application/json" } });
}

function withFetchStub<T>(stub: (url: string) => Response, fn: () => Promise<T>): Promise<T> {
  const orig = globalThis.fetch;
  globalThis.fetch = ((input: URL | Request | string) =>
    Promise.resolve(stub(String(input instanceof Request ? input.url : input)))) as typeof fetch;
  return fn().finally(() => { globalThis.fetch = orig; });
}

Deno.test("fallback: skips providers without keys, succeeds on the first keyed one", async () => {
  const keys: Record<string, string | null> = { groq: null, openai: "sk-test" };
  const out = await withFetchStub(
    () => openAiResponse("hello"),
    () => llmGenerateWithFallback(
      ["groq", "openai"] as LlmProvider[],
      { prompt: "hi", systemPrompt: "sys" },
      (p) => Promise.resolve(keys[p] ?? null),
    ),
  );
  assertEquals(out.provider, "openai");
  assertEquals(out.text, "hello");
  assertEquals(out.attempts, [{ provider: "groq", error: "no api key configured" }]);
});

Deno.test("fallback: walks past an HTTP failure to the next provider", async () => {
  const out = await withFetchStub(
    (url) => url.includes("api.groq.com")
      ? new Response("rate limited", { status: 429 })
      : openAiResponse("from openai"),
    () => llmGenerateWithFallback(
      ["groq", "openai"] as LlmProvider[],
      { prompt: "hi", systemPrompt: "sys" },
      () => Promise.resolve("sk-test"),
    ),
  );
  assertEquals(out.provider, "openai");
  assertEquals(out.attempts.length, 1);
  assert(out.attempts[0].error!.includes("429"));
});

Deno.test("fallback: de-duplicates repeated providers in the chain", async () => {
  let calls = 0;
  await assertRejects(() =>
    withFetchStub(
      () => { calls++; return new Response("boom", { status: 500 }); },
      () => llmGenerateWithFallback(
        ["openai", "openai", "openai"] as LlmProvider[],
        { prompt: "hi", systemPrompt: "sys" },
        () => Promise.resolve("sk-test"),
      ),
    ));
  assertEquals(calls, 1);
});

Deno.test("fallback: all fail → throws with the attempt log", async () => {
  const err = await assertRejects(() =>
    llmGenerateWithFallback(
      ["groq", "gemini"] as LlmProvider[],
      { prompt: "hi", systemPrompt: "sys" },
      () => Promise.resolve(null),
    ));
  assert(String(err).includes("no api key configured"));
});
