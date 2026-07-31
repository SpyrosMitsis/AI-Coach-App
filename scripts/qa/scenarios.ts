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
    // The guided goal sheet: four questions, one per screen, and the two that
    // are easy to get wrong on a real phone. Step 2 is onboarding's own
    // distance picker inside a bottom sheet (a Canvas figure and a drag track
    // that have never been laid out in one before), and the counter has to say
    // FOUR for the gym, which takes a different second question.
    name: "goal-sheet",
    async run({ d, ok, snap }) {
      const opened = await d.openTab("Settings", { textContains: "Goals & races" });
      const entry = opened.find({ textContains: "Goals & races" }, d.appId)!;
      await d.tap({ text: entry.text }, { settle: 2500 });

      // "Add goal" is the button on the empty state and the list alike.
      await d.tap({ textContains: "Add goal" }, { settle: 2200 });
      const step1 = await snap("step1");
      ok.that(step1.has({ textContains: "What's the event?" }, d.appId), "step 1 asks the event");
      ok.that(step1.has({ textContains: "STEP 1 OF 4" }, d.appId), "counter starts at 1 of 4");
      // The name field sits ABOVE the sport tiles: the athlete arrives holding
      // the name, and the tiles are the qualifier.
      const field = step1.find({ textContains: "Call it what you call it" }, d.appId);
      const runTile = step1.find({ text: "Run" }, d.appId);
      ok.that(field !== null && runTile !== null, "name field and sport tiles are both present");
      if (field && runTile) {
        ok.that(field.bounds.y1 < runTile.bounds.y1, "the name field is above the tiles",
          `field ${field.bounds.y1}, tiles ${runTile.bounds.y1}`);
      }

      // Name it, and the sheet must not have panned the button off screen.
      await d.tap({ textContains: "Call it what you call it" }, { settle: 1200 });
      await d.type("QA Marathon");
      await d.closeKeyboard();
      const named = await snap("named");
      const cont = named.find({ textContains: "Continue" }, d.appId);
      ok.that(cont !== null, "Continue is reachable with the name typed");

      // Step 2 for a run: the picker, the pace panel, and the figure.
      await d.tap({ textContains: "Continue" }, { settle: 2500 });
      const step2 = await snap("step2-distance");
      ok.that(step2.has({ textContains: "How far, and how fast?" }, d.appId), "step 2 is the picker");
      ok.that(step2.has({ textContains: "STEP 2 OF 4" }, d.appId), "counter reads 2 of 4");
      ok.that(step2.has({ textContains: "Distance goal" }, d.appId), "the drag track rendered");
      ok.that(step2.has({ desc: "Faster" }, d.appId) && step2.has({ desc: "Slower" }, d.appId),
        "the pace steppers rendered");

      // The stepper that could only ever move once (a pointerInput(Unit) that
      // never restarted). Press it three times and the pace must keep moving.
      const paceOf = (dump: Awaited<ReturnType<typeof snap>>) =>
        dump.findAll({ re: /^\d+:\d\d$/ }, d.appId).map((n) => n.text)[0] ?? null;
      const before = paceOf(step2);
      for (let i = 0; i < 3; i++) await d.tap({ desc: "Faster" }, { settle: 700 });
      const after = paceOf(await snap("step2-stepped"));
      ok.that(before !== null && after !== null && before !== after,
        "the pace stepper keeps stepping", `${before} then ${after}`);

      // Step 3, then step 4 with the phase strip a main goal earns.
      await d.tap({ textContains: "Continue" }, { settle: 2200 });
      const step3 = await snap("step3-priority");
      ok.that(step3.has({ textContains: "How much does it matter?" }, d.appId), "step 3 asks priority");
      ok.that(step3.has({ text: "Main goal" }, d.appId) && step3.has({ text: "Tune-up" }, d.appId),
        "the three priorities are cards");

      await d.tap({ textContains: "Next: when is it?" }, { settle: 2200 });
      const step4 = await snap("step4-date");
      ok.that(step4.has({ textContains: "STEP 4 OF 4" }, d.appId), "counter ends at 4 of 4");
      ok.that(step4.has({ text: "RACE DAY" }, d.appId), "the date readout rendered");
      ok.that(step4.has({ textContains: "weeks away" }, d.appId), "the countdown rendered");
      ok.that(step4.has({ textContains: "TAPER" }, d.appId), "a main goal shows the phase strip");
      ok.that(step4.has({ textContains: "Add goal" }, d.appId), "the last step offers Add goal");

      // Leave without saving: this is the athlete's real goal list. Two backs,
      // not one: the first closes the sheet and the second leaves the Goals
      // page for the Settings index. Stopping on the sub-page left the next
      // step tapping a Settings tab it was already inside, which cannot pop it.
      await d.back();
      await d.back();
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
      // "Test me" is the button; the card's own caption explains that logging a
      // test is what updates the number. The check is that ONE editor exists,
      // so it anchors on the button the screen actually shows.
      const logTest = dump.find({ textContains: "Test me" }, d.appId);
      ok.that(logTest !== null, "'Test me' is the editor");

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
