// plan-week — the mature planner: generate a coherent, periodized 7-day
// microcycle in one shot (80/20 distribution, recovery spacing, weekly volume
// target, deload when fatigued), persist it idempotently to planned_workouts,
// store the rationale, and optionally push every session to Intervals.icu.
//
// Two modes:
//   - Client (user JWT): POST { start_date?, push? } → plan that user's week.
//   - Cron (service_role): POST { all_users: true } → plan NEXT week for every
//     user with user_profiles.auto_plan = true.

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, getUserId } from "../_shared/supabase.ts";
import { llmAccess } from "../_shared/llm_keys.ts";
import { estimateCostUsd, extractJson, llmGenerateWithFallback } from "../_shared/llm.ts";
import {
  buildWeekPrompt,
  trainingPhase,
  validateWeekPlan,
  WEEK_SYSTEM_PROMPT,
} from "../_shared/prompt.ts";
import { createEvent, deleteEvent, latestFitness } from "../_shared/intervals.ts";
import { renderIntervalsWorkout } from "../_shared/intervals_workout.ts";
import { adherenceBlock, executionBlock, goalBlock, intervalsPhysiology, knowledgeBlock, memoryBlock } from "../_shared/context.ts";
import { exerciseCatalogBlock, registerUnknownExercises } from "../_shared/exercise_catalog.ts";
import type { LlmProvider, Workout } from "../_shared/types.ts";
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";

const DAY = 86_400_000;
const WD = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const addDays = (iso: string, n: number) => {
  const d = new Date(iso + "T12:00:00");
  d.setDate(d.getDate() + n);
  return d.toISOString().slice(0, 10);
};
const weekdayOf = (iso: string) => WD[new Date(iso + "T12:00:00").getDay()];

function nextMonday(): string {
  const today = new Date().toISOString().slice(0, 10);
  let d = (8 - new Date(today + "T12:00:00").getDay()) % 7;
  if (d === 0) d = 7; // always the upcoming Monday, never today
  return addDays(today, d);
}

const safeJson = (text: string): unknown => {
  try { return extractJson(text); } catch { return null; }
};

async function planForUser(admin: SupabaseClient, userId: string, start: string, shouldPush: boolean) {
  const allDates = Array.from({ length: 7 }, (_, i) => addDays(start, i));
  const end = allDates[6];
  // Never touch days that have already happened: re-planning mid-week only
  // rebuilds today onward. The earlier days stay as the record of what was
  // planned (and the actuals feed the prompt via the adherence block).
  const today = new Date().toISOString().slice(0, 10);
  const dates = allDates.filter((d) => d >= today);
  if (dates.length === 0) throw new Error("that week is already over — nothing left to plan");
  const planFrom = dates[0];

  const { data: profile } = await admin.from("user_profiles").select("*").eq("id", userId).single();
  if (!profile) throw new Error("profile not found");
  const onboarding = profile.onboarding ?? {};

  const since28 = new Date(Date.now() - 28 * DAY).toISOString().slice(0, 10);
  const since14 = new Date(Date.now() - 14 * DAY).toISOString().slice(0, 10);
  const since7 = new Date(Date.now() - 7 * DAY).toISOString().slice(0, 10);
  const { data: activities } = await admin
    .from("completed_activities")
    .select("type, date, distance_m, tss, ctl, atl")
    .eq("user_id", userId).gte("date", since28).order("date", { ascending: false });
  const acts28 = activities ?? [];
  const acts14 = acts28.filter((a) => (a.date ?? "") >= since14);
  const runs = acts14.filter((a) => (a.type ?? "").toLowerCase().includes("run"));

  const load7 = acts28.filter((a) => (a.date ?? "") >= since7).reduce((s, a) => s + (a.tss ?? 0), 0);
  const load28 = acts28.reduce((s, a) => s + (a.tss ?? 0), 0);
  const acwr = load28 > 0 ? load7 / (load28 / 4) : null;

  const fitnessRow = acts14.find((a) => a.ctl != null) ?? { ctl: 0, atl: 0 };
  const fitness = latestFitness([{ id: start, ctl: fitnessRow.ctl ?? 0, atl: fitnessRow.atl ?? 0 }]);

  const { data: wellness } = await admin
    .from("wellness_checkins").select("date, energy, soreness, sleep_quality")
    .eq("user_id", userId).gte("date", since7).order("date", { ascending: false });
  const wells = wellness ?? [];
  const avg = (v: number[]) => v.length ? v.reduce((a, b) => a + b, 0) / v.length : 3;
  const wellness3d = {
    energy: avg(wells.slice(0, 3).map((w) => w.energy ?? 3)),
    soreness: avg(wells.slice(0, 3).map((w) => w.soreness ?? 3)),
    sleep: avg(wells.slice(0, 3).map((w) => w.sleep_quality ?? 3)),
  };

  let weeksToGoal: number | null = null;
  if (onboarding.goal_date) {
    const d = (new Date(onboarding.goal_date).getTime() - new Date(start + "T12:00:00").getTime()) / (7 * DAY);
    weeksToGoal = d >= 0 ? Math.round(d) : null;
  }
  const phase = trainingPhase(weeksToGoal);

  const weeklyKm = runs.filter((r) => (r.date ?? "") >= since7).reduce((s, r) => s + (r.distance_m ?? 0) / 1000, 0);
  const weeklyTssTarget = (onboarding as { weekly_tss_target?: number }).weekly_tss_target ?? 350;
  const availableDays: string[] = Array.isArray(onboarding.days) ? onboarding.days : [];

  const phys = await intervalsPhysiology(admin, profile);
  const hrZones = phys.hrZones ?? onboarding.hr_zones ?? [
    { zone: "Z1", min: 95, max: 130 }, { zone: "Z2", min: 131, max: 145 },
    { zone: "Z3", min: 146, max: 160 }, { zone: "Z4", min: 161, max: 172 },
    { zone: "Z5", min: 173, max: 190 },
  ];

  // Athlete-locked fixed sessions in this week (social runs, gym with a friend…).
  // The planner must work around them, not over them.
  const { data: lockedRows } = await admin
    .from("planned_workouts").select("date, type, workout_json")
    .eq("user_id", userId).gte("date", planFrom).lte("date", end).eq("locked", true);
  const lockedByDate = new Map((lockedRows ?? []).map((r) => [r.date as string, r]));
  const lockedBlock = lockedByDate.size === 0 ? "" :
    `\n\nFIXED SESSIONS — the athlete has LOCKED these days; DO NOT schedule anything on them. Plan the rest of the week AROUND them (respect easy/recovery before and after a hard fixed session):\n` +
    [...lockedByDate.entries()].map(([d, r]) =>
      `- ${d} (${weekdayOf(d)}): ${(r.workout_json as Workout)?.title ?? r.type} [${r.type}]`).join("\n");

  const contextBlocks =
    knowledgeBlock(profile) + memoryBlock(profile) + phys.block +
    await adherenceBlock(admin, userId, since14, start, acts28) +
    await executionBlock(admin, userId, since14) +
    goalBlock(onboarding, weeksToGoal, phase, acts28) + lockedBlock +
    await exerciseCatalogBlock(admin, userId);

  const dayList = dates.map((d) => ({
    date: d, weekday: weekdayOf(d),
    available: (availableDays.length === 0 ? true : availableDays.includes(weekdayOf(d))) && !lockedByDate.has(d),
  }));

  const userPrompt = buildWeekPrompt({
    startDate: start, dayList,
    goal: onboarding.goal ?? "General fitness",
    experience: onboarding.experience ?? "Intermediate",
    phase, tsb: fitness.tsb, ctl: fitness.ctl, atl: fitness.atl, acwr,
    wellness3d, weeklyKm, weeklyTssTarget, hrZones, contextBlocks,
  });

  const { chain, resolveKey, resolveModel } = llmAccess(admin, userId, profile);
  if (chain.length === 0) throw new Error("No AI provider configured");

  // Failures are logged to generation_logs (like generate-workout does) so a
  // "re-plan failed" in the app is diagnosable from the logs table.
  const logFailure = async (provider: string | null, raw: string | null, error: string) => {
    await admin.from("generation_logs").insert({
      user_id: userId, provider, system_prompt: WEEK_SYSTEM_PROMPT,
      user_prompt: userPrompt, raw_response: raw, parsed_ok: false, error,
    }).then(() => {}, () => {});
  };

  let outcome;
  try {
    outcome = await llmGenerateWithFallback(chain, { prompt: userPrompt, systemPrompt: WEEK_SYSTEM_PROMPT }, resolveKey, resolveModel);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    await logFailure(chain[0] ?? null, null, msg);
    throw new Error(`AI generation failed: ${msg}`);
  }
  let v = validateWeekPlan(safeJson(outcome.text));
  if (!v.ok) {
    const retry = await llmGenerateWithFallback(
      chain,
      { prompt: `${userPrompt}\n\nYOUR PREVIOUS RESPONSE was invalid (${v.error}). Return ONLY corrected JSON.`, systemPrompt: WEEK_SYSTEM_PROMPT },
      resolveKey,
      resolveModel,
    ).catch(() => null);
    if (retry) {
      const v2 = validateWeekPlan(safeJson(retry.text));
      if (v2.ok) { v = v2; outcome = retry; }
    }
  }
  if (!v.ok || !v.plan) {
    await logFailure(outcome.provider, outcome.text, `could not parse week plan: ${v.error}`);
    throw new Error(`could not parse week plan: ${v.error}`);
  }
  const plan = v.plan;

  // Idempotent replace of the whole week (clean up old watch events) — but
  // NEVER touch locked, athlete-fixed sessions.
  const { data: stale } = await admin
    .from("planned_workouts").select("id, intervals_event_id")
    .eq("user_id", userId).gte("date", planFrom).lte("date", end).eq("completed", false).eq("locked", false);
  if (stale?.length && phys.apiKey && profile.intervals_athlete_id) {
    for (const r of stale) {
      if (r.intervals_event_id) {
        try { await deleteEvent(profile.intervals_athlete_id, phys.apiKey, r.intervals_event_id); } catch (_e) { /* ignore */ }
      }
    }
  }
  await admin.from("planned_workouts").delete()
    .eq("user_id", userId).gte("date", planFrom).lte("date", end).eq("completed", false).eq("locked", false);

  // Don't insert on dates that already hold a locked session.
  const rows = dates.map((date, i) => {
    const session = plan.days[i]?.session ??
      { type: "rest", title: "Rest day", duration_minutes: 0, tss_estimate: 0, rpe_target: 0, sections: [], coach_note: "Recovery." } as Workout;
    return { user_id: userId, date, type: session.type, workout_json: session, llm_provider: outcome.provider, llm_model: outcome.model };
  }).filter((row) => !lockedByDate.has(row.date));
  const { data: inserted, error: insErr } = await admin.from("planned_workouts").insert(rows).select("id, date");
  if (insErr) throw new Error(`save failed: ${insErr.message}`);

  // Register any off-catalog exercises the model introduced in strength days.
  for (const row of rows) {
    if (row.type === "strength") {
      await registerUnknownExercises(admin, userId, row.workout_json as Workout);
    }
  }

  // Persist the week's rationale for the "explain this week" view.
  await admin.from("week_plans").upsert(
    { user_id: userId, start_date: start, focus: plan.week_focus, rationale: plan.rationale },
    { onConflict: "user_id,start_date" },
  );

  let pushed = 0;
  if (shouldPush && phys.apiKey && profile.intervals_athlete_id) {
    for (const row of rows) {
      const session = row.workout_json as Workout;
      if (session.type === "rest") continue;
      const id = inserted?.find((r) => r.date === row.date)?.id;
      try {
        const ev = await createEvent(profile.intervals_athlete_id, phys.apiKey, {
          date: row.date, name: session.title, description: renderIntervalsWorkout(session),
          type: session.type === "run" ? "Run" : session.type === "ride" ? "Ride" : "WeightTraining",
        });
        pushed++;
        if (id) await admin.from("planned_workouts").update({ intervals_event_id: ev.id, pushed_at: new Date().toISOString() }).eq("id", id);
      } catch (_e) { /* best-effort */ }
    }
  }

  const cost = estimateCostUsd(outcome.provider, outcome.promptTokens, outcome.completionTokens);
  await admin.from("generation_logs").insert({
    user_id: userId, provider: outcome.provider, model: outcome.model,
    prompt_tokens: outcome.promptTokens, completion_tokens: outcome.completionTokens,
    estimated_cost_usd: cost, system_prompt: WEEK_SYSTEM_PROMPT, user_prompt: userPrompt,
    raw_response: outcome.text, parsed_ok: true,
  });

  return {
    start_date: start, end_date: end, week_focus: plan.week_focus, rationale: plan.rationale,
    provider: outcome.provider, model: outcome.model, estimated_cost_usd: cost,
    scheduled: rows.length, pushed,
    days: rows.map((row) => {
      const s = row.workout_json as Workout;
      return { date: row.date, weekday: weekdayOf(row.date), type: s.type, title: s.title, tss: Math.round(s.tss_estimate) };
    }),
  };
}

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const admin = adminClient();
    const body = await req.json().catch(() => ({}));

    // Cron path: plan NEXT week for every opted-in user. One failure doesn't abort.
    if (body?.all_users) {
      const start = nextMonday();
      const { data: users } = await admin.from("user_profiles").select("id").eq("auto_plan", true);
      const results: unknown[] = [];
      for (const u of users ?? []) {
        try {
          const r = await planForUser(admin, u.id, start, body.push ?? true);
          results.push({ user_id: u.id, scheduled: r.scheduled, pushed: r.pushed });
        } catch (e) {
          results.push({ user_id: u.id, error: e instanceof Error ? e.message : String(e) });
        }
      }
      return json({ mode: "all_users", start_date: start, count: results.length, results });
    }

    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);
    const start: string = body.start_date ?? new Date().toISOString().slice(0, 10);
    const result = await planForUser(admin, userId, start, body.push ?? true);
    return json(result);
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
