import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  assessFeasibility,
  matchDemand,
  RACE_DEMANDS,
  rampWeeks,
} from "./feasibility.ts";

// ---------------------------------------------------------------------------
// matchDemand: substring order is load-bearing.
// ---------------------------------------------------------------------------

Deno.test("half marathon is not read as a marathon", () => {
  assertEquals(matchDemand("half marathon")?.label, "half marathon");
  assertEquals(matchDemand("my first half-marathon")?.label, "half marathon");
  assertEquals(matchDemand("marathon")?.label, "marathon");
});

Deno.test("50K and 100K are not read as 5K or 10K", () => {
  assertEquals(matchDemand("50k trail race")?.label, "50K ultra");
  assertEquals(matchDemand("100km ultra")?.label, "100K ultra");
  assertEquals(matchDemand("5k parkrun")?.label, "5K");
  assertEquals(matchDemand("10k road race")?.label, "10K");
});

Deno.test("a 70.3 is not read as a full Ironman", () => {
  assertEquals(matchDemand("70.3 in Nice")?.label, "70.3");
  assertEquals(matchDemand("half ironman")?.label, "70.3");
  assertEquals(matchDemand("Ironman Barcelona")?.label, "full Ironman");
});

Deno.test("an unknown event matches nothing rather than guessing", () => {
  assertEquals(matchDemand("club handicap race"), null);
});

// ---------------------------------------------------------------------------
// rampWeeks: the 10%-a-week rule the rest of the engine already enforces.
// ---------------------------------------------------------------------------

Deno.test("rampWeeks is zero when already at or above the target", () => {
  assertEquals(rampWeeks(50, 50), 0);
  assertEquals(rampWeeks(60, 50), 0);
});

Deno.test("rampWeeks follows 10% a week", () => {
  // 20 -> 50km is ln(2.5)/ln(1.1) = 9.6 -> 10 weeks.
  assertEquals(rampWeeks(20, 50), 10);
  // Doubling takes ~7.3 weeks.
  assertEquals(rampWeeks(25, 50), 8);
});

Deno.test("rampWeeks stays finite from a standing start", () => {
  const w = rampWeeks(0, 50);
  assert(Number.isFinite(w) && w > 0, "starting from zero must not be infinite");
  assert(w >= rampWeeks(20, 50), "less base should never need fewer weeks");
});

// ---------------------------------------------------------------------------
// The cases the user actually described.
// ---------------------------------------------------------------------------

Deno.test("an Ironman in 10 weeks off a tiny base is unrealistic and pushes back", () => {
  const f = assessFeasibility({
    goal: "Ironman",
    weeksAway: 10,
    currentWeeklyHours: 3,
    ctl: 15,
  });
  assertEquals(f.band, "unrealistic");
  assert(f.pushBack, "the coach must not just plan this");
  assert(f.score < 40, `expected a low score, got ${f.score}`);
  assert(f.suggestion != null && f.suggestion.includes("70.3"), "should offer a smaller step");
  assert(f.reasons.length > 0, "must give quotable evidence");
});

Deno.test("a half marathon on numbers that say otherwise pushes back", () => {
  const f = assessFeasibility({
    goal: "half marathon",
    weeksAway: 4,
    currentWeeklyKm: 8,
    longestRecentKm: 4,
    ctl: 10,
  });
  assertEquals(f.band, "unrealistic");
  assert(f.pushBack);
  // The long-run gap is the most persuasive single fact; it must be quoted.
  assert(
    f.reasons.some((r) => r.includes("4km") && r.includes("18km")),
    `expected the long-run gap in the reasons, got ${JSON.stringify(f.reasons)}`,
  );
});

Deno.test("a well-judged goal is approved without a lecture", () => {
  const f = assessFeasibility({
    goal: "half marathon",
    weeksAway: 16,
    currentWeeklyKm: 30,
    longestRecentKm: 15,
    ctl: 45,
  });
  assertEquals(f.band, "ready");
  assertEquals(f.pushBack, false);
  assertEquals(f.suggestion, null);
});

Deno.test("a stretch goal is flagged but not blocked", () => {
  const f = assessFeasibility({
    goal: "marathon",
    weeksAway: 15,
    currentWeeklyKm: 35,
    longestRecentKm: 20,
    ctl: 50,
  });
  assertEquals(f.band, "stretch");
  assertEquals(f.pushBack, false, "a stretch is a caveat, not a veto");
  assert(f.suggestion != null, "still offer the safer option");
});

Deno.test("being under half the event's minimum block is always unrealistic", () => {
  // Strong runner, but a marathon in 5 weeks is a floor violation, not a
  // question of ramp rate.
  const f = assessFeasibility({
    goal: "marathon",
    weeksAway: 5,
    currentWeeklyKm: 55,
    longestRecentKm: 28,
    ctl: 70,
  });
  assertEquals(f.band, "unrealistic");
  assert(f.reasons.some((r) => r.includes("16 weeks")), "should cite the minimum block");
});

Deno.test("an already-fit athlete with plenty of time scores at the top", () => {
  const f = assessFeasibility({
    goal: "marathon",
    weeksAway: 30,
    currentWeeklyKm: 55,
    longestRecentKm: 30,
    ctl: 75,
  });
  assertEquals(f.band, "ready");
  assertEquals(f.score, 100);
});

Deno.test("an unknown event neither approves nor lectures", () => {
  const f = assessFeasibility({ goal: "club handicap", weeksAway: 6, currentWeeklyKm: 20 });
  assertEquals(f.pushBack, false);
  assertEquals(f.matched, null);
  assertEquals(f.reasons.length, 0);
});

Deno.test("no volume on record is stated as missing, not treated as unfit", () => {
  const f = assessFeasibility({ goal: "10k", weeksAway: 12 });
  assert(
    f.reasons.some((r) => r.toLowerCase().includes("no recent training volume")),
    "absent data must be named as absent",
  );
});

Deno.test("every demand is internally consistent", () => {
  for (const [key, d] of Object.entries(RACE_DEMANDS)) {
    assert(d.minWeeks > 0, `${key} needs a positive minWeeks`);
    assert(
      d.peakWeeklyKm != null || d.peakWeeklyHours != null,
      `${key} needs a volume demand`,
    );
    if (d.longSessionKm != null && d.peakWeeklyKm != null) {
      assert(
        d.longSessionKm < d.peakWeeklyKm,
        `${key}: a long session cannot exceed the whole week`,
      );
    }
  }
});

Deno.test("feasibility text carries no em or en dashes", () => {
  const cases = [
    assessFeasibility({ goal: "Ironman", weeksAway: 10, currentWeeklyHours: 3 }),
    assessFeasibility({ goal: "marathon", weeksAway: 40, currentWeeklyKm: 50 }),
    assessFeasibility({ goal: "club handicap", weeksAway: 6 }),
  ];
  for (const f of cases) {
    const text = [f.headline, f.suggestion ?? "", ...f.reasons].join(" ");
    assert(!/[—–]/.test(text), `feasibility text used a dash: ${text}`);
  }
});
