// Default HR zones when Intervals.icu hasn't provided real ones.
//
// The old fallback was ONE hardcoded table (95-130 ... 173-190) for every
// human: a 22-year-old and a 58-year-old got identical zone prescriptions, and
// every "Z2 131-145bpm" instruction the model faithfully followed was wrong for
// both. This derives per-athlete defaults from what onboarding actually knows,
// best signal first:
//
//   1. lthr           — zones as %LTHR (Friel bands), the most direct anchor
//                       ("the HR you can hold for ~an hour"), asked at onboarding.
//   2. birth_year     — HRmax via Tanaka (208 - 0.7 x age), zones as %HRmax.
//                       Population formulas are +-10bpm, still far better than
//                       one table for everyone.
//   3. neither        — the legacy table, unchanged, so nothing regresses.
//
// Real measured zones (Intervals.icu) always win; callers keep that precedence.

export interface HrZone {
  zone: string;
  min: number;
  max: number;
}

/** The legacy one-size-fits-all table. Kept as the last resort, verbatim. */
export const LEGACY_HR_ZONES: HrZone[] = [
  { zone: "Z1", min: 95, max: 130 },
  { zone: "Z2", min: 131, max: 145 },
  { zone: "Z3", min: 146, max: 160 },
  { zone: "Z4", min: 161, max: 172 },
  { zone: "Z5", min: 173, max: 190 },
];

const r = (v: number) => Math.round(v);

/** Friel run bands, % of LTHR. Z1 <85, Z2 85-89, Z3 90-94, Z4 95-99, Z5 100+. */
export function zonesFromLthr(lthr: number): HrZone[] {
  return [
    { zone: "Z1", min: r(lthr * 0.65), max: r(lthr * 0.84) },
    { zone: "Z2", min: r(lthr * 0.85), max: r(lthr * 0.89) },
    { zone: "Z3", min: r(lthr * 0.90), max: r(lthr * 0.94) },
    { zone: "Z4", min: r(lthr * 0.95), max: r(lthr * 0.99) },
    { zone: "Z5", min: r(lthr * 1.00), max: r(lthr * 1.08) },
  ];
}

/** %HRmax bands (50-60/60-70/70-80/80-90/90-100), HRmax via Tanaka. */
export function zonesFromAge(age: number): HrZone[] {
  const hrMax = 208 - 0.7 * age;
  const band = (lo: number, hi: number) => ({ min: r(hrMax * lo), max: r(hrMax * hi) });
  return [
    { zone: "Z1", ...band(0.50, 0.60) },
    { zone: "Z2", ...band(0.60, 0.70) },
    { zone: "Z3", ...band(0.70, 0.80) },
    { zone: "Z4", ...band(0.80, 0.90) },
    { zone: "Z5", ...band(0.90, 1.00) },
  ];
}

/**
 * The default zones for an athlete, from the best onboarding signal available.
 * Callers apply this only when no measured zones exist:
 *   `ivHrZones ?? onboarding.hr_zones ?? defaultHrZones(onboarding)`.
 */
export function defaultHrZones(o: { lthr?: unknown; birth_year?: unknown }): HrZone[] {
  const lthr = typeof o.lthr === "number" && o.lthr >= 120 && o.lthr <= 210 ? o.lthr : null;
  if (lthr !== null) return zonesFromLthr(lthr);
  const by = typeof o.birth_year === "number" ? o.birth_year : null;
  if (by !== null && by > 1900 && by < 2200) {
    const age = new Date().getFullYear() - by;
    if (age >= 10 && age <= 90) return zonesFromAge(age);
  }
  return LEGACY_HR_ZONES;
}
