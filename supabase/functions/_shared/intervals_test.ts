import { assertEquals } from "jsr:@std/assert@1";
import { athleteDemographics, type IntervalsAthlete, mergeDemographics } from "./intervals.ts";

const base: IntervalsAthlete = { id: "i1", name: "Test" };

Deno.test("athleteDemographics: maps sex, age, weight and height", () => {
  const dob = new Date(Date.now() - 30.5 * 365.25 * 86_400_000).toISOString().slice(0, 10);
  const d = athleteDemographics({
    ...base,
    sex: "M",
    icu_date_of_birth: dob,
    icu_weight: 78.44,
    height: 1.8,
  });
  assertEquals(d.sex, "male");
  assertEquals(d.age, 30);
  assertEquals(d.weightKg, 78.4);
  assertEquals(d.heightCm, 180);
});

Deno.test("athleteDemographics: latest wellness weight beats the profile value", () => {
  const d = athleteDemographics({ ...base, icu_weight: 80 }, 77.2);
  assertEquals(d.weightKg, 77.2);
});

Deno.test("mergeDemographics: manual settings override Intervals per-field", () => {
  const fromIntervals = { sex: "male", age: 30, weightKg: 80, heightCm: 180 };
  const merged = mergeDemographics(fromIntervals, {
    weight_kg: 77,
    birth_year: new Date().getFullYear() - 25,
  });
  assertEquals(merged, { sex: "male", age: 25, weightKg: 77, heightCm: 180 });
});

Deno.test("mergeDemographics: works with no Intervals data at all", () => {
  const merged = mergeDemographics({}, { sex: "male", weight_kg: 77 });
  assertEquals(merged, { sex: "male", weightKg: 77 });
});

Deno.test("mergeDemographics: out-of-range manual values are ignored", () => {
  const fromIntervals = { weightKg: 80 };
  assertEquals(mergeDemographics(fromIntervals, { weight_kg: 5, height_cm: 20, birth_year: 1800, sex: "yes" }), fromIntervals);
});

Deno.test("athleteDemographics: garbage values are dropped, not passed through", () => {
  const d = athleteDemographics({
    ...base,
    sex: "X",
    icu_date_of_birth: "1200-01-01",
    weight: 4,
  });
  assertEquals(d, {});
});
