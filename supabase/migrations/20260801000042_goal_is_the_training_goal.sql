-- onboarding.goal is the athlete's OVERARCHING TRAINING GOAL, and nothing else.
--
-- It used to carry two meanings and the last writer won. The phone's
-- deriveLegacyFields writes the training goal ("Run 42.2 km at 5:33 /km + Build
-- muscle"); setGoalRace, the web goal picker and the coach's set_goal_race tool
-- wrote the goal RACE's name straight over the top of it. So the coach was told
-- the athlete's goal was "Athens Marathon" and never heard about the muscle, and
-- remove_goal_race nulled the field outright.
--
-- The dated goal lives in `races`. onboarding.goal_date is a pointer into that
-- table, resolved through pickGoalRace, and it is the only thing the goal tools
-- write now.

-- 1. An anchor that never got a race row (set before set_goal_race wrote to
--    `races`) is the one case where onboarding.goal is the ONLY copy of a race
--    name. Give it its row BEFORE step 2 clears the field, or the goal is lost.
--    This is coach_tools.backfillAnchorRace, run once here so that function can
--    be deleted rather than left to insert training-goal phrases as races.
insert into races (user_id, name, date, priority, sport)
select
  p.id,
  trim(p.onboarding->>'goal'),
  (p.onboarding->>'goal_date')::date,
  'A',
  'run'
from user_profiles p
where p.onboarding->>'goal_date' ~ '^\d{4}-\d{2}-\d{2}$'
  and coalesce(trim(p.onboarding->>'goal'), '') <> ''
  -- Derived training-goal phrases are multi-goal joins ("A + B") or distance
  -- phrases ("Run 42.2 km at 5:33 /km"). A real event name is neither, so
  -- neither becomes a race.
  and p.onboarding->>'goal' not like '% + %'
  and p.onboarding->>'goal' !~ '[0-9]+(\.[0-9]+)? ?(km|m) at '
  -- Keyed on the DATE, the way the anchor is: a row already on that date is the
  -- goal, whatever it is called.
  and not exists (
    select 1 from races r
    where r.user_id = p.id
      and r.date = (p.onboarding->>'goal_date')::date
  );

-- 2. Drop every `goal` that is really a race name: it now duplicates a row in
--    `races`, which is where the goal event belongs. Deleting the key rather
--    than guessing a replacement is the honest repair. goalsText then reads
--    goals[], and an account with no training goals says "General fitness",
--    which is true.
update user_profiles p
set onboarding = p.onboarding - 'goal'
where coalesce(trim(p.onboarding->>'goal'), '') <> ''
  and exists (
    select 1 from races r
    where r.user_id = p.id
      and lower(trim(r.name)) = lower(trim(p.onboarding->>'goal'))
  );
