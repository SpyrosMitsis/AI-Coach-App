import { assert, assertEquals } from "jsr:@std/assert@1";
import { pickDebrief } from "./debrief.ts";

const TODAY = "2026-07-18";
const YESTERDAY = "2026-07-17";

const runAnalysis = { score: 78, label: "solid", feedback: "Held the tempo well." };

Deno.test("pickDebrief: today's session beats yesterday's", () => {
  const d = pickDebrief(
    [
      { id: "a1", date: YESTERDAY, type: "Run", analysis_json: { score: 90, label: "great", feedback: "x" } },
      { id: "a2", date: TODAY, type: "Ride", analysis_json: runAnalysis },
    ],
    [],
    TODAY,
  );
  assertEquals(d?.activity_id, "a2");
  assertEquals(d?.kind, "activity");
  assertEquals(d?.label, "solid");
});

Deno.test("pickDebrief: strength-only input works and carries no activity id", () => {
  const d = pickDebrief([], [{ date: TODAY, analysis_json: { score: 65, label: "grinder", feedback: "Squats moved slow." } }], TODAY);
  assertEquals(d?.kind, "strength");
  assertEquals(d?.activity_id, null);
  assertEquals(d?.type, "strength");
});

Deno.test("pickDebrief: same-day tie prefers the endurance activity", () => {
  const d = pickDebrief(
    [{ id: "a1", date: TODAY, type: "Run", analysis_json: runAnalysis }],
    [{ date: TODAY, analysis_json: { score: 60, label: "ok", feedback: "y" } }],
    TODAY,
  );
  assertEquals(d?.kind, "activity");
});

Deno.test("pickDebrief: null on empty or label-and-feedback-less input", () => {
  assertEquals(pickDebrief([], [], TODAY), null);
  assertEquals(pickDebrief([{ id: "a1", date: TODAY, analysis_json: { score: 50 } }], [], TODAY), null);
  assertEquals(pickDebrief([{ id: "a1", date: TODAY, analysis_json: null }], [], TODAY), null);
});

Deno.test("pickDebrief: future-dated rows are ignored", () => {
  assertEquals(pickDebrief([{ id: "a1", date: "2026-07-19", type: "Run", analysis_json: runAnalysis }], [], TODAY), null);
});

Deno.test("pickDebrief: feedback is clipped to a card-sized snippet", () => {
  const d = pickDebrief(
    [{ id: "a1", date: TODAY, type: "Run", analysis_json: { label: "solid", feedback: "word ".repeat(120) } }],
    [],
    TODAY,
  );
  assert(d !== null);
  assert(d.feedback !== null && d.feedback.length <= 240);
  assert(d.feedback.endsWith("…"));
});
