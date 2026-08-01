-- ============================================================================
-- Injury follow-up loop + workout-level pain backoff.
--
-- Injuries were captured ONCE at onboarding and never revisited: no timestamp
-- on when one was raised, no follow-up, and no way for "my knee hurt during
-- that run" to reach the generator as anything stronger than prose. Prose has
-- no date-level authority (see the header comment on memoryDocsBlock in
-- _shared/agent_memory.ts) — so the backoff gets a structured column, exactly
-- like training_paused_until (migration 40), which is the reference example.
--
-- injury_backoff is an ARRAY (unlike the scalar training_paused_until) because
-- an athlete can be protecting two areas at once, and each carries its own end
-- date. Every entry is self-expiring the same way: readers compare `until`
-- against "today" (the CLIENT's local date), so nothing needs a cleanup job.
--
--   [{ "area": "Knee",
--      "level": "ease" | "avoid",
--      "until": "2026-08-08",      -- inclusive
--      "reason": "pain 4/5 during Tuesday's run",
--      "set_at": "2026-08-01" }]
--
--   level "ease"  = keep training it, cap the intensity (no Z4/Z5, no heavy
--                   loading of the area).
--   level "avoid" = do not load that area at all; the affected sport is
--                   dropped from what plan-week may schedule.
--
-- Consumed by _shared/injury.ts (the single seam), which feeds generate-workout
-- (via reviewWorkout's structural strip) and plan-week (via the sports list).
--
-- The FOLLOW-UP state itself needs no columns: it lives on each entry in
-- user_profiles.onboarding.injuries (already jsonb) as raised_at / last_checked
-- / status, so injuriesOf() in _shared/profile.ts stays the one reader for both
-- old and new profiles.
-- ============================================================================

alter table user_profiles
  add column if not exists injury_backoff jsonb not null default '[]'::jsonb;

comment on column user_profiles.injury_backoff is
  'Active per-area injury backoffs: [{area, level: ease|avoid, until (inclusive date), reason, set_at}]. Self-expiring: every reader compares `until` against "today". Structured on purpose, so it overrides generation the way training_paused_until overrides the schedule.';

-- Post-workout pain, scoped to the session it was felt in. Same 1-5 shape as
-- the morning wellness check-in's soreness scale (1 = none, 5 = sharp) so the
-- athlete only ever learns one scale; pain_area names WHICH injury on file the
-- answer was about, since a session can involve more than one.
alter table workout_feedback
  add column if not exists pain_score int2 check (pain_score between 1 and 5),
  add column if not exists pain_area text;

comment on column workout_feedback.pain_score is
  'Post-workout pain in an injured area, 1-5 (1 = none, 5 = sharp). Same scale as wellness_checkins.soreness. NULL = not asked or not answered.';
comment on column workout_feedback.pain_area is
  'Which injury area pain_score refers to, matching an entry in onboarding.injuries.';
