// Agentic coach tools — give the coach real reads into the athlete's data and
// real actions (plan, generate, set goals, remember). Provider-agnostic: the
// coach selects a tool by emitting JSON, the server runs it here and feeds back
// an observation. Works on any LLM the user has configured (no native
// function-calling required).
import type { SupabaseClient } from "jsr:@supabase/supabase-js@2";

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
  args: string; // human description of the args shape
}

export const TOOL_CATALOG: ToolDef[] = [
  { name: "get_fitness", kind: "read", args: "{}", description: "Current fitness: CTL (fitness), ATL (fatigue), TSB (form), weekly TSS load and acute:chronic ratio." },
  { name: "get_recent_activities", kind: "read", args: "{ days?: number = 14 }", description: "Recently completed activities from Intervals.icu (type, date, distance, duration, HR, TSS)." },
  { name: "get_planned_week", kind: "read", args: "{ week_start?: 'YYYY-MM-DD' }", description: "The planned sessions for a week (title, type, completed, locked). Defaults to the current week." },
  { name: "get_strength_summary", kind: "read", args: "{}", description: "Recent strength sessions and 28-day working-set volume per muscle group." },
  { name: "get_profile", kind: "read", args: "{}", description: "Athlete profile: goal, experience, available days, session length, equipment, injuries, thresholds (LTHR/FTP/pace) and upcoming races." },
  { name: "plan_week", kind: "act", args: "{ start_date?: 'YYYY-MM-DD' }", description: "Generate/regenerate a full training week (pushes near-term sessions to the watch). Use after agreeing a plan with the athlete." },
  { name: "generate_workout", kind: "act", args: "{ date?: 'YYYY-MM-DD', type?: 'run'|'strength'|'auto', duration?: number, request?: string, lock?: boolean }", description: "Create one workout on a date. Pass `request` for a specific session ('easy 8k', 'upper-body push'); set lock=true to fix it." },
  { name: "set_goal_race", kind: "act", args: "{ name: string, date: 'YYYY-MM-DD' }", description: "Set the athlete's goal race; this anchors periodization and taper." },
  { name: "remember", kind: "act", args: "{ fact: string }", description: "Save a durable fact/preference/constraint about the athlete (e.g. 'dislikes burpees', 'left knee niggle')." },
];

export function toolCatalogPrompt(): string {
  const lines = TOOL_CATALOG.map((t) => `- ${t.name}(${t.args}) — ${t.description}`);
  return lines.join("\n");
}

async function callFunction(auth: string, name: string, body: unknown): Promise<unknown> {
  const url = `${Deno.env.get("SUPABASE_URL")}/functions/v1/${name}`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "Authorization": auth, "Content-Type": "application/json" },
    body: JSON.stringify(body ?? {}),
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
        const a = data ?? [];
        const fit = a.find((r) => r.ctl != null) ?? { ctl: 0, atl: 0 };
        const ctl = fit.ctl ?? 0, atl = fit.atl ?? 0;
        const since7 = iso(Date.now() - 7 * DAY);
        const load7 = a.filter((r) => (r.date ?? "") >= since7).reduce((s, r) => s + (r.tss ?? 0), 0);
        const load28 = a.reduce((s, r) => s + (r.tss ?? 0), 0);
        const acwr = load28 > 0 ? (load7 / (load28 / 4)).toFixed(2) : "n/a";
        return JSON.stringify({ ctl: +ctl.toFixed(0), atl: +atl.toFixed(0), tsb: +(ctl - atl).toFixed(0), weekly_tss: Math.round(load7), acwr });
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
          .select("date, type, completed, locked, workout_json")
          .eq("user_id", userId).gte("date", start).lte("date", end).order("date");
        const rows = (data ?? []).map((r) => ({
          date: r.date, type: r.type, completed: r.completed, locked: r.locked,
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
          .select("name, date, priority, distance").eq("user_id", userId).order("date");
        return JSON.stringify({
          goal: o.goal, goal_date: o.goal_date, experience: o.experience,
          days: o.days, session_min: o.session_duration, equipment: o.equipment,
          injuries: o.injury_history, lthr: o.lthr, ftp: o.ftp, threshold_pace: o.threshold_pace_per_km,
          races: races ?? [], known: p?.coach_knowledge ?? "",
        });
      }
      case "plan_week": {
        const r = await callFunction(auth, "plan-week", { start_date: args.start_date }) as Record<string, unknown>;
        return JSON.stringify({ ok: !r.error, error: r.error ?? null, scheduled: r.scheduled, week_focus: r.week_focus, pushed: r.pushed });
      }
      case "generate_workout": {
        const r = await callFunction(auth, "generate-workout", {
          date: args.date, type: args.type ?? "auto", duration: args.duration ?? 60,
          request: args.request, lock: args.lock === true, push: true,
        }) as Record<string, unknown>;
        const w = r.workout as { title?: string } | undefined;
        return JSON.stringify({ ok: !!r.workout_id, title: w?.title ?? null, date: args.date ?? "today", error: r.error ?? null });
      }
      case "set_goal_race": {
        if (!args.name || !args.date) return "error: name and date are required";
        const { data: p } = await admin.from("user_profiles").select("onboarding").eq("id", userId).single();
        const o = (p?.onboarding ?? {}) as Record<string, unknown>;
        await admin.from("user_profiles").update({ onboarding: { ...o, goal: args.name, goal_date: args.date } }).eq("id", userId);
        return JSON.stringify({ ok: true, goal: args.name, goal_date: args.date });
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
