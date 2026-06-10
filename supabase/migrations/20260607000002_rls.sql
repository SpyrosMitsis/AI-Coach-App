-- ============================================================================
-- Row Level Security — every table is owner-scoped except the shared,
-- read-only exercise_library. Edge Functions use the service_role key, which
-- bypasses RLS, so server-side sync/generation is unaffected by these policies.
-- ============================================================================

alter table user_profiles       enable row level security;
alter table llm_api_keys         enable row level security;
alter table wellness_checkins    enable row level security;
alter table planned_workouts     enable row level security;
alter table completed_activities enable row level security;
alter table strength_logs        enable row level security;
alter table generation_logs      enable row level security;
alter table exercise_library     enable row level security;

-- Generic owner policy generator pattern, written out per-table for clarity.

-- user_profiles ----------------------------------------------------------------
create policy "own profile select" on user_profiles
  for select using (auth.uid() = id);
create policy "own profile update" on user_profiles
  for update using (auth.uid() = id) with check (auth.uid() = id);
create policy "own profile insert" on user_profiles
  for insert with check (auth.uid() = id);

-- llm_api_keys -----------------------------------------------------------------
-- NOTE: api_key_encrypted is ciphertext; clients never receive plaintext.
create policy "own keys all" on llm_api_keys
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- wellness_checkins ------------------------------------------------------------
create policy "own wellness all" on wellness_checkins
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- planned_workouts -------------------------------------------------------------
create policy "own planned all" on planned_workouts
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- completed_activities ---------------------------------------------------------
create policy "own activities all" on completed_activities
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- strength_logs ----------------------------------------------------------------
create policy "own strength all" on strength_logs
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- generation_logs --------------------------------------------------------------
create policy "own genlogs select" on generation_logs
  for select using (auth.uid() = user_id);
-- inserts happen server-side via service_role; no client insert policy needed.

-- exercise_library -------------------------------------------------------------
-- Shared reference data: any authenticated user may read.
create policy "library read" on exercise_library
  for select to authenticated using (true);
