-- E4: threshold test results (LTHR, FTP, threshold pace). Recording a test
-- updates the athlete's current threshold, from which zones are derived (E1).
-- value units by kind: lthr=bpm, ftp=watts, threshold_pace=seconds per km.
create table if not exists threshold_tests (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  date date not null default current_date,
  kind text not null,           -- 'lthr' | 'ftp' | 'threshold_pace'
  value numeric not null,
  notes text,
  created_at timestamptz not null default now()
);

alter table threshold_tests enable row level security;

create policy "own threshold tests" on threshold_tests
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());

create index if not exists threshold_tests_user_date on threshold_tests(user_id, date desc);
