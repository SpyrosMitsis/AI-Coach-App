-- ============================================================================
-- Swimming as a first-class workout type: allow 'swim' in planned_workouts.type.
-- The auto-type chooser (generate-workout) and the workout schema already emit
-- 'swim' for swim-capable athletes, but the CHECK still rejected it — so an
-- auto-generated swim day failed the insert with a 500.
-- ============================================================================

alter table planned_workouts drop constraint if exists planned_workouts_type_check;
alter table planned_workouts add constraint planned_workouts_type_check
  check (type in ('run', 'ride', 'swim', 'strength', 'rest'));
