import { assert, assertEquals } from "jsr:@std/assert@1";
import { nativeToolDefs, TOOL_CATALOG, toolCatalogPrompt } from "./coach_tools.ts";

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
