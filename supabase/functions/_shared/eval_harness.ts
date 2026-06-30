// ============================================================================
// Offline eval harness — turn "is this LLM good enough for the workout maker?"
// into data. It scores a generated workout with the SAME deterministic tools the
// engine already trusts (schema validity, review violations, independent TSS) and
// attaches the cost estimate, so providers can be compared on quality AND price.
//
// Two layers:
//   • scoreRaw() — pure, no network: parse → validate → review → tss. Unit-tested.
//   • runLive()  — calls every provider that has a key in the environment with a
//                  fixed, deterministic prompt set and prints a comparison table.
//                  Run it directly:  deno run -A eval_harness.ts
// ============================================================================

import { extractJson, estimateCostUsd, llmGenerate, PROVIDERS } from "./llm.ts";
import { SYSTEM_PROMPT, validateWorkout, buildRunPrompt, buildStrengthPrompt } from "./prompt.ts";
import { reviewWorkout, computeTss, type ReviewContext } from "./workout_review.ts";
import type { LlmProvider } from "./types.ts";

export interface Fixture {
  name: string;
  systemPrompt: string;
  userPrompt: string;
  ctx: ReviewContext;
}

export interface EvalScore {
  fixture: string;
  valid: boolean;
  violations: number;
  unsafe: number;
  tss: number;
  parseError?: string;
}

// Pure scorer: take raw model text for a fixture and grade it with the engine's
// own deterministic guards. No network — this is what the unit tests exercise.
export function scoreRaw(raw: string, fx: Fixture): EvalScore {
  try {
    const v = validateWorkout(extractJson(raw));
    if (!v.ok || !v.workout) {
      return { fixture: fx.name, valid: false, violations: 0, unsafe: 0, tss: 0, parseError: v.error };
    }
    const review = reviewWorkout(v.workout, fx.ctx);
    return {
      fixture: fx.name,
      valid: true,
      violations: review.violations.length,
      unsafe: review.unsafe.length,
      tss: computeTss(review.corrected),
    };
  } catch (e) {
    return {
      fixture: fx.name, valid: false, violations: 0, unsafe: 0, tss: 0,
      parseError: e instanceof Error ? e.message : String(e),
    };
  }
}

// A small, representative fixture set. Deterministic so runs are comparable.
export function buildFixtures(): Fixture[] {
  const baseStrengthCtx: ReviewContext = {
    mainLifts: [
      { exercise: "Barbell Bench Press", estimated1rm: 110, lastWeight: 90, lastReps: 5, lastSets: 3 },
      { exercise: "Barbell Squat", estimated1rm: 150, lastWeight: 120, lastReps: 5, lastSets: 3 },
    ],
    weeklySetsByMuscle: { Chest: 6, Back: 4, Quads: 6 },
    muscleGroupsLast48h: [],
    tsb: 2,
    daysSinceLastHard: 3,
    experience: "Intermediate",
    equipment: "Full gym",
  };
  const runCtx: ReviewContext = {
    mainLifts: [], weeklySetsByMuscle: {}, muscleGroupsLast48h: [],
    tsb: 5, daysSinceLastHard: 3, experience: "Intermediate",
  };
  const hrZones = [
    { zone: "Z1", min: 95, max: 130 }, { zone: "Z2", min: 131, max: 145 },
    { zone: "Z3", min: 146, max: 160 }, { zone: "Z4", min: 161, max: 172 },
    { zone: "Z5", min: 173, max: 190 },
  ];
  return [
    {
      name: "strength/intermediate/full-gym",
      systemPrompt: SYSTEM_PROMPT,
      userPrompt: buildStrengthPrompt({
        muscleGroupsLast48h: [], weeklySetsByMuscle: baseStrengthCtx.weeklySetsByMuscle,
        equipment: "Full gym", experience: "Intermediate", goal: "Hybrid athlete",
        soreness: 2, phase: "Base (aerobic + general strength)", mainLifts: baseStrengthCtx.mainLifts,
        durationNote: "around 60 min", splitStyle: "Upper / lower",
      }),
      ctx: baseStrengthCtx,
    },
    {
      name: "run/tempo/fresh",
      systemPrompt: SYSTEM_PROMPT,
      userPrompt: buildRunPrompt({
        hrZones, tsb: 5, ctl: 55, atl: 50, acwr: 1.1, phase: "Build (threshold + VO2)",
        wellness3d: { energy: 4, soreness: 2, sleep: 4 }, weeklyKm: 40, goal: "10K pace",
        targetPace: "4:30/km", daysSinceLastRun: 1, daysSinceLastHard: 3,
        durationNote: "around 50 min", experience: "Intermediate", sport: "run",
      }),
      ctx: runCtx,
    },
  ];
}

const ENV_KEY: Partial<Record<LlmProvider, string>> = {
  anthropic: "ANTHROPIC_API_KEY",
  openai: "OPENAI_API_KEY",
  deepseek: "DEEPSEEK_API_KEY",
  groq: "GROQ_API_KEY",
  gemini: "GEMINI_API_KEY",
};

export interface ProviderResult {
  provider: LlmProvider;
  runs: number;
  validPct: number;
  avgViolations: number;
  anyUnsafe: number;
  avgCostUsd: number;
}

// Live run: every provider with a key in the env generates each fixture
// (deterministic), is scored, and aggregated. Network — not run by the tests.
export async function runLive(fixtures = buildFixtures()): Promise<ProviderResult[]> {
  const out: ProviderResult[] = [];
  for (const provider of Object.keys(ENV_KEY) as LlmProvider[]) {
    const apiKey = Deno.env.get(ENV_KEY[provider]!);
    if (!apiKey) continue;
    let valid = 0, violations = 0, unsafe = 0, cost = 0;
    for (const fx of fixtures) {
      try {
        const r = await llmGenerate(provider, {
          prompt: fx.userPrompt, systemPrompt: fx.systemPrompt, apiKey,
          model: PROVIDERS[provider].model, deterministic: true, seed: 7,
        });
        const score = scoreRaw(r.text, fx);
        if (score.valid) valid++;
        violations += score.violations;
        unsafe += score.unsafe;
        cost += estimateCostUsd(provider, r.promptTokens, r.completionTokens);
      } catch {
        // a failed call counts as an invalid run
      }
    }
    out.push({
      provider, runs: fixtures.length,
      validPct: Math.round((valid / fixtures.length) * 100),
      avgViolations: +(violations / fixtures.length).toFixed(2),
      anyUnsafe: unsafe,
      avgCostUsd: +(cost / fixtures.length).toFixed(5),
    });
  }
  return out;
}

if (import.meta.main) {
  const rows = await runLive();
  if (!rows.length) {
    console.log("No provider keys in env. Set e.g. GROQ_API_KEY / ANTHROPIC_API_KEY and re-run.");
  } else {
    console.table(rows);
  }
}
