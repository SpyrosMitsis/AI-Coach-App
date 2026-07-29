import { assert, assertEquals } from "jsr:@std/assert@1";
import { coerceForPause, computeDayList, computePeriodization, weekdayOf } from "./week_planning.ts";
import { validateWorkout } from "./workout_schema.ts";

function mk(over: Record<string, unknown>) {
  const v = validateWorkout({
    type: "run", title: "Session", duration_minutes: 40, tss_estimate: 40,
    rpe_target: 5, coach_note: "", sections: [], ...over,
  });
  assert(v.ok, `fixture failed to validate: ${v.error}`);
  return v.workout!;
}

// --- weekdayOf ---------------------------------------------------------------
Deno.test("weekdayOf: matches the real calendar", () => {
  assertEquals(weekdayOf("2026-07-27"), "Mon"); // known Monday
  assertEquals(weekdayOf("2026-08-14"), "Fri"); // known Friday
});

// --- computeDayList ----------------------------------------------------------
Deno.test("computeDayList: available with no constraints", () => {
  const days = computeDayList(["2026-07-27", "2026-07-28"], [], new Set(), null);
  assertEquals(days.map((d) => d.available), [true, true]);
});

Deno.test("computeDayList: a weekday not in the athlete's recurring days is unavailable", () => {
  // 2026-07-27 is a Monday; restrict to Tue/Thu only.
  const days = computeDayList(["2026-07-27", "2026-07-28"], ["Tue", "Thu"], new Set(), null);
  assertEquals(days[0], { date: "2026-07-27", weekday: "Mon", available: false });
  assertEquals(days[1], { date: "2026-07-28", weekday: "Tue", available: true });
});

Deno.test("computeDayList: a locked day is unavailable even if the weekday is normally open", () => {
  const days = computeDayList(["2026-07-27"], [], new Set(["2026-07-27"]), null);
  assertEquals(days[0].available, false);
});

Deno.test("computeDayList: a date inside the pause window is unavailable, inclusive of the last day", () => {
  const days = computeDayList(
    ["2026-07-27", "2026-08-14", "2026-08-15"],
    [], new Set(), "2026-08-14",
  );
  assertEquals(days.map((d) => d.available), [false, false, true]);
});

Deno.test("computeDayList: no pause (null) never restricts availability", () => {
  const days = computeDayList(["2026-07-27"], [], new Set(), null);
  assertEquals(days[0].available, true);
});

// --- computePeriodization -----------------------------------------------------
Deno.test("computePeriodization: counts build weeks until a deload/recovery focus breaks the streak", () => {
  const r = computePeriodization(
    ["Build: threshold work", "Build: base miles", "Recovery week", "Build: base miles"],
    300, null, 350, 4,
  );
  assertEquals(r.buildWeeks, 2); // stops at "Recovery week"
  assertEquals(r.deloadDue, false);
});

Deno.test("computePeriodization: deload is due once buildWeeks reaches the threshold", () => {
  const r = computePeriodization(["Build", "Build", "Build", "Build"], 300, null, 350, 4);
  assertEquals(r.buildWeeks, 4);
  assertEquals(r.deloadDue, true);
  assert(r.block.includes("DELOAD WEEK"));
});

Deno.test("computePeriodization: ramp target is ~8% over last week, clamped by the availability ceiling", () => {
  const r = computePeriodization([], 300, 320, 350, 4);
  assertEquals(r.rampTss, 324); // round(300 * 1.08)
  assertEquals(r.targetTss, 320); // clamped below the 324 ramp
  assert(r.block.includes("BUILD WEEK 1"));
});

Deno.test("computePeriodization: no availability ceiling means the ramp target stands as-is", () => {
  const r = computePeriodization([], 300, null, 350, 4);
  assertEquals(r.targetTss, 324);
});

Deno.test("computePeriodization: no prior week TSS falls back to the weekly target", () => {
  const r = computePeriodization([], 0, null, 350, 4);
  assertEquals(r.rampTss, 350);
});

// --- coerceForPause ------------------------------------------------------------
Deno.test("coerceForPause: a real session on a paused date is forced to rest", () => {
  const w = mk({ type: "run", title: "Easy 8k", sections: [{ name: "Main", duration_minutes: 40, exercises: [] }] });
  const out = coerceForPause(w, "2026-08-01", "2026-08-14", "travel to Italy");
  assertEquals(out.type, "rest");
  assertEquals(out.coach_note, "Paused: travel to Italy.");
});

Deno.test("coerceForPause: already-rest on a paused date is left untouched (no-op)", () => {
  const w = mk({ type: "rest", title: "Rest day", coach_note: "Recovery.", sections: [] });
  const out = coerceForPause(w, "2026-08-01", "2026-08-14", null);
  assert(out === w); // same reference, not a new object
});

Deno.test("coerceForPause: a date past the pause window is untouched", () => {
  const w = mk({ type: "run", title: "Easy 8k", sections: [{ name: "Main", duration_minutes: 40, exercises: [] }] });
  const out = coerceForPause(w, "2026-08-15", "2026-08-14", null);
  assert(out === w);
});

Deno.test("coerceForPause: no active pause (null) never touches the session", () => {
  const w = mk({ type: "run", title: "Easy 8k", sections: [{ name: "Main", duration_minutes: 40, exercises: [] }] });
  const out = coerceForPause(w, "2026-08-01", null, null);
  assert(out === w);
});

Deno.test("coerceForPause: no reason falls back to a generic coach_note", () => {
  const w = mk({ type: "run", title: "Easy 8k", sections: [{ name: "Main", duration_minutes: 40, exercises: [] }] });
  const out = coerceForPause(w, "2026-08-01", "2026-08-14", null);
  assertEquals(out.coach_note, "Training paused.");
});
