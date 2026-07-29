// ============================================================================
// Is this goal actually doable in the time available?
//
// The coach used to accept any goal and immediately plan toward it. "Ironman in
// ten weeks" off 3 hours a week got the same enthusiastic week-planning as a
// well-judged target, which is both bad coaching and the fastest route to an
// injury.
//
// This quantifies it from the athlete's OWN numbers using rules the engine
// already enforces elsewhere, so the verdict and the training agree:
//   - weekly volume may rise ~10% a week (COACHING_PRINCIPLES, plan_checks)
//   - the last 1-2 weeks are taper, not build (periodization)
//   - an event has a peak weekly volume and a long-session demand below which
//     finishing it is not a training question but a medical one
//
// The output is deliberately a BAND plus reasons, not just a number: the coach
// has to explain itself to the athlete, and "your longest run this month was
// 6 km" persuades where "feasibility 31/100" does not.
// ============================================================================

export type FeasibilityBand = "ready" | "stretch" | "risky" | "unrealistic";

export interface GoalDemand {
  /** Peak weekly volume the event needs, in km (endurance) or hours (multisport). */
  peakWeeklyKm?: number;
  peakWeeklyHours?: number;
  /** Longest single session the athlete should have built to. */
  longSessionKm?: number;
  /** Weeks of focused training below which this event is not sensible at all. */
  minWeeks: number;
  label: string;
}

/**
 * Demands by event. These are conservative FINISHING targets for a healthy
 * adult, not competitive ones, and they are the numbers the pushback quotes,
 * so they must stay defensible.
 */
export const RACE_DEMANDS: Record<string, GoalDemand> = {
  "5k": { peakWeeklyKm: 20, longSessionKm: 8, minWeeks: 4, label: "5K" },
  "10k": { peakWeeklyKm: 25, longSessionKm: 10, minWeeks: 6, label: "10K" },
  "half marathon": { peakWeeklyKm: 35, longSessionKm: 18, minWeeks: 10, label: "half marathon" },
  "marathon": { peakWeeklyKm: 50, longSessionKm: 30, minWeeks: 16, label: "marathon" },
  "50k": { peakWeeklyKm: 65, longSessionKm: 35, minWeeks: 20, label: "50K ultra" },
  "100k": { peakWeeklyKm: 85, longSessionKm: 45, minWeeks: 24, label: "100K ultra" },
  "sprint tri": { peakWeeklyHours: 4, minWeeks: 8, label: "sprint triathlon" },
  "olympic tri": { peakWeeklyHours: 6, minWeeks: 12, label: "olympic triathlon" },
  "70.3": { peakWeeklyHours: 9, minWeeks: 20, label: "70.3" },
  "ironman": { peakWeeklyHours: 13, minWeeks: 30, label: "full Ironman" },
  "century": { peakWeeklyKm: 200, longSessionKm: 100, minWeeks: 12, label: "century ride" },
  "gran fondo": { peakWeeklyKm: 180, longSessionKm: 90, minWeeks: 12, label: "gran fondo" },
  "hyrox": { peakWeeklyHours: 5, minWeeks: 12, label: "Hyrox" },
};

/**
 * Map free text ("my first marathon", "70.3 in Nice") onto a known demand.
 * Order matters: "half marathon" must be tested before "marathon", and "50k"
 * before "5k", or the substring match picks the wrong event.
 */
export function matchDemand(text: string): GoalDemand | null {
  const t = text.toLowerCase();
  const ordered: Array<[string, string[]]> = [
    // "half ironman" contains "ironman", exactly as "half marathon" contains
    // "marathon", so the narrower event is always tested first.
    ["70.3", ["70.3", "half ironman", "half-ironman", "middle distance"]],
    ["ironman", ["ironman", "full distance", "140.6"]],
    ["olympic tri", ["olympic tri", "olympic distance"]],
    ["sprint tri", ["sprint tri", "sprint triathlon"]],
    ["100k", ["100k", "100 km", "100km"]],
    ["50k", ["50k", "50 km", "50km", "ultra"]],
    ["half marathon", ["half marathon", "half-marathon", "21k", "21.1", "hm"]],
    ["marathon", ["marathon", "42k", "42.2"]],
    ["century", ["century", "100 mile", "100-mile"]],
    ["gran fondo", ["gran fondo", "granfondo", "fondo"]],
    ["hyrox", ["hyrox"]],
    ["10k", ["10k", "10 km", "10km"]],
    ["5k", ["5k", "5 km", "5km", "parkrun"]],
  ];
  for (const [key, needles] of ordered) {
    if (needles.some((n) => t.includes(n))) return RACE_DEMANDS[key];
  }
  return null;
}

export interface FeasibilityInput {
  /** What they want to do, free text: "marathon", "my first 70.3". */
  goal: string;
  /** Weeks from today until race day. */
  weeksAway: number;
  /** Current weekly running/riding volume in km, if known. */
  currentWeeklyKm?: number | null;
  /** Current weekly training hours across all sports, if known. */
  currentWeeklyHours?: number | null;
  /** Longest single session in the last month, km. */
  longestRecentKm?: number | null;
  /** Chronic training load. Near zero means no history, NOT necessarily unfit. */
  ctl?: number | null;
  /** Self-reported level, used only to break ties when data is thin. */
  experience?: string | null;
}

export interface Feasibility {
  band: FeasibilityBand;
  /** 0-100. Presented to the coach, never to the athlete as a bare number. */
  score: number;
  /** One sentence the coach can build its reply around. */
  headline: string;
  /** Concrete, quotable evidence. These are what actually persuade. */
  reasons: string[];
  /** A realistic alternative when the goal does not fit the time available. */
  suggestion: string | null;
  /** True when the coach must NOT jump straight to planning the block. */
  pushBack: boolean;
  /** Weeks this athlete would realistically need from where they are now. */
  weeksNeeded: number | null;
  matched: string | null;
}

/** Weeks of ~10%/week progression to get from `from` to `to`, plus a taper. */
export function rampWeeks(from: number, to: number): number {
  if (to <= from) return 0;
  // A floor keeps the maths sane for someone starting from nothing: you cannot
  // multiply zero, so treat "no volume" as a token base rather than infinity.
  const base = Math.max(from, to * 0.15);
  return Math.ceil(Math.log(to / base) / Math.log(1.1));
}

const TAPER_WEEKS = 2;

export function assessFeasibility(input: FeasibilityInput): Feasibility {
  const demand = matchDemand(input.goal);
  const weeks = Math.max(0, Math.round(input.weeksAway));

  if (!demand) {
    // An unknown event is not a licence to be reckless, but it is also not
    // something to lecture about. Say so plainly and let the coach ask.
    return {
      band: "stretch",
      score: 55,
      headline: `I don't have a standard training demand for "${input.goal}", so I can't size it from your numbers alone.`,
      reasons: [],
      suggestion: null,
      pushBack: false,
      weeksNeeded: null,
      matched: null,
    };
  }

  const reasons: string[] = [];
  const usesHours = demand.peakWeeklyHours != null;
  const currentVol = usesHours
    ? (input.currentWeeklyHours ?? 0)
    : (input.currentWeeklyKm ?? 0);
  const targetVol = usesHours ? demand.peakWeeklyHours! : demand.peakWeeklyKm!;
  const unit = usesHours ? "h" : "km";

  const build = rampWeeks(currentVol, targetVol);
  const weeksNeeded = Math.max(build + TAPER_WEEKS, demand.minWeeks);

  // --- evidence ------------------------------------------------------------
  if (currentVol > 0) {
    reasons.push(
      `Currently around ${round1(currentVol)}${unit} a week; a ${demand.label} wants to peak near ${targetVol}${unit}.`,
    );
  } else {
    reasons.push(`No recent training volume on record to build from.`);
  }

  if (!usesHours && demand.longSessionKm != null && input.longestRecentKm != null) {
    if (input.longestRecentKm < demand.longSessionKm * 0.5) {
      reasons.push(
        `Longest session in the last month was ${round1(input.longestRecentKm)}km, against a ${demand.longSessionKm}km long day this event needs.`,
      );
    }
  }

  if (weeks < demand.minWeeks) {
    reasons.push(
      `${weeks} weeks out, and a ${demand.label} normally needs at least ${demand.minWeeks} weeks of focused training.`,
    );
  }

  if ((input.ctl ?? 0) < 20 && currentVol > 0) {
    reasons.push(`Chronic load is still low, so the base to build on is thin.`);
  }

  // --- verdict -------------------------------------------------------------
  // The ratio of time available to time genuinely needed is the whole story.
  const ratio = weeksNeeded > 0 ? weeks / weeksNeeded : 1;
  let band: FeasibilityBand;
  if (ratio >= 1) band = "ready";
  else if (ratio >= 0.75) band = "stretch";
  else if (ratio >= 0.5) band = "risky";
  else band = "unrealistic";

  // A hard floor no amount of ramping can buy back: being under the event's
  // minimum block length is a different failure from merely being behind.
  if (weeks < demand.minWeeks * 0.5) band = "unrealistic";

  const score = Math.round(Math.max(0, Math.min(100, ratio * 100)));

  const headline = {
    ready: `A ${demand.label} in ${weeks} weeks is realistic from where you are.`,
    stretch: `A ${demand.label} in ${weeks} weeks is a stretch, but defensible if everything goes well.`,
    risky: `A ${demand.label} in ${weeks} weeks would mean progressing faster than is safe.`,
    unrealistic: `A ${demand.label} in ${weeks} weeks is not realistically trainable from your current base.`,
  }[band];

  return {
    band,
    score,
    headline,
    reasons,
    suggestion: band === "ready" ? null : suggestFor(demand, weeks, weeksNeeded),
    // "stretch" is a warning the coach mentions in passing; risky and
    // unrealistic mean stop and talk before planning anything.
    pushBack: band === "risky" || band === "unrealistic",
    weeksNeeded,
    matched: demand.label,
  };
}

/** A concrete alternative, since "no" without an option is not coaching. */
function suggestFor(demand: GoalDemand, weeks: number, weeksNeeded: number): string {
  const smaller: Record<string, string> = {
    "full Ironman": "a 70.3 now and the full next season",
    "70.3": "an olympic-distance race now and the 70.3 later",
    "olympic triathlon": "a sprint triathlon now",
    "100K ultra": "a 50K now",
    "50K ultra": "a marathon now",
    "marathon": "a half marathon now, with the marathon a few months later",
    "half marathon": "a 10K now, then the half",
    "10K": "a 5K now",
    "century ride": "a gran fondo or a 100km ride now",
    "gran fondo": "a 100km ride now",
  };
  const step = smaller[demand.label];
  const later = `keep the ${demand.label} but move it out to about ${weeksNeeded} weeks away`;
  return step ? `Either ${step}, or ${later}.` : `Consider whether you can ${later}.`;
}

function round1(n: number): number {
  return Math.round(n * 10) / 10;
}
