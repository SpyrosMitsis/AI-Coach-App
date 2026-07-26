import { assert, assertEquals } from "jsr:@std/assert@1";
import { dashScrubber, finalLegStream, type StreamEvent } from "./coach_stream.ts";
import { stripDashes } from "./coach_eval.ts";

// Collect what the client would be told, and reconstruct what it would show.
function harness() {
  const events: StreamEvent[] = [];
  const s = finalLegStream((e) => events.push(e));
  const screen = () => {
    let out = "";
    for (const e of events) {
      if (e.reset) out = "";
      else if (e.text) out += e.text;
    }
    return out;
  };
  return { s, events, screen, resets: () => events.filter((e) => e.reset).length };
}

const LONG = "You're fresh this week, so let's put the hard work on Thursday.";

Deno.test("a text-only reply is delivered in full when the turn ends", () => {
  const h = harness();
  for (const c of LONG) h.s.onDelta(c);
  assertEquals(h.screen(), "", "nothing may be shown before the turn ends");
  h.s.endMessage();
  assertEquals(h.screen(), LONG);
  assertEquals(h.s.committed(), LONG);
  assertEquals(h.resets(), 0);
});

Deno.test("a preamble before a tool call is dropped silently", () => {
  const h = harness();
  for (const c of "Let me check.") h.s.onDelta(c);
  h.s.onToolStart();
  assertEquals(h.screen(), "");
  assertEquals(h.s.committed(), "");
  assertEquals(h.events.length, 0, "nothing at all should reach the client");
});

// THE REGRESSION THIS FILE EXISTS FOR. Measured on deepseek-v4-flash, a leg can
// write 947 characters of confident, answer-shaped prose and THEN call a tool,
// while real final answers ran 175 to 397. Under the old hold-back policy that
// long preamble was flushed to the screen and then yanked back, once per leg,
// for the whole turn. Length must now be irrelevant.
Deno.test("a long preamble before a tool call is dropped, however long", () => {
  const h = harness();
  const essay = "You're in a good spot, Alex. ".repeat(40); // ~1160 chars
  assert(essay.length > 947);
  for (const c of essay) h.s.onDelta(c);
  h.s.onToolStart();
  assertEquals(h.screen(), "", "a long preamble must not reach the screen");
  assertEquals(h.resets(), 0, "and must not need taking back");
  assertEquals(h.events.length, 0);
});

Deno.test("a reset from a tool call is impossible by construction", () => {
  // Any interleaving at all: the only text ever emitted is what survives to
  // endMessage, so onToolStart can never need to retract anything.
  const h = harness();
  for (let i = 0; i < 5; i++) {
    for (const c of LONG) h.s.onDelta(c);
    h.s.onToolStart();
  }
  const answer = "All good, you're on track for the half.";
  for (const c of answer) h.s.onDelta(c);
  h.s.endMessage();
  assertEquals(h.resets(), 0);
  assertEquals(h.screen(), answer);
  assertEquals(h.s.committed(), answer);
});

Deno.test("discarded() reports what was thrown away, for cost logging", () => {
  const h = harness();
  for (const c of "Let me check.") h.s.onDelta(c);
  h.s.onToolStart();
  for (const c of "Answer.") h.s.onDelta(c);
  h.s.endMessage();
  assertEquals(h.s.discarded(), "Let me check.".length);
  assertEquals(h.screen(), "Answer.");
});

// THE INVARIANT THE WHOLE THING EXISTS FOR: the client's screen and the
// server's idea of what it committed must never disagree, whatever the model
// does. If these drift, the persisted reply differs from the displayed one.
Deno.test("committed() always equals what the client would have on screen", () => {
  const scripts: Array<Array<string | "TOOL" | "END">> = [
    ["hi", "END"],
    ["Let me look.", "TOOL", "Here's the answer, in full.", "END"],
    [LONG, "TOOL", LONG, "TOOL", "final", "END"],
    ["a", "b", "c", "END"],
    ["TOOL", "TOOL", "answer after two silent tools", "END"],
    [LONG, "END"],
    ["TOOL", "END"],
    ["END"],
  ];
  for (const script of scripts) {
    const h = harness();
    for (const step of script) {
      if (step === "TOOL") h.s.onToolStart();
      else if (step === "END") h.s.endMessage();
      else for (const c of step) h.s.onDelta(c);
    }
    assertEquals(h.s.committed(), h.screen(), `drift on script: ${script.join("|")}`);
  }
});

Deno.test("a short final message is still delivered", () => {
  const h = harness();
  for (const c of "Done.") h.s.onDelta(c);
  h.s.endMessage();
  assertEquals(h.screen(), "Done.");
});

Deno.test("a turn that only ran tools emits nothing at all", () => {
  const h = harness();
  h.s.onToolStart();
  h.s.onToolStart();
  h.s.endMessage();
  assertEquals(h.events.length, 0, "an empty final leg must not emit an empty token");
  assertEquals(h.s.committed(), "");
});

Deno.test("empty deltas are ignored", () => {
  const h = harness();
  h.s.onDelta("");
  h.s.onToolStart();
  h.s.endMessage();
  assertEquals(h.events.length, 0);
  assertEquals(h.resets(), 0);
});

// ---------------------------------------------------------------------------
// dashScrubber: the house dash rule, applied to a stream.
//
// cleanReply strips dashes from the finished reply. If the deltas keep theirs,
// the streamed and persisted text differ and coach-chat's display/persist
// invariant resets the reply. 6 of 7 live replies contain a dash, so without
// this nearly every reply would stream in, vanish and reappear.
// ---------------------------------------------------------------------------

/** Feed a full text through the scrubber in arbitrary chunks. */
function scrubbed(chunks: string[]): string {
  const s = dashScrubber(stripDashes);
  return chunks.map((c) => s.push(c)).join("") + s.flush();
}

Deno.test("a dash split across delta boundaries still becomes a comma", () => {
  // The exact shape that broke: the space, the dash and the next word all
  // arrive in different deltas.
  assertEquals(scrubbed(["Nothing alarming", " ", "—", " just", " fatigue."]), "Nothing alarming, just fatigue.");
});

Deno.test("scrubbing a stream matches scrubbing the whole text at once", () => {
  const full = "You're fine — really. Keep 5—8 reps, easy pace – all week.";
  for (const size of [1, 2, 3, 7, 13, 500]) {
    const chunks: string[] = [];
    for (let i = 0; i < full.length; i += size) chunks.push(full.slice(i, i + size));
    assertEquals(
      scrubbed(chunks),
      stripDashes(full),
      `chunk size ${size} disagreed with whole-text scrubbing`,
    );
  }
});

Deno.test("dash-free text passes through byte for byte", () => {
  const full = "Easy run today, then strides. Keep 5-8 reps in reserve.";
  assertEquals(scrubbed([full]), full);
  assertEquals(scrubbed(full.split("")), full);
});

Deno.test("nothing is swallowed: trailing whitespace survives the flush", () => {
  assertEquals(scrubbed(["hello "]), "hello ");
  assertEquals(scrubbed(["hello", " ", "world"]), "hello world");
});

Deno.test("a streamed reply needs no reset when only dashes differ", () => {
  // The end-to-end property: what the client was shown equals what cleanReply
  // will persist, so coach-chat's invariant check stays quiet.
  const raw = "You're carrying fatigue — nothing alarming, just volume.";
  const events: StreamEvent[] = [];
  const spec = finalLegStream((e) => events.push(e));
  const s = dashScrubber(stripDashes);
  for (const ch of raw) spec.onDelta(s.push(ch));
  spec.onDelta(s.flush());
  spec.endMessage();
  assertEquals(spec.committed(), stripDashes(raw));
  assertEquals(events.filter((e) => e.reset).length, 0);
});
