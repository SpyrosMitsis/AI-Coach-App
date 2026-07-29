#!/usr/bin/env python3
"""Generate notebooks/llm_eval.ipynb. Kept as a script so the notebook is
regenerable and reviewable as source rather than hand-edited JSON."""
import json, pathlib

def lines(src):
    # nbformat keeps the trailing newline on every line but the last; without it
    # the whole cell concatenates into one unparseable line.
    ls = src.strip("\n").split("\n")
    return [l + "\n" for l in ls[:-1]] + ls[-1:]

def md(src):   return {"cell_type": "markdown", "metadata": {}, "source": lines(src)}
def code(src): return {"cell_type": "code", "execution_count": None, "metadata": {},
                       "outputs": [], "source": lines(src)}

cells = []

cells.append(md(r"""
# LLM evaluation

Is the coach any good? This turns that from a vibe into evidence.

**What this reads.** `eval_runs/*.jsonl`, written by `scripts/dev.sh eval:run`. That runner
drives the *real* prompts (`buildRunPrompt` / `buildStrengthPrompt` / `buildWeekPrompt`)
through each configured model and scores the output with the engine's *own* checkers
(`reviewWorkout`, `plan_checks`) — the same code that gates generation in production. No
rules are re-implemented here, so a green cell means the engine agrees.

**How to read a verdict.** Two independent signals per row:

| | Trust | Meaning |
|---|---|---|
| **Deterministic** | high | reproducible, free, encodes rules we wrote down. Owns pass/fail. |
| **Judge** (advisory) | low | a model's opinion, graded against `COACHING_PRINCIPLES`. Never overrides. |

Where they **disagree**, one of them is wrong — and that is the most informative
thing in the run. Section 7.

> No data yet? `scripts/dev.sh eval:run --tier smoke` (needs a provider key in the
> untracked `scripts/dev.local.sh`).
"""))

cells.append(code(r'''
import json, pathlib, warnings
import pandas as pd, numpy as np
import matplotlib.pyplot as plt
from matplotlib.ticker import PercentFormatter

warnings.filterwarnings("ignore")
pd.set_option("display.max_colwidth", 90)

# --- validated palette (dataviz skill; blue/aqua worst adjacent CVD dE 73.6) ---
# Aqua is 2.74:1 on a light surface, so the relief rule applies: every chart below
# carries direct value labels or a table view. Never color alone.
MODEL_COLORS = ["#2a78d6", "#1baf7a", "#eda100", "#008300", "#4a3aa7", "#e34948"]
GOOD, WARNING, SERIOUS, CRITICAL = "#0ca30c", "#fab219", "#ec835a", "#d03b3b"
INK, INK2, GRID = "#0b0b0b", "#52514e", "#e6e5e2"

plt.rcParams.update({
    "figure.facecolor": "#fcfcfb", "axes.facecolor": "#fcfcfb",
    "axes.edgecolor": GRID, "axes.labelcolor": INK2, "text.color": INK,
    "xtick.color": INK2, "ytick.color": INK2, "axes.grid": True,
    "grid.color": GRID, "grid.linewidth": 0.8, "axes.axisbelow": True,
    "axes.spines.top": False, "axes.spines.right": False, "font.size": 10,
})

def bare(ax, x=False):
    ax.grid(axis="x" if x else "y", alpha=.7); ax.grid(axis="y" if x else "x", visible=False)

def legend_below(ax, n):
    # Always outside the data area. An in-axes legend sits on top of the bars
    # on short charts, which is how you lose a series without noticing.
    ax.legend(frameon=False, ncol=min(n, 4), loc="upper center",
              bbox_to_anchor=(0.5, -0.18), borderaxespad=0)

RUNS = sorted(pathlib.Path("../eval_runs").glob("*.jsonl"))
if not RUNS:
    raise SystemExit(
        "No eval_runs/*.jsonl yet.\n\n"
        "  1. put a provider key in the untracked scripts/dev.local.sh:\n"
        "       export GROQ_API_KEY=...\n"
        "       export DEEPSEEK_API_KEY=...\n"
        "  2. scripts/dev.sh eval:run --tier smoke\n\n"
        "Models are configured in scripts/eval/models.ts."
    )

LATEST = RUNS[-1]
df = pd.DataFrame([json.loads(l) for l in LATEST.read_text().splitlines() if l.strip()])
MODELS = sorted(df.model_id.unique())
CMAP = dict(zip(MODELS, MODEL_COLORS))

print(f"run:       {LATEST.name}")
print(f"rows:      {len(df)}  ({df.scenario.nunique()} scenarios x {len(MODELS)} models x {df.repeat.nunique()} repeats)")
print(f"models:    {', '.join(MODELS)}")
print(f"spend:     ${df.cost_usd.sum():.4f}")
if not df.reproducible.all():
    print(f"NOTE:      {sorted(df[~df.reproducible].model_id.unique())} ignore seed; their rows are not reproducible")
'''))

cells.append(md(r"""
## 1. Does generation work at all?

The floor. A model that cannot reliably emit parseable, schema-valid JSON fails before
any question of coaching quality. `ok=False` means the raw text did not survive
`extractJson` → `validateWorkout`/`validateWeekPlan` — note those are deliberately
*coercing*, so a failure here is a real structural break, not a nitpick.
"""))

cells.append(code(r'''
valid = df.groupby("model_id").ok.mean().reindex(MODELS)
fig, ax = plt.subplots(figsize=(7, 1.4 + .55*len(MODELS)))
b = ax.barh(valid.index, valid.values, color=[CMAP[m] for m in valid.index], height=.55)
ax.bar_label(b, labels=[f"{v:.0%}" for v in valid.values], padding=6, color=INK, fontsize=10)
ax.set_xlim(0, 1.12); ax.xaxis.set_major_formatter(PercentFormatter(1))
ax.set_title("Schema-valid generations", color=INK, fontsize=12, pad=12, loc="left")
ax.axvline(1.0, color=GOOD, lw=1.5, ls="--", alpha=.6)
bare(ax, x=True); plt.tight_layout(); plt.show()

fails = df[~df.ok]
if len(fails):
    print(f"{len(fails)} failed generations:\n")
    display(fails.groupby(["model_id", "scenario"]).parse_error.first().to_frame())
else:
    print("Every generation parsed and validated.")
'''))

cells.append(md(r"""
## 2. Are our own rules followed?

Every bar is a rule **we wrote** and the engine enforces — `reviewWorkout` for sessions,
`plan_checks` for weeks. This is the section that answers "if the rules are followed, if not".

Two tiers of severity:

- **`unsafe`** — contraindicated against a stated injury. The engine *strips* these and
  falls back to a recovery day. Any non-zero count is a hard fail, not a nitpick.
- **`violations`** — everything else. Many are auto-corrected downstream, so they are
  "the model was wrong and the engine saved it", not "the user got hurt". Still a
  quality signal: a model that needs saving often is a model you cannot trust unattended.
"""))

cells.append(code(r'''
kinds = df.explode("violation_kinds").dropna(subset=["violation_kinds"])
if len(kinds):
    piv = (kinds.groupby(["violation_kinds", "model_id"]).size()
                .unstack(fill_value=0).reindex(columns=MODELS, fill_value=0))
    piv = piv.loc[piv.sum(1).sort_values().index]
    fig, ax = plt.subplots(figsize=(9, .6 + .34*len(piv)))
    y = np.arange(len(piv)); h = .8/len(MODELS)
    for i, m in enumerate(MODELS):
        off = (i - (len(MODELS)-1)/2) * h
        bb = ax.barh(y + off, piv[m], height=h*.9, color=CMAP[m], label=m)
        ax.bar_label(bb, padding=3, fontsize=8, color=INK2,
                     labels=[str(v) if v else "" for v in piv[m]])
    ax.set_yticks(y); ax.set_yticklabels(piv.index, fontsize=9)
    ax.set_title("Rule violations by kind  (lower is better)", color=INK, fontsize=12, pad=12, loc="left")
    ax.set_xlabel("occurrences across the run")
    legend_below(ax, len(MODELS))
    bare(ax, x=True); plt.tight_layout(); plt.show()
else:
    print("No violations recorded anywhere. Verify the checkers ran before believing this.")

unsafe = df[df.unsafe.map(len) > 0]
print(f"\nUNSAFE prescriptions: {len(unsafe)} of {len(df)}")
if len(unsafe):
    for _, r in unsafe.iterrows():
        print(f"  [{r.model_id}] {r.scenario}")
        for u in r.unsafe: print(f"      {u}")
'''))

cells.append(code(r'''
# Which scenarios are hardest? `catches` says what each was built to expose, so a
# scenario at the top is a named failure mode reproducing on demand.
sc = (df.assign(nv=df.violations.map(len))
        .groupby(["scenario", "catches"])
        .agg(violations=("nv", "mean"), valid=("ok", "mean"), n=("ok", "size"))
        .sort_values("violations", ascending=False))
display(sc.style.format({"violations": "{:.1f}", "valid": "{:.0%}"})
          .background_gradient(subset=["violations"], cmap="Reds"))
'''))

cells.append(md(r"""
## 3. Too easy or too hard?

Deterministic proxies first, opinion second.

- **TSS vs target** — the load the engine independently computes (`computeTss`, not the
  model's self-report) against the athlete's weekly target. Under the band = too easy.
- **RPE vs readiness** — a wrecked athlete (readiness < 35) getting RPE ≥ 7 is the
  engine's own red line.
- **Judge difficulty** — advisory, but it catches "legal yet pointless" that no
  threshold can.
"""))

cells.append(code(r'''
wk = df[(df.kind == "week") & df.ok].copy()
if len(wk):
    wk["delta"] = wk.apply(lambda r: (r.week["total_tss"] - r.week["target_tss"]) / r.week["target_tss"]
                           if r.week and r.week.get("target_tss") else np.nan, axis=1)
    fig, ax = plt.subplots(figsize=(9, 4))
    for i, m in enumerate(MODELS):
        v = wk[wk.model_id == m].delta.dropna()
        if not len(v): continue
        ax.scatter(v, np.full(len(v), i) + np.random.uniform(-.12, .12, len(v)),
                   color=CMAP[m], s=54, alpha=.85, edgecolor="#fcfcfb", linewidth=1.5, label=m)
    ax.axvspan(-.15, .15, color=GOOD, alpha=.10)
    ax.axvline(0, color=INK2, lw=1)
    ax.set_yticks(range(len(MODELS))); ax.set_yticklabels(MODELS)
    ax.xaxis.set_major_formatter(PercentFormatter(1))
    ax.set_xlabel("weekly load vs target   (left = too easy, right = too hard; band = the 15% tolerance)")
    ax.set_title("Prescribed weekly load vs the athlete's target", color=INK, fontsize=12, pad=12, loc="left")
    legend_below(ax, len(MODELS)); bare(ax, x=True); plt.tight_layout(); plt.show()
    display(wk.groupby("model_id").delta.describe()[["mean", "50%", "min", "max"]]
              .style.format("{:+.1%}"))
else:
    print("No valid week plans in this run.")
'''))

cells.append(code(r'''
# The engine's red line: hard work prescribed to a wrecked athlete.
wrecked = df[(df.meta.map(lambda m: (m or {}).get("readiness", 99)) < 35) & df.ok]
if len(wrecked):
    t = wrecked[["model_id", "scenario", "rpe_target"]].copy()
    t["engine_had_to_cap"] = wrecked.violation_kinds.map(lambda k: "intensity_ceiling" in (k or []))
    print("Wrecked athlete (readiness < 35). The engine caps at RPE 5 — did the model need saving?\n")
    display(t.reset_index(drop=True))
    print(f"\nneeded the engine to intervene: {t.engine_had_to_cap.mean():.0%} of the time")
else:
    print("No wrecked-athlete scenarios in this tier (they are in --tier full).")
'''))

cells.append(code(r'''
# A FAILED verdict (judge.error set, coaching_sense 0 fallback) is silence, not
# an opinion — counting it as "poor by the judge" fabricated blind-spot rows.
j = df[df.judge.notna() & df.ok].copy()
if len(j):
    failed = j.judge.map(lambda x: bool(x.get("error")))
    if failed.any():
        print(f"judge failed on {int(failed.sum())}/{len(j)} rows (excluded below) — "
              "a reasoning-model judge truncates; prefer a plain chat model as EVAL_JUDGE")
    j = j[~failed]
if len(j):
    j["difficulty"] = j.judge.map(lambda x: x.get("difficulty"))
    j["sense"] = j.judge.map(lambda x: x.get("coaching_sense"))
    j["self"] = j.judge.map(lambda x: x.get("self_judged", False))

    order = ["too_easy", "about_right", "too_hard"]
    counts = (j.groupby(["model_id", "difficulty"]).size().unstack(fill_value=0)
               .reindex(columns=order, fill_value=0).reindex(MODELS))
    fig, ax = plt.subplots(figsize=(8, 1.6 + .6*len(MODELS)))
    left = np.zeros(len(counts))
    for col, c in zip(order, [SERIOUS, GOOD, CRITICAL]):
        b = ax.barh(counts.index, counts[col], left=left, color=c, height=.55,
                    label=col.replace("_", " "), edgecolor="#fcfcfb", linewidth=2)  # 2px surface gap
        ax.bar_label(b, labels=[str(v) if v else "" for v in counts[col]],
                     label_type="center", color="#fcfcfb", fontsize=9)
        left += counts[col].values
    ax.set_title("Judge verdict on difficulty  (advisory)", color=INK, fontsize=12, pad=12, loc="left")
    legend_below(ax, 3); bare(ax, x=True)
    plt.tight_layout(); plt.show()

    if j["self"].any():
        sj = sorted(j[j["self"]].model_id.unique())
        print(f"CAUTION: {sj} judged its own output. Discount those rows; add a third key to fix.")
    display(j.groupby("model_id").sense.agg(["mean", "min", "max"]).style.format("{:.2f}"))
else:
    print("No judge verdicts (ran with --no-judge, or every judge call failed).")
'''))

cells.append(md(r"""
## 4. Does periodization actually happen?

Each check maps to a written rule, cited in `plan_checks.ts`:

| Check | Rule | Source |
|---|---|---|
| `hard_spacing` | never back-to-back hard; ≤ 2-3 hard/week | `prompt.ts:58`, `:476` |
| `polarization` | ~80% of endurance time in Z1-Z2 | `prompt.ts:47`, `:475` |
| `tss_target` | weekly load near the target | `prompt.ts:477` |
| `ramp` | volume up ≤ ~10% week on week | `prompt.ts:52` |
| `taper` | a taper cuts volume 40-60% | `prompt.ts:50-51` |
| `set_landmarks` | experience-tiered sets/muscle | `prompt.ts:79-88` |
"""))

cells.append(code(r'''
if len(wk):
    ck = pd.DataFrame(list(wk.checks.values), index=wk.index)
    # Cast BEFORE grouping: `taper` is None on non-taper scenarios, which makes the
    # column object-dtype and numeric_only=True drops it silently — losing the single
    # most important periodization check from the chart. None -> NaN keeps it, and a
    # check that never asserted shows as "n/a" rather than vanishing.
    ck = ck.astype("float64").assign(model_id=wk.model_id.values)
    rates = ck.groupby("model_id").mean().reindex(MODELS)
    fig, ax = plt.subplots(figsize=(9, 1.6 + .5*len(rates.columns)))
    y = np.arange(len(rates.columns)); h = .8/len(MODELS)
    for i, m in enumerate(MODELS):
        if m not in rates.index: continue
        off = (i - (len(MODELS)-1)/2) * h
        vals = rates.loc[m].values
        bb = ax.barh(y + off, vals, height=h*.9, color=CMAP[m], label=m)
        ax.bar_label(bb, labels=[f"{v:.0%}" if pd.notna(v) else "n/a" for v in vals],
                     padding=4, fontsize=8, color=INK2)
    ax.set_yticks(y); ax.set_yticklabels(rates.columns)
    ax.set_xlim(0, 1.15); ax.xaxis.set_major_formatter(PercentFormatter(1))
    ax.axvline(1.0, color=GOOD, lw=1.5, ls="--", alpha=.6)
    ax.set_title("Periodization checks passed  (higher is better)", color=INK, fontsize=12, pad=12, loc="left")
    legend_below(ax, len(MODELS)); bare(ax, x=True)
    plt.tight_layout(); plt.show()
    display(rates.style.format("{:.0%}", na_rep="n/a").background_gradient(cmap="RdYlGn", vmin=0, vmax=1))
'''))

cells.append(code(r'''
# The two rules most likely to be quietly ignored, isolated.
for name, label in [("week/taper-1wk", "TAPER: must cut volume 40-60%"),
                    ("week/periodized/deload-due", "DELOAD: must cut ~40% after 4 build weeks")]:
    sub = wk[wk.scenario == name] if len(wk) else pd.DataFrame()
    print(f"\n{label}")
    if not len(sub):
        print("  (not in this tier)"); continue
    for _, r in sub.iterrows():
        d = (r.check_detail or {})
        cut = (d.get("taper") or {}).get("cut_pct")
        prior, tot = r.week["target_tss"], r.week["total_tss"]
        got = f"cut {cut:.0%}" if cut is not None else f"{tot} TSS vs {prior} target"
        ok = r.checks.get("taper") if cut is not None else r.checks.get("tss_target")
        print(f"  [{r.model_id} #{r.repeat}] {got}  ->  {'PASS' if ok else 'FAIL'}")
'''))

cells.append(md(r"""
## 5. Race, no race, and multiple races

**Finding, established in code rather than sampled:** the `races` table is read in exactly
one place — `_shared/coach_tools.ts:239`, the chat coach's `get_profile`. **No planner
reads it.** Every periodization decision derives from a single scalar,
`onboarding.goal_date`, and `set_goal_race` writes only that scalar.

So server-side:

- **multiple races ≡ one race.** B and C races are invisible to every planner. There is no
  A/B/C periodization; the prompts are byte-identical.
- **a past goal_date ≡ no race.** It silently becomes `"General / maintenance"`.

The runner asserts the first claim directly (`proveMultiRaceIsIdenticalToOne`) rather than
paying for LLM calls to infer it. Below is that assertion plus how phase actually lands.
"""))

cells.append(code(r'''
claims = LATEST.with_suffix("").with_suffix("")  # strip .jsonl
claims = pathlib.Path(str(LATEST).replace(".jsonl", ".claims.json"))
if claims.exists():
    c = json.loads(claims.read_text())
    verdict = "PROVEN" if c["multi_race_identical_to_one"] else "NOT PROVEN — investigate"
    print(f"multi-race prompt == one-race prompt:  {verdict}\n")
    print(c["explanation"])
else:
    print("no .claims.json beside this run")

race = df[df.meta.map(lambda m: "race" in (m or {}))].copy()
if len(race):
    race["race"] = race.meta.map(lambda m: m["race"])
    race["phase"] = race.meta.map(lambda m: m.get("phase"))
    print("\nHow each race state actually reaches the planner:\n")
    display(race.groupby(["race", "phase"]).agg(scenarios=("scenario", "nunique"),
                                                valid=("ok", "mean"),
                                                violations=("violations", lambda s: np.mean([len(x) for x in s])))
                .style.format({"valid": "{:.0%}", "violations": "{:.1f}"}))
'''))

cells.append(md(r"""
## 6. Model comparison

The plug-and-play table. Add a model in `scripts/eval/models.ts`, export its key, re-run
— it appears here automatically.

**Variance across repeats matters as much as the mean.** A model that passes once and
fails twice on the same seeded prompt is not a model you can ship; the spread column is
the honest read.
"""))

cells.append(code(r'''
summary = df.assign(nv=df.violations.map(len), nu=df.unsafe.map(len)).groupby("model_id").agg(
    runs=("ok", "size"),
    valid=("ok", "mean"),
    violations_per_run=("nv", "mean"),
    unsafe=("nu", "sum"),
    tokens_in=("prompt_tokens", "sum"),
    tokens_out=("completion_tokens", "sum"),
    cost=("cost_usd", "sum"),
    median_ms=("ms", "median"),
).reindex(MODELS)
summary["cost_per_run"] = summary.cost / summary.runs
display(summary.style.format({
    "valid": "{:.0%}", "violations_per_run": "{:.2f}", "cost": "${:.4f}",
    "cost_per_run": "${:.4f}", "median_ms": "{:.0f}",
}).background_gradient(subset=["violations_per_run"], cmap="Reds")
  .background_gradient(subset=["valid"], cmap="Greens"))
'''))

cells.append(code(r'''
# Variance: same scenario, same seed, repeated. Spread = unreliability.
if df.repeat.nunique() > 1:
    v = (df.assign(nv=df.violations.map(len)).groupby(["model_id", "scenario"]).nv
           .agg(["mean", "std"]).reset_index())
    fig, ax = plt.subplots(figsize=(9, 4))
    for m in MODELS:
        s = v[v.model_id == m]
        ax.scatter(s["mean"], s["std"].fillna(0), color=CMAP[m], s=60, alpha=.85,
                   edgecolor="#fcfcfb", linewidth=1.5, label=m)
    ax.set_xlabel("mean violations per run"); ax.set_ylabel("std dev across repeats")
    ax.set_title("Consistency  (bottom-left = good and reliable; up = unpredictable)",
                 color=INK, fontsize=12, pad=12, loc="left")
    legend_below(ax, len(MODELS)); plt.tight_layout(); plt.show()

    worst = v.dropna(subset=["std"]).sort_values("std", ascending=False).head(8)
    if len(worst) and worst["std"].max() > 0:
        print("Least reproducible scenario/model pairs (same seed, different answers):")
        display(worst.style.format({"mean": "{:.1f}", "std": "{:.2f}"}))
else:
    print("Single repeat: no variance to measure. Use --repeats 3 for that.")
'''))

cells.append(md(r"""
## 7. Judge vs the checkers

The disagreement matrix. Each cell is a claim about which signal is wrong:

- **Judge says fine, checker says violation** → either the judge is too soft, or the rule
  is pedantic and worth revisiting. Check the examples before assuming the model is at fault.
- **Judge says bad, checker says clean** → the interesting quadrant. A gap in our
  deterministic coverage: something is wrong that no rule of ours can see. That is where
  the next checker comes from.
"""))

cells.append(code(r'''
if len(j):
    j["det_clean"] = j.violations.map(len) == 0
    j["judge_ok"] = j.sense >= 4
    x = pd.crosstab(j.det_clean, j.judge_ok).rename(
        index={True: "checker: clean", False: "checker: violations"},
        columns={True: "judge: good (4-5)", False: "judge: poor (1-3)"})
    display(x)
    agree = (j.det_clean == j.judge_ok).mean()
    print(f"\nagreement: {agree:.0%}")

    gap = j[j.det_clean & ~j.judge_ok]
    print(f"\nBLIND SPOT — clean by our rules, poor by the judge: {len(gap)} rows")
    print("If this is non-trivial, our checkers are missing something real:\n")
    for _, r in gap.head(6).iterrows():
        print(f"  [{r.model_id}] {r.scenario}")
        print(f"      sense {r.sense}/5 | {r.judge.get('reasoning','')[:150]}")
        for b in (r.judge.get("rule_breaches") or [])[:3]: print(f"      - {b}")
else:
    print("No judge data.")
'''))

cells.append(md(r"""
## 8. Verdict: prompt, data, or model?

Read the evidence above against these three, in order. The order matters — the cheap fixes
come first, and "change the model" is the expensive last resort.

**It's the PROMPT if…**
- every model breaks the *same* rule at a similar rate. A rule the strongest model also
  breaks is a rule that isn't really in the prompt, or is contradicted elsewhere in it.
- violations cluster on `week_*` kinds (the rules that were prose-only until now — the
  model was being asked to follow something nothing verified).
- a `contradiction` scenario fires: `plan_checks.KNOWN_CONTRADICTIONS` lists places where
  the prompt and the checker disagree, so **no** model can satisfy both. That's a spec
  bug, and no amount of model swapping fixes it. (The original three — tiered weekly
  sets, the amber recovery cap, invisible swims — are fixed and enforced now; the tagged
  scenarios remain to measure how often models NEED the enforcement.)

**It's the DATA/CONTEXT if…**
- failures concentrate in scenarios where a fact reaches the *checker* but never the
  *prompt* (`strength/int/knee-injury` is built exactly this way — the injury is in the
  review context, and in the real app it arrives via `coach_knowledge`).
- the judge's blind-spot quadrant (section 7) is full: the output is legal but ungrounded.

**It's the MODEL if…**
- one model is materially worse on the *same* prompts, especially on schema validity or
  variance across repeats.
- the gap survives a prompt fix.

Then re-run and compare: `scripts/dev.sh eval:run` writes a new timestamped JSONL, so
two runs are directly diffable.
"""))

cells.append(code(r'''
# Everything each scenario was built to catch, and whether it caught it.
out = (df.assign(nv=df.violations.map(len), nu=df.unsafe.map(len))
         .groupby(["kind", "scenario", "catches"])
         .agg(valid=("ok", "mean"), violations=("nv", "mean"), unsafe=("nu", "sum"))
         .sort_values(["kind", "violations"], ascending=[True, False]))
display(out.style.format({"valid": "{:.0%}", "violations": "{:.1f}"})
          .background_gradient(subset=["violations"], cmap="Reds"))

print("\nKnown spec contradictions — no model can satisfy both sides of these:")
for c in ["weekly-sets: prompt is experience-tiered (8-12/12-18/16-22+), the checker is a flat 22",
          "recovery-cap: context.ts promises amber -> RPE 6, but nothing checks it (ceiling is readiness<35 -> RPE 5)",
          "swim: isHardSession/computeTss are run/ride only, so a hard swim is invisible to spacing"]:
    print(f"  - {c}")
'''))

nb = {
    "cells": cells,
    "metadata": {
        "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
        "language_info": {"name": "python", "version": "3.14.0"},
    },
    "nbformat": 4, "nbformat_minor": 5,
}

out = pathlib.Path("/home/smitsis/projects/workout_maker/notebooks/llm_eval.ipynb")
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(nb, indent=1) + "\n")
print(f"wrote {out}  ({len(cells)} cells)")
