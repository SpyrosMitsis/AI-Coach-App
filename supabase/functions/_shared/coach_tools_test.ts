import { assert, assertEquals } from "jsr:@std/assert@1";
import { executeTool, nativeToolDefs, TOOL_CATALOG, toolCatalogPrompt } from "./coach_tools.ts";
// deno-lint-ignore no-explicit-any
type Any = any;

// Minimal PostgREST query-builder stub: every method chains, and the chain
// resolves (awaited or .single()) to the canned `data` for that table.
function adminStub(byTable: Record<string, unknown>): Any {
  const chainFor = (data: unknown): Any => {
    const c: Any = {};
    for (const m of ["select", "eq", "gte", "lte", "in", "not", "order", "limit"]) c[m] = () => c;
    c.single = () => Promise.resolve({ data: Array.isArray(data) ? data[0] : data });
    c.then = (res: Any, rej: Any) => Promise.resolve({ data }).then(res, rej);
    return c;
  };
  return { from: (t: string) => chainFor(byTable[t] ?? []) };
}

Deno.test("coach tools: set_rest_day + make_easier are registered as act tools", () => {
  const byName = (n: string) => TOOL_CATALOG.find((t) => t.name === n);
  const rest = byName("set_rest_day");
  const easier = byName("make_easier");
  assert(rest, "set_rest_day missing");
  assert(easier, "make_easier missing");
  assertEquals(rest!.kind, "act");
  assertEquals(easier!.kind, "act");
  // set_rest_day requires a date; make_easier's date is optional.
  assertEquals((rest!.schema as { required?: string[] }).required, ["date"]);
  assert(!(easier!.schema as { required?: string[] }).required);
});

Deno.test("coach tools: new tools are advertised to the model", () => {
  const native = nativeToolDefs().map((t) => t.name);
  assert(native.includes("set_rest_day"));
  assert(native.includes("make_easier"));
  const prompt = toolCatalogPrompt();
  assert(prompt.includes("set_rest_day"));
  assert(prompt.includes("make_easier"));
});

Deno.test("executeTool get_fitness: leads with an interpreted word, keeps the raw figures", async () => {
  const admin = adminStub({
    completed_activities: [
      { date: "2026-06-29", tss: 50, ctl: 42, atl: 50 },
      { date: "2026-06-22", tss: 60, ctl: 41, atl: 48 },
    ],
  });
  const obs = JSON.parse(await executeTool(admin, "u1", "auth", "get_fitness", {}));
  // Plain-language interpretation is present so the coach doesn't recite numbers.
  assert(typeof obs.freshness === "string" && obs.freshness.length > 0);
  assert(String(obs.note).toLowerCase().includes("interpret"));
  // Raw figures still come through for the model's own reasoning.
  assertEquals(obs.ctl, 42);
  assertEquals(obs.tsb, -8);
});
