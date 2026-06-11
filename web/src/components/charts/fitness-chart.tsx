"use client";

// CTL/ATL fitness curve drawn exactly like the Android Home chart: two plain
// lines (sage = fitness, sand = fatigue) over a baseline, no axes.
type Point = { ctl: number; atl: number };

const CTL_COLOR = "#B6CCB6"; // Sage — matches Android theme
const ATL_COLOR = "#D3C4B3"; // Sand

export function FitnessChart({ points }: { points: Point[] }) {
  if (points.length < 2) return <p className="text-sm text-muted-foreground">No fitness data yet.</p>;
  const w = 600;
  const h = 140;
  const maxV = Math.max(1, ...points.map((p) => Math.max(p.ctl, p.atl)));
  const x = (i: number) => (i / (points.length - 1)) * w;
  const y = (v: number) => h - (v / maxV) * h;
  const path = (sel: (p: Point) => number) =>
    points.map((p, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(sel(p)).toFixed(1)}`).join(" ");

  return (
    <div className="space-y-2">
      <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" className="h-[140px] w-full">
        <line x1="0" y1={h} x2={w} y2={h} stroke="#333535" strokeWidth="1" />
        <path d={path((p) => p.atl)} fill="none" stroke={ATL_COLOR} strokeWidth="2" vectorEffect="non-scaling-stroke" />
        <path d={path((p) => p.ctl)} fill="none" stroke={CTL_COLOR} strokeWidth="2" vectorEffect="non-scaling-stroke" />
      </svg>
      <div className="flex gap-4">
        <LegendDot color={CTL_COLOR} label="Fitness (CTL)" />
        <LegendDot color={ATL_COLOR} label="Fatigue (ATL)" />
      </div>
    </div>
  );
}

function LegendDot({ color, label }: { color: string; label: string }) {
  return (
    <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
      <span className="h-2.5 w-2.5 rounded-full" style={{ background: color }} />
      {label}
    </span>
  );
}
