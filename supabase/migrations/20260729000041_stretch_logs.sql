-- ============================================================================
-- stretch_logs — a completed stretching/mobility session logged through the
-- coach chat (Trello #73). Unstructured on purpose: unlike strength_logs
-- there are no sets/reps/weight, just when it happened, how long, and any
-- note the athlete gave. Written only by coach-chat's log_stretch_session
-- tool via the service-role client, mirroring generation_logs' pattern.
-- ============================================================================

create table stretch_logs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade not null,
  date date not null,
  duration_min integer,
  notes text,
  created_at timestamptz default now()
);

create index on stretch_logs (user_id, date desc);

alter table stretch_logs enable row level security;

create policy "own stretch logs select" on stretch_logs
  for select using (auth.uid() = user_id);
-- inserts happen server-side via service_role; no client insert policy needed.
