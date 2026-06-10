-- Persistent coaching knowledge: durable, free-text facts the coach should
-- always honour when generating workouts and weekly plans — injuries, equipment
-- the athlete does/doesn't have, scheduling constraints, exercise preferences
-- and dislikes. The coach-chat function maintains this automatically from the
-- conversation, and the athlete can edit it directly in Settings.
alter table user_profiles
  add column if not exists coach_knowledge text;

comment on column user_profiles.coach_knowledge is
  'Durable athlete constraints/preferences (injuries, equipment, schedule, dislikes) — a bullet list the LLM honours and maintains from chat.';
