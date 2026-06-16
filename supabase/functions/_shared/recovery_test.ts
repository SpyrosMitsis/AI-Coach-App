import { assert, assertEquals } from "jsr:@std/assert@1";
import { computeRecovery } from "./recovery.ts";

Deno.test("recovery: neutral inputs land mid-scale amber/green boundary-ish", () => {
  const r = computeRecovery([], [], []);
  // wellness defaults to 3/5 → (3-1)/4*55 + 22 = 49.5 → 50
  assertEquals(r.score, 50);
  assertEquals(r.band, "amber");
  assertEquals(r.hrv, null);
  assertEquals(r.rhr, null);
  assertEquals(r.sleep, null);
});

Deno.test("recovery: great wellness + rising HRV + low RHR is green", () => {
  const wells = [
    { date: "2026-06-10", energy: 5, soreness: 1, sleep_score: 100, zepp_sleep_minutes: 480 },
    { date: "2026-06-09", energy: 5, soreness: 1, sleep_score: 100, zepp_sleep_minutes: 470 },
  ];
  const r = computeRecovery(wells, [60, 62, 70], [52, 52, 49]);
  assertEquals(r.band, "green");
  assert(r.score > 80);
  assert(r.hrv!.deltaPct > 0);
  assert(r.rhr!.deltaPct < 0);
  assertEquals(r.sleep!.hours, 8);
});

Deno.test("recovery: poor wellness + crashed HRV + spiked RHR is red", () => {
  const wells = [
    { date: "2026-06-10", energy: 1, soreness: 5, sleep_score: 0, zepp_sleep_minutes: 300 },
  ];
  const r = computeRecovery(wells, [70, 70, 45], [50, 50, 62]);
  assertEquals(r.band, "red");
  assert(r.summary.startsWith("Under-recovered"));
});

Deno.test("recovery: HRV trend needs at least 2 points", () => {
  const r = computeRecovery([], [60], [50]);
  assertEquals(r.hrv, null);
  assertEquals(r.rhr, null);
});

Deno.test("recovery: trend compares latest to baseline of the rest", () => {
  const r = computeRecovery([], [50, 50, 60], []);
  assertEquals(r.hrv!.latest, 60);
  assertEquals(r.hrv!.baseline, 50);
  assertEquals(r.hrv!.deltaPct, 0.2);
});
