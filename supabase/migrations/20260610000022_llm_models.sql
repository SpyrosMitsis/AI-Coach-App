-- Dynamic model selector: per-provider model override chosen by the user
-- (e.g. {"anthropic": "claude-sonnet-4-20250514", "groq": "llama-3.3-70b-versatile"}).
-- Empty object → every provider uses its default model.
alter table user_profiles
  add column if not exists llm_models jsonb not null default '{}'::jsonb;
