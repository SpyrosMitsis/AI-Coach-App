import { assert } from "jsr:@std/assert@1";
import { recoveryBlock } from "./context.ts";

Deno.test("recoveryBlock: amber gives a concrete graded cap, not vague advice", () => {
  const b = recoveryBlock({ score: 48, band: "amber", summary: "Moderately recovered." });
  assert(/AMBER \(48\/100\)/.test(b));
  assert(/RPE 6/.test(b));
  assert(/no intervals|NO intervals/i.test(b));
});

Deno.test("recoveryBlock: red caps hard and trims volume", () => {
  const b = recoveryBlock({ score: 20, band: "red", summary: "Under-recovered." });
  assert(/RED \(20\/100\)/.test(b));
  assert(/RPE 4/.test(b));
});

Deno.test("recoveryBlock: null recovery yields nothing", () => {
  assert(recoveryBlock(null) === "");
});
