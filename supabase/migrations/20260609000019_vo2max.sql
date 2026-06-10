-- VO2 max (mL/kg/min) synced from the watch via Health Connect, stored per day
-- so the dashboard can show the current value and its trend over time.

alter table wellness_checkins
  add column if not exists vo2max numeric;
