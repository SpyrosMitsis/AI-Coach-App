import { assert } from "jsr:@std/assert@1";

// ---------------------------------------------------------------------------
// Cross-surface contract guard. The workout `type` and LLM `provider` unions
// live in four hand-maintained places (functions types, web-facing types, the
// web API client, and the DB CHECK constraints). They have silently drifted
// before — a swim the generator could pick but the DB rejected. These tests
// fail loudly the next time one surface adds a value the others don't.
// ---------------------------------------------------------------------------

const root = new URL("../../../", import.meta.url); // repo root
const read = (p: string) => Deno.readTextFileSync(new URL(p, root));

Deno.test("contract: 'swim' is a valid workout type everywhere", () => {
  // functions schema (the source the generator validates against)
  assert(read("supabase/functions/_shared/workout_schema.ts").includes('"swim"'), "workout_schema.ts missing swim");
  // shared (web/Android-facing) types
  assert(read("shared/types.ts").includes('"swim"'), "shared/types.ts missing swim");
  // web API client union
  assert(read("web/src/lib/api.ts").includes('"swim"'), "web api.ts missing swim");

  // DB CHECK: the latest migration that (re)defines planned_workouts_type_check
  // must allow swim, or an auto-generated swim day 500s on insert.
  const migDir = new URL("supabase/migrations/", root);
  const checks = [...Deno.readDirSync(migDir)]
    .map((e) => e.name)
    .filter((n) => n.endsWith(".sql") && read(`supabase/migrations/${n}`).includes("planned_workouts_type_check"))
    .sort(); // filename date-prefix sort → last is newest
  assert(checks.length > 0, "no planned_workouts_type_check migration found");
  const latest = read(`supabase/migrations/${checks[checks.length - 1]}`);
  assert(latest.includes("'swim'"), `latest type-check migration (${checks.at(-1)}) missing 'swim'`);
});

Deno.test("contract: 'custom' + 'openrouter' providers agree across type unions", () => {
  for (const provider of ["custom", "openrouter"]) {
    assert(read("supabase/functions/_shared/types.ts").includes(`"${provider}"`), `_shared/types.ts missing ${provider}`);
    assert(read("shared/types.ts").includes(`"${provider}"`), `shared/types.ts missing ${provider}`);
  }
});
