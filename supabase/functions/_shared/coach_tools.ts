// Agentic coach tools — give the coach real reads into the athlete's data and
// real actions (plan, generate, set goals, remember). Provider-agnostic: the
// coach selects a tool by emitting JSON, the server runs it here and feeds back
// an observation. Works on any LLM the user has configured (no native
// function-calling required).
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";

import { computeRecovery } from "./recovery.ts";
import { freshnessWord, recoveryWord } from "./prompt.ts";
import { applyFallbackFitness } from "./load.ts";

const DAY = 86400000;
const iso = (d: number) => new Date(d).toISOString().slice(0, 10);

function mondayOf(date = new Date()): string {
  const d = new Date(date);
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return d.toISOString().slice(0, 10);
}

export interface ToolDef {
  name: string;
  kind: "read" | "act";
  description: string;
  args: string; // human description of the args shape (JSON-protocol prompt)
  schema: Record<string, unknown>; // JSON Schema (native tool calling)
}

const NO_ARGS = { type: "object", properties: {}, additionalProperties: false };

export const TOOL_CATALOG: ToolDef[] = [
  {
    name: "get_fitness", kind: "read", args: "{}", schema: NO_ARGS,
    description: "Current fitness: CTL (fitness), ATL (fatigue), TSB (form), weekly TSS load and acute:chronic ratio.",
  },
  {
    name: "get_recent_activities", kind: "read", args: "{ days?: number = 14 }",
    schema: { type: "object", properties: { days: { type: "number", description: "Lookback window in days (default 14, max 60)" } } },
    description: "Recently completed activities recorded by the athlete's watch or Intervals.icu (type, date, distance, duration, HR, TSS).",
  },
  {
    name: "get_planned_week", kind: "read", args: "{ week_start?: 'YYYY-MM-DD' }",
    schema: { type: "object", properties: { week_start: { type: "string", description: "Week start date YYYY-MM-DD (defaults to the current week's Monday)" } } },
    description: "The planned sessions for a week (title, type, completed, locked). Defaults to the current week.",
  },
  {
    name: "get_strength_summary", kind: "read", args: "{}", schema: NO_ARGS,
    description: "Recent strength sessions and 28-day working-set volume per muscle group.",
  },
  {
    name: "get_profile", kind: "read", args: "{}", schema: NO_ARGS,
    description: "Athlete profile: goal, experience, available days, session length, equipment, injuries, thresholds (LTHR/FTP/pace) and upcoming goals/races across sports (run/ride/swim/strength), each with date, priority and target.",
  },
  {
    name: "get_readiness", kind: "read", args: "{}", schema: NO_ARGS,
    description: "Today's recovery/readiness score (0-100) with the signals behind it: HRV trend, resting-HR trend, sleep, subjective wellness.",
  },
  {
    name: "get_execution_analysis", kind: "read", args: "{}", schema: NO_ARGS,
    description: "Measured execution of recent sessions vs their plan: per-workout score (0-100), what drifted (duration/load/intensity, or skipped exercises for strength). Use to answer 'how did my run/lift go?' and to autoregulate.",
  },
  {
    name: "plan_week", kind: "act", args: "{ start_date?: 'YYYY-MM-DD' }",
    schema: { type: "object", properties: { start_date: { type: "string", description: "YYYY-MM-DD; defaults to today" } } },
    description: "Generate/regenerate a full training week (pushes near-term sessions to the watch). Use after agreeing a plan with the athlete.",
  },
  {
    name: "generate_workout", kind: "act",
    args: "{ date?: 'YYYY-MM-DD', type?: 'run'|'strength'|'auto', duration?: number, request?: string, lock?: boolean }",
    schema: {
      type: "object",
      properties: {
        date: { type: "string", description: "YYYY-MM-DD; defaults to today" },
        type: { type: "string", enum: ["run", "strength", "auto"] },
        duration: { type: "number", description: "Target minutes (default 60)" },
        request: { type: "string", description: "Free-text session request, e.g. 'easy 8k' or 'upper-body push'" },
        lock: { type: "boolean", description: "Fix the session so re-planning won't replace it" },
      },
    },
    description: "Create one workout on a date. Pass `request` for a specific session ('easy 8k', 'upper-body push'); set lock=true to fix it.",
  },
  {
    name: "move_workout", kind: "act",
    args: "{ new_date: 'YYYY-MM-DD', workout_id?: string, date?: 'YYYY-MM-DD' }",
    schema: {
      type: "object",
      properties: {
        new_date: { type: "string", description: "Target date YYYY-MM-DD" },
        workout_id: { type: "string", description: "Planned workout id (from get_planned_week)" },
        date: { type: "string", description: "Alternative to workout_id: move the (first incomplete) session on this date" },
      },
      required: ["new_date"],
    },
    description: "Move a planned workout to another date (the watch event moves with it). Identify it by workout_id from get_planned_week, or just by its current date.",
  },
  {
    name: "set_rest_day", kind: "act", args: "{ date: 'YYYY-MM-DD' }",
    schema: {
      type: "object",
      properties: { date: { type: "string", description: "Day to clear to rest, YYYY-MM-DD" } },
      required: ["date"],
    },
    description: "Turn a day into a rest day, removes any planned session on that date (the watch event is removed too). Use when the athlete needs a day off.",
  },
  {
    name: "make_easier", kind: "act", args: "{ date?: 'YYYY-MM-DD' }",
    schema: {
      type: "object",
      properties: { date: { type: "string", description: "Day to ease off; defaults to today" } },
    },
    description: "Regenerate a day's workout noticeably easier, lower intensity and volume, aerobic/recovery focus. Use when the athlete feels rough or wants to back off without skipping entirely.",
  },
  {
    name: "set_goal_race", kind: "act", args: "{ name: string, date: 'YYYY-MM-DD', target_pace?: string }",
    schema: {
      type: "object",
      properties: {
        name: { type: "string" },
        date: { type: "string", description: "YYYY-MM-DD" },
        target_pace: { type: "string", description: "optional run target pace like 4:45/km" },
      },
      required: ["name", "date"],
    },
    description: "Set the athlete's goal event; this anchors periodization and taper. For run goals you may also set the target pace.",
  },
  {
    name: "remember", kind: "act", args: "{ fact: string }",
    schema: { type: "object", properties: { fact: { type: "string" } }, required: ["fact"] },
    description: "Save a durable fact/preference/constraint about the athlete (e.g. 'dislikes burpees', 'left knee niggle').",
  },
  {
    name: "update_profile",
    kind: "act",
    args: "{ ftp?: number, lthr?: number, threshold_pace_per_km?: 'm:ss', css_per_100m?: 'm:ss', weekly_tss_target?: number }",
    schema: {
      type: "object",
      properties: {
        ftp: { type: "number", description: "Cycling FTP in watts (50-600)" },
        lthr: { type: "number", description: "Lactate threshold heart rate in bpm (120-210)" },
        threshold_pace_per_km: { type: "string", description: "Run threshold pace per km, like 4:45" },
        css_per_100m: { type: "string", description: "Swim critical pace per 100m, like 1:55" },
        weekly_tss_target: { type: "number", description: "Weekly training load target in TSS (100-1000)" },
      },
    },
    description:
      "Save the athlete's performance numbers when they mention them in conversation (a new FTP test, " +
      "a threshold pace, a swim CSS, a weekly load target). Better numbers make every generated workout " +
      "more accurate, so save them whenever the athlete states one. Only include fields the athlete gave.",
  },
];

/** Tool definitions in the shape native tool-calling APIs expect. */
export function nativeToolDefs(): { name: string; description: string; input_schema: Record<string, unknown> }[] {
  return TOOL_CATALOG.map((t) => ({ name: t.name, description: t.description, input_schema: t.schema }));
}

export function toolCatalogPrompt(): string {
  const lines = TOOL_CATALOG.map((t) => `- ${t.name}(${t.args}), ${t.description}`);
  return lines.join("\n");
}

async function callFunction(auth: string, name: string, body: unknown): Promise<unknown> {
  const url = `${Deno.env.get("SUPABASE_URL")}/functions/v1/${name}`;
  // Generous deadline: these proxy to generate-workout / plan-week, which run
  // their own LLM calls — but a hung one shouldn't stall the coach turn forever.
  const res = await fetch(url, {
    method: "POST",
    headers: { "Authorization": auth, "Content-Type": "application/json" },
    body: JSON.stringify(body ?? {}),
    signal: AbortSignal.timeout(120_000),
  });
  const text = await res.text();
  try { return JSON.parse(text); } catch { return { raw: text, status: res.status }; }
}

/** Execute a coach tool; returns a concise observation string for the LLM. */
export async function executeTool(
  admin: SupabaseClient,
  userId: string,
  auth: string,
  name: string,
  args: Record<string, unknown>,
): Promise<string> {
  try {
    switch (name) {
      case "get_fitness": {
        const since = iso(Date.now() - 28 * DAY);
        const { data } = await admin.from("completed_activities")
          .select("date, tss, ctl, atl").eq("user_id", userId).gte("date", since)
          .order("date", { ascending: false });
        // No intervals-provided CTL? Fill estimated values from stored TSS so
        // the coach still sees a fitness signal without intervals.icu.
        const a = await applyFallbackFitness(admin, userId, iso(Date.now()), data ?? []);
        const fit = a.find((r) => r.ctl != null) ?? { ctl: 0, atl: 0 };
        const ctl = fit.ctl ?? 0, atl = fit.atl ?? 0;
        const since7 = iso(Date.now() - 7 * DAY);
        const load7 = a.filter((r) => (r.date ?? "") >= since7).reduce((s, r) => s + (r.tss ?? 0), 0);
        const load28 = a.reduce((s, r) => s + (r.tss ?? 0), 0);
        const acwr = load28 > 0 ? (load7 / (load28 / 4)).toFixed(2) : "n/a";
        return JSON.stringify({
          freshness: freshnessWord(ctl - atl),
          ctl: +ctl.toFixed(0), atl: +atl.toFixed(0), tsb: +(ctl - atl).toFixed(0),
          weekly_tss: Math.round(load7), acwr,
          note: "Interpret this in plain words, don't read the raw numbers back to the athlete.",
        });
      }
      case "get_recent_activities": {
        const days = Math.min(60, Math.max(1, Number(args.days) || 14));
        const since = iso(Date.now() - days * DAY);
        const { data } = await admin.from("completed_activities")
          .select("type, date, distance_m, duration_seconds, avg_hr, tss")
          .eq("user_id", userId).gte("date", since).order("date", { ascending: false }).limit(40);
        const rows = (data ?? []).map((r) => ({
          date: r.date, type: r.type,
          km: r.distance_m ? +(r.distance_m / 1000).toFixed(1) : null,
          min: r.duration_seconds ? Math.round(r.duration_seconds / 60) : null,
          hr: r.avg_hr, tss: r.tss ? Math.round(r.tss) : null,
        }));
        return JSON.stringify(rows);
      }
      case "get_planned_week": {
        const start = (typeof args.week_start === "string" && args.week_start) || mondayOf();
        const end = iso(new Date(start + "T00:00:00").getTime() + 6 * DAY);
        const { data } = await admin.from("planned_workouts")
          .select("id, date, type, completed, locked, workout_json")
          .eq("user_id", userId).gte("date", start).lte("date", end).order("date");
        const rows = (data ?? []).map((r) => ({
          id: r.id, date: r.date, type: r.type, completed: r.completed, locked: r.locked,
          title: (r.workout_json as { title?: string })?.title ?? "",
        }));
        return JSON.stringify({ week_start: start, sessions: rows });
      }
      case "get_strength_summary": {
        const cutoff = Date.now() - 28 * DAY;
        const { data: ws } = await admin.from("strength_workouts")
          .select("id, name, started_at, total_volume_kg").eq("user_id", userId)
          .gte("started_at", cutoff).order("started_at", { ascending: false }).limit(50);
        const workouts = ws ?? [];
        const ids = workouts.map((w) => w.id);
        let volByMuscle: Record<string, number> = {};
        if (ids.length) {
          const { data: sets } = await admin.from("strength_workout_sets")
            .select("muscle, is_warmup, workout_id").in("workout_id", ids);
          for (const s of sets ?? []) {
            if (s.is_warmup) continue;
            volByMuscle[s.muscle] = (volByMuscle[s.muscle] ?? 0) + 1;
          }
        }
        const recent = workouts.slice(0, 5).map((w) => ({
          name: w.name, date: iso(w.started_at), volume_kg: Math.round(w.total_volume_kg ?? 0),
        }));
        return JSON.stringify({ recent_sessions: recent, sets_per_muscle_28d: volByMuscle });
      }
      case "get_profile": {
        const { data: p } = await admin.from("user_profiles")
          .select("display_name, onboarding, coach_knowledge").eq("id", userId).single();
        const o = (p?.onboarding ?? {}) as Record<string, unknown>;
        const { data: races } = await admin.from("races")
          .select("name, date, priority, sport, distance, target").eq("user_id", userId).order("date");
        return JSON.stringify({
          goal: o.goal, goal_date: o.goal_date, experience: o.experience,
          days: o.days, session_min: o.session_duration, equipment: o.equipment,
          injuries: o.injury_history, lthr: o.lthr, ftp: o.ftp, threshold_pace: o.threshold_pace_per_km,
          target_pace: o.target_pace, goals: races ?? [], known: p?.coach_knowledge ?? "",
          // Richer onboarding: per-activity goals + experience, per-day availability.
          training_goals: o.goals, goals_by_sport: o.goals_by_sport,
          experience_by_sport: o.experience_by_sport, availability: o.day_availability,
        });
      }
      case "get_readiness": {
        const since = iso(Date.now() - 7 * DAY);
        const { data } = await admin.from("wellness_checkins")
          .select("date, energy, soreness, sleep_score, hrv_rmssd, resting_hr, zepp_sleep_minutes")
          .eq("user_id", userId).gte("date", since).order("date", { ascending: false });
        const wells = data ?? [];
        const isNum = (v: unknown): v is number => typeof v === "number";
        const chrono = [...wells].reverse();
        const rec = computeRecovery(
          wells,
          chrono.map((w) => (w as { hrv_rmssd?: number }).hrv_rmssd).filter(isNum),
          chrono.map((w) => (w as { resting_hr?: number }).resting_hr).filter(isNum),
        );
        return JSON.stringify({
          readiness: recoveryWord(rec.band),
          score: rec.score, band: rec.band, summary: rec.summary,
          hrv: rec.hrv ?? null, resting_hr: rec.rhr ?? null, sleep: rec.sleep ?? null,
          wellness_1to5: rec.wellness,
          note: "Interpret this in plain words, don't read the raw numbers back to the athlete.",
        });
      }
      case "get_execution_analysis": {
        interface Comp { name: string; score: number; detail: string }
        interface Stored { score?: number; label?: string; components?: Comp[]; feedback?: string }
        const { data: endu } = await admin.from("completed_activities")
          .select("date, type, analysis_json")
          .eq("user_id", userId).not("analysis_json", "is", null)
          .order("date", { ascending: false }).limit(5);
        const { data: str } = await admin.from("strength_analyses")
          .select("date, analysis_json")
          .eq("user_id", userId).order("date", { ascending: false }).limit(3);
        const brief = (kind: string, date: string, a: Stored) => ({
          date, kind,
          score: a.score ?? null, label: a.label ?? null,
          components: (a.components ?? []).map((c) => `${c.name} ${c.score}/100 (${c.detail})`),
        });
        const rows = [
          ...(endu ?? []).map((r) => brief(String(r.type ?? "endurance"), r.date, r.analysis_json as Stored)),
          ...(str ?? []).map((r) => brief("strength", r.date, r.analysis_json as Stored)),
        ].sort((x, y) => y.date.localeCompare(x.date));
        if (!rows.length) return JSON.stringify({ note: "no analyzed sessions yet, the athlete can analyze a workout from its detail page, or it happens automatically after a sync" });
        return JSON.stringify(rows);
      }
      case "move_workout": {
        const newDate = String(args.new_date ?? "");
        if (!/^\d{4}-\d{2}-\d{2}$/.test(newDate)) return "error: new_date (YYYY-MM-DD) is required";
        let workoutId = typeof args.workout_id === "string" ? args.workout_id : null;
        if (!workoutId && typeof args.date === "string") {
          const { data: rows } = await admin.from("planned_workouts")
            .select("id, type, completed")
            .eq("user_id", userId).eq("date", args.date).neq("type", "rest")
            .order("created_at", { ascending: false });
          workoutId = (rows ?? []).find((r) => !r.completed)?.id ?? (rows ?? [])[0]?.id ?? null;
        }
        if (!workoutId) return "error: no workout found, pass workout_id (see get_planned_week) or date";
        const r = await callFunction(auth, "move-workout", { workout_id: workoutId, new_date: newDate }) as Record<string, unknown>;
        return JSON.stringify({ ok: !r.error, old_date: r.old_date ?? null, new_date: r.new_date ?? newDate, event_moved: r.event_moved ?? null, error: r.error ?? null });
      }
      case "set_rest_day": {
        const d = String(args.date ?? "");
        if (!/^\d{4}-\d{2}-\d{2}$/.test(d)) return "error: date (YYYY-MM-DD) is required";
        // Clear every non-rest planned session that day; delete-workout removes the
        // watch event too (same path the app's delete uses).
        const { data: rows } = await admin.from("planned_workouts")
          .select("id, type").eq("user_id", userId).eq("date", d).neq("type", "rest");
        let cleared = 0;
        for (const row of rows ?? []) {
          await callFunction(auth, "delete-workout", { workout_id: row.id });
          cleared++;
        }
        return JSON.stringify({ ok: true, date: d, cleared });
      }
      case "make_easier": {
        const r = await callFunction(auth, "generate-workout", {
          date: args.date, type: "auto",
          request: "Make this day noticeably easier, lower the intensity and volume, keep it aerobic/recovery.",
          push: true,
        }) as Record<string, unknown>;
        const w = r.workout as { title?: string } | undefined;
        return JSON.stringify({ ok: !!r.workout_id, title: w?.title ?? null, date: args.date ?? "today", error: r.error ?? null });
      }
      case "plan_week": {
        const r = await callFunction(auth, "plan-week", { start_date: args.start_date }) as Record<string, unknown>;
        return JSON.stringify({ ok: !r.error, error: r.error ?? null, scheduled: r.scheduled, week_focus: r.week_focus, pushed: r.pushed });
      }
      case "generate_workout": {
        const r = await callFunction(auth, "generate-workout", {
          date: args.date, type: args.type ?? "auto",
          // Only pin a duration when explicitly requested — otherwise the
          // generator applies the athlete's flexible length preference.
          ...(typeof args.duration === "number" ? { duration: args.duration } : {}),
          request: args.request, lock: args.lock === true, push: true,
        }) as Record<string, unknown>;
        const w = r.workout as { title?: string } | undefined;
        return JSON.stringify({ ok: !!r.workout_id, title: w?.title ?? null, date: args.date ?? "today", error: r.error ?? null });
      }
      case "set_goal_race": {
        if (!args.name || !args.date) return "error: name and date are required";
        const { data: p } = await admin.from("user_profiles").select("onboarding").eq("id", userId).single();
        const o = (p?.onboarding ?? {}) as Record<string, unknown>;
        const pace = typeof args.target_pace === "string" && args.target_pace.trim() ? args.target_pace.trim() : undefined;
        await admin.from("user_profiles").update({
          onboarding: { ...o, goal: args.name, goal_date: args.date, ...(pace ? { target_pace: pace } : {}) },
        }).eq("id", userId);
        return JSON.stringify({ ok: true, goal: args.name, goal_date: args.date, target_pace: pace ?? null });
      }
      case "update_profile": {
        // Bounded per field: a misheard "FTP 9000" must not become the zones
        // every future workout is built on. Out-of-range values are rejected
        // per-field with a reason the model can relay.
        const paceRe = /^\d{1,2}:\d{2}$/;
        const updates: Record<string, unknown> = {};
        const rejected: string[] = [];
        const num = (v: unknown) => typeof v === "number" && Number.isFinite(v) ? v : null;
        const ftp = num(args.ftp);
        if (args.ftp !== undefined) {
          if (ftp !== null && ftp >= 50 && ftp <= 600) updates.ftp = Math.round(ftp);
          else rejected.push("ftp must be 50-600 watts");
        }
        const lthr = num(args.lthr);
        if (args.lthr !== undefined) {
          if (lthr !== null && lthr >= 120 && lthr <= 210) updates.lthr = Math.round(lthr);
          else rejected.push("lthr must be 120-210 bpm");
        }
        if (args.threshold_pace_per_km !== undefined) {
          const p = String(args.threshold_pace_per_km).trim();
          if (paceRe.test(p)) updates.threshold_pace_per_km = p;
          else rejected.push("threshold_pace_per_km must look like 4:45");
        }
        if (args.css_per_100m !== undefined) {
          const p = String(args.css_per_100m).trim();
          if (paceRe.test(p)) updates.css_per_100m = p;
          else rejected.push("css_per_100m must look like 1:55");
        }
        const tss = num(args.weekly_tss_target);
        if (args.weekly_tss_target !== undefined) {
          if (tss !== null && tss >= 100 && tss <= 1000) updates.weekly_tss_target = Math.round(tss);
          else rejected.push("weekly_tss_target must be 100-1000");
        }
        if (Object.keys(updates).length === 0) {
          return `error: nothing to save${rejected.length ? ` (${rejected.join("; ")})` : ""}`;
        }
        const { data: p } = await admin.from("user_profiles").select("onboarding").eq("id", userId).single();
        const o = (p?.onboarding ?? {}) as Record<string, unknown>;
        await admin.from("user_profiles").update({ onboarding: { ...o, ...updates } }).eq("id", userId);
        return JSON.stringify({ ok: true, saved: updates, ...(rejected.length ? { rejected } : {}) });
      }
      case "remember": {
        const fact = String(args.fact ?? "").trim();
        if (!fact) return "error: fact is empty";
        const { data: p } = await admin.from("user_profiles").select("coach_knowledge").eq("id", userId).single();
        const prev = (p?.coach_knowledge ?? "") as string;
        const next = (prev.trim() ? prev.trim() + "\n" : "") + `- ${fact}`;
        await admin.from("user_profiles").update({ coach_knowledge: next }).eq("id", userId);
        return JSON.stringify({ ok: true, remembered: fact });
      }
      default:
        return `error: unknown tool "${name}"`;
    }
  } catch (e) {
    return `error: ${e instanceof Error ? e.message : String(e)}`;
  }
}
