-- Body-composition history: dated measurements ride on the existing per-day
-- wellness_checkins table (unique(user_id, date), RLS already covers it).
-- Smart-scale syncs and manual quick-logs upsert these; trends are computed
-- on read (_shared/body_trend.ts). Lean mass is stored only when measured;
-- when absent it is derived from weight and body fat at read time.
alter table wellness_checkins
  add column if not exists weight_kg numeric,
  add column if not exists body_fat_pct numeric,
  add column if not exists lean_mass_kg numeric;
