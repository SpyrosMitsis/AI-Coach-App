// ============================================================================
// Hosted-AI entitlement — who may run on the owner's LLM key.
//
// Hosted AI is a *capability of the deployment*, not of the app: it exists
// only when the operator has set the HOSTED_LLM_* secrets. A self-hosted
// stack without them advertises `hosted_ai: false` in daily-summary and no
// Pro UI ever appears — no app-side constants, no forks.
// ============================================================================

import type { LlmProvider } from "./types.ts";
import { PROVIDERS } from "./llm.ts";
import { logger } from "./log.ts";

const log = logger("entitlement");

export interface HostedLlm {
  provider: LlmProvider;
  key: string;
  // Operator-chosen model; undefined → provider default.
  model?: string;
}

export interface PlanFields {
  plan?: string | null;
  plan_expires_at?: string | null;
  use_hosted_ai?: boolean | null;
}

// The deployment's hosted-LLM config, or null when hosted AI is unavailable
// (unset, misconfigured, or explicitly killed via HOSTED_AI_DISABLED).
export function hostedLlm(): HostedLlm | null {
  if (Deno.env.get("HOSTED_AI_DISABLED")) return null;
  const provider = Deno.env.get("HOSTED_LLM_PROVIDER") as LlmProvider | undefined;
  const key = Deno.env.get("HOSTED_LLM_KEY");
  if (!provider || !key) return null;
  // "custom" needs a per-key base URL we don't have here; restrict the hosted
  // path to registry providers so a typo can't silently route nowhere.
  if (!(provider in PROVIDERS) || provider === "custom") return null;
  const model = Deno.env.get("HOSTED_LLM_MODEL")?.trim();
  // The €5/mo quota math assumes a flash/mini-class model (~$0.01/call). An
  // opus-class model burns a day's allowance in one agentic turn. Warn, don't
  // block: a self-hoster serving family on a strong model is legitimate.
  if (model && /opus|o3|gpt-5(?!-mini|-nano)/i.test(model)) {
    log.error("HOSTED_LLM_MODEL looks expensive for the default quota caps", { model });
  }
  return { provider, key, model: model || undefined };
}

// Belt-and-braces expiry: RTDN normally flips the plan column, but if a
// notification is lost, an expired Pro simply stops being entitled here.
export function isPro(profile: PlanFields | null | undefined): boolean {
  if (profile?.plan !== "pro") return false;
  const exp = profile.plan_expires_at;
  return !exp || new Date(exp).getTime() > Date.now();
}

// The full gate: Pro user, toggle on (default on), deployment configured.
export function wantsHostedAi(profile: PlanFields | null | undefined): boolean {
  return isPro(profile) && (profile?.use_hosted_ai ?? true) && hostedLlm() !== null;
}
