-- Masked credential hints, so Settings can show WHICH key is configured
-- without ever exposing the secret (e.g. "sk-an••••3kQx"). Written by
-- test-llm-key / connect-intervals at save time; old keys saved before this
-- migration simply show a generic "••••••••" until re-saved.

alter table llm_api_keys
  add column if not exists key_hint text;

alter table user_profiles
  add column if not exists intervals_api_key_hint text;
