-- ============================================================================
-- Conversational coach + reusable workout templates.
--   coach_conversations — full chat history per session (goal-setting, planning)
--   workout_templates    — a finalized, reusable plan distilled from a chat
-- ============================================================================

create table coach_conversations (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade not null,
  title text,
  -- messages: [{ role: 'user'|'assistant', content: text, ts }]
  messages jsonb not null default '[]'::jsonb,
  -- what the conversation is for: 'setup' (goal/workload), 'plan', 'workout'
  purpose text default 'plan',
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);

create table workout_templates (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade not null,
  name text not null,
  description text,
  -- 'run' | 'strength' | 'hybrid' | 'plan'
  kind text default 'workout',
  -- structured payload: either a single Workout, or a multi-week plan
  -- { weeks: [{ week, focus, days: [{ day, workout }] }] }
  structure jsonb not null,
  source_conversation_id uuid references coach_conversations on delete set null,
  created_at timestamptz default now()
);

create index on coach_conversations (user_id, updated_at desc);
create index on workout_templates (user_id, created_at desc);

create trigger coach_conversations_set_updated_at
  before update on coach_conversations
  for each row execute function set_updated_at();

alter table coach_conversations enable row level security;
alter table workout_templates   enable row level security;

create policy "own conversations all" on coach_conversations
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own templates all" on workout_templates
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
