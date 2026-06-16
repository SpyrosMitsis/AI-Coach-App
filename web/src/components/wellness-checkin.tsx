"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { createClient } from "@/lib/supabase-browser";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";

// Sleep is sourced objectively from Intervals.icu (sleep_score), so the manual
// check-in only asks the two subjective signals that can't be measured.
const QUESTIONS = [
  { key: "energy", label: "Energy" },
  { key: "soreness", label: "Soreness" },
] as const;

type Key = (typeof QUESTIONS)[number]["key"];

// Morning 1-5 check-in. Hides itself once today's row exists.
export function WellnessCheckin() {
  const qc = useQueryClient();
  const supabase = createClient();
  const today = new Date().toISOString().slice(0, 10);

  const existing = useQuery({
    queryKey: ["wellness-today", today],
    queryFn: async () => {
      const { data } = await supabase.from("wellness_checkins").select("*").eq("date", today).maybeSingle();
      return data as Record<string, number> | null;
    },
  });

  const save = useMutation({
    mutationFn: async (vals: Record<Key, number>) => {
      const { error } = await supabase.from("wellness_checkins").upsert(
        { date: today, ...vals },
        { onConflict: "user_id,date" },
      );
      if (error) throw error;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["wellness-today", today] });
      qc.invalidateQueries({ queryKey: ["daily-summary"] });
    },
  });

  if (existing.isLoading || existing.data) return null;

  function submit(form: HTMLFormElement) {
    const fd = new FormData(form);
    const vals = Object.fromEntries(
      QUESTIONS.map((q) => [q.key, Number(fd.get(q.key) ?? 3)]),
    ) as Record<Key, number>;
    save.mutate(vals);
  }

  return (
    <Card className="border-primary/40">
      <CardHeader className="pb-2"><CardTitle className="text-base">Morning check-in</CardTitle></CardHeader>
      <CardContent>
        <form
          onSubmit={(e) => { e.preventDefault(); submit(e.currentTarget); }}
          className="space-y-3"
        >
          {QUESTIONS.map((q) => (
            <div key={q.key} className="space-y-1">
              <span className="text-sm">{q.label}</span>
              <div className="flex gap-1.5">
                {[1, 2, 3, 4, 5].map((n) => (
                  <label key={n} className="flex-1">
                    <input type="radio" name={q.key} value={n} defaultChecked={n === 3} className="peer sr-only" />
                    <div className={cn(
                      "cursor-pointer rounded-md border border-border py-1.5 text-center text-sm",
                      "peer-checked:border-primary peer-checked:bg-primary/15 peer-checked:text-primary",
                    )}>{n}</div>
                  </label>
                ))}
              </div>
            </div>
          ))}
          <button type="submit" disabled={save.isPending}
            className="w-full rounded-md bg-primary py-2 text-sm font-medium text-primary-foreground disabled:opacity-50">
            {save.isPending ? "Saving…" : "Save check-in"}
          </button>
        </form>
      </CardContent>
    </Card>
  );
}
