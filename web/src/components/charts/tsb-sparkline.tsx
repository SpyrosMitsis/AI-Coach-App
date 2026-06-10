"use client";

import { Area, AreaChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

type Point = { date: string; tsb: number; ctl: number; atl: number };

export function TsbSparkline({ data }: { data: Point[] }) {
  if (!data.length) return <p className="text-sm text-muted-foreground">No fitness data yet.</p>;
  return (
    <ResponsiveContainer width="100%" height={160}>
      <AreaChart data={data} margin={{ top: 4, right: 4, bottom: 0, left: -20 }}>
        <defs>
          <linearGradient id="tsbFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="hsl(142 71% 45%)" stopOpacity={0.4} />
            <stop offset="100%" stopColor="hsl(142 71% 45%)" stopOpacity={0} />
          </linearGradient>
        </defs>
        <XAxis dataKey="date" tickFormatter={(d) => String(d).slice(5)} tick={{ fontSize: 10 }} stroke="hsl(var(--muted-foreground))" />
        <YAxis tick={{ fontSize: 10 }} stroke="hsl(var(--muted-foreground))" width={40} />
        <Tooltip
          contentStyle={{ background: "hsl(var(--card))", border: "1px solid hsl(var(--border))", borderRadius: 8, fontSize: 12 }}
        />
        <Area type="monotone" dataKey="tsb" stroke="hsl(142 71% 45%)" fill="url(#tsbFill)" name="Form (TSB)" />
        <Line type="monotone" dataKey="ctl" stroke="hsl(210 90% 60%)" dot={false} name="Fitness (CTL)" />
        <Line type="monotone" dataKey="atl" stroke="hsl(38 92% 50%)" dot={false} name="Fatigue (ATL)" />
      </AreaChart>
    </ResponsiveContainer>
  );
}
