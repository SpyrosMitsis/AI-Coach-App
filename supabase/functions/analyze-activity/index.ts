// analyze-activity — Garmin-style post-workout execution analysis + AI feedback.
//
// POST { activity_id: "<completed_activities.id>", force?: boolean }
//
// 1. Load the activity + the planned workout for that date.
// 2. Pull per-second streams from Intervals.icu (time/velocity/HR/distance).
// 3. Compute execution components (duration / load / intensity adherence),
//    a downsampled pace+HR series with the planned target pace band, and
//    per-km splits.
// 4. Ask the athlete's LLM for short coach feedback on the execution.
// 5. Cache everything in completed_activities.analysis_json (force re-runs).

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, decryptSecret, getUserId } from "../_shared/supabase.ts";
import {
  getActivityStreams,
  getAthleteFull,
  runHrZones,
  runPaceZones,
  runSportSettings,
} from "../_shared/intervals.ts";
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
} from "../_shared/analysis.ts";
import { llmGenerateWithFallback } from "../_shared/llm.ts";
import { llmAccess } from "../_shared/llm_keys.ts";
import type { Workout } from "../_shared/types.ts";

function typeMatches(plannedType: string, actualType: string | null): boolean {
  const a = (actualType ?? "").toLowerCase();
  switch (plannedType) {
    case "run": return a.includes("run") || a.includes("walk");
    case "ride": return a.includes("ride") || a.includes("bike") || a.includes("cycl");
    case "strength": return a.includes("weight") || a.includes("strength") || a.includes("gym") || a === "workout";
    default: return false;
  }
}

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const body = await req.json().catch(() => ({}));
    const activityId: string | undefined = body.activity_id;
    if (!activityId) return json({ error: "activity_id required" }, 400);

    const admin = adminClient();
    const { data: act } = await admin
      .from("completed_activities")
      .select("*")
      .eq("id", activityId)
      .eq("user_id", userId)
      .single();
    if (!act) return json({ error: "activity not found" }, 404);

    if (act.analysis_json && body.force !== true) {
      return json(act.analysis_json);
    }

    const { data: profile } = await admin
      .from("user_profiles")
      .select("*")
      .eq("id", userId)
      .single();
    const onboarding = profile?.onboarding ?? {};

    // --- planned session that day (prefer a type match) ---------------------
    const { data: plans } = await admin
      .from("planned_workouts")
      .select("id, type, workout_json")
      .eq("user_id", userId)
      .eq("date", act.date)
      .neq("type", "rest");
    const plannedRow = (plans ?? []).find((p) => typeMatches(p.type, act.type)) ?? (plans ?? [])[0] ?? null;
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
            // Touch runPaceZones so a future pace-zone table is easy to add.
            void runPaceZones;
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
      .eq("id", activityId)
      .eq("user_id", userId);

    return json(analysis);
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
