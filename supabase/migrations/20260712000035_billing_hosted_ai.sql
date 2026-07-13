-- Billing + hosted AI (Play subscription "Pro" tier).
--
-- user_profiles gains the plan columns; clients can READ them but only the
-- service role (verify-purchase / play-rtdn) may change plan/expiry/token —
-- a trigger silently reverts client-side writes so the existing "own profile
-- update" policy keeps working for everything else. use_hosted_ai stays
-- client-writable: it is the user's own toggle between hosted and BYO keys.

alter table user_profiles
  add column if not exists plan text not null default 'free'
    check (plan in ('free', 'pro')),
  add column if not exists plan_expires_at timestamptz,
  add column if not exists play_purchase_token text,
  add column if not exists use_hosted_ai boolean not null default true;

create or replace function protect_billing_columns()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  -- auth.role() is 'authenticated' for end-user JWTs and 'service_role' /
  -- null for the admin client and direct DB connections.
  if auth.role() = 'authenticated' then
    new.plan := old.plan;
    new.plan_expires_at := old.plan_expires_at;
    new.play_purchase_token := old.play_purchase_token;
  end if;
  return new;
end;
$$;

drop trigger if exists protect_billing_columns on user_profiles;
create trigger protect_billing_columns
  before update on user_profiles
  for each row execute function protect_billing_columns();

-- Hosted generations are the owner's money — flag them for quota accounting.
alter table generation_logs
  add column if not exists hosted boolean not null default false;

create index if not exists generation_logs_hosted_idx
  on generation_logs (user_id, created_at)
  where hosted;

-- Spend sums the quota check needs, one round trip. UTC windows: these are
-- cost-control caps, not user-facing dates, so the local-date rule doesn't
-- apply. Service-role only (security definer + revoked from clients).
create or replace function hosted_spend(p_user uuid)
returns table (user_day float8, user_month float8, global_month float8)
language sql
security definer
set search_path = public
as $$
  select
    coalesce(sum(estimated_cost_usd) filter (
      where user_id = p_user and created_at >= date_trunc('day', now())), 0)::float8,
    coalesce(sum(estimated_cost_usd) filter (
      where user_id = p_user), 0)::float8,
    coalesce(sum(estimated_cost_usd), 0)::float8
  from generation_logs
  where hosted and created_at >= date_trunc('month', now());
$$;

revoke all on function hosted_spend(uuid) from public, anon, authenticated;

-- Audit trail for every billing decision (verify-purchase, RTDN, lazy expiry
-- checks). RLS enabled with no policies = service-role only. user_id cascades
-- so delete-account keeps its "everything is gone" promise.
create table if not exists billing_events (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz default now(),
  user_id uuid references auth.users on delete cascade,
  source text not null,          -- 'verify-purchase' | 'rtdn' | 'expiry-check'
  event_type text,               -- RTDN notificationType / subscriptionState
  purchase_token text,
  payload jsonb,
  outcome text                   -- 'pro' | 'free' | 'ignored' | 'error: …'
);

alter table billing_events enable row level security;
