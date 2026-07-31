import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  defaultHrZones,
  LEGACY_HR_ZONES,
  paceToSec,
  paceZonesFromThreshold,
  zonesFromAge,
  zonesFromLthr,
} from "./zones.ts";

Deno.test("lthr beats age beats the legacy table", () => {
  const both = defaultHrZones({ lthr: 165, birth_year: 1990 });
  assertEquals(both, zonesFromLthr(165));
  const ageOnly = defaultHrZones({ birth_year: 1990 });
  assertEquals(ageOnly, zonesFromAge(new Date().getFullYear() - 1990));
  assertEquals(defaultHrZones({}), LEGACY_HR_ZONES);
});

Deno.test("implausible anchors fall through instead of producing garbage zones", () => {
  // A typo'd LTHR of 20 or 500 must not become someone's training zones.
  assertEquals(defaultHrZones({ lthr: 20, birth_year: 1990 }), zonesFromAge(new Date().getFullYear() - 1990));
  assertEquals(defaultHrZones({ lthr: 500 }), LEGACY_HR_ZONES);
  assertEquals(defaultHrZones({ birth_year: 3000 }), LEGACY_HR_ZONES);
});

Deno.test("age scaling actually separates the athletes the flat table conflated", () => {
  const young = zonesFromAge(22); // HRmax ~193
  const older = zonesFromAge(58); // HRmax ~167
  assert(young[4].max > older[4].max + 15, "a 22yo and a 58yo must not share a Z5 ceiling");
  assertEquals(young[4].max, Math.round(208 - 0.7 * 22));
});

Deno.test("zones are contiguous-ish and monotonic for both derivations", () => {
  for (const zones of [zonesFromLthr(165), zonesFromAge(35)]) {
    for (let i = 0; i < zones.length; i++) {
      assert(zones[i].min < zones[i].max, `${zones[i].zone} inverted`);
      if (i > 0) assert(zones[i].min >= zones[i - 1].max - 1, `${zones[i].zone} overlaps ${zones[i - 1].zone}`);
    }
  }
});

Deno.test("lthr bands put the threshold at the Z4/Z5 boundary", () => {
  // The definition of LTHR: what you can hold ~1h = the top of Z4.
  const z = zonesFromLthr(170);
  assertEquals(z[4].min, 170);
  assertEquals(z[3].max, Math.round(170 * 0.99));
});

// Pace zones from a typed threshold: the number an athlete logs in "Test me"
// used to reach their own screen and stop there.
Deno.test("paceZonesFromThreshold: mirrors the client's bands", () => {
  const z = paceZonesFromThreshold(300); // 5:00 /km
  assertEquals(z.length, 5);
  assertEquals(z[0].zone, "Z1 Easy");
  assertEquals(z[0].range, "5:45-6:30/km");
  assertEquals(z[3].zone, "Z4 Threshold");
  assertEquals(z[3].range, "4:51-5:03/km");
  assertEquals(paceZonesFromThreshold(null), []);
  assertEquals(paceZonesFromThreshold(0), []);
});

Deno.test("paceToSec: accepts m:ss in a runnable range, rejects the rest", () => {
  assertEquals(paceToSec("4:45"), 285);
  assertEquals(paceToSec("12:00"), 720);
  assertEquals(paceToSec("1:00"), null);   // faster than any human threshold
  assertEquals(paceToSec("20:00"), null);  // slower than the picker allows
  assertEquals(paceToSec("445"), null);
  assertEquals(paceToSec(285), null);
  assertEquals(paceToSec(undefined), null);
});
