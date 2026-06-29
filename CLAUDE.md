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
scripts/dev.sh android:install        # build + install debug APK (JDK17, device R3CT40D46AD)
scripts/dev.sh android:log [regex]     # tail THIS app's logcat by pid, optional grep
scripts/dev.sh deno:test               # run the _shared/ test suite
scripts/dev.sh deno:check [fn ...]     # type-check functions (default: all)
scripts/dev.sh fn:call <name> [json]   # drive an edge fn with a seeded-user JWT, print JSON
scripts/dev.sh fn:logs <name>          # tail a deployed function's logs
scripts/dev.sh fn:deploy <name ...>    # deploy to project ref
scripts/dev.sh db:push                 # run pending migrations (asks first)
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
- **Project ref**: `qkcyavbuuhljjplufrlu`. **Device**: `R3CT40D46AD`.

## Logging & observability

- **Android** — `util/AppLog.kt`: tag family `"WM"`, debug-gated (stripped in release),
  `AppLog.time(area, label){…}` logs latency. Wired into the LLM/generate/coach hot paths in
  `WorkoutRepository.kt`. Read it live with `scripts/dev.sh android:log` (filters to the app's
  pid; `WM` tag groups all app chatter).
- **Edge functions** — `_shared/log.ts`: `logger(fn)` emits one JSON line per event
  (`{t,lvl,fn,msg,…}`) — greppable in `fn:logs`. Wired into `generate-workout`, `coach-chat`,
  and `_shared/llm.ts` (provider/model/latency/usage/errors). Default level is `info`; set
  `WM_LOG=debug` for verbose, `WM_LOG=silent` to mute.

## Test / deploy

```
scripts/dev.sh deno:test                       # 93+ shared tests
scripts/dev.sh deno:check generate-workout …   # type-check before deploy
scripts/dev.sh fn:deploy generate-workout coach-chat
scripts/dev.sh db:push                          # migrations (confirm first)
```

There are pending migrations and function deploys tracked in my memory — confirm with the
user before running `db:push` / `fn:deploy`.
