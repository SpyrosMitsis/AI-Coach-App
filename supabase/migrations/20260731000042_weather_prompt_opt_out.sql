-- Opt-out of the daily weather-viability prompt ("I have other options" —
-- e.g. gym/treadmill/trainer access regardless of outdoor conditions).
-- Mirrors training_paused_until's shape: a structured boolean, because a
-- prose fact ("I have a treadmill") in coach_knowledge has no authority over
-- the deterministic check in weather-check/index.ts — same reasoning as
-- training_paused_until (see that migration). Unlike a pause this has no
-- expiry: the athlete flips it back off via the coach or a future Settings
-- toggle when the workaround no longer applies.
alter table user_profiles
  add column if not exists weather_prompt_opt_out boolean not null default false,
  add column if not exists weather_prompt_opt_out_reason text;

comment on column user_profiles.weather_prompt_opt_out is
  'true = never show the daily "weather unsafe, swap?" prompt (weather-check/index.ts short-circuits on this). Set from the in-app dialog''s "Don''t ask again" button, or via the set_weather_prompt_pref coach tool.';
comment on column user_profiles.weather_prompt_opt_out_reason is
  'Optional free-text reason, e.g. "have a treadmill/home gym" — for the coach''s own context, not read by weather-check.';
