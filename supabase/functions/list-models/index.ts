// list-models — dynamic model discovery for the settings model selector.
//
// POST { provider: "anthropic" | "deepseek" | "openai" | "gemini" | "groq" }
//
// Queries the provider's own /models endpoint with the user's stored API key
// and returns the chat-capable model ids, plus the provider default and the
// user's current override (user_profiles.llm_models).

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, getUserId } from "../_shared/supabase.ts";
import { PROVIDERS } from "../_shared/llm.ts";
import { llmAccess } from "../_shared/llm_keys.ts";
import type { LlmProvider } from "../_shared/types.ts";

// Drop non-chat models (embeddings, audio, images, moderation…) so the picker
// only offers ids that will actually work for generation.
const NON_CHAT = /embed|whisper|audio|tts|dall-e|image|moderation|realtime|transcribe|guard|aqa|imagen|veo/i;

async function fetchModels(provider: LlmProvider, apiKey: string, baseUrl?: string | null): Promise<string[]> {
  switch (provider) {
    case "anthropic": {
      const res = await fetch("https://api.anthropic.com/v1/models?limit=100", {
        headers: { "x-api-key": apiKey, "anthropic-version": "2023-06-01" },
      });
      if (!res.ok) throw new Error(`anthropic HTTP ${res.status}: ${await res.text()}`);
      const data = await res.json();
      return (data.data ?? []).map((m: { id: string }) => m.id);
    }
    case "gemini": {
      const res = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=${apiKey}`,
      );
      if (!res.ok) throw new Error(`gemini HTTP ${res.status}: ${await res.text()}`);
      const data = await res.json();
      return (data.models ?? [])
        .filter((m: { supportedGenerationMethods?: string[] }) =>
          m.supportedGenerationMethods?.includes("generateContent")
        )
        .map((m: { name: string }) => m.name.replace(/^models\//, ""))
        .filter((id: string) => !NON_CHAT.test(id));
    }
    // OpenAI-compatible /models endpoints.
    default: {
      const base = provider === "openai"
        ? "https://api.openai.com/v1"
        : provider === "deepseek"
        ? "https://api.deepseek.com/v1"
        : provider === "openrouter"
        ? "https://openrouter.ai/api/v1"
        : provider === "custom"
        ? (baseUrl ?? "")
        : "https://api.groq.com/openai/v1";
      if (provider === "custom" && !base) return [];
      const res = await fetch(`${base}/models`, {
        headers: { Authorization: `Bearer ${apiKey}` },
      });
      if (!res.ok) throw new Error(`${provider} HTTP ${res.status}: ${await res.text()}`);
      const data = await res.json();
      let ids = (data.data ?? []).map((m: { id: string }) => m.id)
        .filter((id: string) => !NON_CHAT.test(id));
      if (provider === "openai") {
        // OpenAI lists dozens of non-chat ids; keep the chat families.
        ids = ids.filter((id: string) => /^(gpt-|o\d|chatgpt)/.test(id));
      }
      return ids;
    }
  }
}

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const body = await req.json().catch(() => ({}));
    const provider = body.provider as LlmProvider;
    if (!provider || !(provider in PROVIDERS)) {
      return json({ error: `unknown provider: ${provider}` }, 400);
    }

    const admin = adminClient();
    const { data: profile } = await admin
      .from("user_profiles")
      .select("active_llm_provider, llm_fallback_chain, llm_models")
      .eq("id", userId)
      .single();

    const { resolveKey, resolveModel, resolveBaseUrl } = llmAccess(admin, userId, profile);
    const apiKey = await resolveKey(provider);
    if (!apiKey) {
      return json({
        provider,
        default_model: PROVIDERS[provider].model,
        current: resolveModel(provider) ?? null,
        models: [],
        error: "No API key saved for this provider yet.",
      });
    }

    const baseUrl = provider === "custom" ? await resolveBaseUrl(provider) : null;
    const models = (await fetchModels(provider, apiKey, baseUrl)).sort();
    return json({
      provider,
      default_model: PROVIDERS[provider].model,
      current: resolveModel(provider) ?? null,
      models,
    });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
