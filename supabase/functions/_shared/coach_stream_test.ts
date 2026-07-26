import { assert, assertEquals } from "jsr:@std/assert@1";
import { HOLDBACK_CHARS, speculativeStream, type StreamEvent } from "./coach_stream.ts";

// Collect what the client would be told, and reconstruct what it would show.
function harness(holdback = HOLDBACK_CHARS) {
  const events: StreamEvent[] = [];
  const s = speculativeStream((e) => events.push(e), holdback);
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

Deno.test("a text-only reply streams through in full with no resets", () => {
  const h = harness();
  for (const c of LONG) h.s.onDelta(c);
  h.s.endMessage();
  assertEquals(h.screen(), LONG);
  assertEquals(h.s.committed(), LONG);
  assertEquals(h.resets(), 0);
});

// The common Anthropic shape: narrate, then call a tool. The athlete should see
// only the tool progress row, never the abandoned sentence.
Deno.test("a short preamble before a tool call is dropped silently", () => {
  const h = harness();
  for (const c of "Let me check.") h.s.onDelta(c);
  h.s.onToolStart();
  assertEquals(h.screen(), "");
  assertEquals(h.s.committed(), "");
  assertEquals(h.resets(), 0);
  assertEquals(h.events.length, 0, "nothing at all should reach the client");
});

Deno.test("text past the hold-back then a tool call costs exactly one reset", () => {
  const h = harness();
  for (const c of LONG) h.s.onDelta(c);
  h.s.onToolStart();
  assertEquals(h.resets(), 1);
  assertEquals(h.screen(), "");
  assertEquals(h.s.committed(), "");
});

Deno.test("a reply after a reset still arrives intact", () => {
  const h = harness();
  for (const c of LONG) h.s.onDelta(c);
  h.s.onToolStart();
  const answer = "Your CTL is climbing nicely, keep Thursday hard.";
  for (const c of answer) h.s.onDelta(c);
  h.s.endMessage();
  assertEquals(h.screen(), answer);
  assertEquals(h.s.committed(), answer);
  assertEquals(h.resets(), 1);
});

Deno.test("several tool calls after only short preambles never reset", () => {
  const h = harness();
  for (let i = 0; i < 3; i++) {
    for (const c of "Checking.") h.s.onDelta(c);
    h.s.onToolStart();
  }
  const answer = "All good, you're on track for the half.";
  for (const c of answer) h.s.onDelta(c);
  h.s.endMessage();
  assertEquals(h.resets(), 0);
  assertEquals(h.screen(), answer);
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

Deno.test("a short final message is still delivered when the stream ends", () => {
  const h = harness();
  // Well under the hold-back, so it only escapes via endMessage().
  for (const c of "Done.") h.s.onDelta(c);
  h.s.endMessage();
  assertEquals(h.screen(), "Done.");
});

Deno.test("each assistant message gets its own hold-back window", () => {
  const h = harness();
  for (const c of "First.") h.s.onDelta(c);
  h.s.endMessage();
  // A short second message must not ride the first one's flushed state.
  for (const c of "Hm.") h.s.onDelta(c);
  h.s.onToolStart();
  assertEquals(h.resets(), 0, "a fresh short preamble should drop, not reset");
  assertEquals(h.screen(), "First.");
});

Deno.test("empty deltas are ignored and never trigger an empty reset", () => {
  const h = harness();
  h.s.onDelta("");
  h.s.onToolStart();
  h.s.endMessage();
  assertEquals(h.events.length, 0);
  assertEquals(h.resets(), 0);
});

Deno.test("the hold-back threshold is configurable and respected", () => {
  const h = harness(4);
  for (const c of "abc") h.s.onDelta(c);
  assertEquals(h.screen(), "", "under the threshold, nothing is shown yet");
  h.s.onDelta("d");
  assertEquals(h.screen(), "abcd", "crossing the threshold flushes the buffer");
});

Deno.test("a chunky delta larger than the hold-back flushes whole, not truncated", () => {
  const h = harness();
  h.s.onDelta(LONG);
  h.s.endMessage();
  assertEquals(h.screen(), LONG);
  assert(LONG.length > HOLDBACK_CHARS);
});
