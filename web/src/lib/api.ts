"use client";

import { createClient } from "./supabase-browser";
import type {
  DailySummary,
  LlmProvider,
  Workout,
} from "@shared/types";

// Thin wrapper over supabase.functions.invoke that surfaces the JSON error
// bodies our Edge Functions return.
async function invoke<T>(name: string, body?: unknown): Promise<T> {
  const supabase = createClient();
  const { data, error } = await supabase.functions.invoke(name, { body: body ?? {} });
  if (error) {
    // functions.invoke wraps non-2xx; try to read the JSON body for detail.
    const ctx = (error as unknown as { context?: Response }).context;
    if (ctx) {
      try {
        const parsed = await ctx.json();
        throw new Error(parsed.error ?? parsed.detail ?? error.message);
      } catch {
        /* fall through */
      }
    }
    throw new Error(error.message);
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
    type?: "run" | "strength" | "auto";
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
