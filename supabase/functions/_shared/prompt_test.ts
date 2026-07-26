import { assert, assertStringIncludes } from "jsr:@std/assert@1";
import { BRIEF_SYSTEM, buildBriefPrompt, buildRunPrompt, buildWeekReviewPrompt, WEEK_REVIEW_SYSTEM } from "./prompt.ts";

const hrZones = [
  { zone: "Z1", min: 95, max: 130 }, { zone: "Z2", min: 131, max: 145 },
  { zone: "Z3", min: 146, max: 160 }, { zone: "Z4", min: 161, max: 172 },
  { zone: "Z5", min: 173, max: 190 },
];
const runBase = {
  hrZones, tsb: 2, ctl: 50, atl: 48, acwr: 1.0, phase: "Build",
  wellness3d: { energy: 4, soreness: 2, sleep: 4 }, weeklyKm: 40, goal: "10K",
  daysSinceLastRun: 1, daysSinceLastHard: 3, durationNote: "~50 min", experience: "Intermediate",
};

Deno.test("brief system enforces the no-dashboard voice", () => {
  assert(/not a\s+dashboard/i.test(BRIEF_SYSTEM));
  assert(/interpret the signals/i.test(BRIEF_SYSTEM));
  assert(/1-2 sentences/i.test(BRIEF_SYSTEM));
});

Deno.test("buildBriefPrompt interprets signals, never dumps raw numbers as the ask", () => {
  const p = buildBriefPrompt({
    name: "Sam", readiness: 42, band: "amber", tsb: -12, tsbTrend: "falling",
    todayPlan: "Tempo run (run)", todayDone: false, phase: "Build", goal: "10K",
    weeklyLoadPct: 80,
  });
  assertStringIncludes(p, "Sam");
  assertStringIncludes(p, "Tempo run");
  // Freshness/readiness are framed as words, not bare metrics to read back.
  assertStringIncludes(p, "carrying fatigue");
  assertStringIncludes(p, "moderately recovered");
});

Deno.test("buildBriefPrompt flags when no objective recovery synced today", () => {
  const p = buildBriefPrompt({
    name: "Sam", readiness: 55, band: "amber", tsb: -4, tsbTrend: "flat",
    todayPlan: "Easy run (run)", todayDone: false, phase: "Base", goal: "10K",
    weeklyLoadPct: 60, objectiveData: false,
  });
  assert(/no hrv\/sleep synced/i.test(p));
  assert(/subjective feel|how they're feeling/i.test(p));
  // It must NOT frame readiness as a settled recovery verdict here.
  assert(!p.includes("moderately recovered"));
});

Deno.test("buildBriefPrompt weaves in debrief and missed-session signals only when present", () => {
  const base = {
    name: "Sam", readiness: 62, band: "amber", tsb: -6, tsbTrend: "flat" as const,
    todayPlan: "Easy run (run)", todayDone: false, phase: "Build", goal: "10K",
    weeklyLoadPct: 70,
  };
  const plain = buildBriefPrompt(base);
  assert(!plain.includes("yesterday's session actually went"));
  assert(!plain.includes("done and analyzed"));
  assert(!plain.includes("no recorded activity"));

  const rich = buildBriefPrompt({
    ...base,
    yesterdayDebrief: `ran the tempo hot: "went out too fast, second half drifted"`,
    todayDebrief: `solid: "held the target pace all the way"`,
    yesterdayMissed: true,
  });
  assertStringIncludes(rich, "How yesterday's session actually went (measured vs plan): ran the tempo hot");
  assertStringIncludes(rich, "Connect it to today's advice.");
  assertStringIncludes(rich, "Today's session is done and analyzed: solid");
  assertStringIncludes(rich, "no recorded activity. Don't scold");
  // Debrief lines carry the analyst's words, never numeric execution scores.
  assert(!/\d+\/100/.test(rich.split("SIGNALS")[1].split("Form\/freshness")[1] ?? ""));
});

Deno.test("brief system tells the coach to use recent execution when given", () => {
  assert(/how a recent session actually went/i.test(BRIEF_SYSTEM));
});

Deno.test("week review system enforces coach voice + a forward line", () => {
  assert(/2-4 sentences/i.test(WEEK_REVIEW_SYSTEM));
  assert(/forward-looking/i.test(WEEK_REVIEW_SYSTEM));
  assert(/never recite/i.test(WEEK_REVIEW_SYSTEM));
});

Deno.test("buildWeekReviewPrompt surfaces adherence, load trend, and the standout", () => {
  const p = buildWeekReviewPrompt({
    name: "Sam", sessions: 4, adherenceDone: 4, adherencePlanned: 5,
    tss: 320, targetTss: 350, loadDeltaPct: 12,
    bySport: [{ sport: "run", tss: 220 }, { sport: "strength", tss: 100 }],
    standout: { sport: "run", date: "2026-06-27", tss: 95 },
    phase: "Build", goal: "10K",
  });
  assertStringIncludes(p, "Sam");
  assertStringIncludes(p, "4/5");          // adherence
  assertStringIncludes(p, "+12% vs last week"); // load trend
  assertStringIncludes(p, "run on 2026-06-27"); // standout
});

Deno.test("cycling prompt programs power zones + cadence, not run phrasing", () => {
  const p = buildRunPrompt({ ...runBase, sport: "ride", ftp: 250 });
  assertStringIncludes(p, "CYCLING");
  assertStringIncludes(p, "FTP 250W");
  assert(/cadence/i.test(p));
  // %FTP translated to a concrete watt target (250 * 0.95 ≈ 238W).
  assertStringIncludes(p, "238W");
});

Deno.test("swim prompt programs metres, CSS, and a drill block", () => {
  const p = buildRunPrompt({ ...runBase, sport: "swim" });
  assertStringIncludes(p, "SWIMMING");
  assert(/CSS/i.test(p));
  assert(/drill/i.test(p));
  assert(/metres/i.test(p));
});

// ---------------------------------------------------------------------------
// Body composition in the strength prompt.
// ---------------------------------------------------------------------------

import { assertEquals } from "jsr:@std/assert@1";
import { bodyLine, buildStrengthPrompt } from "./prompt.ts";

const strengthBase = {
  muscleGroupsLast48h: [],
  weeklySetsByMuscle: { Chest: 6 },
  equipment: "Full gym",
  experience: "Intermediate",
  goal: "Hybrid athlete",
  soreness: 2,
  phase: "Base",
  mainLifts: [],
  durationNote: "Aim for about 60 minutes.",
};

Deno.test("strength prompt renders the Body line with computed BMI when body data exists", () => {
  const p = buildStrengthPrompt({ ...strengthBase, body: { weightKg: 74, heightCm: 180, bodyFatPct: 15 } });
  assertStringIncludes(p, "- Body: 74 kg, 180 cm, BMI 22.8, ~15% body fat");
  assert(/bodyweight movements/i.test(p));
});

Deno.test("strength prompt omits the Body line entirely without body data", () => {
  const none = buildStrengthPrompt(strengthBase);
  assert(!none.includes("- Body:"));
  const empty = buildStrengthPrompt({ ...strengthBase, body: {} });
  assert(!empty.includes("- Body:"));
});

Deno.test("bodyLine renders partial data without BMI or blank segments", () => {
  assertEquals(bodyLine({ weightKg: 74 }), "74 kg");
  assertEquals(bodyLine({ bodyFatPct: 12.5 }), "~12.5% body fat");
  assertEquals(bodyLine(null), "");
});

Deno.test("strength prompt appends the body trend line when provided, omits when not", () => {
  const withTrend = buildStrengthPrompt({
    ...strengthBase,
    body: { weightKg: 74 },
    bodyTrend: "Body trend: weight 74.0 kg, down 0.3 kg per week. The trend matches their goal of losing fat.",
  });
  assertStringIncludes(withTrend, "down 0.3 kg per week");
  assertStringIncludes(withTrend, "steer the session's bias");
  const without = buildStrengthPrompt({ ...strengthBase, body: { weightKg: 74 } });
  assert(!without.includes("Body trend:"));
});

Deno.test("brief prompt renders the body trend as an extras line only when present", () => {
  const base = {
    name: "Sam", readiness: 70, band: "green", tsb: 2.0,
    tsbTrend: "flat" as const, todayPlan: "Easy run", todayDone: false,
    phase: "Base", goal: "10K", weeklyLoadPct: 40,
  };
  const withTrend = buildBriefPrompt({
    ...base,
    bodyTrend: "Body trend: lean mass 62.0 kg, up 0.2 kg per week. The trend matches their goal of building muscle.",
  });
  assertStringIncludes(withTrend, "lean mass 62.0 kg");
  assertStringIncludes(withTrend, "only when it earns a place");
  assert(!buildBriefPrompt(base).includes("Body trend:"));
});
