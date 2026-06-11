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
  buildSeries,
  buildSplits,
  combineScore,
  fmtPace,
  hrBandForZones,
  paceBandForZones,
  paceInBandScore,
  parsePaceToSec,
  plannedZones,
  RawStreams,
  scoreLabel,
} from "./analysis.ts";
import { llmGenerateWithFallback } from "./llm.ts";
import { llmAccess } from "./llm_keys.ts";
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
            "time", "velocity_smooth", "heartrate", "distance",
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
      detail: `${Math.round(frac * 100)}% of moving time in the target ${zoneText} pace band (${fmtPace(paceBand.lo)}–${fmtPace(paceBand.hi)})`,
    });
  } else if (hrBand && act.avg_hr) {
    const inBand = act.avg_hr >= hrBand.lo && act.avg_hr <= hrBand.hi;
    const dist = inBand ? 0 : Math.min(Math.abs(act.avg_hr - hrBand.lo), Math.abs(act.avg_hr - hrBand.hi));
    components.push({
      name: "Intensity",
      score: Math.max(0, Math.round(95 - dist * 3)),
      detail: `avg HR ${act.avg_hr} bpm vs target ${zoneText} (${hrBand.lo}–${hrBand.hi} bpm)`,
    });
  }

  const score = planned ? combineScore(components) : null;
  const label = score != null ? scoreLabel(score) : (planned ? "Not enough data" : "No plan that day");

  // --- AI coach feedback ------------------------------------------------------
  let feedback: string | null = null;
  let feedbackProvider: string | null = null;
  try {
    const { chain, resolveKey, resolveModel } = llmAccess(admin, userId, profile ?? {});
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
        .map((s) => `km${s.km} ${fmtPace(s.sec)}${s.avg_hr ? ` @${s.avg_hr}bpm` : ""}`)
        .join("; ");
      const prompt = `Review this completed session against its plan.

PLANNED: ${planned ? `${planned.title} — ${planned.type}, ~${planned.duration_minutes} min, ~${planned.tss_estimate} TSS.${zoneText ? ` Target intensity ${zoneText}.` : ""} Structure: ${(planned.sections ?? []).map((s) => s.name).join(" → ")}` : "nothing was planned this day."}
ACTUAL: ${actualBits || "no summary data"}.
${splitText ? `SPLITS: ${splitText}.` : ""}
${components.length ? `EXECUTION: ${components.map((c) => `${c.name} ${c.score}/100 (${c.detail})`).join("; ")}. Overall ${score}/100.` : ""}

Write 3-5 sentences of specific coach feedback: what was executed well, where pacing/effort drifted and the physiological consequence, and ONE concrete cue for next time. Plain prose, no headings, no bullet points.`;
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
      );
      feedback = out.text.trim() || null;
      feedbackProvider = out.provider;
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

interface LoggedSet { reps?: number; weight_kg?: number; rpe?: number }

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
    admin.from("completed_activities").select("type, duration_seconds, avg_hr, tss, data_json")
      .eq("user_id", userId).eq("date", date),
  ]);

  const logged = logs ?? [];
  if (!logged.length) {
    throw new Error("no logged strength session on this date — sync the session first");
  }
  const planned = ((plans ?? [])[0]?.workout_json ?? null) as Workout | null;
  const watch = (watchActs ?? []).find((a) =>
    activityMatchesPlanned("strength", a.type)
  ) ?? null;

  // --- per-exercise actuals ---------------------------------------------------
  const exercises = logged.map((l) => {
    const sets = (Array.isArray(l.sets) ? l.sets : []) as LoggedSet[];
    const volume = sets.reduce((s, x) => s + (x.weight_kg ?? 0) * (x.reps ?? 0), 0);
    const top = sets.reduce((m, x) => Math.max(m, x.weight_kg ?? 0), 0);
    return {
      name: l.exercise_name as string,
      actual_sets: sets.length,
      top_weight_kg: top > 0 ? top : null,
      volume_kg: Math.round(volume),
      planned: null as string | null,
    };
  });
  const totalVolume = exercises.reduce((s, e) => s + (e.volume_kg ?? 0), 0);
  const totalSets = exercises.reduce((s, e) => s + e.actual_sets, 0);

  // --- planned exercises + components ------------------------------------------
  const components: AnalysisComponent[] = [];
  let plannedExercises: { name: string; sets: number; reps: string; weight_kg: number | null }[] = [];
  if (planned) {
    plannedExercises = (planned.sections ?? []).flatMap((s) =>
      (s.exercises ?? []).filter((e) => (e.sets ?? 0) > 0).map((e) => ({
        name: e.name,
        sets: e.sets ?? 0,
        reps: e.reps ?? "",
        weight_kg: e.weight_kg ?? null,
      }))
    );
    // Annotate each logged exercise with its planned prescription.
    for (const ex of exercises) {
      const p = plannedExercises.find((pe) => namesMatch(pe.name, ex.name));
      if (p) ex.planned = `${p.sets}×${p.reps}${p.weight_kg ? ` @ ${p.weight_kg}kg` : ""}`;
    }

    if (plannedExercises.length) {
      const done = plannedExercises.filter((pe) =>
        exercises.some((ex) => namesMatch(pe.name, ex.name))
      );
      const missed = plannedExercises.filter((pe) =>
        !exercises.some((ex) => namesMatch(pe.name, ex.name))
      ).map((pe) => pe.name);
      const frac = done.length / plannedExercises.length;
      components.push({
        name: "Coverage",
        score: Math.round(frac * 100),
        detail: `${done.length} of ${plannedExercises.length} planned exercises completed` +
          (missed.length ? ` — skipped: ${missed.slice(0, 3).join(", ")}${missed.length > 3 ? "…" : ""}` : ""),
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
    const { chain, resolveKey, resolveModel } = llmAccess(admin, userId, profile ?? {});
    if (chain.length) {
      const actualText = exercises.map((e) =>
        `${e.name}: ${e.actual_sets} sets${e.top_weight_kg ? `, top ${e.top_weight_kg}kg` : ""}, ${e.volume_kg}kg volume${e.planned ? ` (planned ${e.planned})` : ""}`
      ).join("\n");
      const plannedText = planned
        ? `${planned.title} — ~${planned.duration_minutes} min. Prescribed: ${plannedExercises.map((p) => `${p.name} ${p.sets}×${p.reps}${p.weight_kg ? ` @${p.weight_kg}kg` : ""}`).join("; ")}`
        : "nothing was planned this day.";
      const watchText = watch
        ? `Watch: ${watch.duration_seconds ? `${Math.round(watch.duration_seconds / 60)} min` : ""}${watch.avg_hr ? `, avg HR ${watch.avg_hr}` : ""}${watch.tss ? `, TSS ${Math.round(watch.tss)}` : ""}.`
        : "";
      const prompt = `Review this completed STRENGTH session against its plan.

PLANNED: ${plannedText}
LOGGED (${totalSets} sets, ${Math.round(totalVolume)}kg total volume):
${actualText}
${watchText}
${components.length ? `EXECUTION: ${components.map((c) => `${c.name} ${c.score}/100 (${c.detail})`).join("; ")}. Overall ${score}/100.` : ""}

Write 3-5 sentences of specific coach feedback: completion vs the plan, load selection vs the prescription (under/over-shooting weights), anything notable about volume or exercise balance, and ONE concrete cue for the next session (e.g. which lift to progress and by how much). Plain prose, no headings, no bullet points.`;
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
      );
      feedback = out.text.trim() || null;
      feedbackProvider = out.provider;
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
    planned_title: planned?.title ?? null,
    generated_at: new Date().toISOString(),
  };

  await admin.from("strength_analyses").upsert(
    { user_id: userId, date, analysis_json: analysis },
    { onConflict: "user_id,date" },
  );

  return analysis;
}

// Automatic post-sync analysis: pick the last couple of days' endurance
// activities that match a planned session and aren't analyzed yet, and analyze
// them (LLM feedback included) so results are ready when the user opens the
// app or gets the evening prompt. Best-effort; caps LLM spend at 2/run.
export async function autoAnalyzeRecent(admin: SupabaseClient, userId: string): Promise<number> {
  try {
    const since = new Date(Date.now() - 2 * 86_400_000).toISOString().slice(0, 10);
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
    if (!endurance.length) return 0;

    const { data: profile } = await admin
      .from("user_profiles")
      .select("*")
      .eq("id", userId)
      .single();

    let analyzed = 0;
    for (const act of endurance) {
      if (analyzed >= 2) break;
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
        analyzed++;
      } catch (_e) {
        // best-effort per activity
      }
    }
    return analyzed;
  } catch (_e) {
    return 0;
  }
}
