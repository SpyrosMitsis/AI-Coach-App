"use client";

// Race-day toolkit + periodized block timeline.
//
// Anchors on the next A-race (falling back to onboarding goal_date): countdown,
// current training phase, a pacing plan derived from threshold pace, a taper
// checklist inside the final 14 days, and a week-by-week timeline of the
// planned block (focus, sessions, planned vs actual load) to race day.

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { createClient } from "@/lib/supabase-browser";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { CheckSquare, Flag, Square, Timer } from "lucide-react";
import type { Race } from "@shared/types";

const DAY = 86_400_000;
const todayIso = () => new Date().toISOString().slice(0, 10);

function mondayOf(date: string): string {
  const d = new Date(date + "T00:00:00");
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return d.toISOString().slice(0, 10);
}
function addDays(iso: string, n: number): string {
  const d = new Date(iso + "T00:00:00");
  d.setDate(d.getDate() + n);
  return d.toISOString().slice(0, 10);
}

// Mirrors trainingPhase in supabase/functions/_shared/prompt.ts.
function phaseOf(weeksToGoal: number): { name: string; color: string } {
  if (weeksToGoal <= 2) return { name: "Taper", color: "bg-sky-500/70" };
  if (weeksToGoal <= 6) return { name: "Peak", color: "bg-red-500/70" };
  if (weeksToGoal <= 14) return { name: "Build", color: "bg-amber-500/70" };
  return { name: "Base", color: "bg-emerald-500/70" };
}

// "m:ss" → seconds, tolerant of junk.
function paceToSec(p?: string | null): number | null {
  const m = String(p ?? "").trim().match(/^(\d+):(\d{2})$/);
  return m ? parseInt(m[1]) * 60 + parseInt(m[2]) : null;
}
function secToPace(s: number): string {
  return `${Math.floor(s / 60)}:${String(Math.round(s % 60)).padStart(2, "0")}/km`;
}

// Race-pace offsets vs threshold pace (rule-of-thumb, sec/km).
const PACING: { label: string; km: number; offset: number }[] = [
  { label: "5K", km: 5, offset: -10 },
  { label: "10K", km: 10, offset: 0 },
  { label: "Half marathon", km: 21.097, offset: 12 },
  { label: "Marathon", km: 42.195, offset: 30 },
];

const TAPER_CHECKLIST = [
  "Volume cut 40–60% — keep a little intensity (strides, short tempo)",
  "No new shoes, gear, or foods — race in what you've trained in",
  "Plan race nutrition: carbs the days before, gels/fluids per ~30–45 min",
  "Sleep is the priority this week — bank it early, don't chase it the last night",
  "Recon the course: profile, aid stations, where it gets hard",
  "Plan pacing: even or slight negative split; commit to an easy first 10–15%",
  "Lay out kit + bib the night before; know your travel/start logistics",
];

interface WeekRow {
  start: string;
  phase: { name: string; color: string };
  sessions: number;
  focus: string | null;
  plannedTss: number;
  actualTss: number;
  isCurrent: boolean;
}

export function RacePlan() {
  const supabase = createClient();
  const [checked, setChecked] = useState<Set<number>>(new Set());

  const data = useQuery({
    queryKey: ["race-plan"],
    queryFn: async () => {
      const today = todayIso();
      const [{ data: races }, { data: profile }] = await Promise.all([
        supabase.from("races").select("*").gte("date", today).order("date"),
        supabase.from("user_profiles").select("onboarding").maybeSingle(),
      ]);
      const onboarding = (profile?.onboarding ?? {}) as { goal?: string; goal_date?: string; threshold_pace_per_km?: string; target_pace?: string };
      const upcoming = (races ?? []) as Race[];
      const aRace = upcoming.find((r) => r.priority === "A") ?? upcoming[0] ?? null;
      const race = aRace ?? (onboarding.goal_date && onboarding.goal_date >= today
        ? { name: onboarding.goal ?? "Goal race", date: onboarding.goal_date, priority: "A", distance: null, notes: null } as Race
        : null);
      if (!race) return { race: null as Race | null, weeks: [] as WeekRow[], thresholdPace: null as string | null };

      const start = mondayOf(today);
      const end = race.date;
      const [{ data: planned }, { data: acts }, { data: weekPlans }] = await Promise.all([
        supabase.from("planned_workouts").select("date, type, completed, workout_json").gte("date", start).lte("date", end),
        supabase.from("completed_activities").select("date, tss").gte("date", start).lte("date", end),
        supabase.from("week_plans").select("start_date, focus").gte("start_date", start).lte("start_date", end),
      ]);

      const focusByWeek = new Map((weekPlans ?? []).map((w) => [w.start_date as string, w.focus as string]));
      const raceMs = new Date(end + "T00:00:00").getTime();
      const weeks: WeekRow[] = [];
      for (let ws = start; ws <= end; ws = addDays(ws, 7)) {
        const we = addDays(ws, 6);
        const weeksToGoal = Math.max(0, Math.round((raceMs - new Date(ws + "T00:00:00").getTime()) / (7 * DAY)));
        const inWeek = (d: string | null) => !!d && d >= ws && d <= we;
        const plannedWeek = (planned ?? []).filter((p) => inWeek(p.date));
        const plannedTss = plannedWeek.reduce(
          (s, p) => s + (((p.workout_json as { tss_estimate?: number })?.tss_estimate) ?? 0), 0);
        const actualTss = (acts ?? []).filter((a) => inWeek(a.date)).reduce((s, a) => s + (a.tss ?? 0), 0);
        weeks.push({
          start: ws,
          phase: phaseOf(weeksToGoal),
          sessions: plannedWeek.filter((p) => p.type !== "rest").length,
          focus: focusByWeek.get(ws) ?? null,
          plannedTss: Math.round(plannedTss),
          actualTss: Math.round(actualTss),
          isCurrent: today >= ws && today <= we,
        });
      }
      return { race, weeks, thresholdPace: onboarding.threshold_pace_per_km ?? onboarding.target_pace ?? null };
    },
  });

  const race = data.data?.race;
  const weeks = useMemo(() => data.data?.weeks ?? [], [data.data]);
  const maxTss = Math.max(1, ...weeks.map((w) => Math.max(w.plannedTss, w.actualTss)));

  if (data.isLoading) return <div className="h-32 animate-pulse rounded-lg bg-secondary" />;
  if (!race) {
    return (
      <Card>
        <CardContent className="p-4 text-sm text-muted-foreground">
          No upcoming race — add one above (or set a goal date in onboarding) to unlock the
          block timeline, countdown, and race-day toolkit.
        </CardContent>
      </Card>
    );
  }

  const daysLeft = Math.ceil((new Date(race.date + "T00:00:00").getTime() - Date.now()) / DAY);
  const weeksLeft = Math.floor(daysLeft / 7);
  const phase = phaseOf(weeksLeft);
  const thresholdSec = paceToSec(data.data?.thresholdPace);
  const inTaper = daysLeft <= 14;

  return (
    <div className="space-y-4">
      {/* Countdown */}
      <Card>
        <CardContent className="flex items-center justify-between p-4">
          <div>
            <p className="label-caps flex items-center gap-1.5"><Flag className="h-3.5 w-3.5" /> {race.name}</p>
            <p className="mt-1 text-sm text-muted-foreground">
              {new Date(race.date + "T00:00:00").toLocaleDateString("en", { weekday: "long", month: "long", day: "numeric" })}
              {race.distance ? ` · ${race.distance}` : ""}
            </p>
          </div>
          <div className="text-right">
            <p className="text-3xl font-bold">{daysLeft}<span className="ml-1 text-sm font-normal text-muted-foreground">days</span></p>
            <span className={cn("mt-1 inline-block rounded-full px-2 py-0.5 text-[11px] font-medium text-background", phase.color)}>
              {phase.name} phase
            </span>
          </div>
        </CardContent>
      </Card>

      {/* Block timeline to race day */}
      <Card>
        <CardHeader className="pb-2"><CardTitle className="text-base">Block to race day</CardTitle></CardHeader>
        <CardContent className="space-y-1.5">
          {weeks.length === 0 && (
            <p className="text-sm text-muted-foreground">Nothing planned yet — use “Plan block to race” on the Calendar.</p>
          )}
          {weeks.map((w) => (
            <div
              key={w.start}
              className={cn("rounded-lg border border-border/60 px-3 py-2", w.isCurrent && "border-primary/60 bg-primary/5")}
            >
              <div className="flex items-center justify-between text-xs">
                <span className="flex items-center gap-2">
                  <span className={cn("h-2 w-2 rounded-full", w.phase.color)} />
                  <span className="font-medium">{w.start.slice(5)}</span>
                  <span className="text-muted-foreground">{w.focus ?? w.phase.name}</span>
                  {w.isCurrent && <span className="text-primary">· this week</span>}
                </span>
                <span className="text-muted-foreground">{w.sessions} sessions</span>
              </div>
              <div className="mt-1.5 space-y-1">
                <LoadBar label="plan" value={w.plannedTss} max={maxTss} className="bg-primary/60" />
                {w.actualTss > 0 && <LoadBar label="done" value={w.actualTss} max={maxTss} className="bg-emerald-500/70" />}
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      {/* Pacing plan */}
      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="flex items-center gap-2 text-base"><Timer className="h-4 w-4" /> Pacing plan</CardTitle>
        </CardHeader>
        <CardContent>
          {thresholdSec ? (
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-muted-foreground">
                  <th className="pb-2">Distance</th><th>Race pace</th><th>Finish</th>
                </tr>
              </thead>
              <tbody>
                {PACING.map((p) => {
                  const pace = thresholdSec + p.offset;
                  const total = pace * p.km;
                  const h = Math.floor(total / 3600);
                  const m = Math.floor((total % 3600) / 60);
                  const s = Math.round(total % 60);
                  return (
                    <tr key={p.label} className="border-t border-border/60">
                      <td className="py-2 font-medium">{p.label}</td>
                      <td>{secToPace(pace)}</td>
                      <td className="text-muted-foreground">
                        {h > 0 ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          ) : (
            <p className="text-sm text-muted-foreground">
              Set your threshold pace (above, under Zones) to get race-pace targets here.
            </p>
          )}
          <p className="mt-2 text-[11px] text-muted-foreground">
            Estimated from threshold pace — adjust for course, heat, and how the taper feels.
          </p>
        </CardContent>
      </Card>

      {/* Taper checklist — the final 14 days */}
      {inTaper && (
        <Card className="border-sky-500/30">
          <CardHeader className="pb-2"><CardTitle className="text-base">Taper checklist</CardTitle></CardHeader>
          <CardContent className="space-y-1">
            {TAPER_CHECKLIST.map((item, i) => {
              const done = checked.has(i);
              return (
                <button
                  key={i}
                  onClick={() => setChecked((prev) => {
                    const next = new Set(prev);
                    if (done) next.delete(i); else next.add(i);
                    return next;
                  })}
                  className="flex w-full items-start gap-2 rounded-md px-1 py-1.5 text-left text-sm hover:bg-secondary/60"
                >
                  {done
                    ? <CheckSquare className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
                    : <Square className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />}
                  <span className={cn(done && "text-muted-foreground line-through")}>{item}</span>
                </button>
              );
            })}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function LoadBar({ label, value, max, className }: { label: string; value: number; max: number; className: string }) {
  return (
    <div className="flex items-center gap-2">
      <span className="w-8 text-[10px] text-muted-foreground">{label}</span>
      <div className="h-2 flex-1 overflow-hidden rounded-full bg-secondary">
        <div className={cn("h-full rounded-full", className)} style={{ width: `${Math.min(100, (value / max) * 100)}%` }} />
      </div>
      <span className="w-10 text-right text-[10px] text-muted-foreground">{value || ""}</span>
    </div>
  );
}
