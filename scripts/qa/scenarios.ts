// The QA walk: what a careful pass over the app checks, written down.
//
// Each step navigates, asserts, and leaves the app somewhere the next step can
// start from. Anything a step could not reach throws, which fails that step and
// moves on rather than ending the run.

import { Assert } from "./checks.ts";
import type { Device, Dump } from "./device.ts";
import { sleep } from "./device.ts";

export interface Ctx {
  d: Device;
  ok: Assert;
  /** Whether this run may spend an LLM call (`--live`). */
  live: boolean;
  /** Save a screenshot + hierarchy under the step's name. */
  snap: (label?: string) => Promise<Dump>;
}

export interface Step {
  name: string;
  /** Skipped unless --live: sends a real message and costs a coach call. */
  live?: boolean;
  run: (c: Ctx) => Promise<void>;
}

const TABS = ["Home", "Coach", "Calendar", "Strength", "Settings"];
const HERO_SUBTITLE = "What are we training today?";
// The composer is matched by CLASS, not by its placeholder: the placeholder is
// only there while the box is empty, so a leftover draft made every "find the
// composer" step fail with "nothing on screen matches Message your coach…".
const COMPOSER = { cls: "EditText" } as const;
const COMPOSER_HINT = "Message your coach…";
const PROPOSAL_BANNER = "Coach proposed this but didn't apply it:";
const GREETING = /^(Good morning|Good afternoon|Good evening|Still up), /;

export const STEPS: Step[] = [
  {
    name: "launch",
    async run({ d, ok }) {
      await d.forceStop();
      await d.launch();
      const dump = await d.dump();

      // A signed-out app renders a perfectly valid screen that fails every
      // check below for one uninteresting reason. Say so instead.
      if (dump.has({ text: "Sign in" }, d.appId) && dump.has({ text: "Password" }, d.appId)) {
        throw new Error("the app is signed out — sign in on the phone, then re-run");
      }
      ok.that(dump.has({ text: "DAILY READINESS" }, d.appId), "home renders", "no DAILY READINESS label");

      // The readiness card must not claim a recovery state it has no data for.
      // With nothing measured the server still computes 50/amber from its own
      // neutral defaults, and the card used to draw that as a real reading:
      // ring, number, "Moderately recovered". Whatever this account's data
      // looks like on the day, the two halves have to agree.
      const unmeasured = dump.has({ textContains: "No readiness data yet" }, d.appId);
      const claimsState = dump.texts(d.appId).some((t) =>
        /^(Ready to train|Train with care|Prioritise recovery)$/.test(t)
      );
      ok.that(
        !unmeasured || !claimsState,
        "readiness card does not claim a state it cannot measure",
        "shows both 'No readiness data yet' and a readiness headline",
      );
      for (const tab of TABS) {
        ok.that(dump.has({ text: tab }, d.appId), `tab present: ${tab}`);
      }
    },
  },

  {
    name: "tabs",
    async run({ d, ok, snap }) {
      // Every tab opens and renders something. The calendar crash (a goal 20+
      // weeks out produced a negative padding) took the whole app down here.
      const anchors: Record<string, RegExp> = {
        Home: /DAILY READINESS/,
        Coach: /AI COACH/,
        Calendar: /./,
        Strength: /./,
        Settings: /./,
      };
      for (const tab of TABS) {
        await d.tap({ text: tab }, { settle: 2500 });
        const dump = await snap(tab.toLowerCase());
        const texts = dump.texts(d.appId);
        ok.that(texts.length > 0, `${tab}: renders`, "no text on screen");
        ok.that(
          texts.some((t) => anchors[tab].test(t)),
          `${tab}: shows its own content`,
          texts.slice(0, 4).join(" | "),
        );
      }
    },
  },

  {
    name: "coach-landing",
    async run({ d, ok, snap }) {
      await d.openTab("Coach", { text: "AI COACH" });
      const dump = await snap();
      const texts = dump.texts(d.appId);

      ok.that(
        texts.some((t) => GREETING.test(t)),
        "greeting hero",
        texts.slice(0, 6).join(" | "),
      );
      ok.that(dump.has({ text: HERO_SUBTITLE }, d.appId), "hero subtitle");
      ok.that(dump.has(COMPOSER, d.appId), "composer");
      ok.that(dump.has({ text: COMPOSER_HINT }, d.appId), "composer is empty and prompts");

      // Starter chips: present, and hit-testable. They were neither once, and
      // looked perfect in a screenshot while swallowing every tap.
      const chips = dump.findAll({ re: /\?$|^Plan my week$/ }, d.appId)
        .filter((n) => dump.clickableFor(n) !== null);
      ok.that(chips.length >= 2, "starter chips are clickable", `found ${chips.length}`);
    },
  },

  {
    name: "keyboard",
    async run({ d, ok, snap }) {
      // Every step navigates to its own screen: --steps must be able to run any
      // subset, and a step that inherits "wherever the last one ended" fails
      // for the wrong reason when it is run alone.
      await d.openTab("Coach", { text: "AI COACH" });
      // THE regression: targetSdk 35 makes the window edge-to-edge, which
      // retires adjustResize, so an app that ignores the IME inset gets panned
      // instead — the header slid off the top of the screen.
      await d.tap(COMPOSER, { settle: 2500 });
      ok.that(await d.imeShown(), "keyboard opens on the composer");

      const dump = await snap("ime-up");
      // find() only returns nodes with area, so a header panned off the top
      // (reported as [0,0][0,0]) fails here rather than passing a presence check.
      const header = dump.find({ text: "AI COACH" }, d.appId);
      ok.that(header !== null, "header survives the keyboard", "AI COACH is off screen: the window was panned");
      if (header) {
        ok.that(header.bounds.y1 >= 0, "header is not pushed off the top", `top=${header.bounds.y1}`);
      }

      const composer = dump.find(COMPOSER, d.appId);
      const imeTop = dump.imeTop(d.appId);
      if (composer && imeTop !== null) {
        ok.that(
          composer.bounds.y2 <= imeTop + 4,
          "composer sits above the keyboard",
          `composer bottom ${composer.bounds.y2}, keyboard top ${imeTop}`,
        );
      }
      await d.clearText();
      await d.closeKeyboard();
    },
  },

  {
    name: "history",
    async run({ d, ok, snap }) {
      await d.openTab("Coach", { text: "AI COACH" });
      await d.tap({ desc: "Chat history" }, { settle: 2500 });
      const sheet = await snap("sheet");
      ok.that(sheet.has({ text: "Chat history" }, d.appId), "history sheet opens");

      const empty = sheet.has({ text: "No conversations yet" }, d.appId);
      if (empty) {
        ok.that(true, "history is empty (nothing to open)");
        await d.back();
        return;
      }

      // Open the newest thread. Titles are the athlete's own first message, so
      // match structurally: a clickable node inside the sheet that is not the
      // sheet's own heading.
      const row = sheet.findAll({ re: /\S/ }, d.appId)
        .find((n) => n.text.length > 3 && n.text !== "Chat history" && sheet.clickableFor(n) !== null);
      if (!row) throw new Error("no conversation row found in the history sheet");
      await d.tap({ text: row.text }, { settle: 3000 });

      const thread = await snap("thread");
      ok.that(thread.texts(d.appId).length > 3, "thread renders");
      // A restored thread carries no tool record, so there is nothing to have
      // proposed. This fired on a stored answer that only described the week.
      ok.that(
        !thread.has({ text: PROPOSAL_BANNER }, d.appId),
        "no false proposal banner on a restored thread",
      );
      await d.toTabBar();
    },
  },

  {
    name: "settings-thresholds",
    async run({ d, ok, snap }) {
      const opened = await d.openTab("Settings", { textContains: "numbers & zones" });
      const zones = opened.find({ textContains: "numbers & zones" }, d.appId)!;
      await d.tap({ text: zones.text }, { settle: 2500 });

      const dump = await snap();
      for (const label of ["LTHR", "Threshold pace", "FTP"]) {
        ok.that(dump.has({ text: label }, d.appId), `threshold shown: ${label}`);
      }
      const logTest = dump.find({ textContains: "Log a test" }, d.appId);
      ok.that(logTest !== null, "'Log a test' is the editor");

      // Read-only means read-only IN THE THRESHOLDS CARD. The "Your numbers"
      // card below it is a different feature (1RM seeds) and is legitimately
      // editable, so a whole-screen EditText count fails for the wrong reason.
      const heading = dump.find({ text: "Thresholds" }, d.appId);
      if (heading && logTest) {
        const inCard = dump.ofApp(d.appId).filter((n) =>
          n.cls.includes("EditText") &&
          n.bounds.y1 >= heading.bounds.y1 && n.bounds.y2 <= logTest.bounds.y2
        );
        ok.that(inCard.length === 0, "thresholds are read-only", `${inCard.length} editable fields in the card`);
      }
      await d.back();
    },
  },

  {
    name: "coach-turn",
    live: true,
    async run({ d, ok, snap }) {
      await d.openTab("Coach", { text: "AI COACH" });
      await d.tap({ desc: "New chat" }, { settle: 2000 });
      await d.tap(COMPOSER, { settle: 2000 });
      await d.clearText();
      await d.type("How's my fitness?");
      await d.closeKeyboard();
      await d.tap({ desc: "Send" }, { settle: 2500 });

      // While it works: never more than one tool line, and the reply must
      // eventually arrive. Poll rather than sleeping blind.
      let sawStep = false;
      let replied = false;
      for (let i = 0; i < 40; i++) {
        const dump = await d.dump();
        const steps = dump.ofApp(d.appId).filter((n) => /^(Reading|Checking|Planning|Writing|Looking)/.test(n.text));
        if (steps.length > 0) sawStep = true;
        ok.that(steps.length <= 1, "one tool line at a time", `${steps.length} step rows`);
        if (dump.has(COMPOSER, d.appId) && !dump.has({ textContains: "Coach is thinking" }, d.appId) && steps.length === 0 && i > 3) {
          const assistant = dump.texts(d.appId).find((t) => t.length > 120);
          if (assistant) { replied = true; break; }
        }
        await sleep(3000);
      }
      ok.that(sawStep, "the tool line appeared", "no progress line was ever shown");
      ok.that(replied, "a reply arrived within ~2 minutes");
      const dump = await snap("reply");
      ok.that(
        !dump.has({ text: PROPOSAL_BANNER }, d.appId),
        "no false banner after a plain question",
      );
    },
  },
];
