"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createClient } from "@/lib/supabase-browser";
import { api } from "@/lib/api";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { WorkoutDetail } from "@/components/workout-detail";
import { ActivityDetailCard } from "@/components/activity-detail";
import type { CompletedActivity, PlannedWorkout } from "@shared/types";
import { activityMeta, displayName, looksLike } from "@/lib/activity";
import { cn } from "@/lib/utils";
import { CalendarRange, Check, ChevronRight, Lock, LockOpen, RefreshCw, Sparkles, Wand2 } from "lucide-react";

const TYPE_COLOR: Record<string, string> = {
  run: "bg-primary/70",
  strength: "bg-sand/70",
  rest: "bg-muted-foreground/40",
};

function monthMatrix(year: number, month: number): (string | null)[] {
  const first = new Date(year, month, 1);
  const days = new Date(year, month + 1, 0).getDate();
  const lead = (first.getDay() + 6) % 7; // Monday-first
  const cells: (string | null)[] = Array(lead).fill(null);
  for (let d = 1; d <= days; d++) {
    cells.push(new Date(year, month, d).toISOString().slice(0, 10));
  }
  while (cells.length % 7 !== 0) cells.push(null);
  return cells;
}

/** Monday (ISO) of the week containing `date`. */
function mondayOf(date: string): string {
  const d = new Date(date + "T00:00:00");
  const back = (d.getDay() + 6) % 7;
  d.setDate(d.getDate() - back);
  return d.toISOString().slice(0, 10);
}

const todayIso = () => new Date().toISOString().slice(0, 10);

export default function CalendarPage() {
  const qc = useQueryClient();
  const supabase = createClient();
  const [cursor, setCursor] = useState(() => new Date());
  const [selected, setSelected] = useState<string | null>(null);
  const [selectedActivity, setSelectedActivity] = useState<CompletedActivity | null>(null);
  const [request, setRequest] = useState("");
  const [lockRequest, setLockRequest] = useState(true);
  const [banner, setBanner] = useState<string | null>(null);

  const year = cursor.getFullYear();
  const month = cursor.getMonth();
  const cells = useMemo(() => monthMatrix(year, month), [year, month]);
  const monthStart = new Date(year, month, 1).toISOString().slice(0, 10);
  const monthEnd = new Date(year, month + 1, 0).toISOString().slice(0, 10);

  const planned = useQuery({
    queryKey: ["planned", monthStart],
    queryFn: async () => {
      const { data, error } = await supabase
        .from("planned_workouts").select("*")
        .gte("date", monthStart).lte("date", monthEnd)
        .order("created_at", { ascending: false });
      if (error) throw error;
      return data as PlannedWorkout[];
    },
  });

  const activities = useQuery({
    queryKey: ["activities", monthStart],
    queryFn: async () => {
      const { data, error } = await supabase
        .from("completed_activities").select("*")
        .gte("date", monthStart).lte("date", monthEnd)
        .order("date", { ascending: false });
      if (error) throw error;
      return (data ?? []) as CompletedActivity[];
    },
  });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["planned", monthStart] });
    qc.invalidateQueries({ queryKey: ["activities", monthStart] });
  };
  const fail = (e: unknown) => setBanner((e as Error).message);

  const generate = useMutation({
    mutationFn: (vars: { date: string; request?: string; lock?: boolean }) =>
      api.generateWorkout({ date: vars.date, type: "auto", duration: 60, request: vars.request, lock: vars.lock }),
    onSuccess: () => { setRequest(""); setBanner(null); invalidate(); },
    onError: fail,
  });

  const planWeek = useMutation({
    mutationFn: (date: string) => api.planWeek(mondayOf(date)),
    onSuccess: (r) => { setBanner(`✓ Planned ${r.planned ?? ""} sessions this week.`.trim()); invalidate(); },
    onError: fail,
  });

  const planBlock = useMutation({
    mutationFn: () => api.planBlock(),
    onSuccess: (r) => { setBanner(`✓ Planned a ${r.weeks}-week block to your race (${r.weeks_planned} weeks built).`); invalidate(); },
    onError: fail,
  });

  const toggleLock = useMutation({
    mutationFn: async (w: PlannedWorkout) => {
      const { error } = await supabase.from("planned_workouts").update({ locked: !w.locked }).eq("id", w.id);
      if (error) throw error;
    },
    onSuccess: invalidate,
    onError: fail,
  });

  const markComplete = useMutation({
    mutationFn: async (w: PlannedWorkout) => {
      const { error } = await supabase.from("planned_workouts").update({ completed: true }).eq("id", w.id);
      if (error) throw error;
    },
    onSuccess: invalidate,
    onError: fail,
  });

  const sync = useMutation({
    mutationFn: () => api.syncIntervals(),
    onSuccess: (r) => { setBanner(`✓ Synced — ${r.activities_synced} activities up to date.`); invalidate(); },
    onError: fail,
  });

  // Adaptive re-plan: sync fresh actuals, reconcile this week's plan against
  // what was actually done, then re-plan from today around it.
  const adapt = useMutation({
    mutationFn: async () => {
      await api.syncIntervals().catch(() => {}); // best-effort fresh actuals
      const td = todayIso();
      const ws = mondayOf(td);
      const [{ data: pw }, { data: acts }] = await Promise.all([
        supabase.from("planned_workouts").select("*").gte("date", ws).lte("date", td),
        supabase.from("completed_activities").select("date, type").gte("date", ws).lte("date", td),
      ]);
      const byDate = new Map<string, string[]>();
      for (const a of (acts ?? []) as { date: string; type: string | null }[]) {
        const arr = byDate.get(a.date) ?? [];
        arr.push(a.type ?? "");
        byDate.set(a.date, arr);
      }
      let reconciled = 0;
      let missed = 0;
      for (const p of (pw ?? []) as PlannedWorkout[]) {
        if (p.completed || p.locked || p.type === "rest" || p.date >= td) continue;
        const did = (byDate.get(p.date) ?? []).some((t) => looksLike(p.type, t));
        if (did) { await supabase.from("planned_workouts").update({ completed: true }).eq("id", p.id); reconciled++; }
        else missed++;
      }
      await api.planWeek(td);
      return { reconciled, missed };
    },
    onSuccess: ({ reconciled, missed }) => {
      const parts = [];
      if (reconciled) parts.push(`matched ${reconciled} to what you did`);
      if (missed) parts.push(`${missed} skipped`);
      parts.push("re-planned the rest of your week");
      setBanner(`✓ ${parts.join(" · ")}.`);
      invalidate();
    },
    onError: fail,
  });

  const byDate = new Map<string, PlannedWorkout>();
  for (const w of planned.data ?? []) if (!byDate.has(w.date)) byDate.set(w.date, w);
  const activitiesByDate = new Map<string, CompletedActivity[]>();
  for (const a of activities.data ?? []) {
    if (!a.date) continue;
    const arr = activitiesByDate.get(a.date) ?? [];
    arr.push(a);
    activitiesByDate.set(a.date, arr);
  }
  const activityDates = new Set(activitiesByDate.keys());
  const selectedWorkout = selected ? byDate.get(selected) : null;
  const selectedActs = selected ? activitiesByDate.get(selected) ?? [] : [];

  // Divergence: any past planned session this real week left unlogged.
  const divergence = useMemo(() => {
    const ws = mondayOf(todayIso());
    const td = todayIso();
    return (planned.data ?? []).some(
      (w) => w.date >= ws && w.date < td && !w.completed && !w.locked && w.type !== "rest",
    );
  }, [planned.data]);

  const planning = planWeek.isPending || planBlock.isPending;
  const busy = planning || adapt.isPending || sync.isPending;

  return (
    <div className="space-y-4 pb-4">
      <header className="flex items-start justify-between">
        <div className="space-y-1">
          <h1 className="text-3xl font-bold tracking-tight">Calendar</h1>
          <p className="text-sm text-muted-foreground">{planned.data?.length ?? 0} planned this month</p>
        </div>
        <Button size="sm" variant="ghost" disabled={busy} onClick={() => sync.mutate()} title="Sync from Intervals.icu">
          <RefreshCw className={cn("h-4 w-4", sync.isPending && "animate-spin")} />
          Sync
        </Button>
      </header>

      {banner && <p className="text-sm text-primary">{banner}</p>}

      {/* Adaptive re-plan prompt */}
      {divergence && (
        <Card className="border-sand/40">
          <CardContent className="space-y-3 p-4">
            <p className="label-caps" style={{ color: "hsl(var(--sand))" }}>Plan vs reality</p>
            <p className="text-sm text-muted-foreground">
              You have planned sessions this week you haven&apos;t done yet. I can pull what you actually did from
              Intervals.icu and rebuild the rest of your week around it.
            </p>
            <Button disabled={busy} onClick={() => adapt.mutate()}>
              <Sparkles className="h-4 w-4" />
              {adapt.isPending ? "Adapting…" : "Adapt my plan to what I did"}
            </Button>
          </CardContent>
        </Card>
      )}

      {/* AI planning actions */}
      <div className="grid grid-cols-2 gap-2">
        <Button variant="outline" disabled={busy} onClick={() => planWeek.mutate(selected ?? monthStart)}>
          <Sparkles className="h-4 w-4" />
          {planWeek.isPending ? "Planning…" : "Plan my week"}
        </Button>
        <Button variant="outline" disabled={busy} onClick={() => planBlock.mutate()}>
          <CalendarRange className="h-4 w-4" />
          {planBlock.isPending ? "Planning…" : "Plan block to race"}
        </Button>
      </div>

      {/* Month nav */}
      <div className="flex items-center justify-between">
        <Button size="sm" variant="ghost" onClick={() => setCursor(new Date(year, month - 1, 1))}>‹</Button>
        <span className="text-sm font-semibold">{cursor.toLocaleString("en", { month: "long", year: "numeric" })}</span>
        <Button size="sm" variant="ghost" onClick={() => setCursor(new Date(year, month + 1, 1))}>›</Button>
      </div>

      <div className="grid grid-cols-7 gap-1 text-center text-[11px] text-muted-foreground">
        {["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map((d) => <div key={d}>{d}</div>)}
      </div>
      <div className="grid grid-cols-7 gap-1">
        {cells.map((date, i) => {
          if (!date) return <div key={i} />;
          const w = byDate.get(date);
          const hasActivity = activityDates.has(date);
          const day = Number(date.slice(8));
          return (
            <button
              key={date}
              onClick={() => { setSelected(date); setSelectedActivity(null); }}
              className={cn(
                "relative flex aspect-square flex-col items-center justify-start rounded-md border border-border/60 p-1 text-xs hover:border-primary",
                selected === date && "ring-1 ring-primary",
              )}
            >
              <span className="self-end text-[10px] text-muted-foreground">{day}</span>
              {w?.locked && <Lock className="absolute left-1 top-1 h-2.5 w-2.5 text-primary" />}
              <span className="mt-auto flex items-center gap-0.5">
                {w && <span className={cn("h-1.5 w-1.5 rounded-full", TYPE_COLOR[w.type] ?? "bg-secondary")} />}
                {hasActivity && <span className="h-1.5 w-1.5 rounded-full border border-primary" />}
              </span>
            </button>
          );
        })}
      </div>

      {selected && (
        selectedActivity ? (
          <ActivityDetailCard
            activity={selectedActivity}
            planned={byDate.get(selectedActivity.date ?? "") ?? null}
            onBack={() => setSelectedActivity(null)}
          />
        ) : (
          <Card>
            <CardHeader className="pb-2">
              <div className="flex items-center justify-between">
                <CardTitle className="text-base">
                  {new Date(selected + "T00:00:00").toLocaleDateString("en", { weekday: "long", month: "short", day: "numeric" })}
                </CardTitle>
                {selectedWorkout && (
                  <button
                    onClick={() => toggleLock.mutate(selectedWorkout)}
                    className={cn("flex items-center gap-1 text-xs", selectedWorkout.locked ? "text-primary" : "text-muted-foreground hover:text-foreground")}
                  >
                    {selectedWorkout.locked ? <Lock className="h-3.5 w-3.5" /> : <LockOpen className="h-3.5 w-3.5" />}
                    {selectedWorkout.locked ? "Locked" : "Lock"}
                  </button>
                )}
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              {/* Completed activities on this day */}
              {selectedActs.map((act) => (
                <button
                  key={act.id}
                  onClick={() => setSelectedActivity(act)}
                  className="w-full rounded-xl border border-border/60 p-3 text-left transition-colors hover:border-primary"
                >
                  <div className="flex items-center justify-between">
                    <span className="label-caps" style={{ color: "hsl(var(--primary))" }}>Done · {act.type ?? "activity"}</span>
                    <ChevronRight className="h-4 w-4 text-muted-foreground" />
                  </div>
                  <p className="mt-1 font-medium">{displayName(act)}</p>
                  <div className="mt-1.5 flex flex-wrap gap-1.5">
                    {activityMeta(act).map((m) => <span key={m} className="meta-chip">{m}</span>)}
                  </div>
                </button>
              ))}

              {selectedWorkout ? (
                <>
                  <p className="label-caps" style={{ color: selectedWorkout.type === "strength" ? "hsl(var(--sand))" : undefined }}>
                    {selectedWorkout.type}{selectedWorkout.locked ? " · locked" : ""}
                  </p>
                  <h3 className="text-lg font-semibold">{selectedWorkout.workout_json.title}</h3>
                  <WorkoutDetail workout={selectedWorkout.workout_json} />
                  {selectedWorkout.type !== "rest" && (
                    selectedWorkout.completed ? (
                      <p className="flex items-center gap-1.5 text-sm text-primary"><Check className="h-4 w-4" /> Completed</p>
                    ) : (
                      <Button variant="outline" size="sm" disabled={markComplete.isPending} onClick={() => markComplete.mutate(selectedWorkout)}>
                        <Check className="h-4 w-4" /> Mark done
                      </Button>
                    )
                  )}
                </>
              ) : selectedActs.length === 0 ? (
                <>
                  <p className="text-sm text-muted-foreground">Nothing planned for this day.</p>
                  <Button size="sm" disabled={generate.isPending} onClick={() => generate.mutate({ date: selected })}>
                    {generate.isPending && !request ? "Generating…" : "Auto-generate"}
                  </Button>
                </>
              ) : null}

              {/* Ask AI for a specific fixed session (locked by default). */}
              <div className="space-y-2 rounded-xl bg-background/60 p-3">
                <p className="label-caps">Ask AI for a session</p>
                <textarea
                  className="min-h-[60px] w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                  placeholder="e.g. Friday social 10k run with friends, easy pace"
                  value={request}
                  onChange={(e) => setRequest(e.target.value)}
                />
                <label className="flex items-center gap-2 text-xs text-muted-foreground">
                  <input type="checkbox" checked={lockRequest} onChange={(e) => setLockRequest(e.target.checked)} className="accent-primary" />
                  Lock it — re-planning won&apos;t change this session
                </label>
                <Button
                  size="sm"
                  disabled={!request.trim() || generate.isPending}
                  onClick={() => generate.mutate({ date: selected, request: request.trim(), lock: lockRequest })}
                >
                  <Wand2 className="h-4 w-4" />
                  {generate.isPending && request ? "Creating…" : "Create session"}
                </Button>
              </div>
            </CardContent>
          </Card>
        )
      )}
    </div>
  );
}
