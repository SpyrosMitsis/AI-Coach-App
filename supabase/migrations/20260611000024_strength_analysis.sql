-- Post-workout analysis for STRENGTH sessions: planned (planned_workouts) vs
-- logged (strength_logs) + optional watch HR, cached per user+date. Keyed by
-- date because strength sessions don't have one canonical server-side row —
-- the logs are per-exercise and the watch recording is optional.
create table if not exists strength_analyses (
  user_id uuid references auth.users on delete cascade not null,
  date date not null,
  analysis_json jsonb not null,
  created_at timestamptz default now(),
  primary key (user_id, date)
);

alter table strength_analyses enable row level security;
create policy "own strength analyses" on strength_analyses
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
