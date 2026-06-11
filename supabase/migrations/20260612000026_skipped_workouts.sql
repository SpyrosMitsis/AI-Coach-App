-- "Skip" used to only log feedback; the session card looked untouched. A
-- skipped session now carries an explicit flag so both apps can collapse it
-- (and offer Undo) while keeping it on the calendar for adherence history.
alter table planned_workouts
  add column if not exists skipped boolean not null default false;
