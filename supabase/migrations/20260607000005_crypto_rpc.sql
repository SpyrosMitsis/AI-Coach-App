-- ============================================================================
-- Crypto RPCs callable only with the service_role key (revoked from anon +
-- authenticated). Edge Functions pass the ENCRYPTION_KEY secret as p_key so the
-- symmetric key never has to live in the database.
-- ============================================================================

create or replace function encrypt_for_app(p_key text, p_plaintext text)
returns text
language sql
volatile
security definer
set search_path = extensions, public
as $$
  select case
    when p_plaintext is null or p_plaintext = '' then null
    else encode(pgp_sym_encrypt(p_plaintext, p_key), 'base64')
  end;
$$;

create or replace function decrypt_for_app(p_key text, p_ciphertext text)
returns text
language sql
volatile
security definer
set search_path = extensions, public
as $$
  select case
    when p_ciphertext is null or p_ciphertext = '' then null
    else pgp_sym_decrypt(decode(p_ciphertext, 'base64'), p_key)
  end;
$$;

-- Lock these down: clients must never call them.
revoke all on function encrypt_for_app(text, text) from public, anon, authenticated;
revoke all on function decrypt_for_app(text, text) from public, anon, authenticated;
grant execute on function encrypt_for_app(text, text) to service_role;
grant execute on function decrypt_for_app(text, text) to service_role;
