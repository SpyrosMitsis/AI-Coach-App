import { assert, assertAlmostEquals, assertEquals } from "jsr:@std/assert@1";
import { applyFallbackFitness, fitnessFromTss } from "./load.ts";
// deno-lint-ignore no-explicit-any
type Any = any;

Deno.test("fitnessFromTss: empty input yields an empty map", () => {
  assertEquals(fitnessFromTss([], "2026-07-13").size, 0);
  // Rows after the end date don't count either.
  assertEquals(fitnessFromTss([{ date: "2026-07-20", tss: 50 }], "2026-07-13").size, 0);
});

Deno.test("fitnessFromTss: single activity seeds day one, then decays", () => {
  const m = fitnessFromTss([{ date: "2026-07-01", tss: 84 }], "2026-07-05");
  assertEquals(m.get("2026-07-01"), { ctl: 2, atl: 12 });
  // Every later day decays toward 0 (no further load).
  let prev = m.get("2026-07-01")!;
  for (const d of ["2026-07-02", "2026-07-03", "2026-07-04", "2026-07-05"]) {
    const p = m.get(d)!;
    assert(p.ctl <= prev.ctl && p.atl <= prev.atl, `${d} should decay`);
    prev = p;
  }
});

Deno.test("fitnessFromTss: constant load converges toward the daily tss, atl faster", () => {
  const rows = [];
  const start = new Date("2026-01-01T12:00:00Z").getTime();
  for (let i = 0; i < 180; i++) {
    rows.push({ date: new Date(start + i * 86_400_000).toISOString().slice(0, 10), tss: 50 });
  }
  const end = new Date(start + 179 * 86_400_000).toISOString().slice(0, 10);
  const m = fitnessFromTss(rows, end);
  const last = m.get(end)!;
  // Both approach 50 from below; ATL (7d constant) is effectively there,
  // CTL (42d constant) is close after ~4 time constants.
  assertAlmostEquals(last.atl, 50, 0.5);
  assert(last.ctl > 48 && last.ctl <= 50, `ctl ${last.ctl} should be near 50 from below`);
  // Mid-series, ATL is ahead of CTL (fatigue builds faster than fitness).
  const mid = m.get(new Date(start + 10 * 86_400_000).toISOString().slice(0, 10))!;
  assert(mid.atl > mid.ctl, "atl should lead ctl under new constant load");
});

Deno.test("fitnessFromTss: same-day activities are summed into one bucket", () => {
  const m = fitnessFromTss(
    [{ date: "2026-07-01", tss: 30 }, { date: "2026-07-01", tss: 54 }],
    "2026-07-01",
  );
  assertEquals(m.get("2026-07-01"), { ctl: 2, atl: 12 }); // 84/42, 84/7
});

// Minimal PostgREST stub (same shape as coach_tools_test.ts): chains resolve
// to the canned data; records whether the fallback query actually ran.
function adminStub(data: unknown, calls: string[] = []): Any {
  const c: Any = {};
  for (const m of ["select", "eq", "gte", "lte", "order", "limit"]) c[m] = () => c;
  c.then = (res: Any, rej: Any) => Promise.resolve({ data }).then(res, rej);
  return { from: (t: string) => (calls.push(t), c) };
}

Deno.test("applyFallbackFitness: no-op on empty rows and when any row has ctl", async () => {
  const calls: string[] = [];
  const admin = adminStub([], calls);
  assertEquals(await applyFallbackFitness(admin, "u1", "2026-07-13", []), []);
  const mixed = [
    { date: "2026-07-10", tss: 40, ctl: 38, atl: 42 },
    { date: "2026-07-12", tss: 50, ctl: null, atl: null },
  ];
  const out = await applyFallbackFitness(admin, "u1", "2026-07-13", mixed);
  assertEquals(out, mixed); // intervals data wins, rows untouched
  assertEquals(calls.length, 0); // and no extra query was made
});

Deno.test("applyFallbackFitness: fills ctl/atl by date when no row has ctl", async () => {
  const history = [
    { date: "2026-07-10", tss: 84 },
    { date: "2026-07-12", tss: 42 },
  ];
  const admin = adminStub(history);
  // Explicit annotation: an all-null literal would infer ctl/atl as type
  // `null`, making the filled-in numbers a type error below.
  const rows: { date: string; tss: number; ctl: number | null; atl: number | null }[] = [
    { date: "2026-07-10", tss: 84, ctl: null, atl: null },
    { date: "2026-07-12", tss: 42, ctl: null, atl: null },
  ];
  const out = await applyFallbackFitness(admin, "u1", "2026-07-13", rows);
  assert(out[0].ctl != null && out[0].atl != null, "day one annotated");
  assert(out[1].ctl != null && out[1].atl != null, "day three annotated");
  assertEquals(out[0], { date: "2026-07-10", tss: 84, ctl: 2, atl: 12 });
  // Newer day carries the decayed-then-bumped series, not a fresh seed.
  assert(out[1].atl! > out[1].ctl!, "recent load keeps atl above ctl");
});

Deno.test("applyFallbackFitness: swallows query failures and returns input", async () => {
  const admin: Any = { from: () => { throw new Error("boom"); } };
  const rows = [{ date: "2026-07-10", tss: 84, ctl: null, atl: null }];
  assertEquals(await applyFallbackFitness(admin, "u1", "2026-07-13", rows), rows);
});
