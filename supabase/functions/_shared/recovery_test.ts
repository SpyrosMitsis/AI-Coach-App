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

// --- today-anchored (dated series + today) -------------------------------------

Deno.test("recovery (today): picks today's reading as latest, baseline excludes it", () => {
  const hrv = [
    { date: "2026-06-26", value: 50 },
    { date: "2026-06-27", value: 50 },
    { date: "2026-06-28", value: 60 },
  ];
  const r = computeRecovery([], hrv, [], "2026-06-28");
  assertEquals(r.hrv!.latest, 60);
  assertEquals(r.hrv!.baseline, 50);
  assertEquals(r.hrv!.deltaPct, 0.2);
});

Deno.test("recovery (today): missing today reads as null, not yesterday's value", () => {
  const wells = [
    // newest-first; today (06-28) has NO row → sleep should be missing.
    { date: "2026-06-27", energy: 4, soreness: 2, sleep_score: 90, zepp_sleep_minutes: 470 },
    { date: "2026-06-26", energy: 4, soreness: 2, sleep_score: 88, zepp_sleep_minutes: 460 },
  ];
  const hrv = [
    { date: "2026-06-26", value: 55 },
    { date: "2026-06-27", value: 58 }, // last reading is yesterday's
  ];
  const r = computeRecovery(wells, hrv, [], "2026-06-28");
  assertEquals(r.hrv!.latest, null);       // not 58
  assert(r.hrv!.baseline > 0);             // baseline still available for context
  assertEquals(r.sleep!.hours, null);      // today has no sleep
  assert(r.sleep!.avgHours! > 0);          // but the window avg is shown
  // No today objective signal → score leans on the 3-day wellness composite only.
  assert(!r.summary.includes("HRV"));
  assert(!r.summary.includes("slept"));
});

Deno.test("recovery (today): today's sleep present is reported as today's", () => {
  const wells = [
    { date: "2026-06-28", energy: 4, soreness: 2, sleep_score: 80, zepp_sleep_minutes: 450 },
    { date: "2026-06-27", energy: 4, soreness: 2, sleep_score: 90, zepp_sleep_minutes: 470 },
  ];
  const r = computeRecovery(wells, [], [], "2026-06-28");
  assertEquals(r.sleep!.hours, 7.5);   // 450/60 — today's, not 470/60
  assertEquals(r.sleep!.score, 80);
});

// --- drivers + sparkline series ------------------------------------------------

Deno.test("recovery: drivers explain the score with direction + tone", () => {
  const wells = [
    { date: "2026-06-28", energy: 5, soreness: 4, sleep_score: 100, zepp_sleep_minutes: 540 },
  ];
  const hrv = [{ date: "2026-06-26", value: 50 }, { date: "2026-06-27", value: 50 }, { date: "2026-06-28", value: 60 }];
  const rhr = [{ date: "2026-06-26", value: 52 }, { date: "2026-06-27", value: 52 }, { date: "2026-06-28", value: 48 }];
  const r = computeRecovery(wells, hrv, rhr, "2026-06-28");
  const by = (l: string) => r.drivers.find((d) => d.label === l);
  assertEquals(by("HRV")!.dir, "up");
  assertEquals(by("HRV")!.tone, "good");
  assertEquals(by("Resting HR")!.dir, "down"); // RHR falling = good
  assertEquals(by("Resting HR")!.tone, "good");
  assertEquals(by("Sleep")!.tone, "good");     // 9h > target
  assertEquals(by("Soreness")!.tone, "bad");   // soreness 4 ≥ 3.5
});

Deno.test("recovery: missing-today objective signals produce no objective drivers", () => {
  const wells = [{ date: "2026-06-27", energy: 3, soreness: 3 }];
  const hrv = [{ date: "2026-06-26", value: 55 }, { date: "2026-06-27", value: 58 }];
  const r = computeRecovery(wells, hrv, [], "2026-06-28");
  assertEquals(r.drivers.find((d) => d.label === "HRV"), undefined); // today not synced
});

Deno.test("recovery: trend carries a recent sparkline series", () => {
  const r = computeRecovery([], [50, 52, 54, 60], []);
  assertEquals(r.hrv!.series, [50, 52, 54, 60]);
});
