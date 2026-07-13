-- Crash visibility without third-party SDKs: the app writes a crash file on
-- uncaught exceptions and uploads it here on the next start. Clients can only
-- INSERT their own rows; reading is operator-only (service role / psql via
-- ops-report.sh), so one user can never see another's stack traces.
create table if not exists crash_reports (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade not null default auth.uid(),
  created_at timestamptz default now(),
  crashed_at timestamptz,
  version_name text,
  version_code int4,
  flavor text,
  sdk_int int4,
  device text,
  thread text,
  exception text,
  stack text,
  fatal boolean not null default true
);

create index on crash_reports (created_at desc);

alter table crash_reports enable row level security;

create policy "own crash insert" on crash_reports
  for insert with check (auth.uid() = user_id);
