-- ============================================================================
-- seed.sql — one sample athlete with 30 days of realistic training history.
--
-- Login after seeding:  athlete@example.com  /  password123
--
-- Safe to run repeatedly: everything keys off the fixed UUID below and uses
-- upsert / delete-then-insert semantics.
-- ============================================================================

-- Sample athlete UUID used throughout (psql \set is unavailable when the
-- Supabase CLI applies this over a plain SQL connection, so we inline it).

-- --- auth user (local dev only) ---------------------------------------------
-- IMPORTANT: GoTrue (Supabase Auth) scans several token columns into
-- non-nullable Go strings. If they're left NULL (the default when you craft an
-- auth.users row in raw SQL) login fails with "Database error querying schema".
-- So we set them to '' explicitly.
insert into auth.users (
  id, instance_id, aud, role, email, encrypted_password,
  email_confirmed_at, created_at, updated_at,
  raw_app_meta_data, raw_user_meta_data,
  confirmation_token, recovery_token, email_change,
  email_change_token_new, email_change_token_current,
  phone_change, phone_change_token, reauthentication_token
)
values (
  '00000000-0000-0000-0000-0000000000a1',
  '00000000-0000-0000-0000-000000000000',
  'authenticated', 'authenticated',
  'athlete@example.com',
  crypt('password123', gen_salt('bf')),
  now(), now(), now(),
  '{"provider":"email","providers":["email"]}',
  '{"display_name":"Sam Runner"}',
  '', '', '', '', '', '', '', ''
)
on conflict (id) do nothing;

insert into auth.identities (
  id, user_id, provider_id, identity_data, provider, created_at, updated_at, last_sign_in_at
)
values (
  gen_random_uuid(), '00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000a1',
  format('{"sub":"%s","email":"athlete@example.com"}', '00000000-0000-0000-0000-0000000000a1')::jsonb,
  'email', now(), now(), now()
)
on conflict do nothing;

-- --- profile (trigger creates the row; we enrich it) ------------------------
insert into user_profiles (id, display_name, intervals_athlete_id, onboarding,
                           onboarding_complete, active_llm_provider, llm_fallback_chain)
values (
  '00000000-0000-0000-0000-0000000000a1', 'Sam Runner', 'i123456',
  jsonb_build_object(
    'goal', '10K pace',
    'experience', 'Intermediate',
    'days', array['Mon','Tue','Thu','Sat','Sun'],
    'session_duration', 60,
    'equipment', 'Full gym',
    'target_pace', '4:45/km',
    'weekly_tss_target', 380,
    'injury_history', 'Mild right IT band tightness, resolved',
    'hr_zones', jsonb_build_array(
      jsonb_build_object('zone','Z1','min',95,'max',130),
      jsonb_build_object('zone','Z2','min',131,'max',145),
      jsonb_build_object('zone','Z3','min',146,'max',160),
      jsonb_build_object('zone','Z4','min',161,'max',172),
      jsonb_build_object('zone','Z5','min',173,'max',190)
    )
  ),
  true, 'groq', array['groq','deepseek','gemini']
)
on conflict (id) do update set
  intervals_athlete_id = excluded.intervals_athlete_id,
  onboarding = excluded.onboarding,
  onboarding_complete = true;

-- --- clear prior seed rows for idempotency ----------------------------------
delete from completed_activities where user_id = '00000000-0000-0000-0000-0000000000a1';
delete from wellness_checkins   where user_id = '00000000-0000-0000-0000-0000000000a1';
delete from strength_logs       where user_id = '00000000-0000-0000-0000-0000000000a1';
delete from planned_workouts    where user_id = '00000000-0000-0000-0000-0000000000a1';

-- --- 30 days of activities + a rising CTL / oscillating ATL ------------------
-- gs = days ago (29 .. 0). We synthesize a believable build: ~5 sessions/week,
-- mix of easy runs, one quality run, one long run, two strength days.
insert into completed_activities
  (user_id, intervals_id, type, date, duration_seconds, distance_m, avg_hr, tss, ctl, atl, data_json)
select
  '00000000-0000-0000-0000-0000000000a1',
  'seed-' || gs,
  case
    when (29 - gs) % 7 in (0, 3) then 'Run'        -- Mon/Thu quality/easy
    when (29 - gs) % 7 = 5      then 'Run'         -- Sat long
    when (29 - gs) % 7 in (1, 4) then 'WeightTraining'
    else 'Run'
  end as type,
  (current_date - gs)::date as date,
  case when (29 - gs) % 7 = 5 then 5400            -- long run 90min
       when (29 - gs) % 7 in (1,4) then 3000       -- strength 50min
       else 2700 end as duration_seconds,           -- easy run 45min
  case when (29 - gs) % 7 = 5 then 18000.0
       when (29 - gs) % 7 in (1,4) then 0.0
       else 9000.0 end as distance_m,
  case when (29 - gs) % 7 = 0 then 168              -- quality day higher HR
       when (29 - gs) % 7 in (1,4) then 120
       else 142 end as avg_hr,
  case when (29 - gs) % 7 = 5 then 95.0
       when (29 - gs) % 7 = 0 then 80.0
       when (29 - gs) % 7 in (1,4) then 45.0
       else 50.0 end as tss,
  -- CTL ramps 38 -> ~52 over the month; ATL noisier.
  round((38 + (29 - gs) * 0.48)::numeric, 1) as ctl,
  round((40 + 8 * sin((29 - gs) / 2.0))::numeric, 1) as atl,
  jsonb_build_object(
    'hrv', round((62 + 6 * sin((29 - gs) / 3.0))::numeric, 1),
    'restingHR', round((46 + 2 * cos((29 - gs) / 4.0))::numeric, 0),
    'vo2max', round((52 + (29 - gs) * 0.03)::numeric, 1)
  )
from generate_series(29, 0, -1) as gs
-- skip 2 days/week as rest (Wed/Fri pattern)
where (29 - gs) % 7 not in (2, 6);

-- --- 14 days of wellness checkins --------------------------------------------
insert into wellness_checkins (user_id, date, energy, soreness, sleep_quality, zepp_sleep_minutes)
select
  '00000000-0000-0000-0000-0000000000a1',
  (current_date - gs)::date,
  3 + (gs % 3),                                   -- energy 3..5
  1 + ((gs + 1) % 4),                             -- soreness 1..4
  3 + ((gs + 2) % 3),                             -- sleep 3..5
  390 + (gs % 5) * 12                             -- ~6.5-7.5h
from generate_series(13, 0, -1) as gs;

-- --- strength logs (last ~3 weeks, 2x/week) ---------------------------------
insert into strength_logs (user_id, date, exercise_name, muscle_groups, sets, estimated_1rm, notes)
select s.uid::uuid, s.dt, s.ex, s.mg, s.sets, s.rm, s.notes
from (values
  ('00000000-0000-0000-0000-0000000000a1', (current_date - 18)::date, 'Back Squat', array['quads','glutes','hamstrings','core'],
    '[{"reps":5,"weight_kg":100,"rpe":7},{"reps":5,"weight_kg":100,"rpe":8},{"reps":5,"weight_kg":100,"rpe":8}]'::jsonb,
    116.7::float4, 'felt strong'),
  ('00000000-0000-0000-0000-0000000000a1', (current_date - 18)::date, 'Bench Press', array['chest','triceps','shoulders'],
    '[{"reps":5,"weight_kg":75,"rpe":7},{"reps":5,"weight_kg":75,"rpe":8}]'::jsonb, 87.5::float4, null),
  ('00000000-0000-0000-0000-0000000000a1', (current_date - 14)::date, 'Deadlift', array['hamstrings','glutes','back','traps'],
    '[{"reps":3,"weight_kg":140,"rpe":8},{"reps":3,"weight_kg":140,"rpe":8}]'::jsonb, 154.0::float4, null),
  ('00000000-0000-0000-0000-0000000000a1', (current_date - 14)::date, 'Pull-up', array['lats','back','biceps'],
    '[{"reps":8,"weight_kg":0,"rpe":7},{"reps":7,"weight_kg":0,"rpe":8}]'::jsonb, null, 'bodyweight'),
  ('00000000-0000-0000-0000-0000000000a1', (current_date - 11)::date, 'Back Squat', array['quads','glutes','hamstrings','core'],
    '[{"reps":5,"weight_kg":102.5,"rpe":8},{"reps":5,"weight_kg":102.5,"rpe":8}]'::jsonb, 119.6::float4, 'small PR'),
  ('00000000-0000-0000-0000-0000000000a1', (current_date - 7)::date, 'Overhead Press', array['shoulders','triceps','core'],
    '[{"reps":5,"weight_kg":50,"rpe":8},{"reps":5,"weight_kg":50,"rpe":9}]'::jsonb, 58.3::float4, null),
  ('00000000-0000-0000-0000-0000000000a1', (current_date - 4)::date, 'Bench Press', array['chest','triceps','shoulders'],
    '[{"reps":5,"weight_kg":77.5,"rpe":8},{"reps":4,"weight_kg":77.5,"rpe":9}]'::jsonb, 90.4::float4, null)
) as s(uid, dt, ex, mg, sets, rm, notes);

-- --- a couple of already-planned (AI) workouts for the calendar -------------
insert into planned_workouts (user_id, date, type, workout_json, llm_provider, llm_model)
values
('00000000-0000-0000-0000-0000000000a1', current_date, 'run',
 jsonb_build_object(
   'type','run','title','Z2 Aerobic Base 50min','duration_minutes',50,
   'tss_estimate',52,'rpe_target',4,
   'sections', jsonb_build_array(
     jsonb_build_object('name','Warmup','duration_minutes',10,'exercises',jsonb_build_array(
       jsonb_build_object('name','Easy jog + drills','sets',1,'reps','10min','weight_kg',null,'pace_zone','Z1','hr_zone','Z1','rest_seconds',null,'notes','build into Z2'))),
     jsonb_build_object('name','Main Set','duration_minutes',32,'exercises',jsonb_build_array(
       jsonb_build_object('name','Steady aerobic run','sets',1,'reps','32min','weight_kg',null,'pace_zone','Z2','hr_zone','Z2','rest_seconds',null,'notes','conversational pace'))),
     jsonb_build_object('name','Cooldown','duration_minutes',8,'exercises',jsonb_build_array(
       jsonb_build_object('name','Easy jog','sets',1,'reps','8min','weight_kg',null,'pace_zone','Z1','hr_zone','Z1','rest_seconds',null,'notes',null)))),
   'coach_note','TSB is slightly positive and soreness is low, so a relaxed aerobic session keeps fitness ticking without adding fatigue.'),
 'groq','llama-3.3-70b-versatile'),
('00000000-0000-0000-0000-0000000000a1', (current_date + 1)::date, 'strength',
 jsonb_build_object(
   'type','strength','title','Lower Body Strength','duration_minutes',60,
   'tss_estimate',45,'rpe_target',7,
   'sections', jsonb_build_array(
     jsonb_build_object('name','Main','duration_minutes',45,'exercises',jsonb_build_array(
       jsonb_build_object('name','Back Squat','sets',4,'reps','5','weight_kg',105,'pace_zone',null,'hr_zone',null,'rest_seconds',180,'notes','~88% 1RM'),
       jsonb_build_object('name','Romanian Deadlift','sets',3,'reps','8','weight_kg',90,'pace_zone',null,'hr_zone',null,'rest_seconds',120,'notes',null)))),
   'coach_note','Legs are recovered (no lower-body work in 48h), so we progress the squat by 2.5kg.'),
 'groq','llama-3.3-70b-versatile');
