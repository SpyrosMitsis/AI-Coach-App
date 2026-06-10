-- Locked sessions: athlete-fixed events (e.g. "social 10k with friends Friday",
-- "gym with a mate tomorrow"). The weekly AI re-planner must NOT move or replace
-- these, but DOES plan the rest of the week around them.
alter table planned_workouts add column if not exists locked boolean not null default false;
