#!/usr/bin/env bash
# ops-report.sh — the solo-operator dashboard: users, signups, and LLM spend.
# Read-only. Needs a Postgres connection string with read access:
#   SUPABASE_DB_URL='postgresql://postgres:<password>@db.<ref>.supabase.co:5432/postgres' scripts/ops-report.sh
# (Dashboard → Project Settings → Database → Connection string. Or add it to
#  scripts/dev.local.sh.)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ -f "$SCRIPT_DIR/dev.local.sh" ]] && source "$SCRIPT_DIR/dev.local.sh"

: "${SUPABASE_DB_URL:?set SUPABASE_DB_URL (see header of this script)}"
command -v psql >/dev/null || { echo "missing psql" >&2; exit 1; }

psql "$SUPABASE_DB_URL" --quiet <<'SQL'
\echo '=== Users ==='
select count(*)                                           as total_users,
       count(*) filter (where created_at > now() - interval '7 days')  as new_7d,
       count(*) filter (where last_sign_in_at > now() - interval '7 days') as active_7d
from auth.users;

\echo ''
\echo '=== LLM spend by day (last 14) ==='
select created_at::date                        as day,
       count(*)                                as calls,
       round(sum(estimated_cost_usd)::numeric, 4) as usd
from generation_logs
where created_at > now() - interval '14 days'
group by 1 order by 1 desc;

\echo ''
\echo '=== Top spenders this month ==='
select user_id,
       count(*)                                   as calls,
       round(sum(estimated_cost_usd)::numeric, 4) as usd
from generation_logs
where created_at >= date_trunc('month', now())
group by 1 order by usd desc nulls last limit 10;
SQL
