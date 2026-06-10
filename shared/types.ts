// ============================================================================
// Shared TypeScript types — the canonical workout schema and DB row shapes,
// consumed by the web app. (Android mirrors these as Kotlin data classes.)
// Keep in sync with supabase/functions/_shared/types.ts.
// ============================================================================

export type LlmProvider = "anthropic" | "deepseek" | "openai" | "gemini" | "groq";

export const PROVIDER_LABELS: Record<LlmProvider, string> = {
  anthropic: "Anthropic",
  deepseek: "DeepSeek",
  openai: "OpenAI",
  gemini: "Google Gemini",
  groq: "Groq",
};

export const PROVIDER_MODELS: Record<LlmProvider, string> = {
  anthropic: "claude-sonnet-4-20250514",
  deepseek: "deepseek-chat",
  openai: "gpt-4o-mini",
  gemini: "gemini-2.0-flash",
  groq: "llama-3.3-70b-versatile",
};

export const PROVIDER_FREE_KEY_URL: Record<LlmProvider, string> = {
  anthropic: "https://console.anthropic.com/settings/keys",
  deepseek: "https://platform.deepseek.com/api_keys",
  openai: "https://platform.openai.com/api-keys",
  gemini: "https://aistudio.google.com/app/apikey",
  groq: "https://console.groq.com/keys",
};

// ~USD per 1M tokens (input/output) for the per-generation cost estimate.
export const PROVIDER_PRICING: Record<LlmProvider, { inputPer1M: number; outputPer1M: number }> = {
  anthropic: { inputPer1M: 3.0, outputPer1M: 15.0 },
  deepseek: { inputPer1M: 0.27, outputPer1M: 1.1 },
  openai: { inputPer1M: 0.15, outputPer1M: 0.6 },
  gemini: { inputPer1M: 0.1, outputPer1M: 0.4 },
  groq: { inputPer1M: 0.59, outputPer1M: 0.79 },
};

export interface WorkoutExercise {
  name: string;
  sets: number;
  reps: string;
  weight_kg: number | null;
  pace_zone: string | null;
  hr_zone: string | null;
  rest_seconds: number | null;
  notes: string;
}

export interface WorkoutSection {
  name: string;
  duration_minutes: number;
  exercises: WorkoutExercise[];
}

export interface Workout {
  type: "run" | "strength" | "rest";
  title: string;
  duration_minutes: number;
  tss_estimate: number;
  rpe_target: number;
  sections: WorkoutSection[];
  coach_note: string;
}

export interface UserProfile {
  id: string;
  display_name: string | null;
  intervals_athlete_id: string | null;
  onboarding: OnboardingData;
  onboarding_complete: boolean;
  active_llm_provider: LlmProvider;
  llm_fallback_chain: LlmProvider[];
}

export interface OnboardingData {
  goal?: string;
  experience?: "Beginner" | "Intermediate" | "Advanced";
  days?: string[];
  session_duration?: number;
  equipment?: string;
  target_pace?: string;
  weekly_tss_target?: number;
  injury_history?: string;
  hr_zones?: { zone: string; min: number; max: number }[];
  // E1/E4 thresholds — feed zone derivation; shared with Android (Zones.kt).
  lthr?: number;                    // bpm
  ftp?: number;                     // watts
  threshold_pace_per_km?: string;   // "m:ss"
  // P1 periodization anchor (the A-race driving phase/taper).
  goal_date?: string;               // YYYY-MM-DD
}

export interface Race {
  id: string;
  name: string;
  date: string;
  priority: "A" | "B" | "C";
  distance: string | null;
  notes: string | null;
}

export interface ThresholdTest {
  id: string;
  date: string;
  kind: "lthr" | "ftp" | "threshold_pace";
  value: number;
  notes: string | null;
}

// A past activity pulled from Intervals.icu (or logged manually) into
// completed_activities. data_json holds the full Intervals object.
export interface CompletedActivity {
  id: string;
  intervals_id: string;
  type: string | null;
  date: string | null;
  duration_seconds: number | null;
  distance_m: number | null;
  avg_hr: number | null;
  tss: number | null;
  ctl: number | null;
  atl: number | null;
  data_json: Record<string, unknown> | null;
}

export interface WellnessCheckin {
  id: string;
  date: string;
  energy: number;
  soreness: number;
  sleep_quality: number;
  zepp_sleep_minutes: number | null;
}

export interface PlannedWorkout {
  id: string;
  date: string;
  type: "run" | "strength" | "rest";
  workout_json: Workout;
  llm_provider: string | null;
  llm_model: string | null;
  intervals_event_id: string | null;
  pushed_at: string | null;
  completed: boolean;
  // Added by migration 17 — a locked session is honored verbatim and excluded
  // from weekly/block re-planning. May be undefined until that migration runs.
  locked?: boolean;
}

export interface StrengthLog {
  id: string;
  date: string;
  exercise_name: string;
  muscle_groups: string[];
  sets: { reps: number; weight_kg: number; rpe?: number }[];
  estimated_1rm: number | null;
  notes: string | null;
}

export interface DailySummary {
  date: string;
  readiness: {
    score: number;
    band: "green" | "amber" | "red";
    components: { wellness: number; hrvDelta: number; rhrDelta: number };
  };
  today_workout: PlannedWorkout | null;
  tsb_sparkline: { date: string; tsb: number; ctl: number; atl: number }[];
  weekly_load: { tss: number; target: number };
  active_llm_provider: LlmProvider;
}

export interface LlmKeyStatus {
  provider: LlmProvider;
  is_valid: boolean | null;
  last_tested_at: string | null;
}

export interface GenerationLog {
  id: string;
  created_at: string;
  provider: string;
  model: string;
  prompt_tokens: number | null;
  completion_tokens: number | null;
  estimated_cost_usd: number | null;
  system_prompt: string;
  user_prompt: string;
  raw_response: string;
  parsed_ok: boolean;
  workout_id: string | null;
}

// Epley 1RM estimate, shared by web + Android strength trackers.
export function epley1rm(weightKg: number, reps: number): number {
  return weightKg * (1 + reps / 30);
}
