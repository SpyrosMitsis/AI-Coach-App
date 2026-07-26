-- Structured training pause: lets the coach (via chat) record a date-bound
-- "stop training" window (travel, illness, work crunch) with the same
-- authority as a locked session or a weekly-recurring rest day, instead of
-- relying on the LLM to notice a free-text coach_knowledge fact when it
-- later plans a week. See set_training_pause/resume_training in
-- _shared/coach_tools.ts and plan-week's dayList.available computation.
--
-- training_paused_until is inclusive and self-expiring: every consumer
-- compares it against "today", so once the date passes it stops applying
-- with no cleanup job required.
alter table user_profiles
  add column if not exists training_paused_until date,
  add column if not exists training_pause_reason text;

comment on column user_profiles.training_paused_until is
  'Last day (inclusive) of an athlete-declared training pause (travel/illness/etc). NULL = no active pause. Self-expiring: compared against "today" by every reader.';
comment on column user_profiles.training_pause_reason is
  'Optional free-text reason for the active training_paused_until window, e.g. "travel to Italy".';
