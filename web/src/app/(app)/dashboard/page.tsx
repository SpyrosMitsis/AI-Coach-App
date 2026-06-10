"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { ReadinessRing } from "@/components/charts/readiness-ring";
import { TsbSparkline } from "@/components/charts/tsb-sparkline";
import { WorkoutDetail } from "@/components/workout-detail";
import { WellnessCheckin } from "@/components/wellness-checkin";
import { PROVIDER_LABELS } from "@shared/types";
import { fmtCost } from "@/lib/utils";
import { RefreshCw, Sparkles } from "lucide-react";

export default function DashboardPage() {
  const qc = useQueryClient();
  const summary = useQuery({ queryKey: ["daily-summary"], queryFn: api.dailySummary });

  const sync = useMutation({
    mutationFn: api.syncIntervals,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["daily-summary"] }),
  });

  const generate = useMutation({
    mutationFn: () => api.generateWorkout({ type: "auto", duration: 60 }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["daily-summary"] }),
  });

  if (summary.isLoading) return <Skeleton />;
  if (summary.isError) {
    return <p className="text-sm text-red-400">Failed to load: {(summary.error as Error).message}</p>;
  }
  const d = summary.data!;

  return (
    <div className="space-y-5">
      <header className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Today</h1>
          <p className="text-sm text-muted-foreground">{d.date}</p>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant="outline">{PROVIDER_LABELS[d.active_llm_provider]}</Badge>
          <Button size="icon" variant="ghost" onClick={() => sync.mutate()} disabled={sync.isPending}>
            <RefreshCw className={sync.isPending ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
          </Button>
        </div>
      </header>

      <WellnessCheckin />

      <div className="grid gap-4 sm:grid-cols-2">
        <Card>
          <CardContent className="flex items-center gap-5 pt-5">
            <ReadinessRing score={d.readiness.score} band={d.readiness.band} />
            <div className="space-y-1 text-sm">
              <p className="font-medium capitalize text-foreground">{d.readiness.band} — ready to train</p>
              <p className="text-muted-foreground">Wellness {d.readiness.components.wellness.toFixed(1)}/5</p>
              <p className="text-muted-foreground">HRV Δ {(d.readiness.components.hrvDelta * 100).toFixed(0)}%</p>
              <p className="text-muted-foreground">RHR Δ {(d.readiness.components.rhrDelta * 100).toFixed(0)}%</p>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="flex items-center justify-between text-base">
              Weekly Load
              <span className="text-sm font-normal text-muted-foreground">
                {d.weekly_load.tss} / {d.weekly_load.target} TSS
              </span>
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="h-3 w-full overflow-hidden rounded-full bg-secondary">
              <div
                className="h-full rounded-full bg-primary transition-all"
                style={{ width: `${Math.min(100, (d.weekly_load.tss / d.weekly_load.target) * 100)}%` }}
              />
            </div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader className="flex-row items-center justify-between pb-2">
          <CardTitle className="text-base">Today&apos;s Workout</CardTitle>
          <Button size="sm" onClick={() => generate.mutate()} disabled={generate.isPending}>
            <Sparkles className="h-4 w-4" />
            {generate.isPending ? "Generating…" : d.today_workout ? "Regenerate" : "Generate"}
          </Button>
        </CardHeader>
        <CardContent>
          {generate.isError && (
            <p className="mb-2 text-sm text-red-400">{(generate.error as Error).message}</p>
          )}
          {d.today_workout ? (
            <>
              <h3 className="mb-2 text-lg font-semibold">{d.today_workout.workout_json.title}</h3>
              <WorkoutDetail workout={d.today_workout.workout_json} />
            </>
          ) : (
            <p className="text-sm text-muted-foreground">No workout planned. Generate one above.</p>
          )}
          {generate.data && (
            <p className="mt-3 text-xs text-muted-foreground">
              Generated via {PROVIDER_LABELS[generate.data.provider]} · {fmtCost(generate.data.estimated_cost_usd)}
              {generate.data.intervals_event_id && " · pushed to Intervals.icu"}
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="pb-2">
          <CardTitle className="text-base">Fitness · Fatigue · Form (14d)</CardTitle>
        </CardHeader>
        <CardContent>
          <TsbSparkline data={d.tsb_sparkline} />
        </CardContent>
      </Card>
    </div>
  );
}

function Skeleton() {
  return (
    <div className="space-y-4">
      <div className="h-8 w-32 animate-pulse rounded bg-secondary" />
      <div className="h-40 animate-pulse rounded-lg bg-secondary" />
      <div className="h-64 animate-pulse rounded-lg bg-secondary" />
    </div>
  );
}
