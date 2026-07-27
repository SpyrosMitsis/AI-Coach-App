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
  // ...and that 50 is a placeholder, not a reading. Everything downstream keys
  // off this rather than off the number.
  assertEquals(r.basis, "none");
});

// ---------------------------------------------------------------------------
// basis: what the score actually rests on
//
// Reported from the app: "why does it have a readiness score of 50 when it
// doesn't have any data". It shouldn't. The maths runs on neutral defaults and
// lands on exactly 50/amber, which read as a measurement and was treated as
// one: amber caps every hard endurance session at RPE 6 (workout_review.ts), so
// an athlete with no wearable and no check-in could never be given an interval.
// ---------------------------------------------------------------------------

Deno.test("basis: nothing measured says so, and does not claim a recovery state", () => {
  const r = computeRecovery([], [], [], "2026-06-12");
  assertEquals(r.basis, "none");
  assert(!/recovered/i.test(r.summary), `summary claims a state: ${r.summary}`);
  assert(/no readiness data/i.test(r.summary), r.summary);
});

Deno.test("basis: a check-in alone is subjective, a synced signal is measured", () => {
  const checkin = computeRecovery([{ date: "2026-06-12", energy: 4, soreness: 2 }], [], [], "2026-06-12");
  assertEquals(checkin.basis, "subjective");

  const synced = computeRecovery(
    [{ date: "2026-06-12" }],
    [{ date: "2026-06-10", value: 60 }, { date: "2026-06-12", value: 66 }],
    [],
    "2026-06-12",
  );
  assertEquals(synced.basis, "measured");
});

Deno.test("basis: yesterday's watch data is not today's reading", () => {
  // Taken from a live account: device HRV/RHR/sleep-minutes through yesterday,
  // nothing today, and no subjective fields ever. wellness sits at the untouched
  // default of 3, so the score is still the unfounded 50 and must say so.
  // (An earlier version of this check counted zepp_sleep_minutes as subjective
  // input and called this "subjective". It isn't: those minutes never reach the
  // wellness composite.)
  const wells = [
    { date: "2026-06-11", hrv_rmssd: 123.1, resting_hr: 42, zepp_sleep_minutes: 283 },
    { date: "2026-06-10", hrv_rmssd: 138.3, resting_hr: 43, zepp_sleep_minutes: 416 },
    { date: "2026-06-09", hrv_rmssd: 106.2, resting_hr: 47, zepp_sleep_minutes: 427 },
  ];
  const dated = (k: "hrv_rmssd" | "resting_hr") =>
    [...wells].reverse().map((w) => ({ date: w.date, value: w[k] }));
  const r = computeRecovery(wells, dated("hrv_rmssd"), dated("resting_hr"), "2026-06-12");
  assertEquals(r.wellness, 3);
  assertEquals(r.score, 50);
  assertEquals(r.basis, "none");
});

Deno.test("basis: sleep alone counts as measured", () => {
  const r = computeRecovery(
    [{ date: "2026-06-12", zepp_sleep_minutes: 430 }],
    [],
    [],
    "2026-06-12",
  );
  assertEquals(r.basis, "measured");
});

Deno.test("no check-in invents no subjective driver", () => {
  // avg([]) is 0, which sailed under the "energy <= 2.5" test and put an
  // "Energy down" chip on the dashboard of an athlete who never checked in.
  const r = computeRecovery([], [], [], "2026-06-12");
  assertEquals(r.drivers, []);
  // A real low reading still drives it.
  const low = computeRecovery([{ date: "2026-06-12", energy: 1, soreness: 5 }], [], [], "2026-06-12");
  assert(low.drivers.some((d) => d.label === "Energy" && d.dir === "down"));
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
