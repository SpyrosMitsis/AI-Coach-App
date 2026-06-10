-- Full cloud backup of the strength module (so a reinstall doesn't wipe history)
-- + allow client-side manual activity logging into completed_activities.

-- Manual actuals: let clients insert their own completed activities.
alter table completed_activities alter column user_id set default auth.uid();

-- Timestamps stored as epoch millis (bigint) to round-trip with Room exactly.
create table if not exists strength_workouts (
  id uuid primary key,
  user_id uuid references auth.users on delete cascade not null default auth.uid(),
  name text,
  started_at bigint,
  ended_at bigint,
  duration_sec int,
  total_volume_kg float8,
  note text default ''
);

create table if not exists strength_workout_sets (
  id uuid primary key,
  user_id uuid references auth.users on delete cascade not null default auth.uid(),
  workout_id uuid references strength_workouts on delete cascade,
  exercise_name text,
  muscle text,
  idx int,
  weight_kg float8,
  reps int,
  rpe int,
  is_warmup boolean default false
);

create table if not exists strength_routines (
  id uuid primary key,
  user_id uuid references auth.users on delete cascade not null default auth.uid(),
  name text,
  created_at bigint
);

create table if not exists strength_routine_items (
  id uuid primary key,
  user_id uuid references auth.users on delete cascade not null default auth.uid(),
  routine_id uuid references strength_routines on delete cascade,
  exercise_name text,
  position int,
  target_sets int,
  target_reps text,
  rest_sec int
);

alter table strength_workouts      enable row level security;
alter table strength_workout_sets  enable row level security;
alter table strength_routines      enable row level security;
alter table strength_routine_items enable row level security;

create policy "own strength_workouts all" on strength_workouts
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own strength_workout_sets all" on strength_workout_sets
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own strength_routines all" on strength_routines
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own strength_routine_items all" on strength_routine_items
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create index if not exists strength_workouts_user on strength_workouts (user_id, started_at desc);
create index if not exists strength_sets_workout on strength_workout_sets (workout_id);
create index if not exists strength_routine_items_routine on strength_routine_items (routine_id);
