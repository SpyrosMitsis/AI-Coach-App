"use client";

import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createClient, currentUserId } from "@/lib/supabase-browser";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import {
  hrZonesFromLthr, paceZonesFromThreshold, powerZonesFromFtp, parsePace, formatPace,
} from "@/lib/zones";
import type { GoalSport, OnboardingData, Race } from "@shared/types";
import { Flag, Plus, Target, Trash2 } from "lucide-react";

type Profile = { onboarding: OnboardingData };

export function ZonesRaces() {
  const qc = useQueryClient();
  const supabase = createClient();

  const profile = useQuery({
    queryKey: ["profile-thresholds"],
    queryFn: async () => {
      const { data, error } = await supabase.from("user_profiles").select("onboarding").single();
      if (error) throw error;
      return data as Profile;
    },
  });

  const races = useQuery({
    queryKey: ["races"],
    queryFn: async () => {
      const { data, error } = await supabase.from("races").select("*").order("date", { ascending: true });
      if (error) throw error;
      return (data ?? []) as Race[];
    },
  });

  const o = profile.data?.onboarding;

  const patchProfile = useMutation({
    mutationFn: async (p: Partial<OnboardingData>) => {
      const next = { ...(o ?? {}), ...p };
      const { error } = await supabase.from("user_profiles").update({ onboarding: next })
        .eq("id", await currentUserId(supabase));
      if (error) throw error;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["profile-thresholds"] });
      qc.invalidateQueries({ queryKey: ["daily-summary"] }); // goal card on Home
    },
  });

  const addRace = useMutation({
    mutationFn: async (r: Omit<Race, "id">) => {
      const { error } = await supabase.from("races").insert(r);
      if (error) throw error;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["races"] }),
  });

  const deleteRace = useMutation({
    mutationFn: async (r: Race) => {
      const { error } = await supabase.from("races").delete().eq("id", r.id);
      if (error) throw error;
      // Deleting the active goal also moves the anchor, which periodization and
      // taper read. Matched on the DATE alone: `goal` is rewritten from the
      // athlete's training goals elsewhere, so a name comparison quietly stops
      // matching and leaves the anchor on a race that no longer exists. The
      // soonest remaining A goal takes over rather than leaving no anchor.
      if (o && o.goal_date === r.date) {
        const today = new Date().toISOString().slice(0, 10);
        const nextGoal = (races.data ?? [])
          .filter((x) => x.id !== r.id && (x.priority ?? "A").toUpperCase() === "A" && x.date >= today)
          .sort((a, b) => a.date.localeCompare(b.date))[0];
        const next = {
          ...o, goal: nextGoal?.name, goal_date: nextGoal?.date,
          ...(r.target && o.target_pace === r.target ? { target_pace: undefined } : {}),
        };
        const { error: e2 } = await supabase.from("user_profiles").update({ onboarding: next })
          .eq("id", await currentUserId(supabase));
        if (e2) throw e2;
      }
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["races"] });
      qc.invalidateQueries({ queryKey: ["profile-thresholds"] });
      qc.invalidateQueries({ queryKey: ["daily-summary"] });
    },
  });

  return (
    <>
      <ThresholdsCard o={o} loading={profile.isLoading} onSave={(p) => patchProfile.mutate(p)} saving={patchProfile.isPending} />
      <RacesCard
        races={races.data ?? []}
        goalDate={o?.goal_date}
        onAdd={(r) => addRace.mutate(r)}
        onDelete={(r) => deleteRace.mutate(r)}
        onSetGoal={(r) =>
          patchProfile.mutate({
            goal: r.name,
            goal_date: r.date,
            ...(r.sport === "run" && r.target ? { target_pace: r.target } : {}),
          })}
        adding={addRace.isPending}
      />
    </>
  );
}

// --- Thresholds + derived zones -------------------------------------------

function ThresholdsCard({
  o, loading, onSave, saving,
}: {
  o: OnboardingData | undefined;
  loading: boolean;
  onSave: (p: Partial<OnboardingData>) => void;
  saving: boolean;
}) {
  const [lthr, setLthr] = useState("");
  const [ftp, setFtp] = useState("");
  const [pace, setPace] = useState("");
  const [editing, setEditing] = useState(false);

  useEffect(() => {
    if (o) {
      setLthr(o.lthr ? String(o.lthr) : "");
      setFtp(o.ftp ? String(o.ftp) : "");
      setPace(o.threshold_pace_per_km ?? "");
    }
  }, [o]);

  const lthrN = o?.lthr ?? 0;
  const ftpN = o?.ftp ?? 0;
  const paceSec = o?.threshold_pace_per_km ? parsePace(o.threshold_pace_per_km) ?? 0 : 0;

  const hrZones = hrZonesFromLthr(lthrN);
  const paceZones = paceZonesFromThreshold(paceSec);
  const powerZones = powerZonesFromFtp(ftpN);
  const paceValid = pace === "" || parsePace(pace) !== null;

  const save = () => {
    onSave({
      lthr: lthr ? Number(lthr) : undefined,
      ftp: ftp ? Number(ftp) : undefined,
      threshold_pace_per_km: pace || undefined,
    });
    setEditing(false);
  };

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <CardTitle className="text-base">Training zones</CardTitle>
          <button className="text-xs text-muted-foreground hover:text-foreground" onClick={() => setEditing((e) => !e)}>
            {editing ? "Cancel" : "Edit thresholds"}
          </button>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        {loading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : editing ? (
          <div className="space-y-3">
            <Labeled label="LTHR (bpm)"><Input type="number" value={lthr} onChange={(e) => setLthr(e.target.value)} placeholder="e.g. 165" /></Labeled>
            <Labeled label="Threshold pace (m:ss /km)">
              <Input value={pace} onChange={(e) => setPace(e.target.value)} placeholder="e.g. 4:30" className={cn(!paceValid && "border-red-400")} />
            </Labeled>
            <Labeled label="FTP (watts)"><Input type="number" value={ftp} onChange={(e) => setFtp(e.target.value)} placeholder="e.g. 250" /></Labeled>
            <Button className="w-full" disabled={saving || !paceValid} onClick={save}>{saving ? "Saving…" : "Save thresholds"}</Button>
          </div>
        ) : !lthrN && !ftpN && !paceSec ? (
          <p className="text-sm text-muted-foreground">
            No thresholds set. Tap <b>Edit thresholds</b> to enter your LTHR, threshold pace, or FTP, zones derive automatically.
          </p>
        ) : (
          <div className="space-y-4">
            {paceZones.length > 0 && (
              <ZoneTable title="Pace" subtitle={`threshold ${formatPace(paceSec)} /km`} rows={paceZones.map((z) => [z.name, z.range])} />
            )}
            {hrZones.length > 0 && (
              <ZoneTable title="Heart rate" subtitle={`LTHR ${lthrN} bpm`} rows={hrZones.map((z) => [z.name, `${z.lo}-${z.hi} bpm`])} />
            )}
            {powerZones.length > 0 && (
              <ZoneTable title="Power" subtitle={`FTP ${ftpN} W`} rows={powerZones.map((z) => [z.name, z.range])} />
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

const ZONE_TINT = ["bg-primary/15", "bg-primary/25", "bg-sand/25", "bg-sand/40", "bg-destructive/25"];

function ZoneTable({ title, subtitle, rows }: { title: string; subtitle: string; rows: [string, string][] }) {
  return (
    <div className="space-y-1.5">
      <div className="flex items-baseline justify-between">
        <p className="label-caps">{title}</p>
        <span className="text-xs text-muted-foreground">{subtitle}</span>
      </div>
      <div className="overflow-hidden rounded-xl border border-border/60">
        {rows.map(([name, range], i) => (
          <div key={name} className={cn("flex items-center justify-between px-3 py-2 text-sm", ZONE_TINT[i] ?? "")}>
            <span className="font-medium">{name}</span>
            <span className="tabular-nums text-muted-foreground">{range}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// --- Races -----------------------------------------------------------------

function daysUntil(date: string): number {
  const d = new Date(date + "T00:00:00").getTime();
  return Math.ceil((d - Date.now()) / 86400000);
}

const GOAL_SPORTS: [GoalSport, string][] = [
  ["run", "Run"], ["ride", "Ride"], ["swim", "Swim"], ["strength", "Strength"], ["other", "Other"],
];

const TARGET_HINT: Record<GoalSport, string> = {
  run: "Target pace or time (e.g. 4:45/km)",
  ride: "Target (e.g. FTP 260W or finish time)",
  swim: "Target (e.g. 1:50/100m or 0:32:00)",
  strength: "Target (e.g. Squat 120kg ×1)",
  other: "Target (optional)",
};

function sportLabel(sport: GoalSport | null | undefined): string {
  return GOAL_SPORTS.find(([k]) => k === sport)?.[1] ?? "Other";
}

const PRIORITY_STYLE: Record<string, string> = {
  A: "bg-primary/20 text-primary",
  B: "bg-sand/20 text-sand",
  C: "bg-secondary text-muted-foreground",
};

function RacesCard({
  races, goalDate, onAdd, onDelete, onSetGoal, adding,
}: {
  races: Race[];
  goalDate: string | undefined;
  onAdd: (r: Omit<Race, "id">) => void;
  onDelete: (r: Race) => void;
  onSetGoal: (r: Race) => void;
  adding: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [date, setDate] = useState("");
  const [distance, setDistance] = useState("");
  const [target, setTarget] = useState("");
  const [sport, setSport] = useState<GoalSport>("run");
  const [priority, setPriority] = useState<"A" | "B" | "C">("A");

  const submit = () => {
    if (!name.trim() || !date) return;
    onAdd({
      name: name.trim(), date, priority, sport,
      distance: distance.trim() || null, target: target.trim() || null, notes: null,
    });
    setName(""); setDate(""); setDistance(""); setTarget(""); setSport("run"); setPriority("A"); setOpen(false);
  };

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between">
          <CardTitle className="text-base">Goals &amp; races</CardTitle>
          <button className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground" onClick={() => setOpen((v) => !v)}>
            <Plus className="h-3.5 w-3.5" /> Add goal
          </button>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {open && (
          <div className="space-y-2 rounded-xl bg-background/60 p-3">
            <Input placeholder="Goal name (race, event, lift…)" value={name} onChange={(e) => setName(e.target.value)} />
            <div className="flex flex-wrap gap-1.5">
              {GOAL_SPORTS.map(([key, label]) => (
                <button
                  key={key}
                  onClick={() => setSport(key)}
                  className={cn("rounded-full border px-3 py-1 text-xs", sport === key ? "border-transparent bg-accent/60 text-primary" : "border-sand/50 text-muted-foreground")}
                >
                  {label}
                </button>
              ))}
            </div>
            <div className="flex gap-2">
              <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
              <Input
                placeholder={sport === "strength" ? "Lift (Back squat…)" : "Distance (10K…)"}
                value={distance}
                onChange={(e) => setDistance(e.target.value)}
              />
            </div>
            <Input placeholder={TARGET_HINT[sport]} value={target} onChange={(e) => setTarget(e.target.value)} />
            <div className="flex gap-1.5">
              {(["A", "B", "C"] as const).map((p) => (
                <button
                  key={p}
                  onClick={() => setPriority(p)}
                  className={cn("rounded-full border px-3 py-1 text-xs", priority === p ? "border-transparent bg-accent/60 text-primary" : "border-sand/50 text-muted-foreground")}
                >
                  {p} goal
                </button>
              ))}
            </div>
            <Button size="sm" disabled={adding || !name.trim() || !date} onClick={submit}>{adding ? "Adding…" : "Add"}</Button>
          </div>
        )}

        {races.length === 0 && !open && (
          <p className="text-sm text-muted-foreground">
            No goals yet. Add a race, FTP target, swim time or lift, your A-goal drives periodization &amp; taper.
          </p>
        )}

        {races.map((r) => {
          const left = daysUntil(r.date);
          const isGoal = goalDate === r.date;
          return (
            <div key={r.id} className="flex items-center gap-3 rounded-xl border border-border/60 px-3 py-2.5">
              <Badge className={cn("shrink-0", PRIORITY_STYLE[r.priority])}>{r.priority}</Badge>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium">
                  {r.name}
                  {isGoal && <Target className="ml-1.5 inline h-3.5 w-3.5 text-primary" />}
                </p>
                <p className="text-xs text-muted-foreground">
                  {sportLabel(r.sport)} · {r.date}{r.distance ? ` · ${r.distance}` : ""}
                  {r.target ? ` · ${r.target}` : ""} · {left >= 0 ? `${left} days` : `${-left} days ago`}
                </p>
              </div>
              {!isGoal && (
                <button title="Set as goal" className="text-muted-foreground hover:text-primary" onClick={() => onSetGoal(r)}>
                  <Flag className="h-4 w-4" />
                </button>
              )}
              <button title="Delete" className="text-muted-foreground hover:text-red-400" onClick={() => onDelete(r)}>
                <Trash2 className="h-4 w-4" />
              </button>
            </div>
          );
        })}
      </CardContent>
    </Card>
  );
}

function Labeled({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <p className="label-caps">{label}</p>
      {children}
    </div>
  );
}
