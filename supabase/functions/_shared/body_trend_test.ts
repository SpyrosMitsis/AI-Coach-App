import { assert, assertEquals, assertStringIncludes } from "jsr:@std/assert@1";
import { bodyFocus, type BodyRow, computeBodyTrend } from "./body_trend.ts";

const TODAY = "2026-07-22";

// n weekly readings ending just before today, moving linearly by slopePerWeek.
function series(
  field: "weight_kg" | "body_fat_pct" | "lean_mass_kg",
  start: number,
  slopePerWeek: number,
  weeks = 8,
): BodyRow[] {
  const rows: BodyRow[] = [];
  for (let i = 0; i < weeks; i++) {
    const d = new Date(`${TODAY}T12:00:00Z`);
    d.setUTCDate(d.getUTCDate() - 7 * (weeks - 1 - i));
    rows.push({ date: d.toISOString().slice(0, 10), [field]: start + slopePerWeek * i });
  }
  return rows;
}

Deno.test("least-squares slope recovers a linear weekly change", () => {
  const t = computeBodyTrend(series("weight_kg", 78, -0.3), [], TODAY)!;
  assertEquals(t.weight!.slopePerWeek, -0.3);
  assertEquals(t.weight!.latest, 78 - 0.3 * 7);
});

Deno.test("sparse data keeps latest but refuses a slope", () => {
  const twoPoints = computeBodyTrend(
    [{ date: "2026-07-01", weight_kg: 78 }, { date: "2026-07-20", weight_kg: 77 }],
    [],
    TODAY,
  )!;
  assertEquals(twoPoints.weight!.slopePerWeek, null);
  assertEquals(twoPoints.weight!.latest, 77);

  const shortSpan = computeBodyTrend(
    [
      { date: "2026-07-18", weight_kg: 78 },
      { date: "2026-07-19", weight_kg: 77.5 },
      { date: "2026-07-20", weight_kg: 77 },
    ],
    [],
    TODAY,
  )!;
  assertEquals(shortSpan.weight!.slopePerWeek, null);
});

Deno.test("goal strings map to the right focus", () => {
  assertEquals(bodyFocus(["Build muscle"]), "muscle");
  assertEquals(bodyFocus(["Lose fat"]), "fat_loss");
  assertEquals(bodyFocus(["Body recomposition"]), "recomp");
  assertEquals(bodyFocus(["Build muscle", "Lose fat"]), "recomp");
  assertEquals(bodyFocus(["Get stronger"]), "general");
  assertEquals(bodyFocus(["General fitness"]), "general");
  assertEquals(bodyFocus([]), "general");
});

Deno.test("lean mass derives from weight and body fat when unmeasured", () => {
  const rows = series("weight_kg", 80, 0).map((r, i) => ({ ...r, body_fat_pct: 20 - 0.5 * i }));
  const t = computeBodyTrend(rows, ["Build muscle"], TODAY)!;
  // 80 kg at 16.5% fat on the last day: lean = 80 * 0.835 = 66.8.
  assertEquals(t.leanMass!.latest, 66.8);
  assert(t.leanMass!.slopePerWeek! > 0);
  assertEquals(t.onTrack, true);
});

Deno.test("onTrack follows the focus", () => {
  const gainingLean = [
    ...series("lean_mass_kg", 60, 0.2),
    ...series("weight_kg", 78, 0.25),
  ];
  assertEquals(computeBodyTrend(gainingLean, ["Build muscle"], TODAY)!.onTrack, true);
  assertEquals(computeBodyTrend(gainingLean, ["Lose fat"], TODAY)!.onTrack, false);

  const cutting = series("weight_kg", 82, -0.4);
  assertEquals(computeBodyTrend(cutting, ["Lose fat"], TODAY)!.onTrack, true);
  assertEquals(computeBodyTrend(cutting, ["General fitness"], TODAY)!.onTrack, null);

  const recomp = series("lean_mass_kg", 60, 0.1).map((r, i) => ({
    ...r,
    ...series("body_fat_pct", 20, -0.3)[i],
  }));
  assertEquals(computeBodyTrend(recomp, ["Body recomposition"], TODAY)!.onTrack, true);
});

Deno.test("garbage readings and out-of-window rows are ignored", () => {
  const rows: BodyRow[] = [
    { date: "2025-01-01", weight_kg: 90 }, // outside 90d window
    { date: "2026-07-30", weight_kg: 70 }, // future
    { date: "2026-07-10", weight_kg: 900 }, // implausible
    { date: "garbage", weight_kg: 75 },
  ];
  assertEquals(computeBodyTrend(rows, [], TODAY), null);
});

Deno.test("summary is a plain sentence with no dashes", () => {
  const t = computeBodyTrend(series("weight_kg", 78, -0.3), ["Lose fat"], TODAY)!;
  assertStringIncludes(t.summary, "Body trend: weight");
  assertStringIncludes(t.summary, "down 0.3 kg per week");
  assertStringIncludes(t.summary, "matches their goal of losing fat");
  assert(!/[–—]/.test(t.summary), "no em or en dashes in coach-facing text");
});
