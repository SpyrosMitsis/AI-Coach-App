// Shared types used across Edge Functions. The canonical workout schema is
// mirrored in /shared/types.ts for the web + Android clients.

export type LlmProvider =
  | "anthropic"
  | "deepseek"
  | "openai"
  | "gemini"
  | "groq";

export interface WorkoutExercise {
  name: string;
  sets: number;
  reps: string;
  weight_kg: number | null;
  pace_zone: string | null; // "Z1".."Z5"
  hr_zone: string | null; // "Z1".."Z5"
  rest_seconds: number | null;
  notes: string;
}

export interface WorkoutSection {
  name: string;
  duration_minutes: number;
  exercises: WorkoutExercise[];
}

export interface Workout {
  type: "run" | "ride" | "strength" | "rest";
  title: string;
  duration_minutes: number;
  tss_estimate: number;
  rpe_target: number;
  sections: WorkoutSection[];
  coach_note: string;
}

export interface LlmResult {
  text: string;
  promptTokens: number;
  completionTokens: number;
  provider: LlmProvider;
  model: string;
}
