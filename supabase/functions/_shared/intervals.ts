// ============================================================================
// Intervals.icu API client. Auth is HTTP Basic with username "API_KEY" and the
// user's personal key as the password. All calls run server-side only.
// ============================================================================

const BASE = "https://intervals.icu/api/v1";

function authHeader(apiKey: string): string {
  return "Basic " + btoa(`API_KEY:${apiKey}`);
}

export interface IntervalsAthlete {
  id: string;
  name: string;
  email?: string;
}

export interface WellnessRow {
  id: string; // date
  ctl?: number;
  atl?: number;
  rampRate?: number;
  restingHR?: number;
  hrv?: number;
  vo2max?: number;
  sleepSecs?: number;
  // Subjective fields the athlete logs in Intervals.icu (1=good .. 4=bad scale).
  fatigue?: number;
  soreness?: number;
  stress?: number;
  mood?: number;
  motivation?: number;
  injury?: number;
  weight?: number;
}

export interface ActivityRow {
  id: string;
  type?: string;
  start_date_local?: string;
  moving_time?: number;
  distance?: number;
  average_heartrate?: number;
  icu_training_load?: number;
  icu_ctl?: number;
  icu_atl?: number;
  name?: string;
}

async function get<T>(athleteId: string, apiKey: string, path: string): Promise<T> {
  const res = await fetch(`${BASE}/athlete/${athleteId}${path}`, {
    headers: { Authorization: authHeader(apiKey) },
  });
  if (!res.ok) {
    throw new Error(`intervals GET ${path} → HTTP ${res.status}: ${await res.text()}`);
  }
  return (await res.json()) as T;
}

export async function getAthlete(athleteId: string, apiKey: string): Promise<IntervalsAthlete> {
  return get<IntervalsAthlete>(athleteId, apiKey, "");
}

// The athlete payload also carries per-sport training settings (HR/power/pace
// zones). We read these to surface the user's HR zones in the app.
export interface SportSettings {
  types?: string[];
  hr_zones?: number[];
  hr_zone_names?: string[];
  max_hr?: number;
  lthr?: number;
  // Pace settings (m/s). threshold_pace is the athlete's threshold speed.
  threshold_pace?: number;
  pace_zones?: number[];       // % of threshold pace, ascending
  pace_zone_names?: string[];
  ftp?: number;
  power_zones?: number[];
}
export interface IntervalsAthleteFull extends IntervalsAthlete {
  sportSettings?: SportSettings[];
}

export async function getAthleteFull(athleteId: string, apiKey: string): Promise<IntervalsAthleteFull> {
  return get<IntervalsAthleteFull>(athleteId, apiKey, "");
}

export interface HrZone { name: string; min: number; max: number }

export function runSportSettings(athlete: IntervalsAthleteFull): SportSettings | undefined {
  const ss = athlete.sportSettings ?? [];
  return ss.find((s) => s.types?.some((t) => /run/i.test(t))) ?? ss[0];
}

// Derive ascending HR zones from the Run sport settings (fallback: first sport).
// intervals.icu stores `hr_zones` as the absolute bpm upper bound of each zone.
export function runHrZones(athlete: IntervalsAthleteFull): HrZone[] {
  const run = runSportSettings(athlete);
  const zones = run?.hr_zones;
  if (!zones?.length) return [];
  const names = run?.hr_zone_names ?? [];
  let prev = 0;
  return zones.map((upper, i) => {
    const z: HrZone = { name: names[i] ?? `Z${i + 1}`, min: i === 0 ? 0 : prev + 1, max: upper };
    prev = upper;
    return z;
  });
}

// Speed (m/s) → "m:ss/km" pace string.
export function paceFromMs(ms: number): string {
  if (!ms || ms <= 0) return "—";
  const secPerKm = 1000 / ms;
  const m = Math.floor(secPerKm / 60);
  const s = Math.round(secPerKm % 60);
  return `${m}:${s.toString().padStart(2, "0")}/km`;
}

export interface PaceZone { name: string; pace: string }

// Pace zones as /km strings. intervals `pace_zones` are % of threshold pace.
export function runPaceZones(athlete: IntervalsAthleteFull): { thresholdPace: string; zones: PaceZone[] } {
  const run = runSportSettings(athlete);
  const tp = run?.threshold_pace;
  if (!tp) return { thresholdPace: "—", zones: [] };
  const pcts = run?.pace_zones ?? [];
  const names = run?.pace_zone_names ?? [];
  const zones: PaceZone[] = pcts.map((pct, i) => ({
    name: names[i] ?? `Z${i + 1}`,
    // pct is % of threshold speed; speed = tp * pct/100 → slower zones = lower %.
    pace: paceFromMs(tp * (pct / 100)),
  }));
  return { thresholdPace: paceFromMs(tp), zones };
}

// Most-recent non-null subjective wellness values (Intervals 1=good..4=bad).
export function latestWellnessSubjective(wellness: WellnessRow[]): {
  fatigue?: number; soreness?: number; stress?: number; mood?: number;
  motivation?: number; injury?: number; restingHR?: number; hrv?: number;
  vo2max?: number; weight?: number;
} {
  const sorted = [...wellness].sort((a, b) => (a.id < b.id ? 1 : -1));
  const pick = (k: keyof WellnessRow) => {
    const row = sorted.find((r) => typeof r[k] === "number");
    return row ? (row[k] as number) : undefined;
  };
  return {
    fatigue: pick("fatigue"), soreness: pick("soreness"), stress: pick("stress"),
    mood: pick("mood"), motivation: pick("motivation"), injury: pick("injury"),
    restingHR: pick("restingHR"), hrv: pick("hrv"), vo2max: pick("vo2max"),
    weight: pick("weight"),
  };
}

export function isoDaysAgo(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString().slice(0, 10);
}

export async function getActivities(
  athleteId: string,
  apiKey: string,
  days = 60,
): Promise<ActivityRow[]> {
  const oldest = isoDaysAgo(days);
  const newest = isoDaysAgo(0);
  return get<ActivityRow[]>(
    athleteId,
    apiKey,
    `/activities?oldest=${oldest}&newest=${newest}`,
  );
}

// Wellness contains the daily CTL/ATL/HRV/RHR/VO2max/sleep series.
export async function getWellness(
  athleteId: string,
  apiKey: string,
  days = 60,
): Promise<WellnessRow[]> {
  const oldest = isoDaysAgo(days);
  const newest = isoDaysAgo(0);
  return get<WellnessRow[]>(
    athleteId,
    apiKey,
    `/wellness?oldest=${oldest}&newest=${newest}`,
  );
}

export function latestFitness(wellness: WellnessRow[]): {
  ctl: number; atl: number; tsb: number;
} {
  // Wellness rows are keyed by date (id). Pick the most recent with CTL.
  const sorted = [...wellness].sort((a, b) => (a.id < b.id ? 1 : -1));
  const row: Partial<WellnessRow> = sorted.find((r) => typeof r.ctl === "number") ?? {};
  const ctl = row.ctl ?? 0;
  const atl = row.atl ?? 0;
  return { ctl, atl, tsb: ctl - atl };
}

// Raw per-second data streams for one activity (note: activity-scoped path,
// not athlete-scoped). Available types include time, distance, heartrate,
// velocity_smooth, watts, cadence, altitude.
export interface ActivityStream {
  type: string;
  data: (number | null)[];
}

export async function getActivityStreams(
  apiKey: string,
  activityId: string,
  types: string[],
): Promise<ActivityStream[]> {
  const res = await fetch(
    `${BASE}/activity/${activityId}/streams?types=${types.join(",")}`,
    { headers: { Authorization: authHeader(apiKey) } },
  );
  if (!res.ok) {
    throw new Error(`intervals GET streams → HTTP ${res.status}: ${await res.text()}`);
  }
  return (await res.json()) as ActivityStream[];
}

// Push a planned workout to the athlete's Intervals.icu calendar.
export interface IntervalsEventInput {
  date: string; // YYYY-MM-DD
  name: string;
  description: string;
  type: string; // "Run" | "Workout" (strength) | "Other"
}

export async function deleteEvent(athleteId: string, apiKey: string, eventId: string): Promise<void> {
  await fetch(`${BASE}/athlete/${athleteId}/events/${eventId}`, {
    method: "DELETE",
    headers: { Authorization: authHeader(apiKey) },
  });
}

// Update fields on an existing calendar event (e.g. move it to another date).
export async function updateEvent(
  athleteId: string,
  apiKey: string,
  eventId: string,
  fields: Record<string, unknown>,
): Promise<void> {
  const res = await fetch(`${BASE}/athlete/${athleteId}/events/${eventId}`, {
    method: "PUT",
    headers: {
      Authorization: authHeader(apiKey),
      "Content-Type": "application/json",
    },
    body: JSON.stringify(fields),
  });
  if (!res.ok) {
    throw new Error(`intervals PUT event → HTTP ${res.status}: ${await res.text()}`);
  }
}

export async function createEvent(
  athleteId: string,
  apiKey: string,
  ev: IntervalsEventInput,
): Promise<{ id: string }> {
  const res = await fetch(`${BASE}/athlete/${athleteId}/events`, {
    method: "POST",
    headers: {
      Authorization: authHeader(apiKey),
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      start_date_local: `${ev.date}T00:00:00`,
      category: "WORKOUT",
      name: ev.name,
      description: ev.description,
      type: ev.type,
    }),
  });
  if (!res.ok) {
    throw new Error(`intervals POST event → HTTP ${res.status}: ${await res.text()}`);
  }
  const data = await res.json();
  return { id: String(data.id ?? data[0]?.id ?? "") };
}
