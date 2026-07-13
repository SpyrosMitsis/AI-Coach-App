-- Hosted-AI rate limiting: extend hosted_spend() with a calls-per-hour count
-- so quota.ts can burst-limit without a second round trip. The migration-35
-- partial index (user_id, created_at) WHERE hosted makes the count cheap.
--
-- `create or replace` cannot change a function's return table, so drop first.

drop function if exists hosted_spend(uuid);

create function hosted_spend(p_user uuid)
returns table (user_day float8, user_month float8, global_month float8, user_hour_calls int4)
language sql
security definer
set search_path = public
as $$
  select
    coalesce(sum(estimated_cost_usd) filter (
      where user_id = p_user and created_at >= date_trunc('day', now())), 0)::float8,
    coalesce(sum(estimated_cost_usd) filter (
      where user_id = p_user), 0)::float8,
    coalesce(sum(estimated_cost_usd), 0)::float8,
    coalesce(count(*) filter (
      where user_id = p_user and created_at > now() - interval '1 hour'), 0)::int4
  from generation_logs
  where hosted and created_at >= date_trunc('month', now());
$$;

revoke all on function hosted_spend(uuid) from public, anon, authenticated;
