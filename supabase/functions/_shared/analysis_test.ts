import { assertEquals, assertAlmostEquals } from "jsr:@std/assert@1";
import {
  adherenceScore,
  buildSeries,
  buildSplits,
  combineScore,
  hrBandForZones,
  paceBandForZones,
  paceInBandScore,
  parsePaceToSec,
  plannedZones,
  scoreLabel,
} from "./analysis.ts";
import type { Workout } from "./types.ts";

const workout: Workout = {
  type: "run",
  title: "Tempo Tuesday",
  duration_minutes: 50,
  tss_estimate: 60,
  rpe_target: 7,
  coach_note: "",
  sections: [
    {
      name: "Warmup",
      duration_minutes: 10,
      exercises: [{ name: "Easy jog", sets: 1, reps: "10min", pace_zone: "Z1", notes: "" }],
    },
    {
      name: "Main Set",
      duration_minutes: 30,
      exercises: [
        { name: "Tempo", sets: 1, reps: "20min", pace_zone: "Z3-Z4", hr_zone: "Z4", notes: "" },
        { name: "Float", sets: 1, reps: "10min", pace_zone: "Z2", notes: "" },
      ],
    },
  ],
} as unknown as Workout;

Deno.test("parsePaceToSec parses m:ss and rejects junk", () => {
  assertEquals(parsePaceToSec("4:45"), 285);
  assertEquals(parsePaceToSec("4:45/km"), 285);
  assertEquals(parsePaceToSec("fast"), null);
  assertEquals(parsePaceToSec(null), null);
});

Deno.test("plannedZones reads work intervals and skips warmup/cooldown", () => {
  assertEquals(plannedZones(workout, "pace_zone").sort(), [2, 3, 4]);
  assertEquals(plannedZones(workout, "hr_zone"), [4]);
});

Deno.test("plannedZones falls back to all sections when no main set", () => {
  const wuOnly = {
    ...workout,
    sections: [workout.sections[0]],
  } as Workout;
  assertEquals(plannedZones(wuOnly, "pace_zone"), [1]);
});

Deno.test("paceBandForZones derives a sane band from threshold pace", () => {
  // threshold 4:30/km = 270 s/km, zones Z2-Z4 → fast bound from Z4 hi (102%),
  // slow bound from Z2 lo (78%).
  const band = paceBandForZones([2, 3, 4], 270)!;
  assertAlmostEquals(band.lo, Math.round(270 / 1.02), 1);
  assertAlmostEquals(band.hi, Math.round(270 / 0.78), 1);
  assertEquals(paceBandForZones([], 270), null);
  assertEquals(paceBandForZones([2], null), null);
});

Deno.test("hrBandForZones spans the named zones", () => {
  const zones = [
    { zone: "Z1", min: 0, max: 130 },
    { zone: "Z2", min: 131, max: 145 },
    { zone: "Z3", min: 146, max: 160 },
  ];
  assertEquals(hrBandForZones([2, 3], zones), { lo: 131, hi: 160 });
  assertEquals(hrBandForZones([5], zones), null);
});

Deno.test("adherenceScore is 100 exact, decays with deviation", () => {
  assertEquals(adherenceScore(60, 60), 100);
  assertEquals(adherenceScore(66, 60), 75); // 10% over
  assertEquals(adherenceScore(120, 60), 0); // way over
  assertEquals(adherenceScore(30, 0), 0); // no plan value
});

Deno.test("paceInBandScore rewards time-in-band with grace margin", () => {
  const band = { lo: 280, hi: 320 };
  // 8 of 10 samples in band (incl. the 3% grace edges), 1 null ignored.
  const pace = [285, 300, 310, 272, 329, 350, 290, 295, 305, null, 400];
  const { frac } = paceInBandScore(pace, band);
  assertAlmostEquals(frac, 0.8, 0.01);
  assertEquals(paceInBandScore([null, null], band).score, 0);
});

Deno.test("buildSeries converts velocity to pace and gaps stops", () => {
  const s = buildSeries({
    time: [0, 1, 2, 3],
    velocity: [3.333, 3.333, 0.1, 3.5], // ~5:00/km, stopped, ~4:46/km
    hr: [140, 141, null, 150],
    distance: [0, 3.3, 3.4, 6.9],
  }, 10);
  assertEquals(s.t.length, 4);
  assertAlmostEquals(s.pace[0]!, 300, 1);
  assertEquals(s.pace[2], null);
  assertEquals(s.hr[3], 150);
});

Deno.test("buildSeries downsamples long streams", () => {
  const n = 3600;
  const s = buildSeries({
    time: Array.from({ length: n }, (_, i) => i),
    velocity: Array.from({ length: n }, () => 3.0),
    hr: Array.from({ length: n }, () => 145),
    distance: Array.from({ length: n }, (_, i) => i * 3),
  }, 120);
  assertEquals(s.t.length <= 120, true);
});

Deno.test("buildSplits cuts per completed km with avg HR", () => {
  const n = 700;
  const splits = buildSplits({
    time: Array.from({ length: n }, (_, i) => i),
    velocity: Array.from({ length: n }, () => 3.0),
    hr: Array.from({ length: n }, () => 150),
    distance: Array.from({ length: n }, (_, i) => i * 3), // 3 m/s → km every ~334s
  });
  assertEquals(splits.length, 2);
  assertEquals(splits[0].km, 1);
  assertAlmostEquals(splits[0].sec, 334, 1);
  assertEquals(splits[0].avg_hr, 150);
});

Deno.test("combineScore double-weights intensity; label bands", () => {
  const score = combineScore([
    { name: "Duration", score: 100, detail: "" },
    { name: "Load", score: 100, detail: "" },
    { name: "Intensity", score: 50, detail: "" },
  ])!;
  assertEquals(score, 75);
  assertEquals(combineScore([]), null);
  assertEquals(scoreLabel(92), "Executed to plan");
  assertEquals(scoreLabel(80), "Solid execution");
  assertEquals(scoreLabel(60), "Drifted from plan");
  assertEquals(scoreLabel(20), "Off plan");
});
