# Workout Maker — working notes for Claude

Personal, single-user AI training app. Three components:

| Dir                  | Stack                                                        |
|----------------------|-------------------------------------------------------------|
| `android/`           | Jetpack Compose + Hilt + Room + Supabase SDK (the main app)  |
| `supabase/functions` | Deno edge functions + shared `_shared/` modules (the brain)  |
| `web/`               | Next.js 14 companion (App Router, Tailwind, shadcn-style)    |

The phone pushes data and reads coaching; LLM + Intervals.icu keys are **user-supplied,
server-side only** (that's what keeps hosting free). Strength sync is one-directional:
Android pushes `strength_logs`, the web reads them; web sessions don't flow back to the phone.

## Dev CLI — `scripts/dev.sh`

One front door that bakes in the env gotchas (so you don't retype them). Run with no args
for the full list. Most-used:

```
scripts/dev.sh android:install        # build + install debug APK (JDK17, device from dev.local.sh)
scripts/dev.sh android:test           # unit tests, BOTH flavors (what CI runs)
scripts/dev.sh android:uitest         # Compose UI tests on the phone
scripts/dev.sh qa:device [--live]     # walk the app on the phone and assert
scripts/dev.sh android:log [regex]     # tail THIS app's logcat by pid, optional grep
scripts/dev.sh deno:test               # run the _shared/ test suite
scripts/dev.sh deno:check [fn ...]     # type-check functions (default: all)
scripts/dev.sh fn:call <name> [json]   # drive an edge fn with a seeded-user JWT, print JSON
scripts/dev.sh fn:logs <name>          # tail a deployed function's logs
scripts/dev.sh fn:deploy <name ...>    # deploy to project ref
scripts/dev.sh db:push                 # run pending migrations
```

## Gotchas (these bite every time)

- **Android needs JDK 17.** The system JDK breaks Kotlin. `dev.sh` sets
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`; if you call gradle directly, export it yourself.
- **deno lives at `~/.deno/bin`** — not on the default PATH. `dev.sh` prepends it.
- **"Today" = the client's local date, never UTC.** The user is UTC+; computing today via
  `toISOString()`/UTC is a bug. Clients pass their local date to `daily-summary` et al.
- **UI accents must be theme-aware** — map to `MaterialTheme.colorScheme`
  (Sage→primary, Sand→secondary, BandRed→error, amber→`amberAccent()`). Raw brand constants
  wash out in light mode. Design ethos: calm semantic bands, no emergency reds unless needed.
- **Coach voice**: human, not a stats recital. Don't shove raw numbers into the prompt
  context. The biggest coach-quality lever is the LLM model (default groq is weak; strong
  models need Settings opt-in).
- **Free-text `coach_knowledge` has no date-level authority.** It's prose competing with
  `plan-week`'s concrete per-date day list, and prose loses (a stated "stopping training
  until X" got scheduled over anyway until this was fixed). A fact that should override
  specific dates needs a structured field feeding `_shared/week_planning.ts`'s
  `computeDayList` — `training_paused_until`/`set_training_pause` is the reference example.
- **Project ref & device serial** live in `scripts/dev.local.sh` (untracked) — `dev.sh`
  sources it automatically. Don't hardcode them in tracked files; this repo is public.

## Logging & observability

- **Android** — `util/AppLog.kt`: tag family `"WM"`, debug-gated (stripped in release),
  `AppLog.time(area, label){…}` logs latency. Wired into the LLM/generate/coach hot paths in
  `WorkoutRepository.kt`. Read it live with `scripts/dev.sh android:log` (filters to the app's
  pid; `WM` tag groups all app chatter).
- **LLM quality** — `scripts/dev.sh eval:run` drives the REAL prompts through the models
  in `scripts/eval/models.ts` (edit that file to add one) and scores the output with the
  engine's own checkers (`reviewWorkout`, `_shared/plan_checks.ts`). Offline: no supabase,
  no DB, nothing destructive. Writes `eval_runs/*.jsonl`; `notebooks/llm_eval.ipynb`
  turns it into charts + a prompt-vs-data-vs-model verdict. Keys go in `dev.local.sh`.
  Week-level rules (80/20, deload, ramp, taper, hard spacing) live in `plan_checks.ts`,
  where every threshold cites the `prompt.ts` line it mirrors — keep them in step.
- **LLM cost** — every LLM call writes a `generation_logs` row via
  `_shared/generation_log.ts` (the only place that row is built). `scripts/dev.sh
  llm:cost [days]` rolls it up by feature/model, `--recent` lists individual calls.
  Needs `WM_DB_URL` in `dev.local.sh`. Caps, env vars and the maths: `docs/LLM_COSTS.md`.
- **Edge functions** — `_shared/log.ts`: `logger(fn)` emits one JSON line per event
  (`{t,lvl,fn,msg,…}`) — greppable in `fn:logs`. Wired into `generate-workout`, `coach-chat`,
  and `_shared/llm.ts` (provider/model/latency/usage/errors). Default level is `info`; set
  `WM_LOG=debug` for verbose, `WM_LOG=silent` to mute.

## Device QA — `qa:device`

The bugs this app ships are rendering decisions (a hero under a full-size list eating
taps, the keyboard panning the whole window, an em dash on Home), and they used to be
found only by driving the phone by hand. Three layers now catch them:

- **`qa:device`** walks the INSTALLED app across all five tabs, resolving every tap by
  on-screen text in a fresh `uiautomator` dump (never a coordinate) and asserting the
  keyboard, the restored-thread banner, read-only thresholds, plus a per-screen sweep for
  em dashes, leaked tool JSON and `null`/`undefined`, and a logcat crash sweep. Artefacts
  (PNG + hierarchy + `report.json`) land in `qa_runs/`. `--live` adds one real coach turn.
  `qa:test` runs the driver's own pure tests, no phone needed.
- **`android:uitest`** composes `CoachContent` for real (`CoachUiState.kt` is the seam) and
  pins the tap/layering/gating cases a unit test structurally cannot reach.
- **Text hygiene is enforced at the LLM boundary**, in `llmGenerate`, not per function.
  `LlmResult.raw` keeps the model's own words for the eval to score.

Adding a check: prefer a step in `scripts/qa/scenarios.ts` for anything screen-level, and
a `CoachContentTest` case for anything about state gating.

## Test / deploy

```
scripts/dev.sh deno:test                       # 440+ shared tests
scripts/dev.sh qa:test                          # QA driver's own tests
scripts/dev.sh deno:check generate-workout …   # type-check before deploy
scripts/dev.sh fn:deploy generate-workout coach-chat
scripts/dev.sh db:push                          # run pending migrations
```

`db:push` / `fn:deploy` can be run directly, no per-call confirmation needed — type-check
(`deno:check`) and run the shared test suite (`deno:test`) first, and deploy every function
that imports a changed `_shared/` module, not just the one directly edited.
