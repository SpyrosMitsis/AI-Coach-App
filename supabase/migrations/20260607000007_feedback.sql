-- ============================================================================
-- Post-workout feedback — closes the autoregulation loop. The generator reads
-- recent feedback to adjust the next session (e.g. if the last hard run was
-- rated "too hard", back off intensity).
-- ============================================================================

create table workout_feedback (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade not null,
  planned_workout_id uuid references planned_workouts on delete set null,
  date date not null default current_date,
  completed boolean default true,
  actual_rpe int2 check (actual_rpe between 1 and 10),
  -- athlete's perceived fit: 'too_easy' | 'just_right' | 'too_hard'
  difficulty text check (difficulty in ('too_easy','just_right','too_hard')),
  notes text,
  created_at timestamptz default now()
);

create index on workout_feedback (user_id, date desc);

alter table workout_feedback enable row level security;
create policy "own feedback all" on workout_feedback
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
