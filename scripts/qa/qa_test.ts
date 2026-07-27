// The QA driver's own tests. Everything here is pure: the hierarchy parser and
// the sweeps, fed a fixture rather than a phone. Run with `deno test -A scripts/qa/`.

import { assert, assertEquals } from "jsr:@std/assert@1";
import { Dump, inputText, parseDump } from "./device.ts";
import { crashes, dashes, placeholders, rawJson } from "./checks.ts";

const APP = "com.workoutmaker.app";
const IME = "com.google.android.inputmethod.latin";

function node(
  attrs: { text?: string; desc?: string; pkg?: string; cls?: string; clickable?: boolean; bounds?: string },
): string {
  return `<node text="${attrs.text ?? ""}" content-desc="${attrs.desc ?? ""}" ` +
    `class="${attrs.cls ?? "android.view.View"}" package="${attrs.pkg ?? APP}" ` +
    `clickable="${attrs.clickable ?? false}" password="false" focused="false" ` +
    `bounds="${attrs.bounds ?? "[0,0][100,100]"}">`;
}

function dumpOf(nodes: string[]): Dump {
  const xml = `<?xml version='1.0'?><hierarchy rotation="0">${nodes.join("")}</hierarchy>`;
  return new Dump(parseDump(xml), xml);
}

Deno.test("parses text, description and bounds off a Compose hierarchy", () => {
  const d = dumpOf([
    node({ text: "AI COACH", bounds: "[40,150][200,210]" }),
    node({ desc: "Chat history", clickable: true, bounds: "[1080,240][1160,320]" }),
  ]);
  const header = d.find({ text: "AI COACH" }, APP);
  assert(header);
  assertEquals(header.bounds, { x1: 40, y1: 150, x2: 200, y2: 210 });
  assert(d.find({ desc: "Chat history" }, APP)?.clickable);
});

Deno.test("decodes the entities uiautomator escapes", () => {
  const d = dumpOf([node({ text: "Moods &amp; genres &lt;3" })]);
  assertEquals(d.nodes[0].text, "Moods & genres <3");
});

Deno.test("ofApp excludes the keyboard and the system bars", () => {
  const d = dumpOf([
    node({ text: "Message your coach…" }),
    node({ text: "q", pkg: IME }),
    node({ text: "10:05", pkg: "com.android.systemui" }),
  ]);
  assertEquals(d.texts(APP), ["Message your coach…"]);
});

Deno.test("imeTop reads the keyboard's own window, or null when it is down", () => {
  const up = dumpOf([
    node({ text: "composer", bounds: "[0,1600][1440,1780]" }),
    node({ text: "q", pkg: IME, bounds: "[0,1850][144,1990]" }),
    node({ text: "a", pkg: IME, bounds: "[0,1990][144,2130]" }),
  ]);
  assertEquals(up.imeTop(APP), 1850);
  assertEquals(dumpOf([node({ text: "composer" })]).imeTop(APP), null);
});

// The check that would have caught the Home readiness note.
Deno.test("the dash sweep reads rendered prose, not prompts", () => {
  const d = dumpOf([
    node({ text: "today's full body strength session — just keep the knee happy" }),
    node({ text: "Train with care" }),
  ]);
  assertEquals(dashes(d, APP).length, 1);
  assertEquals(dashes(dumpOf([node({ text: "5-8 reps, nothing alarming" })]), APP), []);
});

Deno.test("the dash sweep ignores dashes coming from the keyboard or system UI", () => {
  const d = dumpOf([node({ text: "—", pkg: IME }), node({ text: "fine" })]);
  assertEquals(dashes(d, APP), []);
});

Deno.test("leaked tool protocol is caught where prose belongs", () => {
  // uiautomator escapes quotes inside an attribute, so the fixture must too:
  // this is the shape the parser actually receives.
  const d = dumpOf([node({ text: "{&quot;action&quot;:&quot;final&quot;,&quot;message&quot;:&quot;here is your week&quot;}" })]);
  assertEquals(rawJson(d, APP).length, 1);
  assertEquals(rawJson(dumpOf([node({ text: "Here is your week." })]), APP), []);
});

Deno.test("placeholder holes reaching the screen are caught", () => {
  const d = dumpOf([node({ text: "Last synced: undefined" }), node({ text: "Load 43 TSS" })]);
  assertEquals(placeholders(d, APP).length, 1);
});

Deno.test("the crash sweep finds a fatal, and stays quiet otherwise", () => {
  const log = [
    "I/wm      ( 1234): fine",
    "E/AndroidRuntime( 1234): FATAL EXCEPTION: main",
    "E/AndroidRuntime( 1234): Process: com.workoutmaker.app, PID: 1234",
    "E/AndroidRuntime( 1234): java.lang.IllegalArgumentException: Padding must be non-negative",
  ].join("\n");
  assertEquals(crashes(log, APP).length, 1);
  assert(crashes(log, APP)[0].includes("Padding must be non-negative"));
  assertEquals(crashes("I/wm ( 1): all good\n", APP), []);
});

// The mutation that got away: with the keyboard fix reverted, the coach header
// was panned off the top and uiautomator reported it as [0,0][0,0]. A presence
// check passed on a build with the bug, so "present" now means "has area".
Deno.test("a node panned off screen does not count as present", () => {
  const d = dumpOf([
    node({ text: "AI COACH", bounds: "[0,0][0,0]" }),
    node({ text: "Message your coach…", bounds: "[105,1693][729,1783]" }),
  ]);
  assertEquals(d.find({ text: "AI COACH" }, APP), null);
  assert(d.find({ text: "Message your coach…" }, APP));
  assertEquals(d.texts(APP), ["Message your coach…"]);
});

Deno.test("typed text keeps the apostrophe the coach actually receives", () => {
  // A live run sent "How\'s my fitness?" because the apostrophe was escaped
  // too. Inside double quotes it needs nothing.
  assertEquals(inputText("How's my fitness?"), "How's%smy%sfitness?");
  assertEquals(inputText('say "hi" $now'), 'say%s\\"hi\\"%s\\$now');
});
