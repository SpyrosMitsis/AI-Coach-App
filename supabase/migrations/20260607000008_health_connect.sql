-- Health Connect wellness metrics synced from the Android device (HRV rmssd,
-- resting HR, steps). Sleep continues to use zepp_sleep_minutes. These feed the
-- readiness score in daily-summary alongside Intervals.icu data.

alter table wellness_checkins
  add column if not exists hrv_rmssd  numeric,
  add column if not exists resting_hr int2,
  add column if not exists steps      int4,
  add column if not exists source     text;  -- 'manual' | 'health_connect' | 'intervals'

-- Client inserts (wellness check-ins, feedback, strength logs) don't send
-- user_id; default it to the caller so RLS owner-scoping works on INSERT.
alter table wellness_checkins alter column user_id set default auth.uid();
alter table workout_feedback  alter column user_id set default auth.uid();
alter table strength_logs     alter column user_id set default auth.uid();
