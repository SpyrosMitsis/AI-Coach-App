import { assertEquals } from "jsr:@std/assert@1";
import { nextTarget } from "./progression.ts";

Deno.test("nextTarget: null without working sets", () => {
  assertEquals(nextTarget([], false), null);
  assertEquals(nextTarget([{ weight_kg: 50, reps: 0 }], false), null);
});

Deno.test("nextTarget: adds a rep at the top weight until the ceiling", () => {
  // Isolation (8-12): top sets at 77.5 best 10 reps → 77.5 × 11.
  const t = nextTarget(
    [
      { weight_kg: 77.5, reps: 10 },
      { weight_kg: 77.5, reps: 9 },
      { weight_kg: 70, reps: 12 }, // back-off set must not count as top
    ],
    false,
  );
  assertEquals(t?.weightKg, 77.5);
  assertEquals(t?.reps, 11);
});

Deno.test("nextTarget: bumps load and resets reps once every top set hits the ceiling", () => {
  // Compound (5-8, +2.5kg): all top sets at 100 hit 8 → 102.5 × 5.
  const t = nextTarget(
    [
      { weight_kg: 100, reps: 8 },
      { weight_kg: 100, reps: 8 },
    ],
    true,
  );
  assertEquals(t?.weightKg, 102.5);
  assertEquals(t?.reps, 5);
});

Deno.test("nextTarget: clamps the rep target into the range", () => {
  // Last time 3 reps at top weight (below the compound floor of 5) → target 5.
  const t = nextTarget([{ weight_kg: 120, reps: 3 }], true);
  assertEquals(t?.weightKg, 120);
  assertEquals(t?.reps, 5);
});

Deno.test("nextTarget: isolation increment is 1.25kg", () => {
  const t = nextTarget([{ weight_kg: 20, reps: 12 }], false);
  assertEquals(t?.weightKg, 21.25);
  assertEquals(t?.reps, 8);
});
