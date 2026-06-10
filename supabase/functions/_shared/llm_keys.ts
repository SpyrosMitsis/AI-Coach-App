// ============================================================================
// User LLM provider chain + key resolution, shared by every function that
// calls llmGenerateWithFallback. Builds [active, ...fallback] from the profile
// and a memoized resolver that decrypts llm_api_keys rows on demand.
// ============================================================================

import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { decryptSecret } from "./supabase.ts";
import type { LlmProvider } from "./types.ts";

export interface LlmAccess {
  chain: LlmProvider[];
  resolveKey: (provider: LlmProvider) => Promise<string | null>;
}

interface ProfileLlmFields {
  active_llm_provider?: LlmProvider | null;
  llm_fallback_chain?: LlmProvider[] | null;
}

export function llmAccess(
  admin: SupabaseClient,
  userId: string,
  profile: ProfileLlmFields | null | undefined,
): LlmAccess {
  const chain: LlmProvider[] = [
    profile?.active_llm_provider,
    ...(profile?.llm_fallback_chain ?? []),
  ].filter(Boolean) as LlmProvider[];

  const keyCache = new Map<LlmProvider, string | null>();
  const resolveKey = async (provider: LlmProvider): Promise<string | null> => {
    if (keyCache.has(provider)) return keyCache.get(provider)!;
    const { data } = await admin
      .from("llm_api_keys")
      .select("api_key_encrypted")
      .eq("user_id", userId)
      .eq("provider", provider)
      .maybeSingle();
    const key = data?.api_key_encrypted
      ? await decryptSecret(admin, data.api_key_encrypted)
      : null;
    keyCache.set(provider, key);
    return key;
  };

  return { chain, resolveKey };
}
