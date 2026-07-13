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

\echo ''
\echo '=== HOSTED spend by day (last 14, the money that is yours) ==='
select created_at::date                            as day,
       count(*)                                    as calls,
       round(sum(estimated_cost_usd)::numeric, 4)  as usd
from generation_logs
where hosted and created_at > now() - interval '14 days'
group by 1 order by 1 desc;

\echo ''
\echo '=== Hosted top spenders this month (check against HOSTED_USER_MONTHLY_USD) ==='
select g.user_id,
       p.plan,
       count(*)                                    as calls,
       round(sum(g.estimated_cost_usd)::numeric, 4) as usd
from generation_logs g
left join user_profiles p on p.id = g.user_id
where g.hosted and g.created_at >= date_trunc('month', now())
group by 1, 2 order by usd desc nulls last limit 10;

\echo ''
\echo '=== Per-user cost picture (30 days: hosted vs BYO, top feature) ==='
with per_user as (
  select user_id,
         count(*)                                                   as calls,
         coalesce(sum(prompt_tokens + completion_tokens), 0)        as tokens,
         round(sum(estimated_cost_usd) filter (where hosted)::numeric, 4)     as hosted_usd,
         round(sum(estimated_cost_usd) filter (where not hosted)::numeric, 4) as byo_usd,
         mode() within group (order by feature)                     as top_feature
  from generation_logs
  where created_at > now() - interval '30 days'
  group by 1
)
select u.user_id, p.plan, u.calls, u.tokens,
       coalesce(u.hosted_usd, 0) as hosted_usd,
       coalesce(u.byo_usd, 0)    as byo_usd,
       u.top_feature
from per_user u
left join user_profiles p on p.id = u.user_id
order by coalesce(u.hosted_usd, 0) desc, coalesce(u.byo_usd, 0) desc
limit 25;

\echo ''
\echo '=== Crashes (14 days, grouped) ==='
select version_name, flavor, left(exception, 90) as exception,
       count(*) as hits, max(created_at) as last_seen
from crash_reports
where created_at > now() - interval '14 days'
group by 1, 2, 3 order by last_seen desc limit 20;

\echo ''
\echo '=== Recent billing events ==='
select created_at, source, event_type, outcome, user_id
from billing_events
order by created_at desc limit 20;
SQL
