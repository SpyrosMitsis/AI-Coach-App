-- OpenRouter LLM provider.
--
-- OpenRouter is an OpenAI-compatible aggregator at a fixed endpoint
-- (https://openrouter.ai/api/v1) — one key, hundreds of models. It needs no new
-- schema: the key reuses llm_api_keys, the model id reuses the existing
-- user_profiles.llm_models JSONB override (default "openrouter/auto"). The only
-- change is widening the provider CHECK constraint to allow 'openrouter'.

alter table llm_api_keys
  drop constraint if exists llm_api_keys_provider_check;

alter table llm_api_keys
  add constraint llm_api_keys_provider_check
  check (provider in ('anthropic','deepseek','openai','gemini','groq','openrouter','custom'));
