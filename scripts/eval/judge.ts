// ============================================================================
// The LLM judge — ADVISORY ONLY.
//
// The deterministic checkers own the verdict: they're reproducible, free, and
// they encode rules we actually wrote down. But they cannot tell you a session
// is technically legal and still coaching nonsense ("4x5 back squat" three days
// running is legal; it is also not a program). That judgement is what this adds.
//
// Three rules keep it honest:
//   1. It grades against COACHING_PRINCIPLES — the app's OWN rules, the same
//      prose the generator was given. A judge left to its private opinion is
//      just a second model's vibes, and its score would be noise.
//   2. It never sees the deterministic result. Told "this broke 3 rules" it
//      would anchor and agree; the disagreement between judge and checker is the
//      most informative thing in the run, so it must be independently arrived at.
//   3. Self-judging is flagged, not hidden. With one key configured the judge
//      may be the same model under test.
// ============================================================================

import { extractJson, llmGenerate } from "../../supabase/functions/_shared/llm.ts";
import { COACHING_PRINCIPLES } from "../../supabase/functions/_shared/prompt.ts";
import type { ResolvedModel } from "./models.ts";
import type { Scenario } from "./scenarios.ts";

export interface JudgeVerdict {
  coaching_sense: number; // 1-5
  difficulty: "too_easy" | "about_right" | "too_hard";
  periodization_ok: boolean;
  rule_breaches: string[];
  reasoning: string;
  self_judged: boolean;
  error?: string;
}

const JUDGE_SYSTEM =
  `You are a head coach reviewing another coach's prescription. Judge it ONLY against the
training principles below, which are the standard this program is held to. Do not substitute
your own preferences: if the principles permit something you personally dislike, that is not
a breach.

${COACHING_PRINCIPLES}

Be decisive and be hard to please. "about_right" means an experienced athlete would look at
this and start training. A session that is technically legal but would not survive contact
with a real coach is NOT about_right.

Output ONLY a JSON object, no prose, no code fences:
{
  "coaching_sense": 1-5,
  "difficulty": "too_easy" | "about_right" | "too_hard",
  "periodization_ok": true | false,
  "rule_breaches": ["short phrase naming the principle broken", ...],
  "reasoning": "one or two sentences, concrete"
}

coaching_sense: 5 = a good coach would prescribe exactly this. 3 = defensible but
mediocre. 1 = actively bad for this athlete.
periodization_ok: does this fit the stated training phase and the athlete's fatigue?
rule_breaches: [] when nothing is broken. Name the principle, not the symptom.`;

function judgePrompt(scenario: Scenario, generated: string): string {
  const facts = Object.entries(scenario.meta)
    .filter(([, v]) => v !== null && v !== undefined)
    .map(([k, v]) => `- ${k}: ${v}`)
    .join("\n");

  return `THE ATHLETE AND THE SITUATION
${facts}

WHAT THE COACH WAS ASKED FOR
${scenario.userPrompt}

WHAT THE COACH PRESCRIBED
${generated}

Judge the prescription.`;
}

/**
 * Grade one generated artifact. Never throws: a judge failure must not lose the
 * deterministic result for that row, which is the part we actually trust.
 */
export async function judge(
  judgeModel: ResolvedModel,
  scenario: Scenario,
  generated: string,
  candidateId: string,
): Promise<JudgeVerdict> {
  const selfJudged = judgeModel.id === candidateId;
  const fallback = (error: string): JudgeVerdict => ({
    coaching_sense: 0,
    difficulty: "about_right",
    periodization_ok: false,
    rule_breaches: [],
    reasoning: "",
    self_judged: selfJudged,
    error,
  });

  try {
    const out = await llmGenerate(judgeModel.provider, {
      systemPrompt: JUDGE_SYSTEM,
      prompt: judgePrompt(scenario, generated),
      apiKey: judgeModel.apiKey,
      model: judgeModel.resolvedModel,
      jsonMode: true,
      // A verdict is a short JSON object, but reasoning models (mimo-v2.5-pro)
      // spend thinking tokens INSIDE max_tokens before emitting it: at 700 the
      // JSON was truncated away entirely and every verdict failed. 2500 leaves
      // thinking room and still costs well under what it grades.
      maxTokens: 2500,
      deterministic: true,
      seed: 7,
    });
    const v = extractJson<Partial<JudgeVerdict>>(out.text);
    const sense = Number(v.coaching_sense);
    const diff = v.difficulty;
    return {
      coaching_sense: Number.isFinite(sense) ? Math.min(5, Math.max(1, Math.round(sense))) : 0,
      difficulty: diff === "too_easy" || diff === "too_hard" ? diff : "about_right",
      periodization_ok: v.periodization_ok === true,
      rule_breaches: Array.isArray(v.rule_breaches) ? v.rule_breaches.map(String).slice(0, 8) : [],
      reasoning: String(v.reasoning ?? "").slice(0, 400),
      self_judged: selfJudged,
    };
  } catch (e) {
    return fallback(e instanceof Error ? e.message : String(e));
  }
}
