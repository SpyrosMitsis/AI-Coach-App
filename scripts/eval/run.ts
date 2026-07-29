// ============================================================================
// The eval runner. Drives every scenario through every configured model, scores
// the output with the ENGINE'S OWN checkers, and writes one JSONL row per
// (scenario, model, repeat).
//
//   scripts/dev.sh eval:run [--tier smoke|full] [--models a,b] [--repeats N] [--no-judge]
//
// Offline by design: real prompts + llmGenerate direct. No Supabase, no edge
// functions, no DB. Nothing here can touch a real training plan, which is why it
// is safe to run against your own account's models.
//
// Output: eval_runs/<timestamp>.jsonl  (+ .raw/ for the model text, which is far
// too big to keep in a dataframe).
// ============================================================================

import { estimateCostUsd, llmGenerate } from "../../supabase/functions/_shared/llm.ts";
import type { LlmProvider } from "../../supabase/functions/_shared/types.ts";
import { extractJson } from "../../supabase/functions/_shared/llm.ts";
import { classifyViolation, scoreRaw } from "../../supabase/functions/_shared/eval_harness.ts";
import { checkWeek } from "../../supabase/functions/_shared/plan_checks.ts";
import { validateWeekPlan } from "../../supabase/functions/_shared/workout_schema.ts";
import { computeTss } from "../../supabase/functions/_shared/workout_review.ts";
import { EVAL_JUDGE, resolveModels } from "./models.ts";
import { buildScenarios, proveMultiRaceIsIdenticalToOne, type Scenario } from "./scenarios.ts";
import { judge, type JudgeVerdict } from "./judge.ts";

// ---------------------------------------------------------------------------
// args
// ---------------------------------------------------------------------------

interface Args {
  tier: "smoke" | "full";
  models?: string[];
  /** Scenario-name prefixes to include (e.g. "week/"). Default: all. */
  scenarios?: string[];
  repeats: number;
  useJudge: boolean;
  judge?: string;
  /** Pause between calls. Free tiers meter tokens per MINUTE, not per request. */
  delayMs: number;
  outDir: string;
}

function parseArgs(argv: string[]): Args {
  const a: Args = { tier: "full", repeats: 3, useJudge: true, delayMs: 0, outDir: "eval_runs" };
  for (let i = 0; i < argv.length; i++) {
    const v = argv[i + 1];
    switch (argv[i]) {
      case "--tier":
        if (v !== "smoke" && v !== "full") die(`--tier must be smoke|full, got "${v}"`);
        a.tier = v;
        i++;
        break;
      case "--models":
        a.models = (v ?? "").split(",").map((s) => s.trim()).filter(Boolean);
        i++;
        break;
      case "--scenarios":
        // Prefix match, so "week/" selects all week plans — the shape an A/B
        // needs (change a prompt line, re-run only the scenarios it touches).
        a.scenarios = (v ?? "").split(",").map((s) => s.trim()).filter(Boolean);
        i++;
        break;
      case "--repeats":
        a.repeats = Math.max(1, Number(v) || 1);
        i++;
        break;
      case "--no-judge":
        a.useJudge = false;
        break;
      case "--judge":
        a.judge = v;
        i++;
        break;
      case "--delay":
        a.delayMs = Math.max(0, Number(v) || 0);
        i++;
        break;
      case "--out":
        a.outDir = v ?? a.outDir;
        i++;
        break;
      default:
        die(`unknown arg "${argv[i]}"`);
    }
  }
  // Smoke is for iterating on the harness, not measuring variance.
  if (a.tier === "smoke" && !argv.includes("--repeats")) a.repeats = 1;
  return a;
}

function die(msg: string): never {
  console.error(`\x1b[31mxx\x1b[0m ${msg}`);
  Deno.exit(1);
}
const say = (msg: string) => console.log(`\x1b[34m>>\x1b[0m ${msg}`);
const warn = (msg: string) => console.warn(`\x1b[33m!!\x1b[0m ${msg}`);
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------------------
// Rate limits
//
// Production doesn't need this: a 429 there falls through to the next provider
// in the user's chain. The eval deliberately pins ONE model (a fallback would
// silently attribute another model's output to it), so it has to wait instead.
//
// Groq's free tier is 12,000 tokens/minute and a scenario is ~5k tokens, so it
// throttles after ~2 calls — which is exactly why a groq run scored 1/7 and told
// us nothing about groq. Its 429 body carries the answer ("Please try again in
// 5.68s"), so prefer the provider's own number over guessing.
// ---------------------------------------------------------------------------

const RATE_LIMITED = /\b429\b|rate.?limit|too many requests/i;

function retryAfterMs(msg: string): number | null {
  const m = msg.match(/try again in ([\d.]+)\s*s/i);
  if (m) return Math.ceil(parseFloat(m[1]) * 1000) + 500; // + a little slack
  const h = msg.match(/retry-after["':\s]+([\d.]+)/i);
  return h ? Math.ceil(parseFloat(h[1]) * 1000) + 500 : null;
}

async function generateWithBackoff(
  m: { provider: LlmProvider; apiKey: string; resolvedModel: string },
  args: Parameters<typeof llmGenerate>[1],
  label: string,
  tries = 4,
): Promise<Awaited<ReturnType<typeof llmGenerate>>> {
  for (let attempt = 0;; attempt++) {
    try {
      return await llmGenerate(m.provider, args);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      if (!RATE_LIMITED.test(msg) || attempt >= tries) throw e;
      const wait = retryAfterMs(msg) ?? 5000 * 2 ** attempt;
      warn(`${label}: rate limited, waiting ${(wait / 1000).toFixed(1)}s (try ${attempt + 1}/${tries})`);
      await sleep(wait);
    }
  }
}

// ---------------------------------------------------------------------------
// the row
// ---------------------------------------------------------------------------

interface Row {
  run_id: string;
  scenario: string;
  kind: string;
  catches: string;
  tier: string;
  meta: Record<string, unknown>;

  model_id: string;
  provider: string;
  model: string;
  repeat: number;
  reproducible: boolean;

  ok: boolean;
  parse_error: string | null;
  violations: string[];
  violation_kinds: string[];
  unsafe: string[];

  tss: number | null;
  tss_reported: number | null;
  rpe_target: number | null;
  duration_minutes: number | null;

  week: Record<string, unknown> | null;
  checks: Record<string, boolean | null> | null;
  check_detail: Record<string, unknown> | null;

  judge: JudgeVerdict | null;

  prompt_tokens: number;
  completion_tokens: number;
  cost_usd: number;
  ms: number;
}

// ---------------------------------------------------------------------------
// scoring
// ---------------------------------------------------------------------------

/** Score a single-workout scenario with the engine's own review. */
export function scoreWorkout(raw: string, sc: Scenario) {
  const s = scoreRaw(raw, { name: sc.name, systemPrompt: sc.systemPrompt, userPrompt: sc.userPrompt, ctx: sc.ctx! });
  let reported: number | null = null;
  let rpe: number | null = null;
  let mins: number | null = null;
  try {
    const w = extractJson<Record<string, number>>(raw);
    reported = Number(w.tss_estimate ?? NaN);
    rpe = Number(w.rpe_target ?? NaN);
    mins = Number(w.duration_minutes ?? NaN);
  } catch { /* the parse error is already on `s` */ }

  return {
    ok: s.valid,
    parse_error: s.parseError ?? null,
    violations: s.violationList ?? [],
    violation_kinds: (s.violationKinds ?? []).map(String),
    unsafe: s.unsafeList ?? [],
    tss: s.valid ? s.tss : null,
    tss_reported: Number.isFinite(reported) ? reported : null,
    rpe_target: Number.isFinite(rpe) ? rpe : null,
    duration_minutes: Number.isFinite(mins) ? mins : null,
    week: null,
    checks: null,
    check_detail: null,
  };
}

/** Score a week-plan scenario with plan_checks + per-day review. */
export function scoreWeek(raw: string, sc: Scenario) {
  let plan;
  try {
    const v = validateWeekPlan(extractJson(raw));
    if (!v.ok || !v.plan) {
      return {
        ok: false,
        parse_error: v.error ?? "week plan did not validate",
        violations: [],
        violation_kinds: [],
        unsafe: [],
        tss: null,
        tss_reported: null,
        rpe_target: null,
        duration_minutes: null,
        week: null,
        checks: null,
        check_detail: null,
      };
    }
    plan = v.plan;
  } catch (e) {
    return {
      ok: false,
      parse_error: e instanceof Error ? e.message : String(e),
      violations: [],
      violation_kinds: [],
      unsafe: [],
      tss: null,
      tss_reported: null,
      rpe_target: null,
      duration_minutes: null,
      week: null,
      checks: null,
      check_detail: null,
    };
  }

  const r = checkWeek(plan, sc.weekCtx!);
  const detail: Record<string, unknown> = {};
  const checks: Record<string, boolean | null> = {};
  for (const [name, c] of Object.entries(r.checks)) {
    checks[name] = c.ok;
    detail[name] = c.detail;
  }

  return {
    ok: true,
    parse_error: null,
    violations: r.violations,
    // Week violations come from plan_checks, whose strings are its own; classify
    // them so they share the histogram with reviewWorkout's.
    violation_kinds: r.violations.map((v) => classifyViolation(v)),
    unsafe: [],
    tss: r.metrics.totalTss,
    tss_reported: plan.days.reduce((s, d) => s + (d.session.tss_estimate || 0), 0),
    rpe_target: null,
    duration_minutes: plan.days.reduce((s, d) => s + (d.session.duration_minutes || 0), 0),
    week: {
      total_tss: r.metrics.totalTss,
      target_tss: sc.weekCtx!.targetTss,
      sessions: r.metrics.sessions,
      rest_days: r.metrics.restDays,
      hard_sessions: r.metrics.hardSessions,
      back_to_back_hard: r.metrics.backToBackHard.length,
      easy_fraction: r.metrics.easyFraction,
      easy_minutes: r.metrics.easyMinutes,
      hard_minutes: r.metrics.hardMinutes,
      sessions_by_sport: r.metrics.sessionsBySport,
      sets_by_muscle: r.metrics.setsByMuscle,
      // Per-day independent TSS, so the notebook can see the shape of the week
      // and not just its total.
      day_tss: plan.days.map((d) => computeTss(d.session)),
      day_types: plan.days.map((d) => d.session.type),
    },
    checks,
    check_detail: detail,
  };
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

async function main() {
  const args = parseArgs(Deno.args);
  const { usable, skipped } = resolveModels(args.models);

  for (const s of skipped) warn(`skipping ${s.id}: ${s.reason}`);
  if (usable.length === 0) {
    warn("no usable models, nothing to run.");
    warn("Add a key to scripts/dev.local.sh, e.g.  export GROQ_API_KEY=...");
    warn("Models are configured in scripts/eval/models.ts");
    return; // exit 0: "no keys" is a setup state, not a crash
  }

  const scenarios = buildScenarios(args.tier).filter((sc) =>
    !args.scenarios?.length || args.scenarios.some((p) => sc.name.startsWith(p))
  );
  if (scenarios.length === 0) die(`--scenarios matched nothing`);
  const wantJudge = args.judge ?? EVAL_JUDGE;
  // The judge does not have to be under test: resolve it from the full registry
  // so "--models deepseek/chat --judge mimo/v2.5-pro" grades from outside
  // without re-running the judge's own generations. Falls back to a tested
  // model (self-judging, flagged per-row) only when the wanted judge has no key.
  const judgeModel = args.useJudge
    ? resolveModels([wantJudge]).usable[0] ?? usable.find((m) => m.id === wantJudge) ?? usable[0]
    : null;

  const total = scenarios.length * usable.length * args.repeats;
  say(`tier=${args.tier}  models=${usable.map((m) => m.id).join(", ")}  repeats=${args.repeats}`);
  say(`${scenarios.length} scenarios x ${usable.length} models x ${args.repeats} = ${total} generations`);
  if (judgeModel) {
    say(`judge=${judgeModel.id} (advisory)`);
    if (usable.length === 1 && usable[0].id === judgeModel.id) {
      warn(`${judgeModel.id} is judging its OWN output — every judge score this run is self-graded.`);
      warn(`Add a third provider key and set EVAL_JUDGE (scripts/eval/models.ts) to it.`);
    }
  }
  if (args.delayMs) say(`pacing: ${args.delayMs}ms between calls`);
  for (const m of usable) {
    if (!m.reproducible) warn(`${m.id} ignores seed, its rows are marked reproducible:false`);
  }

  const runId = new Date().toISOString().replace(/[:.]/g, "-");
  await Deno.mkdir(args.outDir, { recursive: true });
  const rawDir = `${args.outDir}/${runId}.raw`;
  await Deno.mkdir(rawDir, { recursive: true });
  const outPath = `${args.outDir}/${runId}.jsonl`;
  const out = await Deno.open(outPath, { write: true, create: true, truncate: true });
  const enc = new TextEncoder();

  // The multi-race claim is a CODE fact, not a sampling result. Assert it once,
  // for free, and record it beside the run it explains.
  const proof = proveMultiRaceIsIdenticalToOne();
  await Deno.writeTextFile(
    `${args.outDir}/${runId}.claims.json`,
    JSON.stringify(
      {
        multi_race_identical_to_one: proof.identical,
        explanation:
          "No planner reads the races table (only coach_tools.ts:239 get_profile does). " +
          "All periodization derives from the scalar onboarding.goal_date, so a week prompt " +
          "for an athlete with one race and one with five, same goal_date, is byte-identical.",
      },
      null,
      2,
    ),
  );
  say(`multi-race == one-race prompt: ${proof.identical ? "PROVEN identical" : "NOT identical (investigate)"}`);

  let done = 0;
  let spend = 0;
  for (const sc of scenarios) {
    for (const m of usable) {
      for (let repeat = 0; repeat < args.repeats; repeat++) {
        done++;
        const label = `[${done}/${total}] ${sc.name} @ ${m.id} #${repeat}`;
        if (args.delayMs && done > 1) await sleep(args.delayMs);
        const started = Date.now();
        let row: Row;

        try {
          const gen = await generateWithBackoff(m, {
            systemPrompt: sc.systemPrompt,
            prompt: sc.userPrompt,
            apiKey: m.apiKey,
            model: m.resolvedModel,
            jsonMode: true,
            // The budget the REAL function would use for this feature, so a
            // truncation here is a truncation there. (It was: the flat 2,500 cap
            // guillotined a week plan mid-JSON, and the eval called it a model
            // failure until we looked.)
            feature: sc.kind === "week" ? "plan" : "workout",
            // Reproducible where the provider allows it; `reproducible` records
            // whether that actually meant anything for this model.
            deterministic: true,
            seed: 7,
          }, label);
          const ms = Date.now() - started;
          // Score what the MODEL wrote, not what llmGenerate cleaned up. It
          // strips em dashes on the way out, so scoring gen.text would mark the
          // no-dash checker green for every model by construction and hide the
          // ones that ignore the rule. `raw` is that text; production still
          // ships the scrubbed one.
          const output = gen.raw ?? gen.text;
          const scored = sc.kind === "week" ? scoreWeek(output, sc) : scoreWorkout(output, sc);

          const cost = estimateCostUsd(
            m.provider,
            gen.promptTokens,
            gen.completionTokens,
            m.inputPer1M != null && m.outputPer1M != null
              ? { inputPer1M: m.inputPer1M, outputPer1M: m.outputPer1M }
              : undefined,
            m.resolvedModel,
          );
          spend += cost;

          await Deno.writeTextFile(`${rawDir}/${sc.name.replace(/\//g, "_")}__${m.id.replace(/\//g, "_")}__${repeat}.txt`, output);

          let verdict: JudgeVerdict | null = null;
          if (judgeModel && scored.ok) {
            verdict = await judge(judgeModel, sc, output, m.id);
            if (verdict.error) warn(`${label}: judge failed: ${verdict.error}`);
          }

          row = {
            run_id: runId,
            scenario: sc.name,
            kind: sc.kind,
            catches: sc.catches,
            tier: sc.tier,
            meta: sc.meta,
            model_id: m.id,
            provider: m.provider,
            model: m.resolvedModel,
            repeat,
            reproducible: m.reproducible,
            ...scored,
            judge: verdict,
            prompt_tokens: gen.promptTokens,
            completion_tokens: gen.completionTokens,
            cost_usd: cost,
            ms,
          };
          const flag = !scored.ok
            ? "\x1b[31mPARSE-FAIL\x1b[0m"
            : scored.unsafe.length
            ? `\x1b[31mUNSAFE x${scored.unsafe.length}\x1b[0m`
            : scored.violations.length
            ? `\x1b[33m${scored.violations.length} viol\x1b[0m`
            : "\x1b[32mclean\x1b[0m";
          console.log(`  ${label} ${flag} ${ms}ms $${cost.toFixed(4)}`);
        } catch (e) {
          // A provider error is DATA (this model failed this scenario), not a
          // reason to lose the rest of the run.
          const msg = e instanceof Error ? e.message : String(e);
          warn(`${label} generation failed: ${msg}`);
          row = {
            run_id: runId,
            scenario: sc.name,
            kind: sc.kind,
            catches: sc.catches,
            tier: sc.tier,
            meta: sc.meta,
            model_id: m.id,
            provider: m.provider,
            model: m.resolvedModel,
            repeat,
            reproducible: m.reproducible,
            ok: false,
            parse_error: msg,
            violations: [],
            violation_kinds: [],
            unsafe: [],
            tss: null,
            tss_reported: null,
            rpe_target: null,
            duration_minutes: null,
            week: null,
            checks: null,
            check_detail: null,
            judge: null,
            prompt_tokens: 0,
            completion_tokens: 0,
            cost_usd: 0,
            ms: Date.now() - started,
          };
        }
        await out.write(enc.encode(JSON.stringify(row) + "\n"));
      }
    }
  }
  out.close();

  say(`wrote ${outPath}  (${done} rows, ~$${spend.toFixed(4)})`);
  say(`raw model output: ${rawDir}`);
  say(`analyse it:  scripts/dev.sh eval:notebook`);
}

if (import.meta.main) await main();
