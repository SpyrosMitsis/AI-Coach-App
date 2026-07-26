import { assert, assertEquals } from "jsr:@std/assert@1";
import { executeTool, nativeToolDefs, TOOL_CATALOG, toolCatalogPrompt } from "./coach_tools.ts";
// deno-lint-ignore no-explicit-any
type Any = any;

// Minimal PostgREST query-builder stub: every method chains, and the chain
// resolves (awaited or .single()) to the canned `data` for that table.
function adminStub(byTable: Record<string, unknown>): Any {
  const chainFor = (data: unknown): Any => {
    const c: Any = {};
    for (const m of ["select", "eq", "gte", "lte", "in", "not", "order", "limit"]) c[m] = () => c;
    c.single = () => Promise.resolve({ data: Array.isArray(data) ? data[0] : data });
    c.then = (res: Any, rej: Any) => Promise.resolve({ data }).then(res, rej);
    return c;
  };
  return { from: (t: string) => chainFor(byTable[t] ?? []) };
}

Deno.test("coach tools: set_rest_day + make_easier are registered as act tools", () => {
  const byName = (n: string) => TOOL_CATALOG.find((t) => t.name === n);
  const rest = byName("set_rest_day");
  const easier = byName("make_easier");
  assert(rest, "set_rest_day missing");
  assert(easier, "make_easier missing");
  assertEquals(rest!.kind, "act");
  assertEquals(easier!.kind, "act");
  // set_rest_day requires a date; make_easier's date is optional.
  assertEquals((rest!.schema as { required?: string[] }).required, ["date"]);
  assert(!(easier!.schema as { required?: string[] }).required);
});

Deno.test("coach tools: new tools are advertised to the model", () => {
  const native = nativeToolDefs().map((t) => t.name);
  assert(native.includes("set_rest_day"));
  assert(native.includes("make_easier"));
  const prompt = toolCatalogPrompt();
  assert(prompt.includes("set_rest_day"));
  assert(prompt.includes("make_easier"));
});

Deno.test("executeTool get_fitness: leads with an interpreted word, keeps the raw figures", async () => {
  const admin = adminStub({
    completed_activities: [
      { date: "2026-06-29", tss: 50, ctl: 42, atl: 50 },
      { date: "2026-06-22", tss: 60, ctl: 41, atl: 48 },
    ],
  });
  const obs = JSON.parse(await executeTool(admin, "u1", "auth", "get_fitness", {}));
  // Plain-language interpretation is present so the coach doesn't recite numbers.
  assert(typeof obs.freshness === "string" && obs.freshness.length > 0);
  assert(String(obs.note).toLowerCase().includes("interpret"));
  // Raw figures still come through for the model's own reasoning.
  assertEquals(obs.ctl, 42);
  assertEquals(obs.tsb, -8);
});

// ---------------------------------------------------------------------------
// update_app_settings: the safe-subset whitelist that lets chat change device
// settings. Everything not whitelisted must bounce, values must normalize to
// strings the app can parse, and update_profile's new body fields must bound.
// ---------------------------------------------------------------------------

import { validateAppSettings } from "./coach_tools.ts";

Deno.test("validateAppSettings: valid values normalize to string pairs", () => {
  const { changes, rejected } = validateAppSettings({
    theme: "Dark",
    units: "lb",
    rest_timer_seconds: 90.4,
    keep_screen_on: false,
  });
  assertEquals(rejected, []);
  assertEquals(changes, [
    { key: "theme", value: "dark" },
    { key: "units", value: "lb" },
    { key: "rest_timer_seconds", value: "90" },
    { key: "keep_screen_on", value: "false" },
  ]);
});

Deno.test("validateAppSettings: non-whitelisted keys are rejected, never passed through", () => {
  const { changes, rejected } = validateAppSettings({
    api_key: "sk-evil",
    delete_account: true,
    theme: "light",
  });
  assertEquals(changes, [{ key: "theme", value: "light" }]);
  assertEquals(rejected.length, 2);
  assert(rejected.some((r) => r.startsWith("api_key")));
});

Deno.test("validateAppSettings: out-of-range and wrong-typed values bounce", () => {
  const { changes, rejected } = validateAppSettings({
    theme: "neon",
    rest_timer_seconds: 5000,
    morning_notification: "yes",
  });
  assertEquals(changes, []);
  assertEquals(rejected.length, 3);
});

Deno.test("update_app_settings is advertised but not executable outside chat", async () => {
  assert(nativeToolDefs().some((t) => t.name === "update_app_settings"));
  assert(toolCatalogPrompt().includes("update_app_settings"));
  const obs = await executeTool(adminStub({}), "u1", "auth", "update_app_settings", { theme: "dark" });
  assert(obs.startsWith("error:"));
});

Deno.test("update_profile: body fields save in range, bounce out of range", async () => {
  const updates: Record<string, unknown>[] = [];
  const admin: Any = {
    from: () => ({
      select: () => ({ eq: () => ({ single: () => Promise.resolve({ data: { onboarding: {} } }) }) }),
      update: (u: Record<string, unknown>) => {
        updates.push(u);
        return { eq: () => Promise.resolve({}) };
      },
    }),
  };
  const ok = JSON.parse(await executeTool(admin, "u1", "auth", "update_profile", {
    weight_kg: 74.4, height_cm: 180, body_fat_pct: 15.25, session_duration: 75,
  }));
  assertEquals(ok.saved, { weight_kg: 74, height_cm: 180, body_fat_pct: 15.3, session_duration: 75 });
  const bad = await executeTool(admin, "u1", "auth", "update_profile", { weight_kg: 900 });
  assert(bad.startsWith("error:"));
});

Deno.test("update_profile: display_name saves into onboarding AND mirrors the column", async () => {
  const updates: Record<string, unknown>[] = [];
  const admin: Any = {
    from: () => ({
      select: () => ({ eq: () => ({ single: () => Promise.resolve({ data: { onboarding: { goal: "10K" } } }) }) }),
      update: (u: Record<string, unknown>) => {
        updates.push(u);
        return { eq: () => Promise.resolve({}) };
      },
    }),
  };
  const ok = JSON.parse(await executeTool(admin, "u1", "auth", "update_profile", {
    display_name: "  Gianluca  ", sex: "male", birth_year: 1992,
  }));
  assertEquals(ok.saved.display_name, "Gianluca");
  assertEquals(updates[0].display_name, "Gianluca"); // top-level column mirror
  const onboarding = updates[0].onboarding as Record<string, unknown>;
  assertEquals(onboarding.display_name, "Gianluca");
  assertEquals(onboarding.goal, "10K"); // existing fields preserved
  assertEquals(onboarding.sex, "male");
  assertEquals(onboarding.birth_year, 1992);

  const bad = await executeTool(admin, "u1", "auth", "update_profile", { display_name: "   ", sex: "yes" });
  assert(bad.startsWith("error:"));
});

Deno.test("remember: appends a new fact, skips an exact duplicate", async () => {
  const updates: Record<string, unknown>[] = [];
  let knowledge = "- dislikes burpees";
  const admin: Any = {
    from: () => ({
      select: () => ({ eq: () => ({ single: () => Promise.resolve({ data: { coach_knowledge: knowledge } }) }) }),
      update: (u: Record<string, unknown>) => {
        updates.push(u);
        knowledge = u.coach_knowledge as string;
        return { eq: () => Promise.resolve({}) };
      },
    }),
  };
  const first = JSON.parse(await executeTool(admin, "u1", "auth", "remember", { fact: "left knee niggle" }));
  assertEquals(first.ok, true);
  assertEquals(knowledge, "- dislikes burpees\n- left knee niggle");

  // Same fact again (even different case/whitespace) must not duplicate the line.
  const dupe = JSON.parse(await executeTool(admin, "u1", "auth", "remember", { fact: "  Left Knee Niggle  " }));
  assertEquals(dupe.duplicate, true);
  assertEquals(updates.length, 1); // no second write
  assertEquals(knowledge, "- dislikes burpees\n- left knee niggle");
});

// ---------------------------------------------------------------------------
// set_training_pause / resume_training: a stated "stop training until X" must
// get real structural effect (see plan-week's dayList.available), not just a
// remember() line the planner has no obligation to notice.
// ---------------------------------------------------------------------------

function withFetchStub<T>(stub: (url: string) => Response, fn: () => Promise<T>): Promise<T> {
  const orig = globalThis.fetch;
  globalThis.fetch = ((input: URL | Request | string) =>
    Promise.resolve(stub(String(input instanceof Request ? input.url : input)))) as typeof fetch;
  return fn().finally(() => { globalThis.fetch = orig; });
}

Deno.test("coach tools: set_training_pause + resume_training are registered as act tools", () => {
  const byName = (n: string) => TOOL_CATALOG.find((t) => t.name === n);
  const pause = byName("set_training_pause");
  const resume = byName("resume_training");
  assert(pause, "set_training_pause missing");
  assert(resume, "resume_training missing");
  assertEquals(pause!.kind, "act");
  assertEquals((pause!.schema as { required?: string[] }).required, ["until_date"]);
  const native = nativeToolDefs().map((t) => t.name);
  assert(native.includes("set_training_pause"));
  assert(native.includes("resume_training"));
  assert(toolCatalogPrompt().includes("set_training_pause"));
});

Deno.test("set_training_pause: writes the pause window and clears upcoming unlocked sessions", async () => {
  const profileUpdates: Record<string, unknown>[] = [];
  const deletedIds: string[] = [];
  const admin: Any = {
    from: (table: string) => {
      if (table === "user_profiles") {
        return {
          update: (u: Record<string, unknown>) => {
            profileUpdates.push(u);
            return { eq: () => Promise.resolve({}) };
          },
        };
      }
      // planned_workouts: chain of eq/gte/lte/neq, resolves to two clearable rows.
      const rows = [{ id: "w1", type: "run" }, { id: "w2", type: "strength" }];
      const chain: Any = {};
      for (const m of ["select", "eq", "gte", "lte", "neq"]) chain[m] = () => chain;
      chain.then = (res: Any) => Promise.resolve({ data: rows }).then(res);
      return chain;
    },
  };
  const farFuture = new Date(Date.now() + 20 * 86400000).toISOString().slice(0, 10);
  const out = await withFetchStub(
    (url) => {
      assert(url.includes("delete-workout"));
      return new Response(JSON.stringify({ ok: true }), { status: 200 });
    },
    () =>
      executeTool(admin, "u1", "auth", "set_training_pause", {
        until_date: farFuture,
        reason: "travel to Italy",
      }),
  );
  const obs = JSON.parse(out);
  assertEquals(obs.ok, true);
  assertEquals(obs.until_date, farFuture);
  assertEquals(obs.cleared, 2);
  assertEquals(profileUpdates[0].training_paused_until, farFuture);
  assertEquals(profileUpdates[0].training_pause_reason, "travel to Italy");
});

Deno.test("set_training_pause: rejects a past until_date without writing anything", async () => {
  const admin: Any = { from: () => ({ update: () => ({ eq: () => Promise.resolve({}) }) }) };
  const out = await executeTool(admin, "u1", "auth", "set_training_pause", { until_date: "2020-01-01" });
  assert(out.startsWith("error:"));
});

Deno.test("resume_training: nulls both pause columns", async () => {
  const updates: Record<string, unknown>[] = [];
  const admin: Any = {
    from: () => ({
      update: (u: Record<string, unknown>) => { updates.push(u); return { eq: () => Promise.resolve({}) }; },
    }),
  };
  const obs = JSON.parse(await executeTool(admin, "u1", "auth", "resume_training", {}));
  assertEquals(obs.ok, true);
  assertEquals(updates[0], { training_paused_until: null, training_pause_reason: null });
});
