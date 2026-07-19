# LLM costs: how they work, where they're capped, how to see them

Every AI feature in this app costs money to run. Who pays depends on the key:

| Key | Whose money | Bounded by |
|---|---|---|
| **BYO** (user's own key in Settings) | the user's | their own provider account |
| **Hosted** (Pro, `HOSTED_LLM_KEY`) | **yours, the operator's** | `_shared/quota.ts` + the caps below |

Only the hosted path can put you in the red, so that's what the guardrails are
built around. `generation_logs.hosted` marks those rows, and everything in this
document keys off that split.

---

## The pricing model

Providers bill **per token**, at different rates for input and output, quoted per
1M tokens. Output is typically **5 to 10x more expensive than input**, which is
why the output cap (not the input cap) is the lever that matters.

Rates live in `PROVIDERS` in `_shared/llm.ts`:

| Provider | Default model | Input $/1M | Output $/1M |
|---|---|---|---|
| `deepseek` | `deepseek-chat` | 0.28 | 0.42 |
| `openai` | `gpt-5-mini` | 0.25 | 2.00 |
| `gemini` | `gemini-2.5-flash` | 0.30 | 2.50 |
| `groq` | `llama-3.3-70b-versatile` | 0.59 | 0.79 |
| `anthropic` | `claude-opus-4-8` | 5.00 | 25.00 |
| `openrouter` | `openrouter/auto` | (varies) | (varies) |
| `custom` | (user-supplied) | (unknown) | (unknown) |

`openrouter` and `custom` have no fixed price, so they log ~$0 unless the user
enters their own rates (`llm_custom_input_per_1m` / `llm_custom_output_per_1m`,
applied by `customPriceFromProfile`).

## How cost is calculated

`estimateCostUsd()` in `_shared/llm.ts`:

```
cost = (promptTokens / 1e6) * inputPer1M + (completionTokens / 1e6) * outputPer1M
```

Token counts come from the provider's own `usage` block. When a provider omits
it, `estTokens()` estimates at ~4 chars/token, so those rows are approximate.

Rate precedence, highest first:

1. **User price override** — `custom`/`openrouter` only, from their profile.
2. **Model-specific price** — `MODEL_PRICES`, so a user who opts into Sonnet
   instead of the default Opus isn't billed at Opus rates.
3. **Provider default** — the table above.

It is an **estimate**: it does not know about prompt caching, batch discounts, or
provider-side rounding. Treat it as a good signal, not an invoice.

> **Note:** `llmStream()` captures no usage at all, so a streamed turn logs no
> tokens. `coach-chat` does not currently use it (see the follow-ups in the
> sprint plan), but if it ever does, streamed spend goes dark.

---

## Where the limits are

### 1. Output tokens per call — `OUTPUT_BUDGETS`

The innermost limit. Resolved by `maxTokensOf()` in `_shared/llm.ts`; **no
adapter is allowed to write `max_tokens` directly.**

Every feature gets a budget sized to its job:

| feature | budget | why |
|---|---|---|
| `brief` | 500 | one or two sentences |
| `week_review` | 700 | a short recap |
| `memory` | 900 | the agent docs cap at ~200 words anyway |
| `analyze` | 1500 | 3-5 sentences of feedback |
| `chat` | 2500 | **the cost driver**: up to 12 per turn |
| `workout` | 2500 | one workout object |
| `finalize` | 3000 | a workout or plan template |
| `plan` | 6000 | seven full workout objects |

**Why not one number.** It was 2,500 for everything, and that number was
simultaneously generous for a brief and fatal for a week plan. Measured on three
consecutive real runs: **2144 / 2216 / 2500** output tokens — 86%, 89%, **100%**
of the cap — and the third truncated mid-JSON, inside a string, with all seven
days still open. `plan-week` then retried into the same ceiling, so the failure
burnt **2,500 + 2,500 for zero output**. The cap was costing what it meant to save.

`max_tokens` is a **ceiling, not a target**: a bigger budget costs nothing on the
calls that don't need it. What a budget bounds is the *worst* case, which is why
`chat` stays tight (12 of them per turn) while `plan` (1-2 calls) can afford room.

| Env var | Default | Applies to |
|---|---|---|
| `WM_MAX_OUTPUT_TOKENS` | unset → the table | BYO calls (the user's own key) |
| `WM_HOSTED_MAX_OUTPUT_TOKENS` | unset → the table | hosted calls (your key) |

These are an operator's **emergency brake**, not the normal path: set either and
it clamps the table down. The BYO/hosted split is deliberate — pulling the BYO
limit down saves the *user's* money, the hosted one saves *yours*. Nothing may
ever exceed the absolute bound of **8000**, whatever a caller or the env asks for.
`llm_test.ts` pins all of this, including a regression test tied to the 2,482
tokens that truncated, so it can't rot silently.

If you change a budget, redo the per-call maths below and revisit `HOSTED_*_USD`.

### 2. LLM calls per coach turn — `MAX_LLM_CALLS_PER_TURN`

In `coach-chat/index.ts` (currently **12**). One turn can fan out across the
native tool loop (`NATIVE_MAX_STEPS`, 6), the JSON-protocol fallback
(`PROTOCOL_MAX_STEPS`, 6), and one anti-stall retry that replays both. The budget
is shared across all three legs, so the worst case is 12 rather than ~24. Each
turn logs its real count: `{"msg":"turn_done","llmCalls":N,"budget":12}`.

### 3. Model class — `HOSTED_LLM_MODEL`

Keep it flash/mini-class (`gemini-2.5-flash`, `gpt-5-mini`). Never opus-class:
at $25/1M output, one 12-call turn costs ~$2 while the caps below assume cents.
`entitlement.ts` warn-logs a model id that looks expensive.

### 4. Spend caps — the `HOSTED_*` env vars

Enforced by `assertHostedQuota()` (`_shared/quota.ts`) at the single choke point
`llm_keys.ts`, backed by the `hosted_spend()` RPC. It **fails closed**: if the
RPC errors, hosted AI is refused rather than served for free.

| Env var | Default | Meaning |
|---|---|---|
| `HOSTED_USER_HOURLY_CALLS` | 30 | per-user rate limit |
| `HOSTED_USER_DAILY_USD` | 0.25 | per-user daily spend |
| `HOSTED_USER_MONTHLY_USD` | 2 | **the hard per-user loss bound** |
| `HOSTED_GLOBAL_MONTHLY_USD` | 25 | total monthly exposure |

Suggested global: `10 + 2.5 x paying subscribers`, revisited every ~10
subscribers. Caps are checked *before* a turn, so a turn already in flight can
overshoot by roughly one turn's cost; that's priced in.

### 5. Kill switch — `HOSTED_AI_DISABLED=1`

`supabase secrets set HOSTED_AI_DISABLED=1`. Takes effect on the next
invocation, no redeploy. Pro users fall back to their own keys and the app hides
the Pro AI surface automatically via `server.hosted_ai=false`.

### Worked example

One hosted `chat` call on `gemini-2.5-flash`, ~4k input + 2.5k output (its budget):

```
(4000 / 1e6 * 0.30) + (2500 / 1e6 * 2.50) = 0.0012 + 0.00625 ≈ $0.0075
```

A worst-case 12-call agentic turn: **~$0.09** — unchanged by the move to
per-feature budgets, because `chat` kept its 2,500.

One hosted `plan` call, ~4k input + up to 6k output:

```
(4000 / 1e6 * 0.30) + (6000 / 1e6 * 2.50) = 0.0012 + 0.015 ≈ $0.016
```

plan-week makes 1-2 of those, so **~$0.03** worst case — and it replaces a
~$0.0125 path that *failed*. Typical plans emit ~2.2k, so the real number is
closer to $0.007.

At `HOSTED_USER_DAILY_USD=0.25` that's ~3 agentic turns a day, and the $2 monthly
bound is the real backstop.

> **Watch `plan-block`**: it fans out one `plan-week` per week, up to 16, and
> concurrently. At the `plan` budget a 16-week block is ~$0.5 hosted and will trip
> the daily cap partway through. The quota gate stops it; the user sees a partial
> block.

---

## Seeing where the money goes

Every LLM call writes one `generation_logs` row through
`_shared/generation_log.ts` — the single place the row is built. Columns:
`feature`, `hosted`, `provider`, `model`, `prompt_tokens`, `completion_tokens`,
`estimated_cost_usd`, `tools_used`, `parsed_ok`, `error`.

This table is not just diagnostics: **`hosted_spend()` SUMs it and the quota gate
reads that sum.** A call that doesn't log here is a call that is both invisible
in the report and uncounted by the cap that's meant to stop your bill.

`feature` values: `chat`, `finalize`, `workout`, `plan`, `brief`, `week_review`,
`analyze`, `memory`.

### The report

```
scripts/dev.sh llm:cost [days]        # default 7, by feature + model, hosted vs byo
scripts/dev.sh llm:cost --recent [n]  # last n individual calls with per-call cost
```

Needs `WM_DB_URL` (your Postgres connection string: Dashboard > Project Settings
> Database > Connection string > URI) in **`scripts/dev.local.sh`**, which is
untracked — this repo is public, so it must not go in a tracked file. Read-only:
no migration, no deploy.

### Live logs

`_shared/llm.ts` emits one JSON line per call:

```
scripts/dev.sh fn:logs coach-chat
WM_LOG=debug scripts/dev.sh fn:logs generate-workout   # verbose
```

---

## Keeping costs down without hurting UX

Roughly in order of leverage:

1. **Model class is everything.** flash/mini vs opus is a ~10x swing and dwarfs
   every other lever here. Keep `HOSTED_LLM_MODEL` flash/mini-class and let
   users opt into a strong model with *their own* key.
2. **Trim context, not answers.** Input is cheap per token but the coach sends a
   lot of it, every turn, and the agentic loop resends the thread on every step.
   `compressThread`/`summarizeDropped` already bound this; keep new prompt blocks
   (like `profileFactsBlock`) terse. Cutting the output cap, by contrast, is felt
   immediately as truncated coaching.
3. **Fewer calls beats shorter calls.** One wasted tool round trip costs a whole
   call's input again. The tool rules that push the coach to "read what you need,
   then act" are a cost feature as much as a quality one.
4. **Cap the tail, not the median.** `MAX_LLM_CALLS_PER_TURN` only bites on
   pathological turns; the median turn is 2 to 4 calls and never notices.
5. **Let background work be cheap.** `memory` calls (`agent_memory.ts`) are
   fire-and-forget and never user-visible, so they're the right place to pass a
   lower `maxTokens` if spend needs trimming. They're also gated
   (`shouldUpdateKnowledge`, the 72h soul cooldown) so they don't run every turn.
6. **Watch `feature` in the report, not the total.** If `memory` or `analyze`
   rivals `chat`, that's background work you can gate harder without any user
   ever noticing.
