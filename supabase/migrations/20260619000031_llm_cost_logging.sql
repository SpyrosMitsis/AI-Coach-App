-- ----------------------------------------------------------------------------
-- LLM cost logging — make spend visible so the user can choose a provider.
--   * tag each generation_logs row by feature (chat | workout | plan) and record
--     the coach tools used, so the diagnostics screen can break spend down.
--   * let the user price their custom (bring-your-own) provider — built-in
--     providers have known pricing, but a self-hosted/BYO endpoint defaults to $0
--     and hides real spend.
-- ----------------------------------------------------------------------------
alter table generation_logs add column if not exists feature text;
alter table generation_logs add column if not exists tools_used text[];

alter table user_profiles add column if not exists llm_custom_input_per_1m float4;
alter table user_profiles add column if not exists llm_custom_output_per_1m float4;
