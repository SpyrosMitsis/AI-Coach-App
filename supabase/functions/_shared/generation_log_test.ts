import { assert, assertEquals } from "jsr:@std/assert@1";
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { generationLogRow, logGeneration } from "./generation_log.ts";

// ---------------------------------------------------------------------------
// generationLogRow — the column mapping + cost maths, no DB needed
// ---------------------------------------------------------------------------

Deno.test("generationLogRow: prices a known provider from its token counts", () => {
  const row = generationLogRow("u1", {
    feature: "chat",
    hosted: true,
    provider: "gemini",
    model: "gemini-2.5-flash",
    promptTokens: 1_000_000,
    completionTokens: 1_000_000,
  });
  assertEquals(row.user_id, "u1");
  assertEquals(row.feature, "chat");
  assertEquals(row.hosted, true);
  // gemini: $0.30 in + $2.50 out per 1M.
  assertEquals(row.estimated_cost_usd, 2.8);
  assertEquals(row.parsed_ok, true);
});

Deno.test("generationLogRow: a failure row claims no cost rather than $0 spent", () => {
  const row = generationLogRow("u1", {
    feature: "workout",
    hosted: false,
    provider: null,
    parsedOk: false,
    error: "all providers failed",
  });
  // null, not 0: nothing was spent because nothing ran, and a 0 would be
  // indistinguishable from a real free call in the report.
  assertEquals(row.estimated_cost_usd, null);
  assertEquals(row.parsed_ok, false);
  assertEquals(row.error, "all providers failed");
  assertEquals(row.prompt_tokens, null);
});

Deno.test("generationLogRow: custom provider uses the athlete's own prices", () => {
  const row = generationLogRow("u1", {
    feature: "plan",
    hosted: false,
    provider: "custom",
    model: "my-local-model",
    promptTokens: 1_000_000,
    completionTokens: 0,
    profile: { llm_custom_input_per_1m: 7, llm_custom_output_per_1m: 21 },
  });
  assertEquals(row.estimated_cost_usd, 7);
});

Deno.test("generationLogRow: empty tools_used stores null, not an empty array", () => {
  const withTools = generationLogRow("u1", {
    feature: "chat",
    hosted: false,
    provider: "groq",
    promptTokens: 10,
    completionTokens: 10,
    toolsUsed: ["get_fitness"],
  });
  const without = generationLogRow("u1", {
    feature: "chat",
    hosted: false,
    provider: "groq",
    promptTokens: 10,
    completionTokens: 10,
    toolsUsed: [],
  });
  assertEquals(withTools.tools_used, ["get_fitness"]);
  assertEquals(without.tools_used, null);
});

Deno.test("generationLogRow: memory calls are attributable and metered", () => {
  // The regression this module exists for: agent_memory fired hosted calls that
  // wrote no row, so they were invisible AND uncounted by hosted_spend().
  const row = generationLogRow("u1", {
    feature: "memory",
    hosted: true,
    provider: "gemini",
    model: "gemini-2.5-flash",
    promptTokens: 4_000,
    completionTokens: 500,
  });
  assertEquals(row.feature, "memory");
  assertEquals(row.hosted, true);
  assert((row.estimated_cost_usd as number) > 0, "a hosted memory call must cost something");
});

// ---------------------------------------------------------------------------
// logGeneration — must never throw; a cost row is not worth a failed reply
// ---------------------------------------------------------------------------

function fakeAdmin(onInsert: (row: unknown) => unknown): SupabaseClient {
  return {
    from: () => ({ insert: (row: unknown) => Promise.resolve(onInsert(row)) }),
  } as unknown as SupabaseClient;
}

Deno.test("logGeneration: writes the mapped row", async () => {
  const seen: Record<string, unknown>[] = [];
  const admin = fakeAdmin((row) => {
    seen.push(row as Record<string, unknown>);
    return { error: null };
  });
  await logGeneration(admin, "u9", {
    feature: "brief",
    hosted: false,
    provider: "groq",
    model: "llama-3.3-70b-versatile",
    promptTokens: 100,
    completionTokens: 20,
  });
  assertEquals(seen.length, 1);
  assertEquals(seen[0].user_id, "u9");
  assertEquals(seen[0].feature, "brief");
  assertEquals(seen[0].model, "llama-3.3-70b-versatile");
});

Deno.test("logGeneration: swallows an insert error", async () => {
  const admin = fakeAdmin(() => ({ error: { message: "permission denied" } }));
  // Must resolve, not reject: the reply already succeeded.
  await logGeneration(admin, "u9", { feature: "chat", hosted: false, provider: "groq" });
});

Deno.test("logGeneration: swallows a thrown insert", async () => {
  const admin = fakeAdmin(() => {
    throw new Error("network down");
  });
  await logGeneration(admin, "u9", { feature: "chat", hosted: false, provider: "groq" });
});
