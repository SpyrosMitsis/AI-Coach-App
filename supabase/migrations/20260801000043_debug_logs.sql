-- Opt-in debug log upload. Off by default (see AppPreferences.debugLogSharingEnabled):
-- the phone always keeps a rolling local log file for export, but it only ever
-- reaches this table if the athlete turns "send debug logs to developer" on.
-- Same shape as crash_reports: clients can only INSERT their own rows; reading
-- is operator-only (service role / psql via ops-report.sh).
create table if not exists debug_logs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade not null default auth.uid(),
  created_at timestamptz default now(),
  version_name text,
  version_code int4,
  flavor text,
  sdk_int int4,
  device text,
  log_text text
);

create index on debug_logs (created_at desc);

alter table debug_logs enable row level security;

create policy "own debug log insert" on debug_logs
  for insert with check (auth.uid() = user_id);
