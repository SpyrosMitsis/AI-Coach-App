-- Persisted week plans (for the "explain this week" view) + auto-plan opt-in.

alter table user_profiles
  add column if not exists auto_plan boolean default false;

create table if not exists week_plans (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade not null default auth.uid(),
  start_date date not null,
  focus text,
  rationale text,
  created_at timestamptz default now(),
  unique (user_id, start_date)
);

alter table week_plans enable row level security;

create policy "own week_plans all" on week_plans
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create index if not exists week_plans_user_start on week_plans (user_id, start_date desc);
