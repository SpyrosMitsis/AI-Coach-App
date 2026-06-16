// test-llm-key — validate a provider API key with a minimal call, persist the
// (encrypted) key + validity, and optionally return a sample generation.
//
// POST { provider, apiKey, sampleGeneration?: boolean, baseUrl?, model? }
// baseUrl + model are required for the "custom" (OpenAI-compatible) provider.

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, encryptSecret, getUserId } from "../_shared/supabase.ts";
import { estimateCostUsd, llmGenerate, PROVIDERS } from "../_shared/llm.ts";
import { maskKey } from "../_shared/mask.ts";
import type { LlmProvider } from "../_shared/types.ts";

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const { provider, apiKey, sampleGeneration, baseUrl, model } = await req.json();
    if (!provider || !(provider in PROVIDERS)) {
      return json({ error: "invalid provider" }, 400);
    }
    if (!apiKey) return json({ error: "apiKey required" }, 400);

    const admin = adminClient();
    const p = provider as LlmProvider;

    // The custom provider is a user-supplied OpenAI-compatible endpoint, so it
    // needs an explicit base URL + model id (there are no built-in defaults).
    const customBaseUrl = typeof baseUrl === "string" ? baseUrl.trim().replace(/\/+$/, "") : "";
    const customModel = typeof model === "string" ? model.trim() : "";
    if (p === "custom") {
      if (!/^https?:\/\//i.test(customBaseUrl)) {
        return json({ error: "custom provider needs a base URL starting with http(s)://" }, 400);
      }
      if (!customModel) return json({ error: "custom provider needs a model id" }, 400);
    }

    let isValid = false;
    let sample: string | null = null;
    let error: string | null = null;
    let cost = 0;

    try {
      const result = await llmGenerate(p, {
        apiKey,
        model: p === "custom" ? customModel : undefined,
        baseUrl: p === "custom" ? customBaseUrl : undefined,
        systemPrompt: "You are a connection tester. Reply with valid JSON only.",
        prompt: sampleGeneration
          ? 'Return a tiny sample workout as JSON: {"type":"rest","title":"Test OK","duration_minutes":0,"tss_estimate":0,"rpe_target":1,"sections":[],"coach_note":"Connection works."}'
          : 'Reply with exactly: {"ok": true}',
      });
      isValid = true;
      sample = result.text;
      cost = estimateCostUsd(p, result.promptTokens, result.completionTokens);
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
    }

    // Persist key (encrypted) + validity, upsert on (user, provider). A masked
    // hint (start + end of the key) lets Settings show WHICH key is saved; the
    // custom provider also stores its base URL on the row.
    const encrypted = await encryptSecret(admin, apiKey);
    const row: Record<string, unknown> = {
      user_id: userId,
      provider: p,
      api_key_encrypted: encrypted,
      is_valid: isValid,
      last_tested_at: new Date().toISOString(),
    };
    const rich = {
      ...row,
      key_hint: maskKey(apiKey),
      ...(p === "custom" ? { base_url: customBaseUrl } : {}),
    };
    const { error: upErr } = await admin.from("llm_api_keys").upsert(rich, { onConflict: "user_id,provider" });
    // Pre-migration fallback: key_hint (27) / base_url (28) columns may not
    // exist yet. Custom REQUIRES base_url, so surface a clear error there.
    if (upErr) {
      if (p === "custom") {
        return json({ error: `could not save custom provider (run DB migrations): ${upErr.message}` }, 500);
      }
      await admin.from("llm_api_keys").upsert(row, { onConflict: "user_id,provider" });
    }

    return json({
      provider: p,
      model: p === "custom" ? customModel : PROVIDERS[p].model,
      is_valid: isValid,
      error,
      sample,
      estimated_cost_usd: cost,
    });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
