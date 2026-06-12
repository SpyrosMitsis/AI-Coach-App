// test-llm-key — validate a provider API key with a minimal call, persist the
// (encrypted) key + validity, and optionally return a sample generation.
//
// POST { provider, apiKey, sampleGeneration?: boolean }

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

    const { provider, apiKey, sampleGeneration } = await req.json();
    if (!provider || !(provider in PROVIDERS)) {
      return json({ error: "invalid provider" }, 400);
    }
    if (!apiKey) return json({ error: "apiKey required" }, 400);

    const admin = adminClient();
    const p = provider as LlmProvider;

    let isValid = false;
    let sample: string | null = null;
    let error: string | null = null;
    let cost = 0;

    try {
      const result = await llmGenerate(p, {
        apiKey,
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
    // hint (start + end of the key) lets Settings show WHICH key is saved.
    const encrypted = await encryptSecret(admin, apiKey);
    const row = {
      user_id: userId,
      provider: p,
      api_key_encrypted: encrypted,
      is_valid: isValid,
      last_tested_at: new Date().toISOString(),
    };
    const { error: upErr } = await admin.from("llm_api_keys").upsert(
      { ...row, key_hint: maskKey(apiKey) },
      { onConflict: "user_id,provider" },
    );
    // Pre-migration-27 fallback: no key_hint column yet.
    if (upErr) await admin.from("llm_api_keys").upsert(row, { onConflict: "user_id,provider" });

    return json({
      provider: p,
      model: PROVIDERS[p].model,
      is_valid: isValid,
      error,
      sample,
      estimated_cost_usd: cost,
    });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
