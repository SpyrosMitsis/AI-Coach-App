// Assertions over a dump. Pure, so they are testable without a phone
// (scripts/qa/qa_test.ts) and reusable by every step.

import type { Dump } from "./device.ts";

export interface Failure {
  check: string;
  detail: string;
}

/** Collects failures instead of throwing, so one bad check does not hide the rest. */
export class Assert {
  readonly failures: Failure[] = [];
  private passes = 0;

  that(ok: boolean, check: string, detail = ""): boolean {
    if (ok) this.passes++;
    else this.failures.push({ check, detail });
    return ok;
  }

  get ok(): boolean {
    return this.failures.length === 0;
  }
  get counted(): number {
    return this.passes + this.failures.length;
  }
}

/**
 * The house no-dash rule, checked where the athlete actually reads it. This is
 * the one that matters most: a dash reached the Home readiness note and no test
 * in the repo looked at rendered output, only at prompts.
 */
export function dashes(d: Dump, pkg: string): string[] {
  return d.texts(pkg).filter((t) => /[—–]/.test(t));
}

/** Tool-protocol envelope or a serialised object where prose belongs. */
export function rawJson(d: Dump, pkg: string): string[] {
  return d.texts(pkg).filter((t) =>
    /\{"(action|message|reply|final|tool)"/.test(t) || /^\s*\{[\s\S]*"\w+"\s*:/.test(t)
  );
}

/** A formatting hole reaching the screen: "null", "undefined", "NaN". */
export function placeholders(d: Dump, pkg: string): string[] {
  return d.texts(pkg).filter((t) => /\b(null|undefined|NaN)\b/.test(t));
}

const CRASH = /FATAL EXCEPTION|ANR in |E\/AndroidRuntime/;

export function crashes(logcat: string, _pkg: string): string[] {
  const lines = logcat.split("\n");
  const hits: string[] = [];
  for (let i = 0; i < lines.length; i++) {
    if (!CRASH.test(lines[i])) continue;
    // One hit per crash, not per line: a stack trace is dozens of matching
    // lines and reporting "37 crashes" for one exception is noise. Take this
    // line plus its continuation as the report, then skip past them.
    let end = i + 1;
    while (end < lines.length && CRASH.test(lines[end]) && end - i < 6) end++;
    hits.push(lines.slice(i, end).join(" ").trim().slice(0, 300));
    i = end - 1;
  }
  return hits;
}
