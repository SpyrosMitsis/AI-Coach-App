package com.workoutmaker.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Kotlin mirror of /shared/types.ts. Keep field names in sync with the schema.

enum class LlmProvider(val label: String, val model: String, val freeKeyUrl: String, val freeTier: Boolean) {
    @SerialName("anthropic") ANTHROPIC("Anthropic", "claude-opus-4-8", "https://console.anthropic.com/settings/keys", false),
    @SerialName("deepseek")  DEEPSEEK("DeepSeek", "deepseek-chat", "https://platform.deepseek.com/api_keys", false),
    @SerialName("openai")    OPENAI("OpenAI", "gpt-5-mini", "https://platform.openai.com/api-keys", false),
    @SerialName("gemini")    GEMINI("Google Gemini", "gemini-2.5-flash", "https://aistudio.google.com/app/apikey", true),
    @SerialName("groq")      GROQ("Groq", "llama-3.3-70b-versatile", "https://console.groq.com/keys", true);

    val key: String get() = name.lowercase()
}

@Serializable
data class WorkoutExercise(
    val name: String,
    val sets: Int = 0,
    val reps: String = "",
    val weight_kg: Double? = null,
    val pace_zone: String? = null,
    val hr_zone: String? = null,
    val rest_seconds: Int? = null,
    val notes: String = "",
    // Catalog metadata, present only on AI-introduced exercises that aren't in
    // the bundled library (used to auto-register them as custom exercises).
    val muscle: String? = null,
    val category: String? = null,
    val compound: Boolean? = null,
)

@Serializable
data class WorkoutSection(
    val name: String,
    val duration_minutes: Double = 0.0,
    val exercises: List<WorkoutExercise> = emptyList(),
)

@Serializable
data class Workout(
    val type: String,
    val title: String,
    val duration_minutes: Double = 0.0,
    val tss_estimate: Double = 0.0,
    val rpe_target: Double = 0.0,
    val sections: List<WorkoutSection> = emptyList(),
    val coach_note: String = "",
)

@Serializable
data class PlannedWorkout(
    val id: String,
    val date: String,
    val type: String,
    val workout_json: Workout,
    val llm_provider: String? = null,
    val llm_model: String? = null,
    val intervals_event_id: String? = null,
    val completed: Boolean = false,
    val locked: Boolean = false,
    val skipped: Boolean = false,
    val created_at: String? = null,
)

@Serializable
data class ReadinessComponents(val wellness: Double, val hrvDelta: Double, val rhrDelta: Double)

@Serializable
data class Readiness(val score: Int, val band: String, val components: ReadinessComponents)

// Deeper recovery breakdown (HRV/RHR/sleep trends behind the readiness score).
@Serializable
data class RecoveryTrend(val latest: Double, val baseline: Double, val deltaPct: Double)

@Serializable
data class RecoverySleep(val hours: Double, val avgHours: Double? = null)

@Serializable
data class Recovery(
    val score: Int,
    val band: String,
    val wellness: Double = 3.0,
    val hrv: RecoveryTrend? = null,
    val rhr: RecoveryTrend? = null,
    val sleep: RecoverySleep? = null,
    val summary: String = "",
)

@Serializable
data class TsbPoint(val date: String, val tsb: Double, val ctl: Double, val atl: Double)

@Serializable
data class WeeklyLoad(val tss: Int, val target: Int)

@Serializable
data class GoalProgress(
    val goal: String,
    val goal_date: String? = null,
    val weeks_to_goal: Int? = null,
    val phase: String = "",
    val ctl_trend: Double = 0.0,
    val on_track: String = "",
)

@Serializable
data class Vo2Max(val value: Double, val change: Double? = null)

@Serializable
data class DailySummary(
    val date: String,
    val readiness: Readiness,
    val recovery: Recovery? = null,
    val vo2max: Vo2Max? = null,
    val today_workout: PlannedWorkout? = null,
    val tsb_sparkline: List<TsbPoint> = emptyList(),
    val weekly_load: WeeklyLoad,
    val active_llm_provider: String,
    val goal: GoalProgress? = null,
)

@Serializable
data class GenerateRequest(
    val date: String? = null,
    val type: String = "auto",
    // null → the server derives length from the profile preference/max and
    // treats it as flexible guidance instead of a fixed number.
    val duration: Int? = null,
    val push: Boolean = true,
    // "Adjust this workout": revise base_workout per a natural-language request.
    val adjustment: String? = null,
    val base_workout: Workout? = null,
    // Coarse location for weather-aware outdoor sessions.
    val lat: Double? = null,
    val lon: Double? = null,
    // Free-text athlete request for a specific date ("social 10k with friends").
    val request: String? = null,
    // Lock the result so the weekly re-planner won't move/replace it.
    val lock: Boolean = false,
)

@Serializable
data class GenerateResult(
    val workout: Workout? = null,
    val workout_id: String? = null,
    val provider: String? = null,
    val estimated_cost_usd: Double = 0.0,
)

@Serializable
data class PlanWeekRequest(val start_date: String? = null, val push: Boolean = true)

@Serializable
data class WeekPlanRow(val start_date: String, val focus: String? = null, val rationale: String? = null)

@Serializable
data class PlanDaySummary(
    val date: String,
    val weekday: String = "",
    val type: String = "",
    val title: String = "",
    val tss: Int = 0,
)

@Serializable
data class PlanWeekResult(
    val start_date: String? = null,
    val end_date: String? = null,
    val week_focus: String? = null,
    val rationale: String? = null,
    val scheduled: Int = 0,
    val pushed: Int = 0,
    val days: List<PlanDaySummary> = emptyList(),
    val error: String? = null,
)

@Serializable
data class PlanBlockRequest(val weeks: Int? = null, val push_weeks: Int = 2)

@Serializable
data class PlanBlockWeek(
    val week: Int = 0,
    val start_date: String = "",
    val focus: String? = null,
    val scheduled: Int = 0,
    val pushed: Int = 0,
    val error: String? = null,
)

@Serializable
data class PlanBlockResult(
    val start_date: String? = null,
    val end_date: String? = null,
    val weeks: Int = 0,
    val weeks_planned: Int = 0,
    val pushed_weeks: Int = 0,
    val weeks_detail: List<PlanBlockWeek> = emptyList(),
    val error: String? = null,
)

@Serializable
data class ScheduleRequest(
    val template_id: String,
    val start_date: String? = null,
    val push: Boolean = true,
)

@Serializable
data class ScheduleResult(
    val scheduled: Int = 0,
    val pushed: Int = 0,
    val start_date: String? = null,
    val error: String? = null,
)

@Serializable
data class ManualActivityInsert(
    val intervals_id: String,
    val type: String,
    val date: String,
    val duration_seconds: Int? = null,
    val distance_m: Double? = null,
    val tss: Double? = null,
)

// A past activity pulled from Intervals.icu (or logged manually) into
// completed_activities. data_json holds the full Intervals object for the
// detail view (avg/max HR & power, elevation, calories, pace, name…).
@Serializable
data class CompletedActivity(
    val id: String,
    val intervals_id: String,
    val type: String? = null,
    val date: String? = null,
    val duration_seconds: Int? = null,
    val distance_m: Double? = null,
    val avg_hr: Int? = null,
    val tss: Double? = null,
    val ctl: Double? = null,
    val atl: Double? = null,
    val data_json: kotlinx.serialization.json.JsonObject? = null,
) {
    val isManual: Boolean get() = intervals_id.startsWith("manual:")
    val distanceKm: Double? get() = distance_m?.let { it / 1000.0 }
    val durationMin: Int? get() = duration_seconds?.let { it / 60 }

    /** A human label: the Intervals activity name if present, else the type. */
    val displayName: String
        get() = str("name")?.takeIf { it.isNotBlank() } ?: (type ?: "Activity")

    /** Average pace in sec/km, when this looks like a run/walk with distance. */
    val paceSecPerKm: Int?
        get() {
            val d = distance_m ?: return null
            val s = duration_seconds ?: return null
            if (d < 100) return null
            return (s / (d / 1000.0)).toInt()
        }

    // --- typed pulls out of the raw Intervals object ---
    fun str(key: String): String? =
        (data_json?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    fun num(key: String): Double? =
        (data_json?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    val avgPower: Int? get() = num("icu_average_watts")?.toInt() ?: num("average_watts")?.toInt()
    val maxHr: Int? get() = num("max_heartrate")?.toInt()
    val elevationGain: Int? get() = num("total_elevation_gain")?.toInt() ?: num("icu_elevation_gain")?.toInt()
    val calories: Int? get() = num("calories")?.toInt()
    val avgCadence: Int? get() = num("average_cadence")?.toInt()
}

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content

// Result of an adaptive re-plan: how the plan was reconciled with reality.
data class AdaptResult(
    val reconciled: Int = 0,   // planned sessions auto-completed from actuals
    val missed: Int = 0,       // past planned sessions with no matching activity
    val replanned: Boolean = false,
    val message: String = "",
    val error: String? = null,
)

// A saved LLM API key row (the key itself never leaves the server — only a
// masked hint like "sk-an••••3kQx" for the settings UI).
@Serializable
data class LlmKeyRow(
    val provider: String,
    val is_valid: Boolean? = null,
    val last_tested_at: String? = null,
    val key_hint: String? = null,
)

@Serializable
data class WorkoutFeedback(
    val planned_workout_id: String? = null,
    val date: String,
    val completed: Boolean = true,
    val actual_rpe: Int? = null,
    val difficulty: String? = null,   // too_easy | just_right | too_hard
    val notes: String? = null,
)

// --- Strength logging -------------------------------------------------------
@Serializable
data class StrengthSet(val reps: Int, val weight_kg: Double, val rpe: Int? = null)

@Serializable
data class StrengthLogInsert(
    val date: String,
    val exercise_name: String,
    val muscle_groups: List<String> = emptyList(),
    val sets: List<StrengthSet> = emptyList(),
    val estimated_1rm: Double? = null,
    val notes: String? = null,
)

@Serializable
data class StrengthLogRow(
    val id: String,
    val date: String,
    val exercise_name: String,
    val sets: List<StrengthSet> = emptyList(),
    val estimated_1rm: Double? = null,
)

// --- Push a strength workout to Intervals.icu → watch -----------------------
@Serializable
data class PushStrengthSet(val reps: Int, val weight_kg: Double? = null, val rpe: Int? = null)

@Serializable
data class PushStrengthExercise(val name: String, val muscle: String? = null, val sets: List<PushStrengthSet> = emptyList())

@Serializable
data class PushStrengthRequest(val date: String, val name: String, val exercises: List<PushStrengthExercise>)

@Serializable
data class PushResult(val ok: Boolean = false, val intervals_event_id: String? = null, val date: String? = null, val error: String? = null)

@Serializable
data class GenerationLogRow(
    val created_at: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val parsed_ok: Boolean = false,
    val error: String? = null,
    val estimated_cost_usd: Double = 0.0,
)

// --- Post-workout execution analysis (analyze-activity) ---------------------
@Serializable
data class AnalysisComponent(val name: String, val score: Int = 0, val detail: String = "")

@Serializable
data class AnalysisSeries(
    val t: List<Double> = emptyList(),
    val pace: List<Double?> = emptyList(),  // sec/km, null while stopped
    val hr: List<Double?> = emptyList(),
)

@Serializable
data class AnalysisTarget(
    val pace_lo: Double? = null,
    val pace_hi: Double? = null,
    val hr_lo: Int? = null,
    val hr_hi: Int? = null,
    val zones: String = "",
)

@Serializable
data class AnalysisSplit(val km: Double, val sec: Int = 0, val avg_hr: Int? = null)

@Serializable
data class ActivityAnalysis(
    val ok: Boolean = false,
    val score: Int? = null,
    val label: String? = null,
    val components: List<AnalysisComponent> = emptyList(),
    val feedback: String? = null,
    val feedback_provider: String? = null,
    val series: AnalysisSeries? = null,
    val target: AnalysisTarget? = null,
    val splits: List<AnalysisSplit> = emptyList(),
    val planned_title: String? = null,
    val streams_error: String? = null,
    val error: String? = null,
)

// --- Strength session analysis (analyze-strength) ---------------------------
@Serializable
data class StrengthAnalysisExercise(
    val name: String,
    val actual_sets: Int = 0,
    val top_weight_kg: Double? = null,
    val volume_kg: Double? = null,
    val planned: String? = null,
)

@Serializable
data class StrengthAnalysisWatch(
    val duration_min: Int? = null,
    val avg_hr: Int? = null,
    val tss: Double? = null,
)

@Serializable
data class StrengthAnalysis(
    val ok: Boolean = false,
    val score: Int? = null,
    val label: String? = null,
    val components: List<AnalysisComponent> = emptyList(),
    val feedback: String? = null,
    val feedback_provider: String? = null,
    val exercises: List<StrengthAnalysisExercise> = emptyList(),
    val total_volume_kg: Double? = null,
    val total_sets: Int? = null,
    val watch: StrengthAnalysisWatch? = null,
    val planned_title: String? = null,
    val error: String? = null,
)

// Dynamic model selector — model ids fetched live from the provider's API.
@Serializable
data class ModelListResponse(
    val provider: String = "",
    val default_model: String? = null,
    val current: String? = null,
    val models: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
data class TestKeyRequest(val provider: String, val apiKey: String, val sampleGeneration: Boolean = false)

@Serializable
data class TestKeyResponse(
    val provider: String,
    val model: String,
    val is_valid: Boolean,
    val error: String? = null,
    val sample: String? = null,
    val estimated_cost_usd: Double = 0.0,
)

@Serializable
data class WellnessCheckin(
    val date: String,
    val energy: Int? = null,
    val soreness: Int? = null,
    // No sleep field: sleep is sourced objectively from the Intervals.icu sleep
    // score (wellness_checkins.sleep_score, mirrored by sync-intervals). The old
    // manual sleep_quality rating has been fully removed.
)

// Health Connect metrics upserted onto the day's wellness row.
@Serializable
data class WellnessHealthUpdate(
    val date: String,
    val hrv_rmssd: Double? = null,
    val resting_hr: Int? = null,
    val zepp_sleep_minutes: Int? = null,
    val steps: Int? = null,
    val sleep_deep_min: Int? = null,
    val sleep_rem_min: Int? = null,
    val vo2max: Double? = null,
    val source: String = "health_connect",
)

// --- Coach chat -------------------------------------------------------------
@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class CoachChatRequest(
    val messages: List<ChatMessage>,
    val mode: String = "chat",                 // "chat" | "finalize"
    val finalizeKind: String? = null,          // "workout" | "plan"
    val conversationId: String? = null,
    val purpose: String = "plan",
    val save: Boolean = false,
    val stream: Boolean = false,
)

@Serializable
data class CoachReply(
    val reply: String? = null,
    val conversation_id: String? = null,
    val provider: String? = null,
    val estimated_cost_usd: Double = 0.0,
    val tools_used: List<String> = emptyList(),
)

@Serializable
data class CoachConversation(
    val id: String,
    val title: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val updated_at: String? = null,
    val pinned: Boolean = false,
)

// --- Training profile (onboarding jsonb) ------------------------------------
@Serializable
data class TrainingProfile(
    val goal: String? = null,
    val experience: String? = null,
    val days: List<String> = emptyList(),
    val session_duration: Int? = null,
    // Optional hard upper limit; session_duration is then a typical length and
    // the AI varies the actual duration with each session's purpose.
    val session_duration_max: Int? = null,
    val equipment: String? = null,
    val target_pace: String? = null,
    val goal_date: String? = null,
    val injury_history: String? = null,
    val weekly_tss_target: Int? = null,
    // E1: thresholds for zone calculation. lthr=bpm, ftp=watts,
    // threshold_pace_per_km="m:ss" (your ~1h race pace).
    val lthr: Int? = null,
    val ftp: Int? = null,
    val threshold_pace_per_km: String? = null,
)

@Serializable
data class Race(
    val id: String? = null,
    val name: String,
    val date: String,
    val priority: String = "A",     // A | B | C
    val sport: String = "run",      // run | ride | swim | strength | other
    val distance: String? = null,
    val target: String? = null,     // free text: "4:45/km", "FTP 260W", "Squat 120kg"
    val notes: String? = null,
)

@Serializable
data class ThresholdTest(
    val id: String? = null,
    val date: String,
    val kind: String,               // lthr | ftp | threshold_pace
    val value: Double,
    val notes: String? = null,
)

// --- Intervals.icu fitness dashboard ----------------------------------------
@Serializable
data class FitnessPoint(val date: String, val ctl: Double = 0.0, val atl: Double = 0.0, val tsb: Double = 0.0)

@Serializable
data class HrZone(val name: String, val min: Int = 0, val max: Int = 0)

@Serializable
data class FitnessSummary(val ctl: Double = 0.0, val atl: Double = 0.0, val tsb: Double = 0.0, val ramp: Double = 0.0)

@Serializable
data class IntervalsActivity(
    val date: String,
    val name: String = "",
    val type: String = "",
    val distance_km: Double? = null,
    val duration_min: Int? = null,
    val tss: Double? = null,
    val avg_hr: Int? = null,
)

@Serializable
data class IntervalsStats(
    val connected: Boolean = false,
    val athlete_name: String? = null,
    val summary: FitnessSummary? = null,
    val fitness: List<FitnessPoint> = emptyList(),
    val hr_zones: List<HrZone> = emptyList(),
    val activities: List<IntervalsActivity> = emptyList(),
    val error: String? = null,
)

@Serializable
data class ConnectIntervalsResult(
    val ok: Boolean = false,
    val athlete_name: String? = null,
    val error: String? = null,
)

@Serializable
data class WorkoutTemplate(
    val id: String,
    val name: String,
    val description: String? = null,
    val kind: String = "workout",
)
