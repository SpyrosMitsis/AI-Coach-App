-- Custom (bring-your-own) OpenAI-compatible LLM provider.
--
-- Adds a per-key base URL (only set for provider = 'custom') and widens the
-- provider CHECK constraint to allow 'custom'. The model id reuses the existing
-- user_profiles.llm_models JSONB override; the key reuses llm_api_keys. Nothing
-- else needs new schema — the request path is otherwise unchanged.

alter table llm_api_keys
  add column if not exists base_url text;

alter table llm_api_keys
  drop constraint if exists llm_api_keys_provider_check;

alter table llm_api_keys
  add constraint llm_api_keys_provider_check
  check (provider in ('anthropic','deepseek','openai','gemini','groq','custom'));
