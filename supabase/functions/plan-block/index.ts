// plan-block — P2: generate a multi-week PERIODIZED block toward the goal race.
//
// This is a thin orchestrator over the existing, battle-tested `plan-week`
// planner: it figures out how many weeks to plan (to the race date, capped) and
// invokes plan-week once per week. Because plan-week derives the training phase
// (Base→Build→Peak→Taper) from weeks-to-goal for whatever Monday it's given,
// looping it forward produces a correctly periodized block — including an
// automatic taper in the final weeks (P3) and recovery spacing within each week.
//
// Near-term weeks are pushed to Intervals.icu; far weeks are planned but not
// pushed (they'll be re-planned adaptively as they approach — P5).

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, getUserId } from "../_shared/supabase.ts";

const DAY = 86_400_000;
const addDays = (iso: string, n: number) => {
  const d = new Date(iso + "T12:00:00");
  d.setDate(d.getDate() + n);
  return d.toISOString().slice(0, 10);
};

function nextMonday(): string {
  const today = new Date().toISOString().slice(0, 10);
  let d = (8 - new Date(today + "T12:00:00").getDay()) % 7;
  if (d === 0) d = 7; // always the upcoming Monday, never today
  return addDays(today, d);
}

const MAX_WEEKS = 16;

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const body = await req.json().catch(() => ({}));
    const admin = adminClient();

    const start: string = body.start_date ?? nextMonday();

    // Decide how many weeks: explicit override, else weeks-to-race, else 8.
    let weeks: number;
    if (Number.isFinite(body.weeks)) {
      weeks = Math.round(body.weeks);
    } else {
      const { data: profile } = await admin.from("user_profiles")
        .select("onboarding").eq("id", userId).single();
      const goalDate = profile?.onboarding?.goal_date as string | undefined;
      if (goalDate) {
        const w = Math.ceil((new Date(goalDate).getTime() - new Date(start + "T12:00:00").getTime()) / (7 * DAY));
        weeks = w;
      } else {
        weeks = 8;
      }
    }
    weeks = Math.max(1, Math.min(MAX_WEEKS, weeks));

    // How many near-term weeks to actually push to the watch.
    const pushWeeks = Math.max(1, Math.min(weeks, body.push_weeks ?? 2));

    // Forward the caller's JWT so plan-week runs as this user (RLS + verify_jwt).
    const auth = req.headers.get("Authorization") ?? "";
    const base = `${Deno.env.get("SUPABASE_URL")}/functions/v1/plan-week`;

    // Plan all weeks concurrently — each plan-week call is an independent LLM
    // invocation on a distinct date range, so running them in parallel turns the
    // wall time from sum(weeks) into ~one week and avoids the client timeout.
    const planOne = async (i: number) => {
      const weekStart = addDays(start, i * 7);
      try {
        const res = await fetch(base, {
          method: "POST",
          headers: { "Authorization": auth, "Content-Type": "application/json" },
          body: JSON.stringify({ start_date: weekStart, push: i < pushWeeks }),
        });
        const r = await res.json();
        return {
          week: i + 1, start_date: weekStart,
          focus: r.week_focus ?? null, scheduled: r.scheduled ?? 0, pushed: r.pushed ?? 0,
          error: r.error ?? null,
        } as Record<string, unknown>;
      } catch (e) {
        return { week: i + 1, start_date: weekStart, error: e instanceof Error ? e.message : String(e) } as Record<string, unknown>;
      }
    };
    const settled = await Promise.all(Array.from({ length: weeks }, (_, i) => planOne(i)));
    const results = settled.sort((a, b) => (a.week as number) - (b.week as number));

    const planned = results.filter((r) => !r.error).length;
    return json({
      start_date: start,
      end_date: addDays(start, weeks * 7 - 1),
      weeks,
      weeks_planned: planned,
      pushed_weeks: pushWeeks,
      weeks_detail: results,
    });
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
