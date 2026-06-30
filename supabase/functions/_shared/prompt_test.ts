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
