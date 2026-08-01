// Open-Meteo weather client — free, no API key. Used to make outdoor running
// sessions weather-aware (heat/humidity pace adjustment, storm → indoor swap).

export interface WeatherSnapshot {
  tempC: number;
  apparentC: number;
  humidity: number;
  windKmh: number;
  precipMm: number;
  precipProbMax: number;
  tMaxC: number;
  summary: string;
}

export async function getWeather(lat: number, lon: number): Promise<WeatherSnapshot | null> {
  const url =
    `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}` +
    `&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,wind_speed_10m` +
    `&daily=temperature_2m_max,precipitation_probability_max&forecast_days=1&timezone=auto`;
  try {
    const res = await fetch(url);
    if (!res.ok) return null;
    const d = await res.json();
    const c = d.current ?? {};
    const daily = d.daily ?? {};
    const snap: WeatherSnapshot = {
      tempC: c.temperature_2m ?? 0,
      apparentC: c.apparent_temperature ?? c.temperature_2m ?? 0,
      humidity: c.relative_humidity_2m ?? 0,
      windKmh: c.wind_speed_10m ?? 0,
      precipMm: c.precipitation ?? 0,
      precipProbMax: daily.precipitation_probability_max?.[0] ?? 0,
      tMaxC: daily.temperature_2m_max?.[0] ?? (c.temperature_2m ?? 0),
      summary: "",
    };
    snap.summary = describe(snap);
    return snap;
  } catch {
    return null;
  }
}

function describe(w: WeatherSnapshot): string {
  const bits: string[] = [];
  if (w.apparentC >= 27) bits.push("hot, slow target paces ~10-20s/km, prioritise hydration, consider early/indoor");
  else if (w.apparentC <= 0) bits.push("freezing, extend warmup, watch for ice, layer up");
  else if (w.apparentC <= 5) bits.push("cold, longer warmup");
  if (w.humidity >= 80 && w.apparentC >= 20) bits.push("very humid, extra cooling/pace caution");
  if (w.precipProbMax >= 70 || w.precipMm >= 1) bits.push("rain likely, waterproof or move indoors/treadmill");
  if (w.windKmh >= 30) bits.push("strong wind, expect slower into-wind splits");
  return bits.length ? bits.join("; ") : "mild, no adjustment needed";
}

// Deterministic (non-LLM) sport-aware viability gate. Separate from describe()
// above on purpose: describe() is prose fed to the model as a soft hint, this
// is a hard threshold used to (a) escalate that prompt to a hard constraint
// and (b) drive the token-free daily swap-prompt check in weather-check/.
//
// Running is heat/cold-limited per effort (no airflow cooling, no coasting
// recovery); cycling is wind/rain-limited for bike control, braking, and
// spray/visibility at speed — the two sports get different thresholds.
export type ViabilityTier = "ok" | "caution" | "blocked";
export interface ViabilityVerdict {
  tier: ViabilityTier;
  reasons: string[];
}

export function assessViability(w: WeatherSnapshot, sport: "run" | "ride"): ViabilityVerdict {
  const blocked: string[] = [];
  const caution: string[] = [];

  if (sport === "run") {
    if (w.apparentC >= 36) blocked.push(`extreme heat (feels ${w.apparentC.toFixed(0)}°C)`);
    if (w.apparentC <= -10) blocked.push(`extreme cold (feels ${w.apparentC.toFixed(0)}°C)`);
    if (blocked.length === 0) {
      if (w.apparentC >= 28) caution.push(`hot (feels ${w.apparentC.toFixed(0)}°C)`);
      if (w.apparentC <= 0) caution.push(`freezing (feels ${w.apparentC.toFixed(0)}°C)`);
      if (w.humidity >= 80 && w.apparentC >= 24) caution.push(`very humid (${w.humidity}% RH)`);
      if (w.windKmh >= 40) caution.push(`strong wind (${w.windKmh.toFixed(0)} km/h)`);
      if (w.precipProbMax >= 70 && w.precipMm >= 3) caution.push(`heavy rain likely (${w.precipProbMax}%, ${w.precipMm.toFixed(1)}mm)`);
    }
  } else {
    if (w.windKmh >= 45) blocked.push(`strong crosswind risk (${w.windKmh.toFixed(0)} km/h)`);
    if (w.precipProbMax >= 70 && w.precipMm >= 2) {
      blocked.push(`heavy rain likely (${w.precipProbMax}%, ${w.precipMm.toFixed(1)}mm) — poor traction/visibility on a bike`);
    }
    if (w.apparentC <= -5) blocked.push(`extreme wind-chill exposure (feels ${w.apparentC.toFixed(0)}°C)`);
    if (w.apparentC >= 37) blocked.push(`extreme heat with no shade on a bike route (feels ${w.apparentC.toFixed(0)}°C)`);
    if (blocked.length === 0) {
      if (w.windKmh >= 30) caution.push(`elevated wind (${w.windKmh.toFixed(0)} km/h)`);
      if (w.precipProbMax >= 50 || w.precipMm >= 0.5) caution.push(`rain likely (${w.precipProbMax}%, ${w.precipMm.toFixed(1)}mm)`);
      if (w.apparentC <= 2) caution.push(`cold (feels ${w.apparentC.toFixed(0)}°C)`);
      if (w.apparentC >= 30) caution.push(`hot (feels ${w.apparentC.toFixed(0)}°C)`);
    }
  }

  if (blocked.length) return { tier: "blocked", reasons: blocked };
  if (caution.length) return { tier: "caution", reasons: caution };
  return { tier: "ok", reasons: [] };
}
