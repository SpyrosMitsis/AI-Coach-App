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
  if (w.apparentC >= 27) bits.push("hot — slow target paces ~10-20s/km, prioritise hydration, consider early/indoor");
  else if (w.apparentC <= 0) bits.push("freezing — extend warmup, watch for ice, layer up");
  else if (w.apparentC <= 5) bits.push("cold — longer warmup");
  if (w.humidity >= 80 && w.apparentC >= 20) bits.push("very humid — extra cooling/pace caution");
  if (w.precipProbMax >= 70 || w.precipMm >= 1) bits.push("rain likely — waterproof or move indoors/treadmill");
  if (w.windKmh >= 30) bits.push("strong wind — expect slower into-wind splits");
  return bits.length ? bits.join("; ") : "mild — no adjustment needed";
}
