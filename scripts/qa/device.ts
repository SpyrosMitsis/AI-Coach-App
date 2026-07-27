// ============================================================================
// adb primitives for the device QA walk.
//
// The point of this file is that NOTHING here takes a screen coordinate from
// the caller. Every tap resolves a node by its text or content description in a
// FRESH accessibility dump and taps the centre of that node's bounds.
//
// That is not a style preference. The manual passes this replaces drove the
// phone with hardcoded pixels, and the failure modes were: taps landing in a
// different app entirely once the app had been uninstalled underneath them, and
// a tap on a password field that missed because the keyboard had reflowed the
// layout since the coordinates were read. Both are impossible below: `tap`
// re-dumps first, and every action asserts the app is in the foreground.
// ============================================================================

export interface Rect {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

export interface Node {
  text: string;
  desc: string;
  cls: string;
  pkg: string;
  bounds: Rect;
  clickable: boolean;
  focused: boolean;
  password: boolean;
}

const ATTR = /(\w[\w-]*)="([^"]*)"/g;
const BOUNDS = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/;

function decode(s: string): string {
  return s
    .replaceAll("&amp;", "&")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&quot;", '"')
    .replaceAll("&apos;", "'")
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)));
}

/** Parse a uiautomator hierarchy into flat nodes. Exported for its own test. */
export function parseDump(xml: string): Node[] {
  const nodes: Node[] = [];
  for (const tag of xml.match(/<node\b[^>]*>/g) ?? []) {
    const a: Record<string, string> = {};
    for (const m of tag.matchAll(ATTR)) a[m[1]] = decode(m[2]);
    const b = BOUNDS.exec(a.bounds ?? "");
    if (!b) continue;
    nodes.push({
      text: a.text ?? "",
      desc: a["content-desc"] ?? "",
      cls: a.class ?? "",
      pkg: a.package ?? "",
      bounds: { x1: +b[1], y1: +b[2], x2: +b[3], y2: +b[4] },
      clickable: a.clickable === "true",
      focused: a.focused === "true",
      password: a.password === "true",
    });
  }
  return nodes;
}

export interface Match {
  /** Exact text match. */
  text?: string;
  /** Substring of the text, case-insensitive. */
  textContains?: string;
  /** Exact content description (icon buttons carry these). */
  desc?: string;
  /** Substring of the widget class, e.g. "EditText". */
  cls?: string;
  re?: RegExp;
}

/**
 * On screen, as opposed to merely present in the hierarchy.
 *
 * A node scrolled or PANNED out of the window is still reported, with its
 * bounds collapsed to [0,0][0,0]. That is not a curiosity: with the keyboard
 * fix reverted, the coach header was panned off the top and still answered a
 * "is it there?" check, so the regression test passed on a build that had the
 * bug. Anything with no area is not on screen.
 */
export function isVisible(n: Node): boolean {
  return n.bounds.x2 > n.bounds.x1 && n.bounds.y2 > n.bounds.y1;
}

export class Dump {
  constructor(readonly nodes: Node[], readonly xml: string) {}

  /** Only the app's own nodes: the IME and system bars are in here too. */
  ofApp(pkg: string): Node[] {
    return this.nodes.filter((n) => n.pkg === pkg);
  }

  /** Every string the app is actually showing. */
  texts(pkg: string): string[] {
    return this.ofApp(pkg)
      .filter(isVisible)
      .flatMap((n) => [n.text, n.desc])
      .filter((s) => s.trim().length > 0);
  }

  findAll(m: Match, pkg?: string): Node[] {
    const pool = (pkg ? this.ofApp(pkg) : this.nodes).filter(isVisible);
    return pool.filter((n) => {
      if (m.text !== undefined && n.text !== m.text && n.desc !== m.text) return false;
      if (m.desc !== undefined && n.desc !== m.desc) return false;
      if (
        m.textContains !== undefined &&
        !n.text.toLowerCase().includes(m.textContains.toLowerCase()) &&
        !n.desc.toLowerCase().includes(m.textContains.toLowerCase())
      ) return false;
      if (m.cls !== undefined && !n.cls.includes(m.cls)) return false;
      if (m.re !== undefined && !m.re.test(n.text) && !m.re.test(n.desc)) return false;
      return true;
    });
  }

  find(m: Match, pkg?: string): Node | null {
    return this.findAll(m, pkg)[0] ?? null;
  }

  /**
   * Whether `n` can actually be tapped: Compose puts `clickable` on the merged
   * semantics node, which is usually the chip or row CONTAINING the label, not
   * the label itself. Asking the text node alone reported zero clickable chips
   * on a screen full of working chips.
   */
  clickableFor(n: Node): Node | null {
    if (n.clickable) return n;
    const contains = (o: Node) =>
      o.clickable &&
      o.bounds.x1 <= n.bounds.x1 && o.bounds.y1 <= n.bounds.y1 &&
      o.bounds.x2 >= n.bounds.x2 && o.bounds.y2 >= n.bounds.y2;
    const area = (o: Node) => (o.bounds.x2 - o.bounds.x1) * (o.bounds.y2 - o.bounds.y1);
    // The tightest enclosing clickable: the row, not the whole screen.
    return this.nodes.filter(contains).sort((a, b) => area(a) - area(b))[0] ?? null;
  }

  isClickable(m: Match, pkg?: string): boolean {
    const n = this.find(m, pkg);
    return n !== null && this.clickableFor(n) !== null;
  }

  has(m: Match, pkg?: string): boolean {
    return this.find(m, pkg) !== null;
  }

  /**
   * Top edge of the on-screen keyboard, or null when it is down. Taken from the
   * IME's own window in the same dump, so it cannot disagree with what the
   * layout was measured against.
   */
  imeTop(appPkg: string): number | null {
    const ime = this.nodes.filter((n) => n.pkg !== appPkg && /inputmethod|latin|keyboard/i.test(n.pkg));
    if (ime.length === 0) return null;
    return Math.min(...ime.map((n) => n.bounds.y1));
  }
}

export function describe(n: Node): string {
  return n.text || n.desc || n.cls;
}

/**
 * Prepare text for `input text "..."` on the device shell.
 *
 * Only what is special INSIDE double quotes needs a backslash. Escaping the
 * apostrophe as well looked harmless and was not: the coach received
 * `How\'s my fitness?`, backslash and all, in a live run.
 */
export function inputText(text: string): string {
  return text.replace(/(["\\$`])/g, "\\$1").replaceAll(" ", "%s");
}

export class Device {
  constructor(readonly serial: string, readonly appId: string) {}

  async sh(args: string[], stdinText?: string): Promise<string> {
    const cmd = new Deno.Command("adb", {
      args: ["-s", this.serial, ...args],
      stdout: "piped",
      stderr: "piped",
      stdin: stdinText ? "piped" : "null",
    });
    const p = cmd.spawn();
    if (stdinText) {
      const w = p.stdin.getWriter();
      await w.write(new TextEncoder().encode(stdinText));
      await w.close();
    }
    const out = await p.output();
    if (!out.success) {
      throw new Error(`adb ${args.join(" ")} failed: ${new TextDecoder().decode(out.stderr).trim()}`);
    }
    return new TextDecoder().decode(out.stdout);
  }

  private shell(line: string): Promise<string> {
    return this.sh(["shell", line]);
  }

  async foreground(): Promise<string> {
    const out = await this.shell("dumpsys window | grep -m1 mCurrentFocus");
    return out.match(/u0 ([\w.]+)/)?.[1] ?? out.trim();
  }

  /** Throws with what IS on screen, rather than letting a tap land elsewhere. */
  async requireForeground(): Promise<void> {
    const fg = await this.foreground();
    if (!fg.startsWith(this.appId)) {
      throw new Error(`${this.appId} is not in the foreground (${fg || "nothing"} is)`);
    }
  }

  async launch(): Promise<void> {
    await this.shell(`monkey -p ${this.appId} -c android.intent.category.LAUNCHER 1`);
    await sleep(6000);
  }

  async forceStop(): Promise<void> {
    await this.shell(`am force-stop ${this.appId}`);
    await sleep(800);
  }

  /**
   * A fresh hierarchy. uiautomator returns "null root node" while the screen is
   * animating, which is most of the time right after a tap, so retry rather
   * than asserting against a half-composed frame.
   */
  async dump(tries = 4): Promise<Dump> {
    for (let i = 0; i < tries; i++) {
      const res = await this.shell("uiautomator dump /sdcard/wm_qa.xml");
      if (res.includes("dumped to")) {
        const xml = await this.sh(["exec-out", "cat", "/sdcard/wm_qa.xml"]);
        const nodes = parseDump(xml);
        if (nodes.length > 0) return new Dump(nodes, xml);
      }
      await sleep(900);
    }
    throw new Error("uiautomator never returned a usable hierarchy");
  }

  /** Resolve `m` in a FRESH dump and tap the centre of it. */
  async tap(m: Match, opts: { settle?: number } = {}): Promise<void> {
    await this.requireForeground();
    const d = await this.dump();
    const n = d.find(m, this.appId) ?? d.find(m);
    if (!n) throw new Error(`nothing on screen matches ${JSON.stringify(m, replacer)}`);
    // Tap the label's own centre: it is inside the clickable ancestor, and the
    // ancestor's centre can fall outside a tall row.
    const x = Math.round((n.bounds.x1 + n.bounds.x2) / 2);
    const y = Math.round((n.bounds.y1 + n.bounds.y2) / 2);
    await this.shell(`input tap ${x} ${y}`);
    await sleep(opts.settle ?? 1800);
  }

  /**
   * Switch tabs and confirm the destination actually rendered. A tap during the
   * previous screen's settle silently does nothing, which then fails a later
   * assertion as if the feature were broken: the run that reported "no numbers
   * & zones entry in Settings" had simply never left the coach thread.
   */
  async openTab(tab: string, anchor: Match, tries = 3): Promise<Dump> {
    for (let i = 0; i < tries; i++) {
      await this.toTabBar(tab);
      await this.tap({ text: tab }, { settle: 2200 });
      const d = await this.dump();
      if (d.has(anchor, this.appId)) return d;
      await sleep(1200);
    }
    throw new Error(`tapped "${tab}" ${tries} times and never landed on ${JSON.stringify(anchor, replacer)}`);
  }

  /**
   * Back to the tab bar, whatever is open. Steps must not inherit a bottom
   * sheet from the step before: the first run failed "settings" only because
   * the history sheet was still covering the tab it needed to tap.
   */
  async toTabBar(tab = "Home", tries = 4): Promise<void> {
    // An inherited keyboard covers the tab bar, and on a build that pans it
    // hides the header the next step is about to look for. Start from down.
    if (await this.imeShown()) await this.closeKeyboard();
    for (let i = 0; i < tries; i++) {
      const d = await this.dump();
      const t = d.find({ text: tab }, this.appId);
      // Present AND near the bottom of the screen: a search result would match
      // the text without being the tab.
      if (t && t.bounds.y1 > 2000) return;
      await this.back();
    }
  }

  async type(text: string): Promise<void> {
    await this.shell(`input text "${inputText(text)}"`);
    await sleep(600);
  }

  async back(): Promise<void> {
    await this.shell("input keyevent 4");
    await sleep(1200);
  }

  /**
   * Put the keyboard away, and be sure it went.
   *
   * BACK first: with the IME up it dismisses the IME and does not pop the
   * screen. ESC (111) is the fallback, not the default, because this phone's
   * keyboard ignores it: a run that assumed ESC had worked went on tapping the
   * tab bar, which was under the keyboard, and typed "333333" into the composer.
   */
  async closeKeyboard(): Promise<void> {
    if (!(await this.imeShown())) return;
    for (const key of [4, 111]) {
      await this.shell(`input keyevent ${key}`);
      await sleep(1200);
      if (!(await this.imeShown())) return;
    }
    throw new Error("the keyboard would not close; every later tap would land on it");
  }

  /** Empty the focused field, so typing appends to nothing. */
  async clearText(): Promise<void> {
    await this.shell("input keycombination 113 29"); // CTRL+A
    await sleep(400);
    await this.shell("input keyevent 67"); // DEL
    await sleep(400);
  }

  async imeShown(): Promise<boolean> {
    const out = await this.shell("dumpsys input_method | grep -m1 mInputShown");
    return /mInputShown=true/.test(out);
  }

  async screenshot(path: string): Promise<void> {
    const png = new Deno.Command("adb", {
      args: ["-s", this.serial, "exec-out", "screencap", "-p"],
      stdout: "piped",
      stderr: "null",
    });
    const out = await png.output();
    if (out.success) await Deno.writeFile(path, out.stdout);
  }

  async clearLogcat(): Promise<void> {
    await this.sh(["logcat", "-c"]).catch(() => {});
  }

  /** Everything logged since clearLogcat, for the crash sweep. */
  async logcat(): Promise<string> {
    return await this.sh(["logcat", "-d", "-v", "brief"]).catch(() => "");
  }
}

function replacer(_k: string, v: unknown) {
  return v instanceof RegExp ? v.source : v;
}

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
