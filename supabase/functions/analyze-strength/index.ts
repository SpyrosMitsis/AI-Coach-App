// analyze-strength — execution analysis + AI feedback for a logged strength
// session: planned_workouts (prescription) vs strength_logs (what was lifted)
// plus the optional watch recording.
//
// POST { date: "YYYY-MM-DD", force?: boolean, peek?: boolean }
//
// Cached in strength_analyses (user_id, date); force = true recomputes.
// peek = true returns only a cached result (never runs the LLM) so the app
// can auto-display background-computed analyses without triggering spend.

import { handleOptions, json } from "../_shared/cors.ts";
import { adminClient, getUserId } from "../_shared/supabase.ts";
import { runStrengthAnalysis } from "../_shared/analyze_core.ts";

Deno.serve(async (req) => {
  const pre = handleOptions(req);
  if (pre) return pre;

  try {
    const userId = await getUserId(req);
    if (!userId) return json({ error: "unauthorized" }, 401);

    const body = await req.json().catch(() => ({}));
    const date: string | undefined = body.date;
    if (!date || !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      return json({ error: "date (YYYY-MM-DD) required" }, 400);
    }

    const admin = adminClient();
    if (body.force !== true) {
      const { data: cached } = await admin
        .from("strength_analyses")
        .select("analysis_json")
        .eq("user_id", userId)
        .eq("date", date)
        .maybeSingle();
      if (cached?.analysis_json) return json(cached.analysis_json);
      if (body.peek === true) return json({ ok: false, not_analyzed: true });
    }

    const analysis = await runStrengthAnalysis(admin, userId, date);
    return json(analysis);
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500);
  }
});
