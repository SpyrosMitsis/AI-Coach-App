// Picks the single most relevant session debrief for the Home dashboard: the
// latest analyzed session from today (preferred) or yesterday. Pure so it's
// testable; daily-summary feeds it best-effort query results.

export interface DebriefActivityRow {
  id: string;
  date: string;
  type?: string | null;
  analysis_json?: unknown;
}

export interface DebriefStrengthRow {
  date: string;
  analysis_json?: unknown;
}

export interface SessionDebrief {
  kind: "activity" | "strength";
  activity_id: string | null;
  date: string;
  type: string | null;
  score: number | null;
  label: string | null;
  feedback: string | null;
}

interface Analysis {
  score?: number;
  label?: string;
  feedback?: string;
}

const FEEDBACK_MAX = 240;

function clip(s: string): string {
  const t = s.replace(/\s+/g, " ").trim();
  return t.length > FEEDBACK_MAX ? t.slice(0, FEEDBACK_MAX - 1) + "…" : t;
}

function fromAnalysis(a: Analysis | null | undefined): Pick<SessionDebrief, "score" | "label" | "feedback"> | null {
  if (!a) return null;
  const label = typeof a.label === "string" && a.label.trim() ? a.label.trim() : null;
  const feedback = typeof a.feedback === "string" && a.feedback.trim() ? clip(a.feedback) : null;
  if (!label && !feedback) return null;
  return { score: typeof a.score === "number" ? a.score : null, label, feedback };
}

export function pickDebrief(
  activityRows: DebriefActivityRow[],
  strengthRows: DebriefStrengthRow[],
  today: string,
): SessionDebrief | null {
  const candidates: SessionDebrief[] = [];
  for (const r of activityRows) {
    const core = fromAnalysis(r.analysis_json as Analysis);
    if (!core) continue;
    candidates.push({ kind: "activity", activity_id: r.id, date: r.date, type: r.type ?? null, ...core });
  }
  for (const r of strengthRows) {
    const core = fromAnalysis(r.analysis_json as Analysis);
    if (!core) continue;
    candidates.push({ kind: "strength", activity_id: null, date: r.date, type: "strength", ...core });
  }
  // Ignore future-dated rows (timezone edge cases); the caller already
  // bounds the query to yesterday..today.
  const fresh = candidates.filter((c) => c.date <= today);
  if (!fresh.length) return null;
  // Today's session beats yesterday's; ties keep endurance first (it carries
  // the richer detail screen).
  fresh.sort((a, b) => {
    if (a.date !== b.date) return b.date.localeCompare(a.date);
    if (a.kind !== b.kind) return a.kind === "activity" ? -1 : 1;
    return 0;
  });
  return fresh[0];
}
