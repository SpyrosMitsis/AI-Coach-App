"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { createClient } from "@/lib/supabase-browser";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ZonesRaces } from "@/components/zones-races";
import { RacePlan } from "@/components/race-plan";
import {
  Bar, BarChart, Cell, Line, LineChart, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from "recharts";

interface Activity {
  date: string;
  type: string | null;
  distance_m: number | null;
  duration_seconds: number | null;
  avg_hr: number | null;
  data_json: { hrv?: number; restingHR?: number; vo2max?: number } | null;
}

const ZONE_COLORS = ["#3b82f6", "#22c55e", "#eab308", "#f97316", "#ef4444"];

export default function RunningPage() {
  const supabase = createClient();

  const activities = useQuery({
    queryKey: ["run-activities"],
    queryFn: async () => {
      const since = new Date(Date.now() - 60 * 86400000).toISOString().slice(0, 10);
      const { data } = await supabase
        .from("completed_activities")
        .select("date, type, distance_m, duration_seconds, avg_hr, data_json")
        .gte("date", since)
        .order("date", { ascending: true });
      return (data ?? []) as Activity[];
    },
  });

  const runs = useMemo(
    () => (activities.data ?? []).filter((a) => (a.type ?? "").toLowerCase().includes("run")),
    [activities.data],
  );

  // Weekly km bars.
  const weeklyKm = useMemo(() => {
    const map = new Map<string, number>();
    for (const r of runs) {
      const d = new Date(r.date);
      const monday = new Date(d);
      monday.setDate(d.getDate() - ((d.getDay() + 6) % 7));
      const key = monday.toISOString().slice(5, 10);
      map.set(key, (map.get(key) ?? 0) + (r.distance_m ?? 0) / 1000);
    }
    return [...map.entries()].map(([week, km]) => ({ week, km: +km.toFixed(1) }));
  }, [runs]);

  // Pace zone distribution by avg HR bucket (proxy when no per-point data).
  const zoneDist = useMemo(() => {
    const buckets = [0, 0, 0, 0, 0];
    for (const r of runs) {
      const hr = r.avg_hr ?? 0;
      const z = hr < 131 ? 0 : hr < 146 ? 1 : hr < 161 ? 2 : hr < 173 ? 3 : 4;
      buckets[z] += (r.duration_seconds ?? 0) / 60;
    }
    return buckets.map((min, i) => ({ name: `Z${i + 1}`, value: +min.toFixed(0) })).filter((b) => b.value > 0);
  }, [runs]);

  const vo2 = useMemo(
    () => (activities.data ?? [])
      .map((a) => ({ date: a.date.slice(5), vo2max: a.data_json?.vo2max }))
      .filter((p): p is { date: string; vo2max: number } => typeof p.vo2max === "number"),
    [activities.data],
  );

  // Best efforts (naive: best avg pace over the run's full distance per bucket).
  const bestEfforts = useMemo(() => {
    const targets = [1000, 5000, 10000, 21097, 42195];
    const labels = ["1K", "5K", "10K", "HM", "M"];
    return targets.map((t, i) => {
      const candidates = runs.filter((r) => (r.distance_m ?? 0) >= t * 0.95 && r.duration_seconds);
      let best: string = "—";
      let bestPace = Infinity;
      for (const r of candidates) {
        const pace = (r.duration_seconds! / 60) / ((r.distance_m! / 1000)); // min/km
        if (pace < bestPace) {
          bestPace = pace;
          const total = (r.duration_seconds! / (r.distance_m! / 1000)) * (t / 1000);
          const m = Math.floor(total / 60);
          const s = Math.round(total % 60);
          best = `${m}:${String(s).padStart(2, "0")}`;
        }
      }
      return { label: labels[i], time: best, pace: bestPace === Infinity ? "—" : `${bestPace.toFixed(2)}/km` };
    });
  }, [runs]);

  return (
    <div className="space-y-5 pb-4">
      <header className="space-y-1">
        <h1 className="text-3xl font-bold tracking-tight">Running</h1>
        <p className="text-sm text-muted-foreground">Zones, races &amp; endurance trends</p>
      </header>

      <ZonesRaces />

      <RacePlan />

      <div className="grid gap-4 sm:grid-cols-2">
        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-base">Pace zone distribution</CardTitle></CardHeader>
          <CardContent>
            {zoneDist.length ? (
              <ResponsiveContainer width="100%" height={200}>
                <PieChart>
                  <Pie data={zoneDist} dataKey="value" nameKey="name" innerRadius={45} outerRadius={75} paddingAngle={2}>
                    {zoneDist.map((_, i) => <Cell key={i} fill={ZONE_COLORS[i]} />)}
                  </Pie>
                  <Tooltip contentStyle={{ background: "hsl(var(--card))", border: "1px solid hsl(var(--border))", borderRadius: 8, fontSize: 12 }} />
                </PieChart>
              </ResponsiveContainer>
            ) : <p className="text-sm text-muted-foreground">No runs yet.</p>}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-2"><CardTitle className="text-base">Weekly km</CardTitle></CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={200}>
              <BarChart data={weeklyKm} margin={{ left: -20 }}>
                <XAxis dataKey="week" tick={{ fontSize: 10 }} stroke="hsl(var(--muted-foreground))" />
                <YAxis tick={{ fontSize: 10 }} stroke="hsl(var(--muted-foreground))" />
                <Tooltip contentStyle={{ background: "hsl(var(--card))", border: "1px solid hsl(var(--border))", borderRadius: 8, fontSize: 12 }} />
                <Bar dataKey="km" fill="hsl(var(--primary))" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader className="pb-2"><CardTitle className="text-base">VO₂max trend</CardTitle></CardHeader>
        <CardContent>
          {vo2.length ? (
            <ResponsiveContainer width="100%" height={180}>
              <LineChart data={vo2} margin={{ left: -20 }}>
                <XAxis dataKey="date" tick={{ fontSize: 10 }} stroke="hsl(var(--muted-foreground))" />
                <YAxis domain={["dataMin - 1", "dataMax + 1"]} tick={{ fontSize: 10 }} stroke="hsl(var(--muted-foreground))" />
                <Tooltip contentStyle={{ background: "hsl(var(--card))", border: "1px solid hsl(var(--border))", borderRadius: 8, fontSize: 12 }} />
                <Line type="monotone" dataKey="vo2max" stroke="hsl(210 90% 60%)" dot={false} />
              </LineChart>
            </ResponsiveContainer>
          ) : <p className="text-sm text-muted-foreground">No VO₂max data.</p>}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="pb-2"><CardTitle className="text-base">Best efforts</CardTitle></CardHeader>
        <CardContent>
          <table className="w-full text-sm">
            <thead><tr className="text-left text-xs text-muted-foreground"><th className="pb-2">Distance</th><th>Time</th><th>Pace</th></tr></thead>
            <tbody>
              {bestEfforts.map((e) => (
                <tr key={e.label} className="border-t border-border/60">
                  <td className="py-2 font-medium">{e.label}</td><td>{e.time}</td><td className="text-muted-foreground">{e.pace}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
}
