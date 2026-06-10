-- P1: goal races (A/B/C targeting). The next A-race drives periodization/taper;
-- B/C races are tune-ups shown on the countdown. RLS-scoped to the owner.
create table if not exists races (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null,
  date date not null,
  priority text not null default 'A',   -- 'A' | 'B' | 'C'
  distance text,                        -- free text, e.g. "Marathon", "10K"
  notes text,
  created_at timestamptz not null default now()
);

alter table races enable row level security;

create policy "own races" on races
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());

create index if not exists races_user_date on races(user_id, date);
