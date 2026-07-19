// ============================================================================
// Rescore an existing run's RAW outputs with the CURRENT checkers — zero LLM
// calls, zero cost. Exists because the checkers moved mid-run (the 2026-07-18
// harmonization: tiered set landmarks, amber recovery caps, swim-as-endurance),
// and re-generating everything to re-grade it would burn money for nothing.
//
//   deno run -A scripts/eval/rescore.ts eval_runs/<ts>.jsonl [more.jsonl ...]
//
// Writes eval_runs/<ts>.rescored.jsonl next to each input.
//
// HONESTY RULE for weeks: the prompt those runs sent asked for the TARGET OF
// ITS DAY. Scenario targets have since changed (availability clamp), so weeks
// are rescored against the target recorded in the original row
// (check_detail.tss_target.target_tss) — the number the model was actually
// given — while everything else (spacing, polarization, sets, taper) uses the
// current logic. A row graded against a target it never saw would be noise.
// ============================================================================

import { buildScenarios, type Scenario } from "./scenarios.ts";
import { scoreWeek, scoreWorkout } from "./run.ts";

const inputs = Deno.args.filter((a) => a.endsWith(".jsonl"));
if (inputs.length === 0) {
  console.error("usage: deno run -A scripts/eval/rescore.ts <run.jsonl> [...]");
  Deno.exit(1);
}

const byName = new Map(buildScenarios("full").map((s) => [s.name, s]));

for (const input of inputs) {
  const rawDir = input.replace(/\.jsonl$/, ".raw");
  const out = input.replace(/\.jsonl$/, ".rescored.jsonl");
  const rows = (await Deno.readTextFile(input)).trim().split("\n").map((l) => JSON.parse(l));
  const lines: string[] = [];
  let rescored = 0, skipped = 0;

  for (const row of rows) {
    const sc = byName.get(row.scenario);
    const rawPath = `${rawDir}/${row.scenario.replace(/\//g, "_")}__${row.model_id.replace(/\//g, "_")}__${row.repeat}.txt`;
    let raw: string | null = null;
    try {
      raw = await Deno.readTextFile(rawPath);
    } catch { /* generation failed rows have no raw file */ }

    if (!sc || raw === null) {
      lines.push(JSON.stringify({ ...row, rescored: false }));
      skipped++;
      continue;
    }

    let scored;
    if (row.kind === "week") {
      const oldTarget = row.check_detail?.tss_target?.target_tss;
      const oldPrior = row.check_detail?.ramp?.prior_tss;
      const weekCtx = {
        ...sc.weekCtx!,
        ...(typeof oldTarget === "number" ? { targetTss: oldTarget } : {}),
        ...(typeof oldPrior === "number" ? { priorWeekTss: oldPrior } : {}),
      };
      scored = scoreWeek(raw, { ...sc, weekCtx } as Scenario);
    } else {
      scored = scoreWorkout(raw, sc);
    }
    lines.push(JSON.stringify({ ...row, ...scored, rescored: true }));
    rescored++;
  }

  await Deno.writeTextFile(out, lines.join("\n") + "\n");
  console.log(`>> ${out}  (${rescored} rescored, ${skipped} kept as-is)`);
}
