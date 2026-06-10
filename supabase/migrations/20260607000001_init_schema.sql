-- ============================================================================
-- Workout Maker — initial schema
-- ----------------------------------------------------------------------------
-- All persistent data lives here. Key material (Intervals.icu + LLM API keys)
-- is encrypted at rest with pgcrypto (pgp_sym_encrypt). The symmetric key is
-- read from the `app.settings.encryption_key` GUC, which is set from the
-- ENCRYPTION_KEY secret inside the Edge Functions' DB role — it is never
-- exposed to clients.
-- ============================================================================

create extension if not exists pgcrypto with schema extensions;

-- ----------------------------------------------------------------------------
-- Helper: read the symmetric encryption key from a GUC.
-- Edge Functions call `select set_config('app.encryption_key', <key>, true)`
-- at the start of a transaction; falls back to a vault secret if present.
-- ----------------------------------------------------------------------------
create or replace function app_encryption_key()
returns text
language plpgsql
stable
as $$
declare
  k text;
begin
  k := current_setting('app.encryption_key', true);
  if k is null or k = '' then
    raise exception 'encryption key not configured (app.encryption_key GUC is empty)';
  end if;
  return k;
end;
$$;

create or replace function encrypt_secret(plaintext text)
returns text
language sql
volatile
as $$
  select case
    when plaintext is null or plaintext = '' then null
    else encode(
      extensions.pgp_sym_encrypt(plaintext, app_encryption_key()),
      'base64'
    )
  end;
$$;

create or replace function decrypt_secret(ciphertext text)
returns text
language sql
volatile
as $$
  select case
    when ciphertext is null or ciphertext = '' then null
    else extensions.pgp_sym_decrypt(decode(ciphertext, 'base64'), app_encryption_key())
  end;
$$;

-- ----------------------------------------------------------------------------
-- user_profiles — extends auth.users
-- ----------------------------------------------------------------------------
create table user_profiles (
  id uuid references auth.users on delete cascade primary key,
  display_name text,
  intervals_athlete_id text,
  intervals_api_key_encrypted text,
  onboarding jsonb default '{}'::jsonb,
  onboarding_complete boolean default false,
  active_llm_provider text default 'groq',
  llm_fallback_chain text[] default array['groq', 'deepseek', 'gemini'],
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);

-- ----------------------------------------------------------------------------
-- llm_api_keys — one row per (user, provider)
-- ----------------------------------------------------------------------------
create table llm_api_keys (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade not null,
  provider text not null check (provider in ('anthropic','deepseek','openai','gemini','groq')),
  api_key_encrypted text,
  is_valid boolean,
  last_tested_at timestamptz,
  created_at timestamptz default now(),
  unique (user_id, provider)
);

-- ----------------------------------------------------------------------------
-- wellness_checkins — daily 1..5 scores
-- ----------------------------------------------------------------------------
create table wellness_checkins (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade not null,
  date date not null,
  energy int2 check (energy between 1 and 5),
  soreness int2 check (soreness between 1 and 5),
  sleep_quality int2 check (sleep_quality between 1 and 5),
  zepp_sleep_minutes int4,
  created_at timestamptz default now(),
  unique (user_id, date)
);

-- ----------------------------------------------------------------------------
-- planned_workouts — AI-generated plans
-- ----------------------------------------------------------------------------
create table planned_workouts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade not null,
  date date not null,
  type text check (type in ('run','strength','rest')),
  workout_json jsonb not null,
  llm_provider text,
  llm_model text,
  intervals_event_id text,
  pushed_at timestamptz,
  completed boolean default false,
  created_at timestamptz default now()
);

-- ----------------------------------------------------------------------------
-- completed_activities — cache of Intervals.icu activities
-- ----------------------------------------------------------------------------
create table completed_activities (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade not null,
  intervals_id text not null,
  type text,
  date date,
  duration_seconds int4,
  distance_m float4,
  avg_hr int2,
  tss float4,
  ctl float4,
  atl float4,
  data_json jsonb,
  unique (user_id, intervals_id)
);

-- ----------------------------------------------------------------------------
-- strength_logs
-- ----------------------------------------------------------------------------
create table strength_logs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade not null,
  date date not null,
  exercise_name text not null,
  muscle_groups text[],
  sets jsonb,                 -- [{ reps, weight_kg, rpe }]
  estimated_1rm float4,
  notes text,
  created_at timestamptz default now()
);

-- ----------------------------------------------------------------------------
-- generation_logs — full LLM audit trail for debugging providers
-- ----------------------------------------------------------------------------
create table generation_logs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users on delete cascade not null,
  created_at timestamptz default now(),
  provider text,
  model text,
  prompt_tokens int4,
  completion_tokens int4,
  estimated_cost_usd float4,
  system_prompt text,
  user_prompt text,
  raw_response text,
  parsed_ok boolean,
  error text,
  workout_id uuid references planned_workouts on delete set null
);

-- ----------------------------------------------------------------------------
-- exercise_library — shared, read-only reference data (seeded)
-- ----------------------------------------------------------------------------
create table exercise_library (
  id uuid primary key default gen_random_uuid(),
  name text unique not null,
  muscle_groups text[],
  equipment text,
  is_compound boolean default false
);

-- ----------------------------------------------------------------------------
-- Indexes for the hot paths the Edge Functions / dashboard hit
-- ----------------------------------------------------------------------------
create index on wellness_checkins (user_id, date desc);
create index on planned_workouts (user_id, date desc);
create index on completed_activities (user_id, date desc);
create index on strength_logs (user_id, date desc);
create index on generation_logs (user_id, created_at desc);

-- ----------------------------------------------------------------------------
-- updated_at trigger for user_profiles
-- ----------------------------------------------------------------------------
create or replace function set_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create trigger user_profiles_set_updated_at
  before update on user_profiles
  for each row execute function set_updated_at();

-- ----------------------------------------------------------------------------
-- Auto-create a profile row when a new auth user signs up
-- ----------------------------------------------------------------------------
create or replace function handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.user_profiles (id, display_name)
  values (new.id, coalesce(new.raw_user_meta_data->>'display_name', split_part(new.email, '@', 1)))
  on conflict (id) do nothing;
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function handle_new_user();
