// Readers for the richer onboarding fields (multiple goals, per-activity
// experience, per-day availability) with graceful fallback to the single-value
// legacy fields the app also writes (deriveLegacyFields on the client). Pure and
// dependency-free so it is trivial to unit test.

export type Onboarding = Record<string, unknown>;

export interface DayAvail {
  day: string;
  max_minutes: number;
}

// Local noon avoids DST edges; matches how the app maps an ISO calendar date to
// its weekday (the "today = client local date" rule, never UTC).
const WD = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const weekdayOf = (iso: string) => WD[new Date(iso + "T12:00:00").getDay()];
const DAY_ORDER = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

function expMap(o: Onboarding): Record<string, string> {
  const raw = o.experience_by_sport;
  return raw && typeof raw === "object" ? raw as Record<string, string> : {};
}

function dayAvail(o: Onboarding): DayAvail[] {
  const raw = o.day_availability;
  if (!Array.isArray(raw)) return [];
  return raw
    .filter((d): d is DayAvail =>
      !!d && typeof (d as DayAvail).day === "string" && typeof (d as DayAvail).max_minutes === "number")
    .map((d) => ({ day: d.day, max_minutes: d.max_minutes }));
}

const SPORT_LABEL: Record<string, string> = {
  run: "Running",
  ride: "Cycling",
  swim: "Swimming",
  strength: "Gym",
};

// Combined goal phrase, e.g. "Marathon + Build Muscle". Falls back to the single
// legacy goal, then a sensible default.
export function goalsText(o: Onboarding): string {
  const goals = Array.isArray(o.goals)
    ? (o.goals as unknown[]).filter((g): g is string => typeof g === "string" && g.trim().length > 0)
    : [];
  if (goals.length) return goals.join(" + ");
  if (typeof o.goal === "string" && o.goal.trim()) return o.goal;
  return "General fitness";
}

// Experience for one sport, with fallbacks: per-sport value → legacy global →
// any per-sport value → "Intermediate".
export function experienceForSport(o: Onboarding, sport: string): string {
  const map = expMap(o);
  const byThis = map[sport];
  if (typeof byThis === "string" && byThis.trim()) return byThis;
  if (typeof o.experience === "string" && o.experience.trim()) return o.experience;
  const anyVal = Object.values(map).find((v) => typeof v === "string" && v.trim());
  return anyVal ?? "Intermediate";
}

// Per-activity experience block for the prompt. "" when nothing is set.
export function experienceBlock(o: Onboarding): string {
  const map = expMap(o);
  const parts = Object.entries(map)
    .filter(([, v]) => typeof v === "string" && v.trim())
    .map(([k, v]) => `${SPORT_LABEL[k] ?? k}: ${v}`);
  if (!parts.length) return "";
  return "\n\nEXPERIENCE BY ACTIVITY (calibrate volume, intensity and complexity per sport; " +
    "an advanced lifter can still be a beginner runner):\n- " + parts.join("; ") + ".";
}

// Total training minutes the athlete's week can hold, from per-day availability.
// 0 when day_availability is unset (legacy profiles) — callers must treat that
// as "no ceiling", not "no time".
export function weeklyAvailableMinutes(o: Onboarding): number {
  return dayAvail(o).reduce((s, d) => s + d.max_minutes, 0);
}

// Minutes available on a given ISO date from per-day availability, or null when
// not set (callers then fall back to session_duration).
export function minutesForDate(o: Onboarding, isoDate: string): number | null {
  const days = dayAvail(o);
  if (!days.length) return null;
  const hit = days.find((d) => d.day === weekdayOf(isoDate));
  return hit ? hit.max_minutes : null;
}

// Durable athlete profile card: the static facts the coach should always know
// (name, demographics, goals, injuries). Deterministic and always injected, so
// these never get dropped by the chat-maintained knowledge doc. "" when empty.
export function profileFactsBlock(o: Onboarding, displayName?: string | null): string {
  const facts: string[] = [];
  const name = (displayName ?? "").trim();
  if (name) facts.push(`Name: ${name}`);
  const by = o.birth_year;
  if (typeof by === "number" && by > 1900 && by < 2200) {
    const age = new Date().getFullYear() - by;
    if (age > 0 && age < 120) facts.push(`Age: ${age}`);
  }
  if (typeof o.sex === "string" && o.sex.trim()) facts.push(`Sex: ${o.sex.trim()}`);
  if (typeof o.height_cm === "number" && o.height_cm > 0) facts.push(`Height: ${o.height_cm} cm`);
  if (typeof o.weight_kg === "number" && o.weight_kg > 0) facts.push(`Weight: ${o.weight_kg} kg`);
  const goals = goalsText(o);
  if (goals && goals !== "General fitness") facts.push(`Goals: ${goals}`);
  if (typeof o.injury_history === "string" && o.injury_history.trim()) {
    facts.push(`Injuries to train around: ${o.injury_history.trim()}`);
  }
  if (!facts.length) return "";
  return "\n\nATHLETE PROFILE (durable facts, always true; address them by name):\n" +
    facts.map((f) => `- ${f}`).join("\n");
}

// Modality constraint for the weekly planner: only schedule what the athlete
// listed. Extracted from plan-week so the eval fixtures send byte-identical
// context instead of a hand-copied string that drifts. "" when nothing is set.
export function sportsBlock(sportsList: string[]): string {
  return sportsList.length
    ? `\n\nSPORTS THE ATHLETE DOES: ${sportsList.join(", ")}. ONLY schedule these modalities (plus rest days). Do NOT introduce a sport they did not list.`
    : "";
}

// Strength split rotation for the weekly planner. "" for auto/unset.
export function splitBlock(splitStyle: string): string {
  return splitStyle && !/^auto$/i.test(splitStyle)
    ? `\n\nSTRENGTH SPLIT: the athlete follows a ${splitStyle} split, sequence the week's strength days to follow that rotation (e.g. push/pull/legs across three days, or alternating upper/lower), each session built around that day's focus and spaced for ≥48h muscle recovery.`
    : "";
}

// The athlete's standing challenge preference (Settings → Planning). This is a
// BIAS on top of the automatic adaptation (readiness caps, measured execution),
// not a replacement: "easier" shifts a normal day down a notch, it never
// overrides a red-recovery cap or a deload. "" for standard/unset.
export function challengeBlock(o: Onboarding): string {
  const c = typeof o.challenge === "string" ? o.challenge.toLowerCase() : "";
  if (c === "easier") {
    return "\n\nCHALLENGE PREFERENCE: the athlete prefers sessions a notch EASIER than standard. " +
      "Bias RPE targets down ~1 and volume down ~10%; keep quality sessions but soften them. " +
      "Recovery rules still apply on top.";
  }
  if (c === "harder") {
    return "\n\nCHALLENGE PREFERENCE: the athlete prefers sessions a notch HARDER than standard. " +
      "Bias toward the top of each phase-appropriate range (RPE, volume), never beyond safe " +
      "recovery rules; a low-readiness day still gets an easy session.";
  }
  return "";
}

// Weekly availability block so the planner sizes long days vs short weekdays.
// "" when nothing is set.
export function availabilityBlock(o: Onboarding): string {
  const days = dayAvail(o);
  if (!days.length) return "";
  const sorted = [...days].sort((a, b) => DAY_ORDER.indexOf(a.day) - DAY_ORDER.indexOf(b.day));
  const line = sorted.map((d) => `${d.day} up to ${d.max_minutes} min`).join(", ");
  const rest = DAY_ORDER.filter((d) => !days.some((x) => x.day === d));
  const restLine = rest.length ? ` Rest days: ${rest.join(", ")}.` : "";
  return "\n\nWEEKLY AVAILABILITY (size each day to its budget; put long sessions on the long days, " +
    "keep short days short, do NOT pad every day to the same length):\n- " + line + "." + restLine;
}
