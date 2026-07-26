import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  availabilityBlock,
  challengeBlock,
  experienceBlock,
  experienceForSport,
  goalsText,
  injuriesOf,
  injuriesText,
  minutesForDate,
  profileFactsBlock,
} from "./profile.ts";

Deno.test("goalsText: combines several goals, falls back to legacy then default", () => {
  assertEquals(goalsText({ goals: ["Marathon", "Build Muscle"] }), "Marathon + Build Muscle");
  assertEquals(goalsText({ goal: "10K pace" }), "10K pace");
  assertEquals(goalsText({}), "General fitness");
  // Blank entries are ignored.
  assertEquals(goalsText({ goals: ["", "Strength"] }), "Strength");
});

Deno.test("experienceForSport: per-sport wins, then legacy global, then any, then default", () => {
  const o = { experience_by_sport: { strength: "Advanced", run: "Beginner" }, experience: "Intermediate" };
  assertEquals(experienceForSport(o, "strength"), "Advanced");
  assertEquals(experienceForSport(o, "run"), "Beginner");
  // Sport with no entry falls back to the legacy global.
  assertEquals(experienceForSport(o, "swim"), "Intermediate");
  // No map, no global.
  assertEquals(experienceForSport({}, "run"), "Intermediate");
  // No global but another sport is set → use any set value.
  assertEquals(experienceForSport({ experience_by_sport: { run: "Experienced" } }, "strength"), "Experienced");
});

Deno.test("experienceBlock: lists per-activity experience, empty when unset", () => {
  const b = experienceBlock({ experience_by_sport: { strength: "Advanced", run: "Beginner" } });
  assert(/Gym: Advanced/.test(b));
  assert(/Running: Beginner/.test(b));
  assertEquals(experienceBlock({}), "");
});

Deno.test("minutesForDate: maps a date to its weekday budget, null when unset", () => {
  // 2026-07-18 is a Saturday.
  const o = {
    day_availability: [
      { day: "Sat", max_minutes: 180 },
      { day: "Mon", max_minutes: 45 },
    ],
  };
  assertEquals(minutesForDate(o, "2026-07-18"), 180); // Saturday long run
  assertEquals(minutesForDate(o, "2026-07-20"), 45); // Monday
  assertEquals(minutesForDate(o, "2026-07-19"), null); // Sunday: rest, no entry
  assertEquals(minutesForDate({}, "2026-07-18"), null);
});

Deno.test("availabilityBlock: orders days and lists rest days", () => {
  const b = availabilityBlock({
    day_availability: [
      { day: "Sat", max_minutes: 180 },
      { day: "Mon", max_minutes: 45 },
    ],
  });
  // Mon comes before Sat regardless of input order.
  assert(b.indexOf("Mon") < b.indexOf("Sat"));
  assert(/Mon up to 45 min/.test(b));
  assert(/Sat up to 180 min/.test(b));
  assert(/Rest days:/.test(b));
  assertEquals(availabilityBlock({}), "");
});

Deno.test("profileFactsBlock: renders the static facts, empty when unset", () => {
  const thisYear = new Date().getFullYear();
  const b = profileFactsBlock(
    { birth_year: thisYear - 30, sex: "male", height_cm: 178, weight_kg: 74, goals: ["Marathon"], injury_history: "left knee" },
    "Spyros",
  );
  assert(/Name: Spyros/.test(b));
  assert(/Age: 30/.test(b));
  assert(/Sex: male/.test(b));
  assert(/Height: 178 cm/.test(b));
  assert(/Weight: 74 kg/.test(b));
  assert(/Goals: Marathon/.test(b));
  assert(/Injuries to train around: left knee/.test(b));
  // Nothing set (and the default "General fitness" goal) => empty.
  assertEquals(profileFactsBlock({}), "");
  assertEquals(profileFactsBlock({}, "  "), "");
});

Deno.test("injuriesOf: structured `injuries` array wins over legacy injury_history", () => {
  const o = {
    injuries: [{ area: "Knee", severity: "moderate" }],
    injury_history: "Shoulder (serious)", // stale legacy value, should be ignored
  };
  assertEquals(injuriesOf(o), [{ area: "Knee", severity: "moderate" }]);
});

Deno.test("injuriesOf: falls back to parsing legacy injury_history when injuries is unset", () => {
  const o = { injury_history: "Knee (moderate), lower back" };
  assertEquals(injuriesOf(o), [
    { area: "Knee", severity: "moderate" },
    { area: "lower back", severity: "" },
  ]);
});

Deno.test("injuriesOf: empty/unset input yields no entries", () => {
  assertEquals(injuriesOf({}), []);
  assertEquals(injuriesOf({ injury_history: "" }), []);
  assertEquals(injuriesOf({ injury_history: "   " }), []);
});

Deno.test("injuriesText: renders severity only when set", () => {
  assertEquals(
    injuriesText({ injuries: [{ area: "Knee", severity: "moderate" }, { area: "Wrist", severity: "" }] }),
    "Knee (moderate); Wrist",
  );
  assertEquals(injuriesText({}), "");
});

// The "no em/en dashes" house rule applies to prompt-facing strings too.
Deno.test("profile blocks carry no em or en dashes", () => {
  const o = {
    experience_by_sport: { strength: "Advanced" },
    day_availability: [{ day: "Sat", max_minutes: 180 }],
    birth_year: 1994,
    sex: "female",
    height_cm: 165,
    weight_kg: 60,
    injury_history: "shoulder",
  };
  for (const s of [experienceBlock(o), availabilityBlock(o), profileFactsBlock(o, "Alex")]) {
    assert(!s.includes("—") && !s.includes("–"), `dash found in: ${s}`);
  }
});

Deno.test("challengeBlock: easier/harder render, standard and junk stay silent", () => {
  assert(challengeBlock({ challenge: "easier" }).includes("EASIER"));
  assert(challengeBlock({ challenge: "harder" }).includes("HARDER"));
  assertEquals(challengeBlock({}), "");
  assertEquals(challengeBlock({ challenge: "standard" }), "");
  assertEquals(challengeBlock({ challenge: 42 }), "");
  // Both variants keep recovery rules sovereign, by contract.
  assert(challengeBlock({ challenge: "harder" }).toLowerCase().includes("recovery"));
  assert(challengeBlock({ challenge: "easier" }).toLowerCase().includes("recovery"));
});
