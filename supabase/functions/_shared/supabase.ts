// ============================================================================
// Server-side Supabase clients + secret encryption helpers.
//
// `adminClient()` uses the service_role key and bypasses RLS — use it only in
// trusted server code after you have resolved the acting user id.
//
// Secrets (Intervals + LLM keys) are encrypted/decrypted via the SQL helpers
// `encrypt_secret` / `decrypt_secret`, which read the symmetric key from the
// `app.encryption_key` GUC. We set that GUC per-call from the ENCRYPTION_KEY
// Edge Function secret so the key never lives in the database.
// ============================================================================

import { createClient, SupabaseClient } from "jsr:@supabase/supabase-js@2";

export function adminClient(): SupabaseClient {
  return createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false } },
  );
}

// Resolve the user id from the caller's JWT (Authorization: Bearer <jwt>).
export async function getUserId(req: Request): Promise<string | null> {
  const authHeader = req.headers.get("Authorization");
  if (!authHeader) return null;
  const client = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader } }, auth: { persistSession: false } },
  );
  const { data, error } = await client.auth.getUser();
  if (error || !data.user) return null;
  return data.user.id;
}

function encKey(): string {
  const k = Deno.env.get("ENCRYPTION_KEY");
  if (!k) throw new Error("ENCRYPTION_KEY env var is not set");
  return k;
}

// Encrypt a plaintext secret via the DB (pgcrypto). Returns base64 ciphertext.
export async function encryptSecret(admin: SupabaseClient, plaintext: string): Promise<string> {
  const { data, error } = await admin.rpc("encrypt_for_app", {
    p_key: encKey(),
    p_plaintext: plaintext,
  });
  if (error) throw new Error(`encryptSecret: ${error.message}`);
  return data as string;
}

export async function decryptSecret(admin: SupabaseClient, ciphertext: string): Promise<string | null> {
  if (!ciphertext) return null;
  const { data, error } = await admin.rpc("decrypt_for_app", {
    p_key: encKey(),
    p_ciphertext: ciphertext,
  });
  if (error) throw new Error(`decryptSecret: ${error.message}`);
  return data as string | null;
}
