-- Richer signals for better workout recommendations:
--   * training_memory  — rolling LLM-written athlete notes, fed into every prompt
--   * last_lat/last_lon — coarse location for weather-aware sessions
--   * sleep stages      — deep/REM minutes from Health Connect

alter table user_profiles
  add column if not exists training_memory text,
  add column if not exists last_lat double precision,
  add column if not exists last_lon double precision;

alter table wellness_checkins
  add column if not exists sleep_deep_min int4,
  add column if not exists sleep_rem_min  int4;
