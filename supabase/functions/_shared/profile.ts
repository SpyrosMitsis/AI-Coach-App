// Readers for the richer onboarding fields (multiple goals, per-activity
// experience, per-day availability) with graceful fallback to the single-value
// legacy fields the app also writes (deriveLegacyFields on the client). Pure and
// dependency-free so it is trivial to unit test.

import type { InjuryEntry } from "./types.ts";

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

const SEVERITY_RE = /\((mild|moderate|serious)\)/i;

function isInjuryEntry(v: unknown): v is InjuryEntry {
  return !!v && typeof v === "object" && typeof (v as InjuryEntry).area === "string";
}

// Structured injuries, falling back to parsing the legacy free-text
// injury_history string (the "Knee (moderate), lower back" format the Android
// InjuryEditor used to write) when `injuries` isn't set. This is the sole seam
// for reading injury data server-side — every other reader should call this,
// never `o.injury_history`/`o.injuries` directly, so old and new profiles
// behave identically to every caller.
export function injuriesOf(o: Onboarding): InjuryEntry[] {
  const raw = o.injuries;
  if (Array.isArray(raw) && raw.every(isInjuryEntry)) return raw as InjuryEntry[];
  const legacy = o.injury_history;
  if (typeof legacy !== "string" || !legacy.trim()) return [];
  return legacy
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
    .map((part) => {
      const m = SEVERITY_RE.exec(part);
      const severity = (m?.[1]?.toLowerCase() ?? "") as InjuryEntry["severity"];
      const area = part.replace(SEVERITY_RE, "").trim();
      return { area, severity };
    });
}

// Prose rendering of injuriesOf() for prompt/tool contexts that need a
// human-readable line rather than the structured array.
export function injuriesText(o: Onboarding): string {
  return injuriesOf(o)
    .map((i) => (i.severity ? `${i.area} (${i.severity})` : i.area))
    .join("; ");
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
  if (typeof o.body_fat_pct === "number" && o.body_fat_pct >= 3 && o.body_fat_pct <= 60) {
    facts.push(`Body fat: ~${o.body_fat_pct}%`);
  }
  const goals = goalsText(o);
  if (goals && goals !== "General fitness") facts.push(`Goals: ${goals}`);
  const injuries = injuriesText(o);
  if (injuries) {
    facts.push(`Injuries to train around: ${injuries}`);
  }
  if (!facts.length) return "";
  return "\n\nATHLETE PROFILE (durable facts, always true; address them by name):\n" +
    facts.map((f) => `- ${f}`).join("\n");
}

// Body composition for the strength prompt (prompt.ts bodyLine). Bounded so a
// typo'd weight never anchors every prescribed load. Null when nothing usable.
export function bodyComposition(
  o: Onboarding,
): { weightKg?: number; heightCm?: number; bodyFatPct?: number } | null {
  const out: { weightKg?: number; heightCm?: number; bodyFatPct?: number } = {};
  if (typeof o.weight_kg === "number" && o.weight_kg >= 30 && o.weight_kg <= 250) out.weightKg = o.weight_kg;
  if (typeof o.height_cm === "number" && o.height_cm >= 120 && o.height_cm <= 230) out.heightCm = o.height_cm;
  if (typeof o.body_fat_pct === "number" && o.body_fat_pct >= 3 && o.body_fat_pct <= 60) {
    out.bodyFatPct = o.body_fat_pct;
  }
  return Object.keys(out).length ? out : null;
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
