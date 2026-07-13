-- Hermes-style coach memory: the third long-term "document" (soul.md) plus the
-- running conversation summary that powers smart context compression.
--
-- The coach already keeps two durable docs on the profile:
--   coach_knowledge  ≈ user.md   (durable facts/constraints)
--   training_memory  ≈ memory.md (rolling episodic notes)
-- This adds:
--   coach_soul       ≈ soul.md   (the coach's identity/voice + its evolving
--                                 relationship with this athlete)
-- soul evolves SLOWLY: coach_soul_updated_at gates how often it may be rewritten.
alter table user_profiles
  add column if not exists coach_soul text,
  add column if not exists coach_soul_updated_at timestamptz;

comment on column user_profiles.coach_soul is
  'soul.md — the coach''s identity/voice and its slowly-evolving relationship narrative with the athlete. Seeded from a default persona, then deepened conservatively (time-gated by coach_soul_updated_at).';
comment on column user_profiles.coach_soul_updated_at is
  'When coach_soul was last auto-evolved; used to throttle soul updates to a slow cadence.';

-- Running summary of turns that have been compressed out of the active window,
-- so long coaching threads keep their context without resending every token.
alter table coach_conversations
  add column if not exists summary text;

comment on column coach_conversations.summary is
  'Rolling LLM summary of earlier turns dropped from the model''s active context window — injected as background so long threads stay coherent without linear token growth.';
