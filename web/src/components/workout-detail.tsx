import type { Workout } from "@shared/types";
import { Badge } from "@/components/ui/badge";

export function WorkoutDetail({ workout }: { workout: Workout }) {
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant={workout.type === "run" ? "success" : workout.type === "strength" ? "warning" : "outline"}>
          {workout.type}
        </Badge>
        <span className="text-sm text-muted-foreground">{workout.duration_minutes} min</span>
        <span className="text-sm text-muted-foreground">RPE {workout.rpe_target}</span>
        <span className="text-sm text-muted-foreground">~{Math.round(workout.tss_estimate)} TSS</span>
      </div>

      {workout.coach_note && (
        <p className="rounded-md bg-secondary/50 p-3 text-sm italic text-muted-foreground">
          “{workout.coach_note}”
        </p>
      )}

      <div className="space-y-4">
        {workout.sections.map((section, i) => (
          <div key={i}>
            <div className="mb-1.5 flex items-baseline justify-between">
              <h4 className="text-sm font-semibold">{section.name}</h4>
              <span className="text-xs text-muted-foreground">{section.duration_minutes} min</span>
            </div>
            <ul className="space-y-1.5">
              {section.exercises.map((ex, j) => (
                <li key={j} className="rounded-md border border-border/60 px-3 py-2 text-sm">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-medium">{ex.name}</span>
                    <span className="text-xs text-muted-foreground">
                      {ex.sets > 0 && ex.reps ? `${ex.sets} × ${ex.reps}` : ex.reps}
                      {ex.weight_kg != null && ` · ${ex.weight_kg}kg`}
                      {ex.pace_zone && ` · pace ${ex.pace_zone}`}
                      {ex.hr_zone && ` · HR ${ex.hr_zone}`}
                      {ex.rest_seconds != null && ` · ${ex.rest_seconds}s rest`}
                    </span>
                  </div>
                  {ex.notes && <p className="mt-0.5 text-xs text-muted-foreground">{ex.notes}</p>}
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </div>
  );
}
