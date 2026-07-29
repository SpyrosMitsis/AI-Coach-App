# LLM eval findings — 2026-07-18 full run

The first complete model comparison: full tier (23 scenarios) x 3 repeats x 4
models, scored by the engine's own checkers (`scripts/dev.sh eval:run`,
analysis in `notebooks/llm_eval.ipynb`, rescoring via `scripts/eval/rescore.ts`).
Runs: `eval_runs/2026-07-18T20-52-36-234Z` (groq + MiMo x2) and
`eval_runs/2026-07-18T20-56-19-641Z` (deepseek), combined and rescored with the
harmonized checkers into `2026-07-18-combined.rescored.jsonl`. Total spend ~$0.49.

## The model verdict

| model | parsed | violations/ok row | week TSS vs target | cost, full-run equiv |
|---|---|---|---|---|
| **deepseek/chat** | **72/72** | 0.75 | avg -16%, 14/36 in ±15% | **$0.10** |
| mimo/v2.5 | 53/72 | 0.87 | avg -19%, 6/29 in ±15% | $0.07 (nominal) |
| mimo/v2.5-pro | 34/72 | 0.97 | avg -12%, 6/18 in ±15% | $0.29 |
| groq/llama-3.3-70b | 16/72 | 0.50 | n too small | $0.03 (partial) |

- **deepseek/chat stays the hosted default.** Only model with zero parse
  failures, best target adherence, cheapest per *successful* call. Keeping
  `HOSTED_LLM_PROVIDER=deepseek` is the data-backed choice, not just the cheap one.
- **MiMo is disqualified on reliability, not quality.** Both variants are
  reasoning models whose hidden thinking counts against `max_tokens`: every
  failure sits at *exactly* the budget (2500 workout / 6000 plan) with no JSON
  emitted. v2.5 fails 26% of calls, v2.5-pro 53% and needs 1-3 min per week
  plan. When v2.5 does answer, quality is deepseek-like — so a follow-up worth
  having: send OpenRouter's `reasoning: {effort: "low"}` in the adapter and
  re-measure. Until then the per-token price is a mirage: retries erase it.
- **groq's free tier cannot run this eval** (100k tokens/day; a full x3 needs
  ~310k). Its 16 completed rows look fine but prove nothing. Don't burn a day's
  quota on it again; use `--models` to exclude it or run `--tier smoke`.
- **A judge must be a non-reasoning model.** mimo/v2.5-pro as judge failed to
  emit JSON on ~half its verdicts even at 2500 tokens. `EVAL_JUDGE` is
  deepseek/chat; its own rows are then self-judged and flagged, which is the
  lesser evil.
- **Seeded ≠ identical.** deepseek's median spread across seeded repeats was
  51 TSS per week scenario. Run `--repeats 3` always; single-shot comparisons
  are noise.

## What the eval fixed in US (the instrument findings)

Each of these was a place the prompt and the code disagreed — no model could
have satisfied both. All are now enforced deterministically and pinned by tests:

1. **Weekly set landmark tiered** 12/18/22 by experience (was flat 22).
2. **Recovery caps enforced**: red (<34) → RPE ≤4, amber (<67) → RPE ≤6 + no
   threshold work (amber was promised in the prompt and checked by nothing;
   deepseek broke it in 2 of 3 repeats — the cap now catches that).
3. **Swim is endurance**: counts in hard-day spacing, priced by zones.
4. **Target vs availability reconciled**: `availabilityTssCeiling` (minutes x
   0.88, the engine's own 80/20 pricing) clamps the weekly target and the
   periodization ramp. Sam's 405-min week holds ~356 TSS; the prompt used to
   ask 380 anyway, and every "under-target" verdict partly measured that.
5. Earlier in the same effort: per-feature `OUTPUT_BUDGETS` (week plans died at
   a flat 2500), phase-scaled taper/deload targets, `zoneOf` range pricing.

## Remaining quality levers (prompt/data, in leverage order)

1. **Residual under-prescription is real but shrunken**: against honest targets,
   base/steady/maintenance weeks still come in -20-30% (the 240-280 TSS cluster
   vs 356). **A/B'd 2026-07-19 and REJECTED**: adding "aim to HIT the target, a
   week far below is undertraining" + "the taper target already includes the
   cut, land on it" moved within-band from 12/36 to 13/36 (noise) and made
   tapers WORSE (+42/+51% vs +19-24%). deepseek's volume behavior is not
   wording-sensitive; the two lines were reverted (runs
   2026-07-19T08-03-08 control / 08-32 treatment). Remaining levers are the
   engine, not adjectives: the checker already computes real weekly TSS, so a
   plan-week repair pass ("week landed X, target Y, add/trim") is the credible
   next step if the gap matters in practice.
2. **Taper cuts too shallow**: asked for ~190 (a 50% cut), deepseek delivers
   ~265 (a 30% cut) in 2 of 3. Bounding it in prose failed (see above); same
   repair-pass remedy applies.
3. **Drop `tss_estimate` from the schema** (or stop asking for it): misreported
   >25% on 12/72 rows; the engine overwrites endurance TSS anyway. Removes a
   known-wrong number and a little output cost.
4. **Token budgets are right-sized**: measured average completion 1.7k, week
   plans peak ~3.8k against the 6000 budget. No cost cut available there
   without re-breaking plans; model choice remains the only big cost lever.

## What the judge sees that our checkers don't

With failed verdicts excluded (74/175 — a reasoning-model judge truncates; keep
`EVAL_JUDGE` a plain chat model), the "clean by our rules, poor by the judge"
quadrant holds 4 rows, and they name real checker gaps:

1. **No per-session quality cap**: a single run stacking 20 min threshold + 3x4
   min VO2 (32 min of Z3+) passes every check. The prompt's session archetypes
   imply one quality block per day; nothing verifies it.
2. **Long run ≤30-35% of weekly volume** is stated in COACHING_PRINCIPLES and
   checked by nothing. Cheap deterministic check, prompt line already cites a number.
3. ~~The judge twice called back-to-back-hard on weeks our spacing check passed~~
   — audited: those weeks put a Z2 long run the day after a quality session
   (quality-Saturday + long-Sunday, a standard structure). The prompt defines
   hard as Z3+/RPE≥7, so the checker is right and the judge over-strict. Not a gap.

Candidate follow-up checks (#1, #2), in `plan_checks.ts` style (threshold cites
the prompt line).

## How to reproduce

```
scripts/dev.sh eval:run --tier full --repeats 3 --models deepseek/chat
deno run -A scripts/eval/rescore.ts eval_runs/<ts>.jsonl   # after checker changes
scripts/dev.sh eval:notebook
```
