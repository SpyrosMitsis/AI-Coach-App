"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createClient } from "@/lib/supabase-browser";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { epley1rm } from "@shared/types";
import { Plus, X } from "lucide-react";

interface SetInput { reps: number; weight_kg: number; rpe?: number; is_warmup?: boolean }
interface SessionExercise { name: string; muscle: string; sets: SetInput[] }

// Full workout model — mirrors the Android cloud tables so sessions sync both ways.
interface CloudWorkout {
  id: string; name: string; started_at: number; ended_at: number;
  duration_sec: number; total_volume_kg: number; note: string;
}
interface CloudSet {
  id: string; workout_id: string; exercise_name: string; muscle: string;
  idx: number; weight_kg: number; reps: number; rpe: number | null; is_warmup: boolean;
}

const dayOf = (millis: number) => new Date(millis).toISOString().slice(0, 10);

export default function StrengthPage() {
  const qc = useQueryClient();
  const supabase = createClient();

  const exercises = useQuery({
    queryKey: ["exercise-library"],
    queryFn: async () => {
      const { data } = await supabase.from("exercise_library").select("name, muscle_groups, equipment, is_compound").order("name");
      return (data ?? []) as { name: string; muscle_groups: string[]; equipment: string; is_compound: boolean }[];
    },
  });

  // Rich history straight from the shared workout model (last 28 days).
  const workouts = useQuery({
    queryKey: ["strength-workouts"],
    queryFn: async () => {
      const cutoff = Date.now() - 28 * 86400000;
      const { data: ws } = await supabase
        .from("strength_workouts").select("*")
        .gte("started_at", cutoff).order("started_at", { ascending: false });
      const workouts = (ws ?? []) as CloudWorkout[];
      if (workouts.length === 0) return { workouts, sets: [] as CloudSet[] };
      const { data: ss } = await supabase
        .from("strength_workout_sets").select("*")
        .in("workout_id", workouts.map((w) => w.id));
      return { workouts, sets: (ss ?? []) as CloudSet[] };
    },
  });

  const lib = exercises.data ?? [];
  const [session, setSession] = useState<SessionExercise[]>([]);
  const [sessionStart, setSessionStart] = useState<number | null>(null);
  const [name, setName] = useState("");
  const [picker, setPicker] = useState("");

  const addExercise = (exName: string) => {
    if (!exName) return;
    const meta = lib.find((e) => e.name === exName);
    const muscle = (meta?.muscle_groups?.[0] ?? "other").toLowerCase();
    setSession((s) => [...s, { name: exName, muscle, sets: [{ reps: 5, weight_kg: 60 }] }]);
    if (sessionStart === null) setSessionStart(Date.now());
    setPicker("");
  };
  const patchSet = (ei: number, si: number, p: Partial<SetInput>) =>
    setSession((s) => s.map((ex, i) => i !== ei ? ex : { ...ex, sets: ex.sets.map((st, j) => j === si ? { ...st, ...p } : st) }));
  const addSet = (ei: number) =>
    setSession((s) => s.map((ex, i) => i !== ei ? ex : { ...ex, sets: [...ex.sets, { ...ex.sets[ex.sets.length - 1] ?? { reps: 5, weight_kg: 60 } }] }));
  const removeSet = (ei: number, si: number) =>
    setSession((s) => s.map((ex, i) => i !== ei ? ex : { ...ex, sets: ex.sets.filter((_, j) => j !== si) }));
  const removeExercise = (ei: number) => setSession((s) => s.filter((_, i) => i !== ei));

  const sessionVolume = useMemo(
    () => session.reduce((t, ex) => t + ex.sets.filter((s) => !s.is_warmup).reduce((a, s) => a + s.weight_kg * s.reps, 0), 0),
    [session],
  );

  const save = useMutation({
    mutationFn: async () => {
      const id = crypto.randomUUID();
      const start = sessionStart ?? Date.now();
      const end = Date.now();
      const setRows: CloudSet[] = [];
      for (const ex of session) {
        ex.sets.forEach((s, i) => {
          setRows.push({
            id: crypto.randomUUID(), workout_id: id, exercise_name: ex.name, muscle: ex.muscle,
            idx: i + 1, weight_kg: s.weight_kg, reps: s.reps, rpe: s.rpe ?? null, is_warmup: s.is_warmup ?? false,
          });
        });
      }
      // 1. Full workout model (syncs to the phone).
      const { error: we } = await supabase.from("strength_workouts").insert({
        id, name: name.trim() || "Workout", started_at: start, ended_at: end,
        duration_sec: Math.round((end - start) / 1000), total_volume_kg: sessionVolume, note: "",
      });
      if (we) throw we;
      if (setRows.length) {
        const { error: se } = await supabase.from("strength_workout_sets").insert(setRows);
        if (se) throw se;
      }
      // 2. Per-exercise strength_logs so the AI generator sees volume / e1RM.
      const date = dayOf(start);
      for (const ex of session) {
        const working = ex.sets.filter((s) => !s.is_warmup && s.reps > 0);
        if (!working.length) continue;
        const best1rm = Math.max(0, ...working.map((s) => epley1rm(s.weight_kg, s.reps)));
        await supabase.from("strength_logs").insert({
          date, exercise_name: ex.name, muscle_groups: [ex.muscle], sets: working, estimated_1rm: best1rm || null,
        });
      }
    },
    onSuccess: () => {
      setSession([]); setSessionStart(null); setName("");
      qc.invalidateQueries({ queryKey: ["strength-workouts"] });
    },
  });

  // Weekly volume per muscle (working-set count) from the rich model.
  const { volume, maxVol, overload, history } = useMemo(() => {
    const ws = workouts.data?.workouts ?? [];
    const sets = workouts.data?.sets ?? [];
    const dateOfWorkout = new Map(ws.map((w) => [w.id, dayOf(w.started_at)]));
    const setsByWorkout = new Map<string, CloudSet[]>();
    for (const s of sets) {
      const arr = setsByWorkout.get(s.workout_id) ?? [];
      arr.push(s);
      setsByWorkout.set(s.workout_id, arr);
    }
    const since7 = new Date(Date.now() - 7 * 86400000).toISOString().slice(0, 10);
    const volMap = new Map<string, number>();
    for (const s of sets) {
      if (s.is_warmup) continue;
      const d = dateOfWorkout.get(s.workout_id) ?? "";
      if (d < since7) continue;
      volMap.set(s.muscle, (volMap.get(s.muscle) ?? 0) + 1);
    }
    const volume = [...volMap.entries()].sort((a, b) => b[1] - a[1]);
    const maxVol = Math.max(1, ...volume.map(([, v]) => v));

    // e1RM trend per exercise across the last two sessions that hit it.
    const byExercise = new Map<string, { date: string; best: number }[]>();
    for (const w of ws) {
      const exMap = new Map<string, number>();
      for (const s of setsByWorkout.get(w.id) ?? []) {
        if (s.is_warmup || s.reps <= 0) continue;
        exMap.set(s.exercise_name, Math.max(exMap.get(s.exercise_name) ?? 0, epley1rm(s.weight_kg, s.reps)));
      }
      for (const [exName, best] of exMap) {
        const arr = byExercise.get(exName) ?? [];
        arr.push({ date: dayOf(w.started_at), best });
        byExercise.set(exName, arr);
      }
    }
    const overload: { name: string; up: boolean }[] = [];
    for (const [exName, list] of byExercise) {
      if (list.length < 2) continue;
      overload.push({ name: exName, up: list[0].best > list[1].best });
    }

    const history = ws.map((w) => ({ w, sets: setsByWorkout.get(w.id) ?? [] }));
    return { volume, maxVol, overload, history };
  }, [workouts.data]);

  return (
    <div className="space-y-5 pb-4">
      <header className="space-y-1">
        <h1 className="text-3xl font-bold tracking-tight">Strength</h1>
        <p className="text-sm text-muted-foreground">Log full sessions · synced with your phone</p>
      </header>

      {/* Session builder */}
      <Card>
        <CardHeader className="pb-2"><CardTitle className="text-base">Log a session</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          <Input placeholder="Session name (optional)" value={name} onChange={(e) => setName(e.target.value)} />

          {session.map((ex, ei) => (
            <div key={ei} className="space-y-2 rounded-xl border border-border/60 p-3">
              <div className="flex items-center justify-between">
                <span className="font-medium">{ex.name}</span>
                <button onClick={() => removeExercise(ei)} className="text-muted-foreground hover:text-red-400"><X className="h-4 w-4" /></button>
              </div>
              {ex.sets.map((s, si) => (
                <div key={si} className="flex items-center gap-2">
                  <Input type="number" value={s.reps} className="w-16"
                    onChange={(e) => patchSet(ei, si, { reps: +e.target.value })} />
                  <span className="text-xs text-muted-foreground">×</span>
                  <Input type="number" value={s.weight_kg} className="w-20"
                    onChange={(e) => patchSet(ei, si, { weight_kg: +e.target.value })} />
                  <span className="text-xs text-muted-foreground">kg</span>
                  <label className="flex items-center gap-1 text-xs text-muted-foreground">
                    <input type="checkbox" checked={!!s.is_warmup} onChange={(e) => patchSet(ei, si, { is_warmup: e.target.checked })} className="accent-primary" />
                    warmup
                  </label>
                  {ex.sets.length > 1 && (
                    <button onClick={() => removeSet(ei, si)} className="ml-auto text-muted-foreground hover:text-red-400"><X className="h-3.5 w-3.5" /></button>
                  )}
                </div>
              ))}
              <Button size="sm" variant="ghost" onClick={() => addSet(ei)}><Plus className="h-3.5 w-3.5" /> Set</Button>
            </div>
          ))}

          <div className="flex gap-2">
            <select
              className="h-9 flex-1 rounded-md border border-input bg-transparent px-3 text-sm"
              value={picker}
              onChange={(e) => addExercise(e.target.value)}
            >
              <option value="">+ Add exercise…</option>
              {lib.map((e) => <option key={e.name} value={e.name}>{e.name}</option>)}
            </select>
          </div>

          {session.length > 0 && (
            <>
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">{session.length} exercise(s)</span>
                <span>Volume: <b>{Math.round(sessionVolume)} kg</b></span>
              </div>
              <Button className="w-full" disabled={save.isPending} onClick={() => save.mutate()}>
                {save.isPending ? "Saving…" : "Finish workout"}
              </Button>
              {save.isError && <p className="text-sm text-red-400">{(save.error as Error).message}</p>}
            </>
          )}
        </CardContent>
      </Card>

      {/* Weekly volume */}
      <Card>
        <CardHeader className="pb-2"><CardTitle className="text-base">Weekly volume by muscle group</CardTitle></CardHeader>
        <CardContent className="space-y-2">
          {volume.length === 0 && <p className="text-sm text-muted-foreground">No working sets logged this week.</p>}
          {volume.map(([mg, v]) => (
            <div key={mg} className="space-y-1">
              <div className="flex justify-between text-xs"><span className="capitalize">{mg}</span><span className="text-muted-foreground">{v} sets</span></div>
              <div className="h-2 rounded-full bg-secondary">
                <div className="h-full rounded-full bg-primary" style={{ width: `${(v / maxVol) * 100}%` }} />
              </div>
            </div>
          ))}
        </CardContent>
      </Card>

      {/* Rich history */}
      <Card>
        <CardHeader className="pb-2"><CardTitle className="text-base">Recent sessions</CardTitle></CardHeader>
        <CardContent className="space-y-2">
          {history.length === 0 && <p className="text-sm text-muted-foreground">No sessions yet.</p>}
          {history.slice(0, 12).map(({ w, sets }) => {
            const byEx = new Map<string, CloudSet[]>();
            for (const s of sets) { const a = byEx.get(s.exercise_name) ?? []; a.push(s); byEx.set(s.exercise_name, a); }
            return (
              <div key={w.id} className="rounded-xl border border-border/60 p-3">
                <div className="flex items-center justify-between">
                  <span className="font-medium">{w.name}</span>
                  <span className="text-xs text-muted-foreground">{dayOf(w.started_at)}</span>
                </div>
                <div className="mt-1 flex flex-wrap gap-1.5">
                  <span className="meta-chip">{Math.round(w.total_volume_kg)} kg</span>
                  {w.duration_sec > 0 && <span className="meta-chip">{Math.round(w.duration_sec / 60)} min</span>}
                </div>
                <div className="mt-2 space-y-0.5 text-sm">
                  {[...byEx.entries()].map(([exName, exSets]) => {
                    const work = exSets.filter((s) => !s.is_warmup);
                    const ov = overload.find((o) => o.name === exName);
                    return (
                      <div key={exName} className="flex items-center justify-between">
                        <span>{exName} <span className="text-muted-foreground">· {work.length}×</span></span>
                        {ov?.up && <Badge variant="success">↑ overload</Badge>}
                      </div>
                    );
                  })}
                </div>
              </div>
            );
          })}
        </CardContent>
      </Card>
    </div>
  );
}
