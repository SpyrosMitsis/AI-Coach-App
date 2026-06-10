-- User-created strength exercises (D1). Backed up to the cloud so custom lifts
-- (and their muscle mapping) survive a reinstall and feed the AI generator.
create table if not exists strength_custom_exercises (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null,
  muscle text not null default 'Other',
  category text not null default 'Barbell',
  compound boolean not null default false,
  created_at timestamptz not null default now(),
  primary key (user_id, name)
);

alter table strength_custom_exercises enable row level security;

create policy "own custom exercises" on strength_custom_exercises
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());
