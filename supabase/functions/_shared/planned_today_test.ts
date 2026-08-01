import { assertEquals } from "jsr:@std/assert@1";
import { pickPrimaryPlannedWorkout, type PlannedTodayRow } from "./planned_today.ts";

function row(overrides: Partial<PlannedTodayRow> & { id: string }): PlannedTodayRow {
  return {
    type: "run",
    completed: false,
    skipped: false,
    locked: false,
    created_at: null,
    workout_json: {},
    ...overrides,
  };
}

Deno.test("empty input returns null", () => {
  assertEquals(pickPrimaryPlannedWorkout([]), null);
});

Deno.test("single row is returned as-is", () => {
  const r = row({ id: "a" });
  assertEquals(pickPrimaryPlannedWorkout([r])?.id, "a");
});

Deno.test("pending beats settled (completed)", () => {
  const settled = row({ id: "a", completed: true, created_at: "2026-07-31T09:00:00Z" });
  const pending = row({ id: "b", created_at: "2026-07-31T08:00:00Z" });
  assertEquals(pickPrimaryPlannedWorkout([settled, pending])?.id, "b");
});

Deno.test("pending beats settled (skipped)", () => {
  const settled = row({ id: "a", skipped: true, created_at: "2026-07-31T09:00:00Z" });
  const pending = row({ id: "b", created_at: "2026-07-31T08:00:00Z" });
  assertEquals(pickPrimaryPlannedWorkout([settled, pending])?.id, "b");
});

Deno.test("real work beats rest, both pending", () => {
  const rest = row({ id: "a", type: "rest", created_at: "2026-07-31T09:00:00Z" });
  const work = row({ id: "b", type: "run", created_at: "2026-07-31T08:00:00Z" });
  assertEquals(pickPrimaryPlannedWorkout([rest, work])?.id, "b");
});

Deno.test("newest created_at wins among otherwise-tied rows", () => {
  const older = row({ id: "a", created_at: "2026-07-31T08:00:00Z" });
  const newer = row({ id: "b", created_at: "2026-07-31T10:00:00Z" });
  assertEquals(pickPrimaryPlannedWorkout([older, newer])?.id, "b");
});

Deno.test("combined: settled-ness beats rest-ness beats recency", () => {
  const settledWork = row({ id: "a", completed: true, type: "run", created_at: "2026-07-31T12:00:00Z" });
  const pendingRest = row({ id: "b", type: "rest", created_at: "2026-07-31T11:00:00Z" });
  const pendingWorkOld = row({ id: "c", type: "run", created_at: "2026-07-31T07:00:00Z" });
  const pendingWorkNew = row({ id: "d", type: "run", created_at: "2026-07-31T09:00:00Z" });
  const picked = pickPrimaryPlannedWorkout([settledWork, pendingRest, pendingWorkOld, pendingWorkNew]);
  assertEquals(picked?.id, "d");
});
