-- Raw device sleep score (0..100) from Intervals.icu, mirrored by sync-intervals.
-- Stored at full resolution so recovery uses the real number instead of the
-- lossy 1..5 sleep_quality bucket. Also lets us verify the score is flowing
-- (select date, sleep_score from wellness_checkins).
alter table wellness_checkins
  add column if not exists sleep_score int2;
