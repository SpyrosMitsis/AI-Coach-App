-- ============================================================================
-- Cycling as a first-class workout type: allow 'ride' in planned_workouts.type.
-- The original inline check only permitted run/strength/rest.
-- ============================================================================

alter table planned_workouts drop constraint if exists planned_workouts_type_check;
alter table planned_workouts add constraint planned_workouts_type_check
  check (type in ('run', 'ride', 'strength', 'rest'));
