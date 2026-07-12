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
import { logger } from "../_shared/log.ts";
import { adminClient, decryptSecret, getUserId } from "../_shared/supabase.ts";
import {
  customPriceFromProfile,
  estimateCostUsd,
  extractJson,
  llmGenerateWithFallback,
  PROVIDERS,
} from "../_shared/llm.ts";
import {
  buildRunPrompt,
  buildStrengthPrompt,
  durationGuidance,
  SYSTEM_PROMPT,
  trainingPhase,
  validateWorkout,
} from "../_shared/prompt.ts";
import { createEvent, deleteEvent, latestFitness } from "../_shared/intervals.ts";
import { renderIntervalsWorkout } from "../_shared/intervals_workout.ts";
import {
  adherenceBlock,
  executionBlock,
  goalBlock,
  intervalsPhysiology,
  knowledgeBlock,
  recoveryBlock,
  weatherBlock,
} from "../_shared/context.ts";
import { memoryDocsBlock, memoryFromProfile } from "../_shared/agent_memory.ts";
import { computeRecovery } from "../_shared/recovery.ts";
import { llmAccess } from "../_shared/llm_keys.ts";
import {
  canonicalizeStrengthExercises,
  compoundForName,
  customExercises,
  exerciseCatalogBlock,
  registerUnknownExercises,
} from "../_shared/exercise_catalog.ts";
import { type MainLift, reviewWorkout } from "../_shared/workout_review.ts";
import { nextTarget } from "../_shared/progression.ts";

const DAY = 86_400_000;
const daysBetween = (a: string, b: Date) => Math.floor((b.getTime() - new Date(a).getTime()) / DAY);

const log = logger("generate-workout");

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  const startedAt = Date.now();
  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const admin = adminClient();
    const body = await req.json().catch(() => ({}));
    const date: string = body.date ?? new Date().toISOString().slice(0, 10);
    const requestedType: string = body.type ?? "auto";
    const shouldPush: boolean = body.push ?? true;
    log.info("request", { date, requestedType, push: shouldPush, hasRequest: !!body.request });

    // 1. profile ------------------------------------------------------------
    const { data: profile } = await admin
      .from("user_profiles")
      .select("*")
      .eq("id", userId)
      .single();
    if (!profile) return json({ error: "profile not found" }, 404);
    const onboarding = profile.onboarding ?? {};

    // Session length is guidance, not a fixed number: an explicit request wins,
    // otherwise the profile preference (+ optional max) becomes a flexible
    // budget so sessions vary with their purpose.
    const prefDuration: number | null = typeof body.duration === "number"
      ? body.duration
      : (typeof onboarding.session_duration === "number" ? onboarding.session_duration : null);
    const maxDuration: number | null =
      typeof onboarding.session_duration_max === "number" ? onboarding.session_duration_max : null;
    const durationNote = typeof body.duration === "number"
      ? `${body.duration} min requested for this session (honor within ±10 min)`
      : durationGuidance(prefDuration, maxDuration);

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
      .select("date, energy, soreness, sleep_score, hrv_rmssd, resting_hr, zepp_sleep_minutes")
      .eq("user_id", userId)
      .gte("date", since7)
      .order("date", { ascending: false });
    const wells = wellness ?? [];

    // Recovery signal (HRV/RHR/sleep trends) — feeds the prompt so a poor night
    // down-regulates intensity. wells is newest-first; series want oldest→newest.
    const isNumR = (v: unknown): v is number => typeof v === "number";
    const chrono = [...wells].reverse();
    // Dated points so computeRecovery can anchor on the target day specifically —
    // a not-synced-today HRV/RHR then reads as missing (and the score leans on the
    // subjective wellness composite) instead of silently inheriting yesterday's.
    const datedSeries = (key: "hrv_rmssd" | "resting_hr") => {
      const out: { date?: string; value: number }[] = [];
      for (const w of chrono) {
        const v = (w as Record<string, unknown>)[key];
        if (isNumR(v)) out.push({ date: (w as { date?: string }).date, value: v });
      }
      return out;
    };
    const hrvSeries = datedSeries("hrv_rmssd");
    const rhrSeries = datedSeries("resting_hr");
    const recovery = computeRecovery(wells, hrvSeries, rhrSeries, date);
    const avg = (vals: number[]) => vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : 3;
    const wellness3d = {
      energy: avg(wells.slice(0, 3).map((w) => w.energy ?? 3)),
      soreness: avg(wells.slice(0, 3).map((w) => w.soreness ?? 3)),
      sleep: avg(wells.slice(0, 3).map((w) => isNumR(w.sleep_score) ? w.sleep_score / 20 : 3)),
    };

    // 4. CTL/ATL/TSB — prefer the freshest cached activity row -------------
    const fitnessRow = acts.find((a) => a.ctl != null) ?? { ctl: 0, atl: 0 };
    const fitness = latestFitness([
      { id: date, ctl: fitnessRow.ctl ?? 0, atl: fitnessRow.atl ?? 0 },
    ]);

    // 5. decide type --------------------------------------------------------
    const isRide = requestedType === "ride";
    const runs = acts.filter((a) => {
      const t = (a.type ?? "").toLowerCase();
      return isRide ? (t.includes("ride") || t.includes("bike") || t.includes("cycl")) : t.includes("run");
    });
    const now = new Date(date + "T12:00:00");
    const daysSinceLastRun = runs.length ? daysBetween(runs[0].date, now) : 99;
    const hardEffort = acts.find((a) => (a.tss ?? 0) > 60);
    const daysSinceLastHard = hardEffort ? daysBetween(hardEffort.date, now) : 99;

    // Surrounding planned week — so a single session slots in instead of clashing
    // (two hard days back-to-back, a leg day next to the long run). Read the day
    // before + the next 6 days; feed both the decision and the prompt.
    const dayBefore = new Date(now.getTime() - DAY).toISOString().slice(0, 10);
    const dayAfter = new Date(now.getTime() + DAY).toISOString().slice(0, 10);
    const weekAhead = new Date(now.getTime() + 6 * DAY).toISOString().slice(0, 10);
    const { data: aroundPlanned } = await admin
      .from("planned_workouts")
      .select("date, type, workout_json")
      .eq("user_id", userId)
      .gte("date", dayBefore).lte("date", weekAhead).neq("date", date)
      .order("date", { ascending: true });
    const around = aroundPlanned ?? [];
    const titleOf = (w: { workout_json?: unknown }) =>
      ((w.workout_json ?? {}) as { title?: string }).title ?? "";
    const isHardPlanned = (w: { workout_json?: unknown }) => {
      const wj = (w.workout_json ?? {}) as { rpe_target?: number; tss_estimate?: number };
      return (wj.rpe_target ?? 0) >= 7 || (wj.tss_estimate ?? 0) >= 70 ||
        /threshold|interval|tempo|vo2|hard|race/i.test(titleOf(w));
    };
    const adjacentHard = around.some((w) =>
      (w.date === dayBefore || w.date === dayAfter) && isHardPlanned(w));
    const surroundingSummary = around.length
      ? around.map((w) => `${w.date} ${w.type}${titleOf(w) ? ` (${titleOf(w)})` : ""}`).join(" | ")
      : "nothing else planned this week";

    const goal: string = onboarding.goal ?? "General fitness";

    // Training phase from weeks until the goal race (onboarding.goal_date).
    let weeksToGoal: number | null = null;
    if (onboarding.goal_date) {
      const d = (new Date(onboarding.goal_date).getTime() - now.getTime()) / (7 * DAY);
      weeksToGoal = d >= 0 ? Math.round(d) : null;
    }
    const phase = trainingPhase(weeksToGoal);

    // Auto-rest when the athlete is genuinely cooked — low readiness or deeply
    // negative form. Today's prescription should be recovery, not another stressor.
    const lowReadiness = recovery.score < 35;
    const veryFatigued = fitness.tsb < -20;

    let type = requestedType;
    if (type === "auto") {
      const sports: string[] = Array.isArray(onboarding.sports) ? onboarding.sports as string[] : [];
      const isStrengthGoal = /muscle|recomp|hybrid|strength/i.test(goal);
      if (lowReadiness || veryFatigued) {
        type = "rest";
      } else if (isStrengthGoal && daysSinceLastRun < 2 && (sports.length === 0 || sports.includes("strength"))) {
        type = "strength";
      } else if (adjacentHard && (sports.length === 0 || sports.includes("strength"))) {
        // A hard day sits next to today → keep today off the legs/aerobic system:
        // a strength day (if they lift) spaces the hard endurance work out.
        type = "strength";
      } else if (sports.length === 0) {
        type = "run";
      } else {
        // First endurance sport the athlete does, else strength, else run.
        type = ["run", "ride", "swim"].find((s) => sports.includes(s)) ??
          (sports.includes("strength") ? "strength" : "run");
      }
    }

    // 5b. live Intervals.icu physiology (shared with the week planner). The
    //     decrypted key is reused for the calendar push later.
    let intervalsApiKey: string | null = null;
    let physiologyBlock = "";
    let ivHrZones: { zone: string; min: number; max: number }[] | null = null;
    if (!body.adjustment) {
      const phys = await intervalsPhysiology(admin, profile, onboarding);
      intervalsApiKey = phys.apiKey;
      physiologyBlock = phys.block;
      ivHrZones = phys.hrZones;
    }

    // 6. build prompt -------------------------------------------------------
    // Hoisted so the post-generation review (step 8d) can re-use the strength
    // context (progressive-overload floor, volume landmarks, 48h recovery).
    let mainLifts: MainLift[] = [];
    let weeklySetsByMuscle: Record<string, number> = {};
    let muscleGroupsLast48h: string[] = [];
    let userPrompt: string;
    if (type === "strength") {
      // muscle groups trained in last 48h
      const since48 = new Date(Date.now() - 2 * DAY).toISOString().slice(0, 10);
      const { data: recentStrength } = await admin
        .from("strength_logs")
        .select("muscle_groups, exercise_name, estimated_1rm, sets, date")
        .eq("user_id", userId)
        .gte("date", since48);
      muscleGroupsLast48h = [
        ...new Set((recentStrength ?? []).flatMap((r) => r.muscle_groups ?? [])),
      ];

      // Weekly hard sets per muscle group (volume landmark check, ~10-20/wk).
      const { data: weekStrength } = await admin
        .from("strength_logs")
        .select("muscle_groups, sets, date")
        .eq("user_id", userId)
        .gte("date", since7);
      weeklySetsByMuscle = {};
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
      const strengthCustom = await customExercises(admin, userId);
      mainLifts = (mainLiftRows ?? [])
        .filter((r) => {
          if (seenLift.has(r.exercise_name)) return false;
          seenLift.add(r.exercise_name);
          return true;
        })
        .slice(0, 8)
        .map((r) => {
          const sets = Array.isArray(r.sets) ? r.sets : [];
          // The athlete's TOP working set last time (heaviest), so the model
          // progresses from real performance instead of a back-off set.
          const top = sets.reduce(
            (best: { w: number; reps: number }, s: { weight_kg?: number; reps?: number }) => {
              const w = Number(s?.weight_kg ?? 0);
              return w > best.w ? { w, reps: Number(s?.reps ?? 0) } : best;
            },
            { w: 0, reps: 0 },
          );
          return {
            exercise: r.exercise_name,
            estimated1rm: r.estimated_1rm ?? top.w,
            lastWeight: top.w,
            lastReps: top.reps,
            lastSets: sets.length,
            // The app's double-progression target — prompt + review keep the
            // plan's numbers identical to the logger's ↗ target.
            target: nextTarget(sets, compoundForName(r.exercise_name, strengthCustom)),
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
        durationNote,
        splitStyle: onboarding.split_style as string | undefined,
      });
      // Pin exercise names to the loggable catalog (+ the athlete's customs).
      userPrompt += await exerciseCatalogBlock(admin, userId);
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
        durationNote,
        experience: onboarding.experience ?? "Intermediate",
        sport: type === "ride" ? "ride" : type === "swim" ? "swim" : "run",
        ftp: typeof onboarding.ftp === "number" ? onboarding.ftp : undefined,
      });
    }

    // 6a-pre. surrounding week so this session fits the block, not just today.
    if (type !== "rest") {
      userPrompt += `\n\nTHIS WEEK AROUND TODAY (fit today INTO this — don't clash): ${surroundingSummary}.` +
        ` Keep ≥24h between hard efforts and between heavy leg work and the long run.` +
        (adjacentHard ? ` A HARD day is adjacent to today, so keep today EASY/recovery or a non-competing modality.` : "");
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

    // 6a-coherence. adjacent already-planned days (±2) so a single-day
    // generation doesn't stack two hard/quality sessions back-to-back.
    if (!body.adjustment) {
      const win = (offset: number) =>
        new Date(now.getTime() + offset * DAY).toISOString().slice(0, 10);
      const { data: neighbors } = await admin
        .from("planned_workouts")
        .select("date, type, workout_json")
        .eq("user_id", userId)
        .gte("date", win(-2)).lte("date", win(2)).neq("date", date)
        .order("date", { ascending: true });
      if (neighbors?.length) {
        const lines = neighbors.map((n) => {
          const wj = (n.workout_json ?? {}) as { title?: string; rpe_target?: number };
          const rpe = typeof wj.rpe_target === "number" ? `, RPE ${wj.rpe_target}` : "";
          return `- ${n.date}: ${wj.title ?? n.type} (${n.type}${rpe})`;
        }).join("\n");
        userPrompt +=
          `\n\nADJACENT PLANNED DAYS (do NOT place a hard/quality session back-to-back ` +
          `with one of these — if a neighbour is hard, make today easy/recovery or the ` +
          `complementary modality; separate hard runs and heavy leg days by ≥24h):\n${lines}`;
      }
    }

    // 6b-ext. shared context blocks (skipped for the adjust path) -----------
    if (!body.adjustment) {
      userPrompt += memoryDocsBlock(memoryFromProfile(profile));
      userPrompt += recoveryBlock(recovery);
      userPrompt += physiologyBlock;
      userPrompt += await adherenceBlock(admin, userId, since14, date, acts28);
      userPrompt += await executionBlock(admin, userId, since14);
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
      const catalogNote = (body.base_workout as { type?: string }).type === "strength"
        ? await exerciseCatalogBlock(admin, userId)
        : "";
      userPrompt =
        `Revise the following workout per the athlete's request, keeping it
physiologically sound and honoring the same training science.

ATHLETE REQUEST: ${String(body.adjustment)}

CURRENT WORKOUT (JSON):
${JSON.stringify(body.base_workout)}
${knowledgeBlock(profile)}${catalogNote}
Return the revised workout as JSON only, same schema.`;
    }

    // 7. resolve fallback chain + keys, then generate ----------------------
    const { chain, resolveKey, resolveModel, resolveBaseUrl } = llmAccess(admin, userId, profile);
    if (chain.length === 0) {
      return json({ error: "No AI provider configured. Add an API key in Settings." }, 400);
    }

    let outcome;
    try {
      outcome = await llmGenerateWithFallback(
        chain,
        { prompt: userPrompt, systemPrompt: SYSTEM_PROMPT, temperature: 0.4 },
        resolveKey,
        resolveModel,
        resolveBaseUrl,
      );
    } catch (e) {
      await admin.from("generation_logs").insert({
        user_id: userId,
        feature: "workout",
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
        const retry = await llmGenerateWithFallback(chain, { prompt: repairPrompt, systemPrompt: SYSTEM_PROMPT, temperature: 0.4 }, resolveKey, resolveModel, resolveBaseUrl);
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

    const cost = estimateCostUsd(outcome.provider, outcome.promptTokens, outcome.completionTokens, customPriceFromProfile(outcome.provider, profile), outcome.model);

    if (!parsedOk || !validated) {
      await admin.from("generation_logs").insert({
        user_id: userId,
        feature: "workout",
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

    // 8c. snap reworded strength names onto the loggable catalog (e.g. "Machine
    // Lat Pulldown" → "Lat Pulldown") BEFORE saving, so the client gets catalog-
    // exact names and only truly-new exercises become customs.
    const customs = validated.type === "strength" ? await customExercises(admin, userId) : [];
    if (validated.type === "strength") canonicalizeStrengthExercises(validated, customs);

    // 8d. CONTENT review — enforce the prescription (progressive-overload floor,
    // load safety ceiling, weekly volume landmark, 48h recovery, endurance
    // readiness) and recompute an independent TSS so a bad self-report can't
    // poison next-day ACWR. Auto-fixable issues are corrected in place; any
    // remaining judgement-call violations trigger ONE targeted repair pass.
    const reviewCtx = {
      mainLifts,
      weeklySetsByMuscle,
      muscleGroupsLast48h,
      tsb: fitness.tsb,
      daysSinceLastHard,
      experience: onboarding.experience ?? "Intermediate",
      // Structured safety: injuries on file + durable coach constraints.
      injuries: [onboarding.injury_history, profile.coach_knowledge].filter(Boolean).join("; "),
      // Deterministic intensity ceiling + equipment hard-filter.
      readiness: recovery.score,
      equipment: onboarding.equipment as string | undefined,
    };
    let review = reviewWorkout(validated, reviewCtx);
    validated = review.corrected;
    if (review.violations.length) {
      try {
        const fixPrompt =
          `${userPrompt}\n\nYOUR PREVIOUS WORKOUT had these problems — fix ALL of them and ` +
          `return ONLY corrected JSON matching the schema:\n- ${review.violations.join("\n- ")}`;
        const retry = await llmGenerateWithFallback(
          chain,
          { prompt: fixPrompt, systemPrompt: SYSTEM_PROMPT, temperature: 0.4 },
          resolveKey,
          resolveModel,
          resolveBaseUrl,
        );
        const v2 = validateWorkout(extractJson(retry.text));
        if (v2.ok && v2.workout) {
          const w2 = v2.workout;
          if (w2.type === "strength") canonicalizeStrengthExercises(w2, customs);
          const r2 = reviewWorkout(w2, reviewCtx);
          // Keep the repair only if it genuinely reduced the violation count.
          if (r2.violations.length < review.violations.length) {
            validated = r2.corrected;
            review = r2;
            outcome = retry;
          }
        }
      } catch (_e) {
        // Keep the auto-corrected version on any repair failure.
      }
    }

    // 8e. SAFETY NET — never serve an unsafe or hollowed-out session. If the
    // contraindication strip (or corrections) left a non-rest workout with no
    // real content, fall back to a recovery/mobility day rather than risk it.
    if (validated.type !== "rest" && validated.sections.every((s) => s.exercises.length === 0)) {
      validated = {
        type: "rest",
        title: "Recovery / mobility",
        duration_minutes: 30,
        tss_estimate: 10,
        rpe_target: 2,
        sections: [],
        coach_note:
          "Held back today: the generated session conflicted with an injury/constraint on file. " +
          "Do easy mobility and recovery instead, and re-generate once it's cleared.",
      } as typeof validated;
      review.violations.push("fell back to a safe recovery day (unsafe/empty after review)");
    }

    // Recompute cost in case the repair pass replaced `outcome`.
    const finalCost = estimateCostUsd(outcome.provider, outcome.promptTokens, outcome.completionTokens, customPriceFromProfile(outcome.provider, profile), outcome.model);

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

    // Any exercise the model introduced outside the catalog becomes a custom
    // entry (with the AI's metadata) so the loggers recognise it by name.
    await registerUnknownExercises(admin, userId, validated);

    await admin.from("generation_logs").insert({
      user_id: userId,
      feature: "workout",
      provider: outcome.provider,
      model: outcome.model,
      prompt_tokens: outcome.promptTokens,
      completion_tokens: outcome.completionTokens,
      estimated_cost_usd: finalCost,
      system_prompt: SYSTEM_PROMPT,
      user_prompt: userPrompt,
      raw_response: outcome.text,
      parsed_ok: true,
      // Record any content-review findings (post-correction) for auditing.
      error: review.violations.length ? JSON.stringify(review.violations) : null,
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
            type: validated.type === "run" ? "Run" : validated.type === "ride" ? "Ride" : validated.type === "swim" ? "Swim" : "Workout",
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

    log.info("done", {
      ms: Date.now() - startedAt,
      provider: outcome.provider,
      model: outcome.model,
      type: validated.type,
      tss: validated.tss_estimate,
      costUsd: finalCost,
      fallbacks: outcome.attempts.length,
      pushError: pushError ?? undefined,
    });
    return json({
      workout: validated,
      workout_id: planned.id,
      provider: outcome.provider,
      model: outcome.model,
      estimated_cost_usd: finalCost,
      fallback_attempts: outcome.attempts,
      intervals_event_id: intervalsEventId,
      push_error: pushError,
      review_violations: review.violations,
      tss_replaced: review.tssReplaced ?? null,
    });
  } catch (e) {
    log.error("failed", { ms: Date.now() - startedAt, err: e instanceof Error ? e.message : String(e) });
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
