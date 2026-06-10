"use client";

import { createClient } from "./supabase-browser";
import type {
  DailySummary,
  LlmProvider,
  Workout,
} from "@shared/types";

// Translate raw edge-function/provider errors into something a human can act
// on. Falls through to the raw message for anything unrecognized.
export function humanizeError(raw: string): string {
  const m = raw.toLowerCase();
  if (m.includes("no ai provider configured") || m.includes("no llm key")) {
    return "No AI provider is set up yet — add an API key in Settings → LLM.";
  }
  if (m.includes("all providers in fallback chain failed")) {
    return "All of your AI providers failed — check your API keys in Settings → LLM (a key may be invalid or out of credit).";
  }
  if (m.includes("http 401") || m.includes("invalid api key") || m.includes("invalid x-api-key")) {
    return "An API key was rejected — re-check it in Settings.";
  }
  if (m.includes("http 429") || m.includes("rate limit")) {
    return "The AI provider is rate-limiting you — wait a minute and try again, or switch the active provider.";
  }
  if (m.includes("could not parse workout")) {
    return "The AI returned something unusable this time — try generating again (this usually works on retry).";
  }
  if (m.includes("intervals") && (m.includes("403") || m.includes("401"))) {
    return "Intervals.icu rejected your credentials — re-connect it in Settings.";
  }
  if (m.includes("profile not found")) {
    return "Your profile isn't set up yet — finish onboarding first.";
  }
  if (m.includes("failed to fetch") || m.includes("network")) {
    return "Network problem — check your connection and try again.";
  }
  return raw;
}

// Thin wrapper over supabase.functions.invoke that surfaces the JSON error
// bodies our Edge Functions return.
async function invoke<T>(name: string, body?: unknown): Promise<T> {
  const supabase = createClient();
  const { data, error } = await supabase.functions.invoke(name, { body: body ?? {} });
  if (error) {
    // functions.invoke wraps non-2xx; try to read the JSON body for detail.
    const ctx = (error as unknown as { context?: Response }).context;
    let detail: string | null = null;
    if (ctx) {
      try {
        const parsed = await ctx.json();
        detail = String(parsed.error ?? parsed.detail ?? "") || null;
      } catch { /* non-JSON error body */ }
    }
    throw new Error(humanizeError(detail ?? error.message));
  }
  return data as T;
}

export const api = {
  dailySummary: () => invoke<DailySummary>("daily-summary"),

  syncIntervals: () => invoke<{ activities_synced: number; ctl: number; atl: number; tsb: number }>("sync-intervals"),

  connectIntervals: (athleteId: string, apiKey: string) =>
    invoke<{ ok: boolean; athlete_name: string; athlete_id: string }>("connect-intervals", { athleteId, apiKey }),

  generateWorkout: (args: {
    date?: string;
    type?: "run" | "ride" | "strength" | "auto";
    duration?: number;
    push?: boolean;
    request?: string; // free-text "Friday social 10k with friends"
    lock?: boolean; // pin it so re-planning won't touch it
  }) =>
    invoke<{
      workout: Workout;
      workout_id: string;
      provider: LlmProvider;
      model: string;
      estimated_cost_usd: number;
      intervals_event_id: string | null;
      push_error: string | null;
    }>("generate-workout", args),

  planWeek: (startDate?: string) =>
    invoke<{ planned?: number; week?: unknown; error?: string }>("plan-week", { start_date: startDate }),

  planBlock: (opts?: { startDate?: string; weeks?: number; pushWeeks?: number }) =>
    invoke<{ weeks: number; weeks_planned: number; pushed_weeks: number; start_date: string; end_date: string }>(
      "plan-block",
      { start_date: opts?.startDate, weeks: opts?.weeks, push_weeks: opts?.pushWeeks },
    ),

  pushWorkout: (workout_id: string) =>
    invoke<{ ok: boolean; intervals_event_id: string }>("push-workout", { workout_id }),

  coachChat: (messages: { role: "user" | "assistant"; content: string }[], conversationId?: string | null) =>
    invoke<{ reply: string; conversation_id: string | null; provider: string; tools_used?: string[] }>(
      "coach-chat",
      { messages, mode: "chat", conversationId: conversationId ?? undefined, purpose: "plan" },
    ),

  moveWorkout: (workout_id: string, new_date: string) =>
    invoke<{ ok: boolean; event_moved: boolean }>("move-workout", { workout_id, new_date }),

  testLlmKey: (provider: LlmProvider, apiKey: string, sampleGeneration = false) =>
    invoke<{
      provider: LlmProvider;
      model: string;
      is_valid: boolean;
      error: string | null;
      sample: string | null;
      estimated_cost_usd: number;
    }>("test-llm-key", { provider, apiKey, sampleGeneration }),
};
