// Shared types used across Edge Functions. The canonical workout schema is
// mirrored in /shared/types.ts for the web + Android clients.

export type LlmProvider =
  | "anthropic"
  | "deepseek"
  | "openai"
  | "gemini"
  | "groq"
  // OpenRouter — OpenAI-compatible aggregator (one key, hundreds of models via a
  // fixed endpoint). Behaves like a built-in provider; the user just picks a
  // model id (e.g. "anthropic/claude-3.5-sonnet"), default "openrouter/auto".
  | "openrouter"
  // User-supplied OpenAI-compatible endpoint (base URL + model + key). Its base
  // URL lives on the llm_api_keys row, not in the hardcoded provider registry.
  | "custom";

// Mirrors shared/types.ts's InjuryEntry. Legacy profiles only have the
// free-text injury_history string; injuriesOf() (profile.ts) is the single
// place that reconciles the two shapes for every server-side reader.
export interface InjuryEntry {
  area: string;
  severity: "mild" | "moderate" | "serious" | "";
  note?: string;
}

export interface WorkoutExercise {
  name: string;
  sets: number;
  reps: string;
  weight_kg: number | null;
  pace_zone: string | null; // "Z1".."Z5"
  hr_zone: string | null; // "Z1".."Z5"
  rest_seconds: number | null;
  notes: string;
  // Catalog metadata, present only on exercises the AI introduced that aren't
  // in the bundled library (used to auto-register them as custom exercises).
  muscle?: string | null;
  category?: string | null;
  compound?: boolean | null;
}

export interface WorkoutSection {
  name: string;
  duration_minutes: number;
  exercises: WorkoutExercise[];
}

export interface Workout {
  type: "run" | "ride" | "swim" | "strength" | "rest";
  title: string;
  duration_minutes: number;
  tss_estimate: number;
  rpe_target: number;
  sections: WorkoutSection[];
  coach_note: string;
}

export interface LlmResult {
  // Ready to show: llmGenerate has already enforced the no-dash house rule.
  text: string;
  // What the model actually wrote, before that scrub. Only the offline eval
  // wants this: scoring `text` would mark the dash checker green by
  // construction and hide a model that ignores the rule.
  raw?: string;
  promptTokens: number;
  completionTokens: number;
  provider: LlmProvider;
  model: string;
}
