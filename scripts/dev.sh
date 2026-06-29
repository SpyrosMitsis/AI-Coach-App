#!/usr/bin/env bash
# ============================================================================
# dev.sh — one front door for building, running, debugging & observing this app.
#
# Bakes in the environment gotchas so commands become one word:
#   - Android/Kotlin needs JDK 17 (system JDK breaks Kotlin)        -> JAVA_HOME
#   - deno lives at ~/.deno/bin                                     -> PATH
#   - function deploys/logs need the project ref                    -> PROJECT_REF
#   - one phone is wired up                                         -> ANDROID_SERIAL
#
# Usage:  scripts/dev.sh <command> [args]      (run with no args for help)
# ============================================================================
set -euo pipefail

# --- repo-rooted, runnable from anywhere ------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- baked-in environment (override via env if your setup differs) ----------
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
DENO_BIN="${DENO_BIN:-$HOME/.deno/bin}"
PROJECT_REF="${PROJECT_REF:-qkcyavbuuhljjplufrlu}"
APP_ID="${APP_ID:-com.workoutmaker.app}"
ANDROID_SERIAL="${ANDROID_SERIAL:-R3CT40D46AD}"
LOCAL_API="${LOCAL_API:-http://localhost:54321}"
# Seeded dev athlete (supabase/seed.sql) — used by fn:call for a real user JWT.
DEV_EMAIL="${DEV_EMAIL:-athlete@example.com}"
DEV_PASSWORD="${DEV_PASSWORD:-password123}"

export JAVA_HOME ANDROID_SERIAL
export PATH="$DENO_BIN:$PATH"

# --- pretty output ----------------------------------------------------------
c_blue=$'\033[34m'; c_green=$'\033[32m'; c_yellow=$'\033[33m'; c_red=$'\033[31m'; c_dim=$'\033[2m'; c_off=$'\033[0m'
say()  { printf '%s>>%s %s\n' "$c_blue" "$c_off" "$*"; }
warn() { printf '%s!!%s %s\n' "$c_yellow" "$c_off" "$*" >&2; }
die()  { printf '%sxx%s %s\n' "$c_red" "$c_off" "$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "missing '$1' on PATH"; }

confirm() { # confirm "question"
  printf '%s?? %s [y/N] %s' "$c_yellow" "$*" "$c_off"
  read -r reply; [[ "$reply" =~ ^[Yy]$ ]]
}

# ============================================================================
# Android
# ============================================================================
gradlew() { ( cd "$ROOT/android" && ./gradlew "$@" ); }

cmd_android_build()   { say "assembleDebug (JDK17)"; gradlew :app:assembleDebug -q; }
cmd_android_install() { say "installDebug (JDK17) -> $ANDROID_SERIAL"; gradlew :app:installDebug -q; say "installed."; }

cmd_android_restart() {
  need adb
  say "restarting $APP_ID on $ANDROID_SERIAL"
  adb shell am force-stop "$APP_ID" || true
  adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null
}

cmd_android_logclear() { need adb; adb logcat -c && say "logcat buffer cleared"; }

# android:log [regex]  — stream the app's own logs (by PID), color E/W, optional grep.
cmd_android_log() {
  need adb
  local filter="${1:-}"
  local pid; pid="$(adb shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
  if [[ -z "$pid" ]]; then
    warn "$APP_ID not running — showing tag-filtered logs (WM*). Start the app to get full PID logs."
    if [[ -n "$filter" ]]; then
      adb logcat -v color "WM:V" "AndroidRuntime:E" "*:S" | grep --line-buffered -iE "$filter"
    else
      adb logcat -v color "WM:V" "AndroidRuntime:E" "*:S"
    fi
    return
  fi
  say "tailing pid $pid ($APP_ID)${filter:+  grep=/$filter/}   — Ctrl-C to stop"
  if [[ -n "$filter" ]]; then
    adb logcat -v color --pid "$pid" | grep --line-buffered -iE "$filter"
  else
    adb logcat -v color --pid "$pid"
  fi
}

# ============================================================================
# Deno / shared edge-function code
# ============================================================================
cmd_deno_test() {
  need deno
  say "deno test functions/_shared/  (PATH has $DENO_BIN)"
  ( cd "$ROOT/supabase" && deno test --allow-all functions/_shared/ "$@" )
}

# deno:check [fn ...]  — type-check named functions, or every functions/*/index.ts.
cmd_deno_check() {
  need deno
  local targets=()
  if [[ $# -gt 0 ]]; then
    for f in "$@"; do targets+=("functions/$f/index.ts"); done
  else
    while IFS= read -r p; do targets+=("$p"); done \
      < <(cd "$ROOT/supabase" && ls functions/*/index.ts)
  fi
  say "deno check: ${targets[*]}"
  ( cd "$ROOT/supabase" && deno check "${targets[@]}" )
}

# ============================================================================
# Supabase edge functions
# ============================================================================
cmd_fn_serve() {
  need supabase
  say "supabase functions serve (local, --env-file functions/.env)"
  ( cd "$ROOT/supabase" && supabase functions serve --env-file functions/.env )
}

cmd_fn_deploy() {
  need supabase
  [[ $# -gt 0 ]] || die "usage: fn:deploy <name> [name ...]"
  say "deploy [$*] -> $PROJECT_REF"
  supabase functions deploy "$@" --project-ref "$PROJECT_REF"
}

cmd_fn_logs() {
  need supabase
  [[ $# -eq 1 ]] || die "usage: fn:logs <name>"
  say "logs $1 -> $PROJECT_REF  (tip: WM_LOG=debug for verbose JSON lines)"
  supabase functions logs "$1" --project-ref "$PROJECT_REF"
}

# fn:call <name> [jsonBody] [--remote]
#   Drives an edge function directly with a real seeded-user JWT — exercises the
#   backend (esp. the LLM path) without the Android UI. Local by default.
cmd_fn_call() {
  need curl
  local name="${1:-}"; [[ -n "$name" ]] || die "usage: fn:call <name> [jsonBody] [--remote]"
  shift
  local body="{}" remote=0
  for a in "$@"; do
    case "$a" in
      --remote) remote=1 ;;
      *) body="$a" ;;
    esac
  done

  local base anon
  if [[ "$remote" == 1 ]]; then
    base="https://${PROJECT_REF}.supabase.co"
    anon="${SUPABASE_ANON_KEY:-}"
    [[ -n "$anon" ]] || die "set SUPABASE_ANON_KEY for --remote (anon key from the dashboard)"
  else
    base="$LOCAL_API"
    # Local supabase prints a stable anon key; prefer `supabase status`, fall back to env.
    if command -v supabase >/dev/null 2>&1; then
      anon="$(cd "$ROOT/supabase" && supabase status -o env 2>/dev/null | sed -n 's/^ANON_KEY=//p' | tr -d '"' || true)"
    fi
    anon="${anon:-${SUPABASE_ANON_KEY:-}}"
    [[ -n "$anon" ]] || die "no local anon key — run 'supabase start' or set SUPABASE_ANON_KEY"
  fi

  say "login $DEV_EMAIL @ $base"
  local token
  token="$(curl -fsS "$base/auth/v1/token?grant_type=password" \
    -H "apikey: $anon" -H "Content-Type: application/json" \
    -d "{\"email\":\"$DEV_EMAIL\",\"password\":\"$DEV_PASSWORD\"}" \
    | _json_field access_token || true)"
  [[ -n "$token" && "$token" != "null" ]] || die "login failed (is the DB seeded / supabase up?)"

  say "POST $base/functions/v1/$name  body=$body"
  curl -sS "$base/functions/v1/$name" \
    -H "Authorization: Bearer $token" -H "apikey: $anon" \
    -H "Content-Type: application/json" -d "$body" | _pretty_json
}

# --- tiny JSON helpers (jq if present, else python3) ------------------------
_pretty_json() {
  if command -v jq >/dev/null 2>&1; then jq .; else python3 -m json.tool 2>/dev/null || cat; fi
}
_json_field() { # _json_field key  (reads stdin)
  if command -v jq >/dev/null 2>&1; then jq -r ".$1 // empty"
  else python3 -c "import sys,json; print(json.load(sys.stdin).get('$1',''))" 2>/dev/null || true; fi
}

# ============================================================================
# Database & web
# ============================================================================
cmd_db_push() {
  need supabase
  warn "db:push runs ALL pending migrations against $PROJECT_REF."
  confirm "Push migrations now?" || { say "aborted."; return; }
  ( cd "$ROOT/supabase" && supabase db push )
}

cmd_web_dev()   { ( cd "$ROOT/web" && npm run dev ); }
cmd_web_build() { ( cd "$ROOT/web" && npm run build ); }

# ============================================================================
# help / dispatch
# ============================================================================
cmd_help() {
  cat <<EOF
${c_blue}dev.sh${c_off} — build, run, debug & observe Workout Maker
  ${c_dim}env: JAVA_HOME=$JAVA_HOME  ref=$PROJECT_REF  device=$ANDROID_SERIAL${c_off}

${c_green}Android${c_off}
  android:install         build + install the debug APK on the phone (JDK17)
  android:build           assembleDebug only
  android:restart         force-stop + relaunch the app
  android:log [regex]     tail THIS app's logcat (by pid), optional grep filter
  android:logclear        clear the logcat buffer

${c_green}Edge functions (Deno)${c_off}
  deno:test [args]        run the _shared/ test suite
  deno:check [fn ...]     type-check named functions (default: all)
  fn:serve                serve functions locally (--env-file functions/.env)
  fn:deploy <name ...>    deploy named functions to the project
  fn:logs <name>          tail a deployed function's logs
  fn:call <name> [json] [--remote]
                          call a function with a seeded-user JWT; prints JSON

${c_green}Database & web${c_off}
  db:push                 run pending migrations (asks first)
  web:dev / web:build     Next.js dev server / production build

${c_dim}Examples:
  scripts/dev.sh android:install
  scripts/dev.sh android:log "WM|Exception"
  scripts/dev.sh fn:call daily-summary '{"date":"$(date +%F)"}'
  WM_LOG=debug scripts/dev.sh fn:logs generate-workout${c_off}
EOF
}

main() {
  local cmd="${1:-help}"; shift || true
  case "$cmd" in
    android:install)  cmd_android_install "$@" ;;
    android:build)    cmd_android_build "$@" ;;
    android:restart)  cmd_android_restart "$@" ;;
    android:log)      cmd_android_log "$@" ;;
    android:logclear) cmd_android_logclear "$@" ;;
    deno:test)        cmd_deno_test "$@" ;;
    deno:check)       cmd_deno_check "$@" ;;
    fn:serve)         cmd_fn_serve "$@" ;;
    fn:deploy)        cmd_fn_deploy "$@" ;;
    fn:logs)          cmd_fn_logs "$@" ;;
    fn:call)          cmd_fn_call "$@" ;;
    db:push)          cmd_db_push "$@" ;;
    web:dev)          cmd_web_dev "$@" ;;
    web:build)        cmd_web_build "$@" ;;
    help|-h|--help)   cmd_help ;;
    *) warn "unknown command: $cmd"; echo; cmd_help; exit 2 ;;
  esac
}

main "$@"
