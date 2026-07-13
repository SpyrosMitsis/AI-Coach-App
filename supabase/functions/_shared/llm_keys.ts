// ============================================================================
// User LLM provider chain + key resolution, shared by every function that
// calls llmGenerateWithFallback. Builds [active, ...fallback] from the profile
// and a memoized resolver that decrypts llm_api_keys rows on demand.
//
// This is the single choke point for LLM access, so the hosted-AI (Pro) path
// lives here too: an entitled user with the hosted toggle on gets a chain of
// exactly [hosted provider] running on the operator's key — after the spend
// quota clears (QuotaError → typed 429). Everyone else is untouched.
// ============================================================================

import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { decryptSecret } from "./supabase.ts";
import type { LlmProvider } from "./types.ts";
import { hostedLlm, type PlanFields, wantsHostedAi } from "./entitlement.ts";
import { assertHostedQuota } from "./quota.ts";

export interface LlmAccess {
  chain: LlmProvider[];
  resolveKey: (provider: LlmProvider) => Promise<string | null>;
  // User-chosen model per provider (user_profiles.llm_models); undefined →
  // provider default.
  resolveModel: (provider: LlmProvider) => string | undefined;
  // OpenAI-compatible base URL for the "custom" provider (llm_api_keys.base_url);
  // null for built-in providers or when unconfigured.
  resolveBaseUrl: (provider: LlmProvider) => Promise<string | null>;
  // True when this access runs on the operator's key (Pro hosted AI) — logged
  // to generation_logs.hosted so quotas can meter it.
  hosted: boolean;
}

interface ProfileLlmFields extends PlanFields {
  active_llm_provider?: LlmProvider | null;
  llm_fallback_chain?: LlmProvider[] | null;
  llm_models?: Record<string, string> | null;
}

export interface LlmAccessOptions {
  // list-models must resolve the user's OWN keys even for Pro users — it
  // drives the BYO-key Settings UI. Everything user-facing that *generates*
  // leaves this true.
  allowHosted?: boolean;
}

export async function llmAccess(
  admin: SupabaseClient,
  userId: string,
  profile: ProfileLlmFields | null | undefined,
  opts: LlmAccessOptions = {},
): Promise<LlmAccess> {
  if ((opts.allowHosted ?? true) && wantsHostedAi(profile)) {
    // Throws QuotaError (status 429) when the user or deployment is capped.
    await assertHostedQuota(admin, userId);
    const h = hostedLlm()!;
    return {
      chain: [h.provider],
      hosted: true,
      resolveKey: (p) => Promise.resolve(p === h.provider ? h.key : null),
      resolveModel: (p) => (p === h.provider ? h.model : undefined),
      resolveBaseUrl: () => Promise.resolve(null),
    };
  }

  const chain: LlmProvider[] = [
    profile?.active_llm_provider,
    ...(profile?.llm_fallback_chain ?? []),
  ].filter(Boolean) as LlmProvider[];

  // One fetch per provider, caching both the decrypted key and the base URL
  // (base_url is only set for the "custom" provider; select is best-effort so a
  // pre-migration DB without the column still resolves the key).
  const keyCache = new Map<LlmProvider, string | null>();
  const baseUrlCache = new Map<LlmProvider, string | null>();
  const load = async (provider: LlmProvider): Promise<void> => {
    if (keyCache.has(provider)) return;
    let row: { api_key_encrypted?: string | null; base_url?: string | null } | null = null;
    const withBase = await admin
      .from("llm_api_keys")
      .select("api_key_encrypted, base_url")
      .eq("user_id", userId)
      .eq("provider", provider)
      .maybeSingle();
    if (withBase.error) {
      const fallback = await admin
        .from("llm_api_keys")
        .select("api_key_encrypted")
        .eq("user_id", userId)
        .eq("provider", provider)
        .maybeSingle();
      row = fallback.data;
    } else {
      row = withBase.data;
    }
    const key = row?.api_key_encrypted
      ? await decryptSecret(admin, row.api_key_encrypted)
      : null;
    keyCache.set(provider, key);
    baseUrlCache.set(provider, row?.base_url ?? null);
  };

  const resolveKey = async (provider: LlmProvider): Promise<string | null> => {
    await load(provider);
    return keyCache.get(provider) ?? null;
  };
  const resolveBaseUrl = async (provider: LlmProvider): Promise<string | null> => {
    await load(provider);
    return baseUrlCache.get(provider) ?? null;
  };

  const models = profile?.llm_models ?? {};
  const resolveModel = (provider: LlmProvider): string | undefined => {
    const m = models[provider];
    return typeof m === "string" && m.trim() ? m : undefined;
  };

  return { chain, resolveKey, resolveModel, resolveBaseUrl, hosted: false };
}
