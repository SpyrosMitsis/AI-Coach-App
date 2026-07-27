// ============================================================================
// The device QA runner.
//
//   scripts/dev.sh qa:device [--live] [--steps a,b] [--serial S]
//
// Walks the installed app on the real phone, resolving every tap by on-screen
// text rather than by coordinate, and asserts what a careful manual pass would:
// each tab opens, the coach landing renders, the keyboard lifts the composer
// instead of the whole app, a restored thread carries no false banner,
// thresholds stay read-only. Every dump is also swept for em dashes, leaked
// tool-protocol JSON and "null"/"undefined" reaching the screen, and the run
// ends with a logcat crash sweep.
//
// Read-only by default: it navigates, it does not write training data. --live
// adds one real coach turn (one LLM call on whatever account the phone is
// signed into).
//
// Artefacts land in qa_runs/<timestamp>/: a PNG and the hierarchy XML per step,
// plus report.json. A failure is meant to come with the evidence attached.
// ============================================================================

import { Assert, crashes, dashes, placeholders, rawJson } from "./checks.ts";
import { Device, type Dump } from "./device.ts";
import { STEPS } from "./scenarios.ts";

const APP_ID = "com.workoutmaker.app";

interface Args {
  live: boolean;
  steps?: string[];
  serial?: string;
  outDir: string;
}

function parseArgs(argv: string[]): Args {
  const a: Args = { live: false, outDir: "qa_runs" };
  for (let i = 0; i < argv.length; i++) {
    const v = argv[i + 1];
    switch (argv[i]) {
      case "--live": a.live = true; break;
      case "--steps": a.steps = (v ?? "").split(",").map((s) => s.trim()).filter(Boolean); i++; break;
      case "--serial": a.serial = v; i++; break;
      case "--out": a.outDir = v ?? a.outDir; i++; break;
      default: die(`unknown arg "${argv[i]}"`);
    }
  }
  return a;
}

function die(msg: string): never {
  console.error(`\x1b[31mxx\x1b[0m ${msg}`);
  Deno.exit(1);
}
const say = (m: string) => console.log(`\x1b[34m>>\x1b[0m ${m}`);
const dim = (m: string) => `\x1b[2m${m}\x1b[0m`;

async function resolveSerial(explicit?: string): Promise<string> {
  if (explicit) return explicit;
  const env = Deno.env.get("ANDROID_SERIAL");
  const out = await new Deno.Command("adb", { args: ["devices"], stdout: "piped" }).output();
  const attached = new TextDecoder().decode(out.stdout).split("\n").slice(1)
    .map((l) => l.split("\t"))
    .filter((p) => p[1]?.trim() === "device")
    .map((p) => p[0]);
  if (attached.length === 0) die("no device attached");
  // Same phone, other transport: dev.sh resolves wired vs wireless this way too.
  if (env) {
    const hit = attached.find((s) => s === env) ?? attached.find((s) => s.includes(env) || env.includes(s));
    if (hit) return hit;
  }
  if (attached.length === 1) return attached[0];
  die(`several devices attached, pass --serial: ${attached.join(", ")}`);
}

interface StepReport {
  step: string;
  passed: number;
  failures: { check: string; detail: string }[];
  error?: string;
  artefacts: string[];
}

async function main() {
  const args = parseArgs(Deno.args);
  const serial = await resolveSerial(args.serial);
  const d = new Device(serial, APP_ID);

  const stamp = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
  const dir = `${args.outDir}/${stamp}`;
  await Deno.mkdir(dir, { recursive: true });

  say(`device ${serial}`);
  say(`artefacts ${dir}`);
  if (args.live) say("--live: this run will send one real coach message");

  await d.clearLogcat();
  // The walk starts from a running app whichever steps were selected: --steps
  // keyboard must not fail because "launch" was not among them.
  if (!(await d.foreground()).startsWith(APP_ID)) await d.launch();

  const steps = STEPS.filter((s) => (args.steps ? args.steps.includes(s.name) : true))
    .filter((s) => (s.live ? args.live : true));

  const reports: StepReport[] = [];
  let n = 0;

  for (const step of steps) {
    n++;
    const ok = new Assert();
    const artefacts: string[] = [];
    let error: string | undefined;
    let lastDump: Dump | null = null;

    const snap = async (label?: string): Promise<Dump> => {
      const base = `${String(n).padStart(2, "0")}-${step.name}${label ? `-${label}` : ""}`;
      const dump = await d.dump();
      await Deno.writeTextFile(`${dir}/${base}.xml`, dump.xml);
      await d.screenshot(`${dir}/${base}.png`);
      artefacts.push(`${base}.png`);
      lastDump = dump;
      return dump;
    };

    try {
      await step.run({ d, ok, live: args.live, snap });
      if (!lastDump) lastDump = await snap();
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
      try { await snap("error"); } catch { /* the device may be unreachable */ }
    }

    // Text hygiene, on whatever the step last had on screen. Cheap, and it is
    // the check that would have caught the em dash on Home.
    if (lastDump) {
      const dump: Dump = lastDump;
      const dashed = dashes(dump, APP_ID);
      ok.that(dashed.length === 0, "no em/en dashes on screen", dashed.join(" | ").slice(0, 200));
      const json = rawJson(dump, APP_ID);
      ok.that(json.length === 0, "no raw tool JSON on screen", json.join(" | ").slice(0, 200));
      const holes = placeholders(dump, APP_ID);
      ok.that(holes.length === 0, "no null/undefined on screen", holes.join(" | ").slice(0, 200));
    }

    reports.push({ step: step.name, passed: ok.counted - ok.failures.length, failures: ok.failures, error, artefacts });

    const bad = ok.failures.length > 0 || error;
    const mark = bad ? "\x1b[31m✗\x1b[0m" : "\x1b[32m✓\x1b[0m";
    console.log(`  ${mark} ${step.name.padEnd(20)} ${dim(`${ok.counted - ok.failures.length}/${ok.counted} checks`)}`);
    if (error) console.log(`      ${dim(`could not run: ${error}`)}`);
    for (const f of ok.failures) {
      console.log(`      \x1b[31m${f.check}\x1b[0m${f.detail ? dim(`  ${f.detail}`) : ""}`);
    }
  }

  // Crashes anywhere in the run, not just where a step happened to look.
  const log = await d.logcat();
  await Deno.writeTextFile(`${dir}/logcat.txt`, log);
  const crashed = crashes(log, APP_ID);
  const crashReport: StepReport = {
    step: "crash-sweep",
    passed: crashed.length === 0 ? 1 : 0,
    failures: crashed.map((c) => ({ check: "no crash in logcat", detail: c })),
    artefacts: ["logcat.txt"],
  };
  reports.push(crashReport);
  console.log(
    `  ${crashed.length === 0 ? "\x1b[32m✓\x1b[0m" : "\x1b[31m✗\x1b[0m"} ${"crash-sweep".padEnd(20)} ${dim(`${crashed.length} crashes`)}`,
  );
  for (const c of crashed) console.log(`      \x1b[31m${c.slice(0, 160)}\x1b[0m`);

  const failed = reports.filter((r) => r.failures.length > 0 || r.error);
  await Deno.writeTextFile(
    `${dir}/report.json`,
    JSON.stringify({ stamp, serial, live: args.live, reports }, null, 2),
  );

  console.log();
  say(`${reports.length - failed.length}/${reports.length} steps clean   ${dim(dir)}`);
  Deno.exit(failed.length === 0 ? 0 : 1);
}

if (import.meta.main) await main();
