// generate-workout — the full AI generation flow.
//
// POST { date?: "YYYY-MM-DD", type?: "run"|"strength"|"auto", duration?: number, push?: boolean }
//
// 1. profile + onboarding   6. build prompt
// 2. last 14d activities     7. call provider (with fallback chain)
// 3. last 7d wellness        8. parse + validate
// 4. CTL/ATL/TSB             9. save planned_workouts + generation_logs
// 5. choose type            10. optional push to Intervals.icu calendar

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, decryptSecret, getUserId } from "../_shared/supabase.ts";
import {
  estimateCostUsd,
  extractJson,
  llmGenerateWithFallback,
  PROVIDERS,
} from "../_shared/llm.ts";
import {
  buildRunPrompt,
  buildStrengthPrompt,
  SYSTEM_PROMPT,
  trainingPhase,
  validateWorkout,
} from "../_shared/prompt.ts";
import { createEvent, deleteEvent, latestFitness } from "../_shared/intervals.ts";
import { renderIntervalsWorkout } from "../_shared/intervals_workout.ts";
import {
  adherenceBlock,
  goalBlock,
  intervalsPhysiology,
  knowledgeBlock,
  memoryBlock,
  recoveryBlock,
  weatherBlock,
} from "../_shared/context.ts";
import { computeRecovery } from "../_shared/recovery.ts";
import type { LlmProvider } from "../_shared/types.ts";

const DAY = 86_400_000;
const daysBetween = (a: string, b: Date) => Math.floor((b.getTime() - new Date(a).getTime()) / DAY);

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const admin = adminClient();
    const body = await req.json().catch(() => ({}));
    const date: string = body.date ?? new Date().toISOString().slice(0, 10);
    const requestedDuration: number = body.duration ?? 45;
    const requestedType: string = body.type ?? "auto";
    const shouldPush: boolean = body.push ?? true;

    // 1. profile ------------------------------------------------------------
    const { data: profile } = await admin
      .from("user_profiles")
      .select("*")
      .eq("id", userId)
      .single();
    if (!profile) return json({ error: "profile not found" }, 404);
    const onboarding = profile.onboarding ?? {};

    // 2. activities (last 28d — enough for acute:chronic workload ratio) ----
    const since28 = new Date(Date.now() - 28 * DAY).toISOString().slice(0, 10);
    const since14 = new Date(Date.now() - 14 * DAY).toISOString().slice(0, 10);
    const { data: activities } = await admin
      .from("completed_activities")
      .select("type, date, distance_m, tss, ctl, atl")
      .eq("user_id", userId)
      .gte("date", since28)
      .order("date", { ascending: false });
    const acts28 = activities ?? [];
    const acts = acts28.filter((a) => (a.date ?? "") >= since14); // last 14d view

    // ACWR = (last 7d load) / (last 28d avg-per-week load). 0.8-1.3 is the
    // "safe" band; >1.5 is a spike → elevated injury risk.
    const since7d = new Date(Date.now() - 7 * DAY).toISOString().slice(0, 10);
    const load7 = acts28.filter((a) => (a.date ?? "") >= since7d).reduce((s, a) => s + (a.tss ?? 0), 0);
    const load28 = acts28.reduce((s, a) => s + (a.tss ?? 0), 0);
    const chronicWeekly = load28 / 4;
    const acwr = chronicWeekly > 0 ? load7 / chronicWeekly : null;

    // 3. wellness (last 7d) -------------------------------------------------
    const since7 = new Date(Date.now() - 7 * DAY).toISOString().slice(0, 10);
    const { data: wellness } = await admin
      .from("wellness_checkins")
      .select("date, energy, soreness, sleep_quality, hrv_rmssd, resting_hr, zepp_sleep_minutes")
      .eq("user_id", userId)
      .gte("date", since7)
      .order("date", { ascending: false });
    const wells = wellness ?? [];

    // Recovery signal (HRV/RHR/sleep trends) — feeds the prompt so a poor night
    // down-regulates intensity. wells is newest-first; series want oldest→newest.
    const isNumR = (v: unknown): v is number => typeof v === "number";
    const chrono = [...wells].reverse();
    const hrvSeries = chrono.map((w) => (w as { hrv_rmssd?: number }).hrv_rmssd).filter(isNumR);
    const rhrSeries = chrono.map((w) => (w as { resting_hr?: number }).resting_hr).filter(isNumR);
    const recovery = computeRecovery(wells, hrvSeries, rhrSeries);
    const avg = (vals: number[]) => vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : 3;
    const wellness3d = {
      energy: avg(wells.slice(0, 3).map((w) => w.energy ?? 3)),
      soreness: avg(wells.slice(0, 3).map((w) => w.soreness ?? 3)),
      sleep: avg(wells.slice(0, 3).map((w) => w.sleep_quality ?? 3)),
    };

    // 4. CTL/ATL/TSB — prefer the freshest cached activity row -------------
    const fitnessRow = acts.find((a) => a.ctl != null) ?? { ctl: 0, atl: 0 };
    const fitness = latestFitness([
      { id: date, ctl: fitnessRow.ctl ?? 0, atl: fitnessRow.atl ?? 0 },
    ]);

    // 5. decide type --------------------------------------------------------
    const runs = acts.filter((a) => (a.type ?? "").toLowerCase().includes("run"));
    const now = new Date(date + "T12:00:00");
    const daysSinceLastRun = runs.length ? daysBetween(runs[0].date, now) : 99;
    const hardEffort = acts.find((a) => (a.tss ?? 0) > 60);
    const daysSinceLastHard = hardEffort ? daysBetween(hardEffort.date, now) : 99;

    const goal: string = onboarding.goal ?? "General fitness";

    // Training phase from weeks until the goal race (onboarding.goal_date).
    let weeksToGoal: number | null = null;
    if (onboarding.goal_date) {
      const d = (new Date(onboarding.goal_date).getTime() - now.getTime()) / (7 * DAY);
      weeksToGoal = d >= 0 ? Math.round(d) : null;
    }
    const phase = trainingPhase(weeksToGoal);

    let type = requestedType;
    if (type === "auto") {
      const isStrengthGoal = /muscle|recomp|hybrid|strength/i.test(goal);
      // Avoid back-to-back hard: if a hard effort was yesterday, bias to the
      // other modality / easy.
      type = isStrengthGoal && daysSinceLastRun < 2 ? "strength" : "run";
    }

    // 5b. live Intervals.icu physiology (shared with the week planner). The
    //     decrypted key is reused for the calendar push later.
    let intervalsApiKey: string | null = null;
    let physiologyBlock = "";
    let ivHrZones: { zone: string; min: number; max: number }[] | null = null;
    if (!body.adjustment) {
      const phys = await intervalsPhysiology(admin, profile);
      intervalsApiKey = phys.apiKey;
      physiologyBlock = phys.block;
      ivHrZones = phys.hrZones;
    }

    // 6. build prompt -------------------------------------------------------
    let userPrompt: string;
    if (type === "strength") {
      // muscle groups trained in last 48h
      const since48 = new Date(Date.now() - 2 * DAY).toISOString().slice(0, 10);
      const { data: recentStrength } = await admin
        .from("strength_logs")
        .select("muscle_groups, exercise_name, estimated_1rm, sets, date")
        .eq("user_id", userId)
        .gte("date", since48);
      const muscleGroupsLast48h = [
        ...new Set((recentStrength ?? []).flatMap((r) => r.muscle_groups ?? [])),
      ];

      // Weekly hard sets per muscle group (volume landmark check, ~10-20/wk).
      const { data: weekStrength } = await admin
        .from("strength_logs")
        .select("muscle_groups, sets, date")
        .eq("user_id", userId)
        .gte("date", since7);
      const weeklySetsByMuscle: Record<string, number> = {};
      for (const r of weekStrength ?? []) {
        const setCount = Array.isArray(r.sets) ? r.sets.length : 0;
        for (const mg of r.muscle_groups ?? []) {
          weeklySetsByMuscle[mg] = (weeklySetsByMuscle[mg] ?? 0) + setCount;
        }
      }
      const { data: mainLiftRows } = await admin
        .from("strength_logs")
        .select("exercise_name, estimated_1rm, sets, date")
        .eq("user_id", userId)
        .order("date", { ascending: false })
        .limit(20);
      const seenLift = new Set<string>();
      const mainLifts = (mainLiftRows ?? [])
        .filter((r) => {
          if (seenLift.has(r.exercise_name)) return false;
          seenLift.add(r.exercise_name);
          return true;
        })
        .slice(0, 5)
        .map((r) => {
          const sets = Array.isArray(r.sets) ? r.sets : [];
          const lastWeight = sets.length ? Number(sets[sets.length - 1]?.weight_kg ?? 0) : 0;
          return {
            exercise: r.exercise_name,
            estimated1rm: r.estimated_1rm ?? lastWeight,
            lastWeight,
          };
        });

      userPrompt = buildStrengthPrompt({
        muscleGroupsLast48h,
        weeklySetsByMuscle,
        equipment: onboarding.equipment ?? "Full gym",
        experience: onboarding.experience ?? "Intermediate",
        goal,
        phase,
        soreness: Math.round(wellness3d.soreness),
        mainLifts,
        requestedDuration,
      });
    } else if (type === "rest") {
      userPrompt = `Generate a REST day. Goal: ${goal}. Recent 3-day wellness energy ${wellness3d.energy.toFixed(1)}/5, soreness ${wellness3d.soreness.toFixed(1)}/5. Return JSON only.`;
    } else {
      const weeklyKm = runs
        .filter((r) => r.date >= since7)
        .reduce((s, r) => s + (r.distance_m ?? 0) / 1000, 0);
      const hrZones = ivHrZones ?? onboarding.hr_zones ?? [
        { zone: "Z1", min: 95, max: 130 },
        { zone: "Z2", min: 131, max: 145 },
        { zone: "Z3", min: 146, max: 160 },
        { zone: "Z4", min: 161, max: 172 },
        { zone: "Z5", min: 173, max: 190 },
      ];
      userPrompt = buildRunPrompt({
        hrZones,
        tsb: fitness.tsb,
        ctl: fitness.ctl,
        atl: fitness.atl,
        acwr,
        phase,
        wellness3d,
        weeklyKm,
        goal,
        targetPace: onboarding.target_pace,
        daysSinceLastRun,
        daysSinceLastHard,
        requestedDuration,
        experience: onboarding.experience ?? "Intermediate",
      });
    }

    // 6a-ext. free-text athlete request for this specific date (e.g. "social
    // 10k run with friends, keep it easy"). Honored as a hard constraint.
    if (typeof body.request === "string" && body.request.trim()) {
      userPrompt += `\n\nATHLETE'S REQUEST FOR THIS SESSION (honor this exactly — it's a fixed plan, e.g. a social run or a session with friends; match the stated distance/duration/vibe and keep it physiologically sensible):\n"${body.request.trim()}"`;
    }

    // 6b. fold in recent post-workout feedback (autoregulation loop) --------
    const { data: feedback } = await admin
      .from("workout_feedback")
      .select("date, actual_rpe, difficulty, completed, notes")
      .eq("user_id", userId)
      .order("date", { ascending: false })
      .limit(5);
    if (feedback?.length) {
      const lines = feedback.map((f) =>
        `- ${f.date}: ${f.completed === false ? "NOT completed; " : ""}rated ${f.difficulty ?? "?"}` +
        `${f.actual_rpe ? `, actual RPE ${f.actual_rpe}` : ""}${f.notes ? ` ("${f.notes}")` : ""}`
      ).join("\n");
      userPrompt += `\n\nRECENT SESSION FEEDBACK (autoregulate from this — if recent sessions were "too_hard" or skipped, reduce intensity/volume; if "too_easy", progress):\n${lines}`;
    }

    // 6b-ext. shared context blocks (skipped for the adjust path) -----------
    if (!body.adjustment) {
      userPrompt += knowledgeBlock(profile);
      userPrompt += memoryBlock(profile);
      userPrompt += recoveryBlock(recovery);
      userPrompt += physiologyBlock;
      userPrompt += await adherenceBlock(admin, userId, since14, date, acts28);
      userPrompt += goalBlock(onboarding, weeksToGoal, phase, acts28);
      if (type === "run") {
        const lat = typeof body.lat === "number" ? body.lat : profile.last_lat;
        const lon = typeof body.lon === "number" ? body.lon : profile.last_lon;
        if (typeof lat === "number" && typeof lon === "number") {
          userPrompt += await weatherBlock(lat, lon);
        }
      }
    }

    // Persist coarse location for future weather lookups / cron use.
    if (typeof body.lat === "number" && typeof body.lon === "number") {
      await admin.from("user_profiles").update({ last_lat: body.lat, last_lon: body.lon }).eq("id", userId);
    }

    // 6c. "adjust this workout" path — revise a base workout per instruction
    if (body.adjustment && body.base_workout) {
      userPrompt =
        `Revise the following workout per the athlete's request, keeping it
physiologically sound and honoring the same training science.

ATHLETE REQUEST: ${String(body.adjustment)}

CURRENT WORKOUT (JSON):
${JSON.stringify(body.base_workout)}
${knowledgeBlock(profile)}
Return the revised workout as JSON only, same schema.`;
    }

    // 7. resolve fallback chain + keys, then generate ----------------------
    const chain: LlmProvider[] = [
      profile.active_llm_provider,
      ...(profile.llm_fallback_chain ?? []),
    ].filter(Boolean);
    if (chain.length === 0) {
      return json({ error: "No AI provider configured. Add an API key in Settings." }, 400);
    }

    const keyCache = new Map<string, string | null>();
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

    let outcome;
    try {
      outcome = await llmGenerateWithFallback(
        chain,
        { prompt: userPrompt, systemPrompt: SYSTEM_PROMPT },
        resolveKey,
      );
    } catch (e) {
      await admin.from("generation_logs").insert({
        user_id: userId,
        provider: chain[0] ?? null,
        system_prompt: SYSTEM_PROMPT,
        user_prompt: userPrompt,
        parsed_ok: false,
        error: e instanceof Error ? e.message : String(e),
      });
      return json({ error: "generation failed", detail: String(e) }, 502);
    }

    // 8. parse + validate ---------------------------------------------------
    let parsedOk = false;
    let validated;
    let parseError: string | null = null;
    try {
      const obj = extractJson(outcome.text);
      const v = validateWorkout(obj);
      if (!v.ok) throw new Error(v.error);
      validated = v.workout!;
      parsedOk = true;
    } catch (e) {
      parseError = e instanceof Error ? e.message : String(e);
    }

    // 8b. one self-repair pass — feed the validation error back to the model.
    if (!parsedOk) {
      try {
        const repairPrompt =
          `${userPrompt}\n\nYOUR PREVIOUS RESPONSE could not be parsed/validated (${parseError}). ` +
          `Return ONLY a corrected JSON object that exactly matches the schema — no prose, no code fences.`;
        const retry = await llmGenerateWithFallback(chain, { prompt: repairPrompt, systemPrompt: SYSTEM_PROMPT }, resolveKey);
        const v2 = validateWorkout(extractJson(retry.text));
        if (v2.ok && v2.workout) {
          validated = v2.workout;
          parsedOk = true;
          parseError = null;
          outcome = retry;
        }
      } catch (_e) {
        // keep the original parseError
      }
    }

    const cost = estimateCostUsd(outcome.provider, outcome.promptTokens, outcome.completionTokens);

    if (!parsedOk || !validated) {
      await admin.from("generation_logs").insert({
        user_id: userId,
        provider: outcome.provider,
        model: outcome.model,
        prompt_tokens: outcome.promptTokens,
        completion_tokens: outcome.completionTokens,
        estimated_cost_usd: cost,
        system_prompt: SYSTEM_PROMPT,
        user_prompt: userPrompt,
        raw_response: outcome.text,
        parsed_ok: false,
        error: parseError,
      });
      return json({ error: "could not parse workout", detail: parseError, raw: outcome.text }, 422);
    }

    // 9. persist planned workout + log -------------------------------------
    // Idempotent: replace any incomplete plan already on this date so repeated
    // generations don't pile up duplicates (and clean up their watch events).
    const { data: stale } = await admin
      .from("planned_workouts")
      .select("id, intervals_event_id")
      .eq("user_id", userId).eq("date", date).eq("completed", false);
    if (stale?.length) {
      const key = intervalsApiKey ??
        (profile.intervals_api_key_encrypted ? await decryptSecret(admin, profile.intervals_api_key_encrypted) : null);
      if (key && profile.intervals_athlete_id) {
        for (const r of stale) {
          if (r.intervals_event_id) {
            try { await deleteEvent(profile.intervals_athlete_id, key, r.intervals_event_id); } catch (_e) { /* ignore */ }
          }
        }
      }
      await admin.from("planned_workouts").delete().eq("user_id", userId).eq("date", date).eq("completed", false);
    }

    const { data: planned, error: insErr } = await admin
      .from("planned_workouts")
      .insert({
        user_id: userId,
        date,
        type: validated.type,
        workout_json: validated,
        llm_provider: outcome.provider,
        llm_model: outcome.model,
        // Lock athlete-requested fixed sessions so the weekly re-planner leaves them be.
        locked: body.lock === true,
      })
      .select()
      .single();
    if (insErr) return json({ error: `save failed: ${insErr.message}` }, 500);

    await admin.from("generation_logs").insert({
      user_id: userId,
      provider: outcome.provider,
      model: outcome.model,
      prompt_tokens: outcome.promptTokens,
      completion_tokens: outcome.completionTokens,
      estimated_cost_usd: cost,
      system_prompt: SYSTEM_PROMPT,
      user_prompt: userPrompt,
      raw_response: outcome.text,
      parsed_ok: true,
      workout_id: planned.id,
    });

    // 10. push to Intervals.icu calendar -----------------------------------
    let intervalsEventId: string | null = null;
    let pushError: string | null = null;
    if (shouldPush && validated.type !== "rest" &&
        profile.intervals_athlete_id && profile.intervals_api_key_encrypted) {
      try {
        const apiKey = intervalsApiKey ?? await decryptSecret(admin, profile.intervals_api_key_encrypted);
        if (apiKey) {
          const description = renderIntervalsWorkout(validated);
          const ev = await createEvent(profile.intervals_athlete_id, apiKey, {
            date,
            name: validated.title,
            description,
            type: validated.type === "run" ? "Run" : "Workout",
          });
          intervalsEventId = ev.id;
          await admin
            .from("planned_workouts")
            .update({ intervals_event_id: ev.id, pushed_at: new Date().toISOString() })
            .eq("id", planned.id);
        }
      } catch (e) {
        pushError = e instanceof Error ? e.message : String(e);
      }
    }

    return json({
      workout: validated,
      workout_id: planned.id,
      provider: outcome.provider,
      model: outcome.model,
      estimated_cost_usd: cost,
      fallback_attempts: outcome.attempts,
      intervals_event_id: intervalsEventId,
      push_error: pushError,
    });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
