// ============================================================================
// Post-workout analysis orchestration — shared by analyze-activity (on-demand,
// from the app) and sync-intervals (automatic, right after new activities
// land). Fetches streams, scores execution vs the plan, asks the athlete's
// LLM for coach feedback, and caches everything in
// completed_activities.analysis_json.
// ============================================================================

import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";
import { decryptSecret } from "./supabase.ts";
import {
  getActivityStreams,
  getAthleteFull,
  runHrZones,
  runSportSettings,
} from "./intervals.ts";
import {
  adherenceScore,
  AnalysisComponent,
  type AnalysisSeries,
  buildSeries,
  buildSplits,
  combineScore,
  fmtPace,
  hrBandForZones,
  markSplitsInBand,
  paceBandForZones,
  paceInBandScore,
  parsePaceToSec,
  plannedZones,
  RawStreams,
  scoreLabel,
} from "./analysis.ts";
import { customPriceFromProfile, estimateCostUsd, llmGenerateWithFallback } from "./llm.ts";
import { llmAccess } from "./llm_keys.ts";
import type { LlmResult } from "./types.ts";

// Best-effort cost row so analysis feedback shows up in spend diagnostics —
// and, for Pro users on the hosted key, gets metered by the quota RPC.
async function logFeedbackCost(
  admin: SupabaseClient,
  userId: string,
  profile: Record<string, unknown> | null | undefined,
  out: LlmResult,
  hosted: boolean,
): Promise<void> {
  try {
    const cost = estimateCostUsd(
      out.provider,
      out.promptTokens,
      out.completionTokens,
      customPriceFromProfile(out.provider, profile),
      out.model,
    );
    await admin.from("generation_logs").insert({
      user_id: userId,
      feature: "analyze",
      hosted,
      provider: out.provider,
      model: out.model,
      prompt_tokens: out.promptTokens,
      completion_tokens: out.completionTokens,
      estimated_cost_usd: cost,
      parsed_ok: true,
    });
  } catch {
    // best effort
  }
}
import { customExercises, muscleForName } from "./exercise_catalog.ts";
import type { Workout } from "./types.ts";

export function activityMatchesPlanned(plannedType: string, actualType: string | null): boolean {
  const a = (actualType ?? "").toLowerCase();
  switch (plannedType) {
    case "run": return a.includes("run") || a.includes("walk");
    case "ride": return a.includes("ride") || a.includes("bike") || a.includes("cycl");
    case "strength": return a.includes("weight") || a.includes("strength") || a.includes("gym") || a === "workout";
    default: return false;
  }
}

// deno-lint-ignore no-explicit-any
type Row = Record<string, any>;

// Computes + persists the analysis for one completed_activities row.
// `profile` is the user's full user_profiles row.
export async function runActivityAnalysis(
  admin: SupabaseClient,
  userId: string,
  act: Row,
  profile: Row | null,
): Promise<Row> {
  const onboarding = profile?.onboarding ?? {};

  // --- planned session that day (prefer a type match) ---------------------
  const { data: plans } = await admin
    .from("planned_workouts")
    .select("id, type, workout_json")
    .eq("user_id", userId)
    .eq("date", act.date)
    .neq("type", "rest");
  const plannedRow = (plans ?? []).find((p) => activityMatchesPlanned(p.type, act.type)) ??
    (plans ?? [])[0] ?? null;
  const planned = (plannedRow?.workout_json ?? null) as Workout | null;

  // --- streams + athlete zone settings ------------------------------------
  const isManual = String(act.intervals_id ?? "").startsWith("manual:");
  let raw: RawStreams | null = null;
  let streamsError: string | null = null;
  let thresholdSecPerKm = parsePaceToSec(onboarding.threshold_pace_per_km);
  let hrZones: { zone: string; min: number; max: number }[] =
    (onboarding.hr_zones as { zone: string; min: number; max: number }[] | undefined) ?? [];

  if (!isManual && profile?.intervals_athlete_id && profile?.intervals_api_key_encrypted) {
    try {
      const apiKey = await decryptSecret(admin, profile.intervals_api_key_encrypted);
      if (apiKey) {
        const [streams, athlete] = await Promise.all([
          getActivityStreams(apiKey, String(act.intervals_id), [
            "time", "velocity_smooth", "heartrate", "distance", "cadence", "watts",
          ]),
          getAthleteFull(profile.intervals_athlete_id, apiKey).catch(() => null),
        ]);
        const by = (t: string) => streams.find((s) => s.type === t)?.data ?? [];
        const time = by("time") as number[];
        if (time.length) {
          raw = {
            time,
            velocity: by("velocity_smooth"),
            hr: by("heartrate"),
            distance: by("distance"),
            cadence: by("cadence"),
            power: by("watts"),
          };
        }
        if (athlete) {
          if (!thresholdSecPerKm) {
            const tp = runSportSettings(athlete)?.threshold_pace; // m/s
            if (tp && tp > 0) thresholdSecPerKm = Math.round(1000 / tp);
          }
          if (!hrZones.length) {
            hrZones = runHrZones(athlete).map((z) => ({ zone: z.name, min: z.min, max: z.max }));
          }
        }
      }
    } catch (e) {
      streamsError = e instanceof Error ? e.message : String(e);
    }
  }

  const series = raw ? buildSeries(raw) : null;
  const splits = raw ? buildSplits(raw) : [];

  // --- target bands from the plan ------------------------------------------
  const pZones = planned ? plannedZones(planned, "pace_zone") : [];
  const hZones = planned ? plannedZones(planned, "hr_zone") : [];
  const paceBand = paceBandForZones(pZones, thresholdSecPerKm);
  const hrBand = hrBandForZones(hZones, hrZones);
  // Per-split target adherence — which kilometres landed in the planned band.
  const onTargetSplits = markSplitsInBand(splits, paceBand, hrBand);
  const bandedSplits = splits.filter((s) => s.in_band != null).length;
  const adherenceText = bandedSplits ? ` · ${onTargetSplits}/${bandedSplits} km on target` : "";
  const zoneText = pZones.length
    ? (Math.min(...pZones) === Math.max(...pZones)
      ? `Z${pZones[0]}`
      : `Z${Math.min(...pZones)}-Z${Math.max(...pZones)}`)
    : hZones.length
    ? `Z${Math.min(...hZones)}${Math.min(...hZones) === Math.max(...hZones) ? "" : `-Z${Math.max(...hZones)}`}`
    : "";

  // --- execution components --------------------------------------------------
  const components: AnalysisComponent[] = [];
  const actualMin = (act.duration_seconds ?? 0) / 60;
  if (planned && planned.duration_minutes > 0 && actualMin > 0) {
    components.push({
      name: "Duration",
      score: adherenceScore(actualMin, planned.duration_minutes),
      detail: `${Math.round(actualMin)} min vs ${Math.round(planned.duration_minutes)} min planned`,
    });
  }
  if (planned && (planned.tss_estimate ?? 0) > 0 && (act.tss ?? 0) > 0) {
    components.push({
      name: "Load",
      score: adherenceScore(act.tss, planned.tss_estimate),
      detail: `TSS ${Math.round(act.tss)} vs ~${Math.round(planned.tss_estimate)} planned`,
    });
  }
  if (series && paceBand) {
    const { score, frac } = paceInBandScore(series.pace, paceBand);
    components.push({
      name: "Intensity",
      score,
      detail: `${Math.round(frac * 100)}% of moving time in the target ${zoneText} pace band (${fmtPace(paceBand.lo)}-${fmtPace(paceBand.hi)})${adherenceText}`,
    });
  } else if (hrBand && act.avg_hr) {
    const inBand = act.avg_hr >= hrBand.lo && act.avg_hr <= hrBand.hi;
    const dist = inBand ? 0 : Math.min(Math.abs(act.avg_hr - hrBand.lo), Math.abs(act.avg_hr - hrBand.hi));
    components.push({
      name: "Intensity",
      score: Math.max(0, Math.round(95 - dist * 3)),
      detail: `avg HR ${act.avg_hr} bpm vs target ${zoneText} (${hrBand.lo}-${hrBand.hi} bpm)${adherenceText}`,
    });
  }

  const score = planned ? combineScore(components) : null;
  const label = score != null ? scoreLabel(score) : (planned ? "Not enough data" : "No plan that day");

  // --- AI coach feedback ------------------------------------------------------
  let feedback: string | null = null;
  let feedbackProvider: string | null = null;
  try {
    const { chain, resolveKey, resolveModel, resolveBaseUrl, hosted } = await llmAccess(admin, userId, profile ?? {});
    if (chain.length) {
      const actualBits = [
        act.type && `type ${act.type}`,
        actualMin > 0 && `${Math.round(actualMin)} min`,
        act.distance_m > 0 && `${(act.distance_m / 1000).toFixed(2)} km`,
        act.distance_m > 0 && act.duration_seconds > 0 &&
          `avg pace ${fmtPace(act.duration_seconds / (act.distance_m / 1000))}`,
        act.avg_hr && `avg HR ${act.avg_hr}`,
        act.tss && `TSS ${Math.round(act.tss)}`,
      ].filter(Boolean).join(", ");
      const splitText = splits.slice(0, 20)
        .map((s) =>
          `km${s.km} ${fmtPace(s.sec)}${s.avg_hr ? ` @${s.avg_hr}bpm` : ""}` +
          (s.in_band == null ? "" : s.in_band ? " ✓" : " ✗")
        )
        .join("; ");
      const targetText = paceBand
        ? ` Target ${zoneText} pace ${fmtPace(paceBand.lo)}-${fmtPace(paceBand.hi)} /km (${onTargetSplits}/${bandedSplits} km on target).`
        : hrBand
        ? ` Target ${zoneText} HR ${hrBand.lo}-${hrBand.hi} bpm (${onTargetSplits}/${bandedSplits} km on target).`
        : "";
      const prompt = `Review this completed session against its plan.

PLANNED: ${planned ? `${planned.title}, ${planned.type}, ~${planned.duration_minutes} min, ~${planned.tss_estimate} TSS.${zoneText ? ` Target intensity ${zoneText}.` : ""} Structure: ${(planned.sections ?? []).map((s) => s.name).join(" → ")}` : "nothing was planned this day."}
ACTUAL: ${actualBits || "no summary data"}.${targetText}
${splitText ? `SPLITS (✓ = in the target band, ✗ = out): ${splitText}.` : ""}
${components.length ? `EXECUTION: ${components.map((c) => `${c.name} ${c.score}/100 (${c.detail})`).join("; ")}. Overall ${score}/100.` : ""}

Write 3-5 sentences of specific coach feedback: what was executed well, where pacing/effort drifted and the physiological consequence, how well the work intervals held the target band (use the ✓/✗ splits), and ONE concrete cue for next time. Plain prose, no headings, no bullet points.`;
      const out = await llmGenerateWithFallback(
        chain,
        {
          prompt,
          systemPrompt:
            "You are an expert endurance coach reviewing a completed workout. Be specific, concise, encouraging but honest.",
          jsonMode: false,
        },
        resolveKey,
        resolveModel,
        resolveBaseUrl,
      );
      feedback = out.text.trim() || null;
      feedbackProvider = out.provider;
      await logFeedbackCost(admin, userId, profile, out, hosted);
    }
  } catch (_e) {
    // best-effort — analysis is still useful without AI feedback
  }

  const analysis = {
    ok: true,
    score,
    label,
    components,
    feedback,
    feedback_provider: feedbackProvider,
    series,
    target: paceBand || hrBand
      ? {
        pace_lo: paceBand?.lo ?? null,
        pace_hi: paceBand?.hi ?? null,
        hr_lo: hrBand?.lo ?? null,
        hr_hi: hrBand?.hi ?? null,
        zones: zoneText,
      }
      : null,
    splits,
    planned_title: planned?.title ?? null,
    streams_error: streamsError,
    generated_at: new Date().toISOString(),
  };

  await admin
    .from("completed_activities")
    .update({ analysis_json: analysis })
    .eq("id", act.id)
    .eq("user_id", userId);

  return analysis;
}

// ============================================================================
// STRENGTH session analysis — planned (planned_workouts) vs logged
// (strength_logs) + optional watch recording. Keyed by user+date and cached in
// strength_analyses.
// ============================================================================

const normName = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, "");
const namesMatch = (a: string, b: string) => {
  const na = normName(a), nb = normName(b);
  return na === nb || na.includes(nb) || nb.includes(na);
};

// Coverage matching for a completed strength session, substitution-aware. A
// planned exercise is "covered" by a name match OR — failing that — by an
// as-yet-unused logged exercise that hits the SAME muscle (a legitimate swap,
// e.g. Preacher Curl for the planned Machine Bicep Curl). Matching is greedy and
// 1:1 so one lift can't paper over two planned slots; "Other"/"Cardio"/unknown
// muscles never substitute (too loose to be meaningful). Pure + unit-testable.
export interface CoverageItem {
  name: string;
  muscle: string | null;
}
export interface CoverageResult {
  done: number;
  total: number;
  substitutions: { logged: string; planned: string }[];
  skipped: string[];
  /** For each planned item, the logged exercise name covering it (or null). */
  coveredBy: (string | null)[];
}

export function matchCoverage(
  planned: CoverageItem[],
  logged: CoverageItem[],
): CoverageResult {
  const realMuscle = (m: string | null) =>
    m && m !== "Other" && m !== "Cardio" ? m : null;
  const consumed = new Set<number>();
  const coveredBy: (string | null)[] = planned.map(() => null);
  const substitutions: { logged: string; planned: string }[] = [];

  // Pass 1 — exact / substring name matches.
  planned.forEach((pe, pi) => {
    const li = logged.findIndex((le, i) => !consumed.has(i) && namesMatch(pe.name, le.name));
    if (li >= 0) {
      consumed.add(li);
      coveredBy[pi] = logged[li].name;
    }
  });
  // Pass 2 — same-muscle substitutions for the still-uncovered planned items.
  planned.forEach((pe, pi) => {
    if (coveredBy[pi]) return;
    const pm = realMuscle(pe.muscle);
    if (!pm) return;
    const li = logged.findIndex((le, i) => !consumed.has(i) && realMuscle(le.muscle) === pm);
    if (li >= 0) {
      consumed.add(li);
      coveredBy[pi] = logged[li].name;
      substitutions.push({ logged: logged[li].name, planned: pe.name });
    }
  });

  const skipped = planned.filter((_, pi) => !coveredBy[pi]).map((pe) => pe.name);
  return { done: planned.length - skipped.length, total: planned.length, substitutions, skipped, coveredBy };
}

interface LoggedSet { reps?: number; weight_kg?: number; rpe?: number }

// Fetch just the heartrate stream of a watch recording and build a downsampled
// series for the chart. Cheap (no LLM) and best-effort — returns null when there
// is no synced watch activity, no credentials, or no HR data.
async function watchHrSeries(
  admin: SupabaseClient,
  profile: Row | null,
  watchIntervalsId: unknown,
): Promise<AnalysisSeries | null> {
  const id = watchIntervalsId ? String(watchIntervalsId) : null;
  if (
    !id || id.startsWith("manual:") ||
    !profile?.intervals_athlete_id || !profile?.intervals_api_key_encrypted
  ) return null;
  try {
    const apiKey = await decryptSecret(admin, profile.intervals_api_key_encrypted);
    if (!apiKey) return null;
    const streams = await getActivityStreams(apiKey, id, ["time", "heartrate"]);
    const by = (t: string) => streams.find((s) => s.type === t)?.data ?? [];
    const time = by("time") as number[];
    if (!time.length) return null;
    const built = buildSeries({ time, velocity: [], hr: by("heartrate"), distance: [] });
    return built.hr.some((h) => h != null) ? built : null;
  } catch (_e) {
    return null; // best-effort — HR chart is optional
  }
}

// Resolve the HR series for a strength session by date — loads the profile +
// paired watch recording itself, so callers (e.g. the cache-return path) can
// backfill a series into an older analysis without re-running the LLM.
export async function fetchStrengthHrSeries(
  admin: SupabaseClient,
  userId: string,
  date: string,
): Promise<AnalysisSeries | null> {
  const [{ data: profile }, { data: watchActs }] = await Promise.all([
    admin.from("user_profiles")
      .select("intervals_athlete_id, intervals_api_key_encrypted").eq("id", userId).single(),
    admin.from("completed_activities")
      .select("intervals_id, type").eq("user_id", userId).eq("date", date),
  ]);
  const watch = (watchActs ?? []).find((a) => activityMatchesPlanned("strength", a.type)) ?? null;
  return watchHrSeries(admin, profile, watch?.intervals_id);
}

export async function runStrengthAnalysis(
  admin: SupabaseClient,
  userId: string,
  date: string,
): Promise<Row> {
  const [{ data: profile }, { data: plans }, { data: logs }, { data: watchActs }] = await Promise.all([
    admin.from("user_profiles").select("*").eq("id", userId).single(),
    admin.from("planned_workouts").select("type, workout_json")
      .eq("user_id", userId).eq("date", date).eq("type", "strength"),
    admin.from("strength_logs").select("exercise_name, sets, estimated_1rm, muscle_groups")
      .eq("user_id", userId).eq("date", date),
    admin.from("completed_activities").select("intervals_id, type, duration_seconds, avg_hr, tss, data_json")
      .eq("user_id", userId).eq("date", date),
  ]);

  const logged = logs ?? [];
  if (!logged.length) {
    throw new Error("no logged strength session on this date, sync the session first");
  }
  const planned = ((plans ?? [])[0]?.workout_json ?? null) as Workout | null;
  const watch = (watchActs ?? []).find((a) =>
    activityMatchesPlanned("strength", a.type)
  ) ?? null;

  // --- HR trace from the paired watch recording (for the chart) -------------
  // Strength sessions have no pace/distance, but the watch still records HR.
  const series = await watchHrSeries(admin, profile, watch?.intervals_id);

  // --- per-exercise actuals ---------------------------------------------------
  const exercises = logged.map((l) => {
    const sets = (Array.isArray(l.sets) ? l.sets : []) as LoggedSet[];
    const volume = sets.reduce((s, x) => s + (x.weight_kg ?? 0) * (x.reps ?? 0), 0);
    const top = sets.reduce((m, x) => Math.max(m, x.weight_kg ?? 0), 0);
    const mg = Array.isArray(l.muscle_groups) ? (l.muscle_groups as string[]) : [];
    return {
      name: l.exercise_name as string,
      actual_sets: sets.length,
      top_weight_kg: top > 0 ? top : null,
      volume_kg: Math.round(volume),
      muscle: (mg[0] && String(mg[0])) || null,
      planned: null as string | null,
    };
  });
  const totalVolume = exercises.reduce((s, e) => s + (e.volume_kg ?? 0), 0);
  const totalSets = exercises.reduce((s, e) => s + e.actual_sets, 0);

  // --- planned exercises + components ------------------------------------------
  const components: AnalysisComponent[] = [];
  let plannedExercises: {
    name: string;
    sets: number;
    reps: string;
    weight_kg: number | null;
    muscle: string | null;
  }[] = [];
  // Accepted same-muscle swaps (planned ← logged), for the coach prompt below.
  let substitutions: { logged: string; planned: string }[] = [];
  if (planned) {
    // Custom exercises let us resolve muscles for names outside the catalog.
    const custom = await customExercises(admin, userId).catch(() => []);
    plannedExercises = (planned.sections ?? []).flatMap((s) =>
      (s.exercises ?? []).filter((e) => (e.sets ?? 0) > 0).map((e) => {
        const explicit = (e as { muscle?: string | null }).muscle;
        return {
          name: e.name,
          sets: e.sets ?? 0,
          reps: e.reps ?? "",
          weight_kg: e.weight_kg ?? null,
          muscle: (explicit && explicit !== "Other" ? explicit : null) ?? muscleForName(e.name, custom),
        };
      })
    );

    if (plannedExercises.length) {
      // Substitution-aware coverage: a same-muscle swap counts as completed.
      for (const ex of exercises) if (!ex.muscle) ex.muscle = muscleForName(ex.name, custom);
      const cov = matchCoverage(plannedExercises, exercises.map((ex) => ({ name: ex.name, muscle: ex.muscle })));
      substitutions = cov.substitutions;

      // Annotate each covering logged exercise (name match or substitution) with
      // its planned prescription so the load comparison still appears.
      cov.coveredBy.forEach((loggedName, pi) => {
        if (!loggedName) return;
        const pe = plannedExercises[pi];
        const ex = exercises.find((e) => e.name === loggedName);
        if (ex) ex.planned = `${pe.sets}×${pe.reps}${pe.weight_kg ? ` @ ${pe.weight_kg}kg` : ""}`;
      });

      const subText = cov.substitutions.length
        ? `, substituted: ${cov.substitutions.map((s) => `${s.logged}→${s.planned}`).slice(0, 3).join(", ")}`
        : "";
      const missText = cov.skipped.length
        ? `, skipped: ${cov.skipped.slice(0, 3).join(", ")}${cov.skipped.length > 3 ? "…" : ""}`
        : "";
      components.push({
        name: "Coverage",
        score: Math.round((cov.done / cov.total) * 100),
        detail: `${cov.done} of ${cov.total} planned exercises completed${subText}${missText}`,
      });

      const plannedSets = plannedExercises.reduce((s, pe) => s + pe.sets, 0);
      if (plannedSets > 0 && totalSets > 0) {
        components.push({
          name: "Volume",
          score: adherenceScore(totalSets, plannedSets),
          detail: `${totalSets} working sets vs ${plannedSets} planned`,
        });
      }
    }
    if (planned.duration_minutes > 0 && watch?.duration_seconds) {
      components.push({
        name: "Duration",
        score: adherenceScore(watch.duration_seconds / 60, planned.duration_minutes),
        detail: `${Math.round(watch.duration_seconds / 60)} min vs ${Math.round(planned.duration_minutes)} min planned`,
      });
    }
  }

  const score = planned ? combineScore(components) : null;
  const label = score != null ? scoreLabel(score) : (planned ? "Not enough data" : "No plan that day");

  // --- AI coach feedback ---------------------------------------------------------
  let feedback: string | null = null;
  let feedbackProvider: string | null = null;
  try {
    const { chain, resolveKey, resolveModel, resolveBaseUrl, hosted } = await llmAccess(admin, userId, profile ?? {});
    if (chain.length) {
      const actualText = exercises.map((e) =>
        `${e.name}: ${e.actual_sets} sets${e.top_weight_kg ? `, top ${e.top_weight_kg}kg` : ""}, ${e.volume_kg}kg volume${e.planned ? ` (planned ${e.planned})` : ""}`
      ).join("\n");
      const plannedText = planned
        ? `${planned.title}, ~${planned.duration_minutes} min. Prescribed: ${plannedExercises.map((p) => `${p.name} ${p.sets}×${p.reps}${p.weight_kg ? ` @${p.weight_kg}kg` : ""}`).join("; ")}`
        : "nothing was planned this day.";
      const watchText = watch
        ? `Watch: ${watch.duration_seconds ? `${Math.round(watch.duration_seconds / 60)} min` : ""}${watch.avg_hr ? `, avg HR ${watch.avg_hr}` : ""}${watch.tss ? `, TSS ${Math.round(watch.tss)}` : ""}.`
        : "";
      const subsText = substitutions.length
        ? `SUBSTITUTIONS (athlete swapped a same-muscle lift, these COUNT as completed, do NOT call them missed): ${
          substitutions.map((s) => `${s.logged} for the planned ${s.planned}`).join("; ")
        }.`
        : "";
      const prompt = `Review this completed STRENGTH session against its plan.

PLANNED: ${plannedText}
LOGGED (${totalSets} sets, ${Math.round(totalVolume)}kg total volume):
${actualText}
${subsText}
${watchText}
${components.length ? `EXECUTION: ${components.map((c) => `${c.name} ${c.score}/100 (${c.detail})`).join("; ")}. Overall ${score}/100.` : ""}

Write 3-5 sentences of specific coach feedback: completion vs the plan, load selection vs the prescription (under/over-shooting weights), anything notable about volume or exercise balance, and ONE concrete cue for the next session (e.g. which lift to progress and by how much). If the athlete substituted a same-muscle exercise, treat it as completed (acknowledge the swap, don't scold a miss). Plain prose, no headings, no bullet points.`;
      const out = await llmGenerateWithFallback(
        chain,
        {
          prompt,
          systemPrompt:
            "You are an expert strength coach reviewing a completed lifting session. Be specific, concise, encouraging but honest.",
          jsonMode: false,
        },
        resolveKey,
        resolveModel,
        resolveBaseUrl,
      );
      feedback = out.text.trim() || null;
      feedbackProvider = out.provider;
      await logFeedbackCost(admin, userId, profile, out, hosted);
    }
  } catch (_e) {
    // best-effort
  }

  const analysis = {
    ok: true,
    kind: "strength",
    score,
    label,
    components,
    feedback,
    feedback_provider: feedbackProvider,
    exercises,
    total_volume_kg: Math.round(totalVolume),
    total_sets: totalSets,
    watch: watch
      ? {
        duration_min: watch.duration_seconds ? Math.round(watch.duration_seconds / 60) : null,
        avg_hr: watch.avg_hr ?? null,
        tss: watch.tss ?? null,
      }
      : null,
    series,
    planned_title: planned?.title ?? null,
    generated_at: new Date().toISOString(),
  };

  await admin.from("strength_analyses").upsert(
    { user_id: userId, date, analysis_json: analysis },
    { onConflict: "user_id,date" },
  );

  return analysis;
}

// Automatic post-sync analysis (best-effort, LLM spend capped per run):
// - endurance: last 2 days' run/ride activities that match a planned session
//   and aren't analyzed yet;
// - strength: last 2 days' logged sessions with a planned strength workout —
//   including a re-run when the watch recording arrived AFTER the first
//   analysis (the immediate post-finish analysis has no HR/duration yet).
// Results are ready when the user opens the app or gets the evening prompt.
export async function autoAnalyzeRecent(admin: SupabaseClient, userId: string): Promise<number> {
  let analyzed = 0;
  const since = new Date(Date.now() - 2 * 86_400_000).toISOString().slice(0, 10);

  // --- endurance -------------------------------------------------------------
  try {
    const { data: acts } = await admin
      .from("completed_activities")
      .select("*")
      .eq("user_id", userId)
      .gte("date", since)
      .is("analysis_json", null)
      .order("date", { ascending: false });
    const endurance = (acts ?? []).filter((a) => {
      const t = (a.type ?? "").toLowerCase();
      return t.includes("run") || t.includes("ride") || t.includes("bike") || t.includes("cycl");
    });
    if (endurance.length) {
      const { data: profile } = await admin
        .from("user_profiles")
        .select("*")
        .eq("id", userId)
        .single();
      let done = 0;
      for (const act of endurance) {
        if (done >= 2) break;
        // Only auto-analyze when a plan existed that day — without one there is
        // no execution score, and the user can still analyze manually.
        const { data: plans } = await admin
          .from("planned_workouts")
          .select("id")
          .eq("user_id", userId)
          .eq("date", act.date)
          .neq("type", "rest")
          .limit(1);
        if (!plans?.length) continue;
        try {
          await runActivityAnalysis(admin, userId, act, profile);
          done++;
        } catch (_e) { /* best-effort per activity */ }
      }
      analyzed += done;
    }
  } catch (_e) { /* best-effort */ }

  // --- strength ----------------------------------------------------------------
  try {
    const { data: logRows } = await admin
      .from("strength_logs")
      .select("date")
      .eq("user_id", userId)
      .gte("date", since);
    const dates = [...new Set((logRows ?? []).map((r) => r.date as string))];
    let done = 0;
    for (const date of dates) {
      if (done >= 2) break;
      const { data: plans } = await admin
        .from("planned_workouts")
        .select("id")
        .eq("user_id", userId)
        .eq("date", date)
        .eq("type", "strength")
        .limit(1);
      if (!plans?.length) continue;
      const { data: existing } = await admin
        .from("strength_analyses")
        .select("analysis_json")
        .eq("user_id", userId)
        .eq("date", date)
        .maybeSingle();
      if (existing?.analysis_json) {
        // Re-run only when the first analysis predates the watch recording.
        if (existing.analysis_json.watch != null) continue;
        const { data: w } = await admin
          .from("completed_activities")
          .select("type")
          .eq("user_id", userId)
          .eq("date", date);
        const watchNow = (w ?? []).some((a) => activityMatchesPlanned("strength", a.type));
        if (!watchNow) continue;
      }
      try {
        await runStrengthAnalysis(admin, userId, date);
        done++;
      } catch (_e) { /* best-effort per date */ }
    }
    analyzed += done;
  } catch (_e) { /* best-effort */ }

  return analyzed;
}
