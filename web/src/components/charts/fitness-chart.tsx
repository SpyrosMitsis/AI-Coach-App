"use client";

// CTL/ATL fitness curve drawn like the Android Home chart: two lines
// (sage = fitness, sand = fatigue) with a value scale (TSS/day), gridline,
// date range, and the current values tagged at the line ends. The lines are a
// stretched SVG; labels are HTML overlays so the text never distorts.
type Point = { date?: string; ctl: number; atl: number };

const CTL_COLOR = "#B6CCB6"; // Sage — matches Android theme
const ATL_COLOR = "#D3C4B3"; // Sand
const GRID = "#333535";

export function FitnessChart({ points }: { points: Point[] }) {
  if (points.length < 2) return <p className="text-sm text-muted-foreground">No fitness data yet.</p>;
  const w = 600;
  const h = 140;
  const maxV = Math.max(1, ...points.map((p) => Math.max(p.ctl, p.atl)));
  const last = points[points.length - 1];
  const x = (i: number) => (i / (points.length - 1)) * w;
  const y = (v: number) => h - (v / maxV) * h;
  const path = (sel: (p: Point) => number) =>
    points.map((p, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(sel(p)).toFixed(1)}`).join(" ");
  const pct = (v: number) => `${((y(v) / h) * 100).toFixed(1)}%`;
  const dateOf = (p: Point) => (p.date ?? "").slice(5); // MM-DD

  const label = "pointer-events-none absolute text-[10px] leading-none text-muted-foreground";
  return (
    <div className="space-y-2">
      <div className="relative h-[140px] w-full">
        <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" className="absolute inset-0 h-full w-full">
          <line x1="0" y1={y(maxV / 2)} x2={w} y2={y(maxV / 2)} stroke={GRID} strokeWidth="1" />
          <line x1="0" y1={h} x2={w} y2={h} stroke={GRID} strokeWidth="1" />
          <path d={path((p) => p.atl)} fill="none" stroke={ATL_COLOR} strokeWidth="2" vectorEffect="non-scaling-stroke" />
          <path d={path((p) => p.ctl)} fill="none" stroke={CTL_COLOR} strokeWidth="2" vectorEffect="non-scaling-stroke" />
        </svg>
        {/* value scale */}
        <span className={label} style={{ left: 4, top: 2 }}>{Math.round(maxV)} TSS/d</span>
        <span className={label} style={{ left: 4, top: `calc(${pct(maxV / 2)} - 12px)` }}>{Math.round(maxV / 2)}</span>
        <span className={label} style={{ left: 4, bottom: 4 }}>0</span>
        {/* date range */}
        {dateOf(points[0]) && <span className={label} style={{ left: 40, bottom: 4 }}>{dateOf(points[0])} →</span>}
        {dateOf(last) && <span className={label} style={{ right: 4, bottom: 4 }}>{dateOf(last)}</span>}
        {/* current values at the line ends */}
        <span className={label} style={{ right: 2, top: `calc(${pct(last.ctl)} - 13px)`, color: CTL_COLOR }}>
          {Math.round(last.ctl)}
        </span>
        <span className={label} style={{ right: 2, top: `calc(${pct(last.atl)} + 3px)`, color: ATL_COLOR }}>
          {Math.round(last.atl)}
        </span>
      </div>
      <div className="flex gap-4">
        <LegendDot color={CTL_COLOR} label={`Fitness (CTL) ${Math.round(last.ctl)}`} />
        <LegendDot color={ATL_COLOR} label={`Fatigue (ATL) ${Math.round(last.atl)}`} />
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
