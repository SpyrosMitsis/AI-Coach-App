// coach-brief — the coach's proactive daily note for the dashboard. One short,
// human, coach-voice sentence-or-two that interprets today (readiness + Form +
// what's planned) so the coach speaks UNPROMPTED, not only inside chat.
//
// POST { date?: "YYYY-MM-DD" } — the client's LOCAL date (same convention as
// daily-summary). The client requests this lazily on Home open and caches the
// result per-day, so at most one generation happens per calendar day.
//
// Returns { brief, date, provider, disabled? }. Best-effort: any failure yields
// { brief: null } and Home falls back to its static readiness headline.

import { errorStatus, handleOptions, json } from "../_shared/cors.ts";
import { adminClient, getUserId } from "../_shared/supabase.ts";
import { llmAccess } from "../_shared/llm_keys.ts";
import { customPriceFromProfile, estimateCostUsd, llmGenerateWithFallback } from "../_shared/llm.ts";
import { computeRecovery } from "../_shared/recovery.ts";
import { BRIEF_SYSTEM, buildBriefPrompt, trainingPhase } from "../_shared/prompt.ts";
import { memoryDocsBlock, memoryFromProfile } from "../_shared/agent_memory.ts";
import { applyFallbackFitness } from "../_shared/load.ts";

const DAY = 86_400_000;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

declare const EdgeRuntime: { waitUntil?: (p: Promise<unknown>) => void };
function waitUntil(p: Promise<unknown>) {
  try {
    if (typeof EdgeRuntime !== "undefined" && EdgeRuntime?.waitUntil) EdgeRuntime.waitUntil(p);
  } catch { /* local dev runtime */ }
}

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const admin = adminClient();
    const body = await req.json().catch(() => ({}));
    const qDate = new URL(req.url).searchParams.get("date");
    const clientDate = [body?.date, qDate].find((d) => typeof d === "string" && ISO_DATE.test(d));
    const today = (clientDate as string | undefined) ?? new Date().toISOString().slice(0, 10);
    const since14 = new Date(Date.now() - 14 * DAY).toISOString().slice(0, 10);
    const since7 = new Date(Date.now() - 7 * DAY).toISOString().slice(0, 10);

    const { data: profile } = await admin
      .from("user_profiles")
      .select("display_name, onboarding, active_llm_provider, llm_fallback_chain, coach_knowledge, training_memory, coach_soul, coach_soul_updated_at, llm_custom_input_per_1m, llm_custom_output_per_1m, plan, plan_expires_at, use_hosted_ai")
      .eq("id", userId)
      .single();
    const onboarding = (profile?.onboarding ?? {}) as Record<string, unknown>;

    // Opt-out: the briefing toggle defaults ON; only `briefing === false` disables.
    if (onboarding.briefing === false) return json({ brief: null, date: today, disabled: true });

    const [{ data: wellness }, { data: activities }, { data: planned }] = await Promise.all([
      admin.from("wellness_checkins").select("date, energy, soreness, sleep_score, hrv_rmssd, resting_hr, zepp_sleep_minutes")
        .eq("user_id", userId).gte("date", since14).order("date", { ascending: false }),
      admin.from("completed_activities").select("date, type, tss, ctl, atl")
        .eq("user_id", userId).gte("date", since14).order("date", { ascending: true }),
      admin.from("planned_workouts").select("type, completed, skipped, workout_json, created_at")
        .eq("user_id", userId).eq("date", today),
    ]);

    // No intervals-provided CTL in the window? Fill estimated values from
    // stored TSS so Form still reads for athletes without intervals.icu.
    const acts = await applyFallbackFitness(admin, userId, today, activities ?? []);
    const wells = wellness ?? [];
    const isNum = (v: unknown): v is number => typeof v === "number";

    // Readiness — same composite the dashboard shows, anchored on today so the
    // brief never narrates yesterday's HRV/sleep as if it were today's.
    const chrono = [...wells].reverse();
    const dated = (key: "hrv_rmssd" | "resting_hr") =>
      chrono.filter((w) => isNum((w as Record<string, unknown>)[key]))
        .map((w) => ({ date: (w as { date?: string }).date, value: (w as Record<string, number>)[key] }));
    const recovery = computeRecovery(wells, dated("hrv_rmssd"), dated("resting_hr"), today);

    // Form (TSB) now + direction over the last week.
    const tsbSeries = acts.filter((a) => a.ctl != null && a.atl != null)
      .map((a) => ({ date: a.date as string, tsb: (a.ctl ?? 0) - (a.atl ?? 0) }));
    const tsbNow = tsbSeries.length ? tsbSeries[tsbSeries.length - 1].tsb : 0;
    const tsbPrev = tsbSeries.find((p) => p.date >= since7)?.tsb ?? tsbSeries[0]?.tsb ?? tsbNow;
    const tsbDelta = tsbNow - tsbPrev;
    const tsbTrend = tsbDelta > 3 ? "rising" : tsbDelta < -3 ? "falling" : "flat";

    // Today's primary session (same precedence as Home/Calendar).
    const primary = [...(planned ?? [])].sort((x, y) => {
      const xs = !!x.completed || !!x.skipped, ys = !!y.completed || !!y.skipped;
      if (xs !== ys) return xs ? 1 : -1;
      const xr = x.type === "rest" ? 1 : 0, yr = y.type === "rest" ? 1 : 0;
      if (xr !== yr) return xr - yr;
      return String(y.created_at ?? "").localeCompare(String(x.created_at ?? ""));
    })[0] ?? null;
    const todayPlan = primary
      ? `${(primary.workout_json as { title?: string })?.title ?? primary.type} (${primary.type})`
      : "nothing planned yet";

    // Weekly load vs target.
    const weeklyTss = acts.filter((a) => (a.date ?? "") >= since7).reduce((s, a) => s + (a.tss ?? 0), 0);
    const targetTss = (onboarding as { weekly_tss_target?: number }).weekly_tss_target ?? 350;
    const weeklyLoadPct = targetTss > 0 ? Math.round((weeklyTss / targetTss) * 100) : null;

    let weeksToGoal: number | null = null;
    if (onboarding.goal_date) {
      const d = (new Date(String(onboarding.goal_date)).getTime() - new Date(today + "T12:00:00").getTime()) / (7 * DAY);
      weeksToGoal = d >= 0 ? Math.round(d) : null;
    }

    // Did any objective recovery signal sync for today? If not, the brief should
    // go off subjective feel rather than stating recovery as fact.
    const objectiveData = recovery.hrv?.latest != null ||
      recovery.rhr?.latest != null || recovery.sleep?.hours != null;

    const userPrompt = buildBriefPrompt({
      name: (profile?.display_name as string) ?? "athlete",
      readiness: recovery.score,
      band: recovery.band,
      tsb: tsbNow,
      tsbTrend,
      todayPlan,
      todayDone: !!primary?.completed,
      phase: trainingPhase(weeksToGoal),
      goal: (onboarding.goal as string) ?? "general fitness",
      weeklyLoadPct,
      objectiveData,
    });
    // Lead with the coach's soul/voice + durable knowledge so the note sounds
    // like a continuation of this relationship, not a generic tip.
    const systemPrompt = BRIEF_SYSTEM + memoryDocsBlock(memoryFromProfile(profile));

    const { chain, resolveKey, resolveModel, resolveBaseUrl, hosted } = await llmAccess(admin, userId, profile);
    if (chain.length === 0) return json({ brief: null, date: today });

    const outcome = await llmGenerateWithFallback(
      chain,
      { prompt: userPrompt, systemPrompt, jsonMode: false },
      resolveKey,
      resolveModel,
      resolveBaseUrl,
    );
    const brief = outcome.text.trim().replace(/^["']|["']$/g, "").slice(0, 400);

    const cost = estimateCostUsd(outcome.provider, outcome.promptTokens, outcome.completionTokens, customPriceFromProfile(outcome.provider, profile));
    waitUntil((async () => {
      try {
        await admin.from("generation_logs").insert({
          user_id: userId,
          feature: "brief",
          hosted,
          provider: outcome.provider,
          model: outcome.model,
          prompt_tokens: outcome.promptTokens,
          completion_tokens: outcome.completionTokens,
          estimated_cost_usd: cost,
          parsed_ok: true,
        });
      } catch { /* best effort */ }
    })());

    return json({ brief, date: today, provider: outcome.provider });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, errorStatus(e));
  }
});
