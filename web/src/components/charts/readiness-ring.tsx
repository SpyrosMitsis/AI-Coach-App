"use client";

export function ReadinessRing({ score, band }: { score: number; band: "green" | "amber" | "red" }) {
  const color = band === "green" ? "hsl(142 71% 45%)" : band === "amber" ? "hsl(38 92% 50%)" : "hsl(0 72% 51%)";
  const r = 52;
  const c = 2 * Math.PI * r;
  const offset = c * (1 - score / 100);
  return (
    <div className="relative h-32 w-32">
      <svg viewBox="0 0 120 120" className="h-32 w-32 -rotate-90">
        <circle cx="60" cy="60" r={r} fill="none" stroke="hsl(var(--secondary))" strokeWidth="10" />
        <circle
          cx="60" cy="60" r={r} fill="none" stroke={color} strokeWidth="10" strokeLinecap="round"
          strokeDasharray={c} strokeDashoffset={offset}
          style={{ transition: "stroke-dashoffset 700ms ease" }}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="text-3xl font-bold">{score}</span>
        <span className="text-[10px] uppercase tracking-wide text-muted-foreground">Readiness</span>
      </div>
    </div>
  );
}
