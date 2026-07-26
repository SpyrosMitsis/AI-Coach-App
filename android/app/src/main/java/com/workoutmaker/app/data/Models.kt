package com.workoutmaker.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Kotlin mirror of /shared/types.ts. Keep field names in sync with the schema.

enum class LlmProvider(val label: String, val model: String, val freeKeyUrl: String, val freeTier: Boolean) {
    @SerialName("anthropic") ANTHROPIC("Anthropic", "claude-opus-4-8", "https://console.anthropic.com/settings/keys", false),
    @SerialName("deepseek")  DEEPSEEK("DeepSeek", "deepseek-chat", "https://platform.deepseek.com/api_keys", false),
    @SerialName("openai")    OPENAI("OpenAI", "gpt-5-mini", "https://platform.openai.com/api-keys", false),
    @SerialName("gemini")    GEMINI("Google Gemini", "gemini-2.5-flash", "https://aistudio.google.com/app/apikey", true),
    @SerialName("groq")      GROQ("Groq", "llama-3.3-70b-versatile", "https://console.groq.com/keys", true),
    // OpenRouter — one key, hundreds of models behind a fixed OpenAI-compatible
    // endpoint. Default model auto-routes; pick any model id in Settings.
    @SerialName("openrouter") OPENROUTER("OpenRouter", "openrouter/auto", "https://openrouter.ai/keys", false),
    // Bring-your-own OpenAI-compatible endpoint (Ollama / LM Studio / vLLM …).
    // No fixed model or free-key link — the user supplies a base URL, model id,
    // and key in Settings.
    @SerialName("custom")    CUSTOM("Custom (OpenAI-compatible)", "", "", false);

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
// `latest`/`hours` are null when today's reading hasn't synced from Intervals —
// the UI shows that explicitly instead of falling back to yesterday's value.
@Serializable
data class RecoveryTrend(
    val latest: Double? = null,
    val baseline: Double,
    val deltaPct: Double,
    // Recent values (oldest→newest) for the inline sparkline next to the trend badge.
    val series: List<Double> = emptyList(),
)

@Serializable
data class RecoverySleep(val hours: Double? = null, val avgHours: Double? = null, val score: Double? = null)

// One reason behind the readiness score, rendered as a chip. dir = up|down|flat,
// tone = good|bad|neutral (drives the chip colour).
@Serializable
data class RecoveryDriver(val label: String, val dir: String, val tone: String)

@Serializable
data class Recovery(
    val score: Int,
    val band: String,
    val wellness: Double = 3.0,
    val hrv: RecoveryTrend? = null,
    val rhr: RecoveryTrend? = null,
    val sleep: RecoverySleep? = null,
    val drivers: List<RecoveryDriver> = emptyList(),
    val summary: String = "",
)

@Serializable
data class TsbPoint(val date: String, val tsb: Double, val ctl: Double, val atl: Double)

@Serializable
data class WeeklyLoad(val tss: Int, val target: Int)

@Serializable
data class SportTss(val sport: String, val tss: Int)

@Serializable
data class WeekAdherence(val done: Int, val planned: Int, val pct: Int? = null)

@Serializable
data class WeekLoad(val tss: Int, val target: Int, val prev_tss: Int, val delta_pct: Int? = null)

@Serializable
data class WeekStandout(val date: String, val sport: String, val tss: Int)

// Deterministic last-7-day recap shown on Home (computed server-side in
// daily-summary; the coach-voice line comes separately from coach-brief).
@Serializable
data class WeekReview(
    val adherence: WeekAdherence,
    val load: WeekLoad,
    val by_sport: List<SportTss> = emptyList(),
    val sessions: Int = 0,
    val standout: WeekStandout? = null,
)

// The coach's proactive daily note (coach-brief edge function).
@Serializable
data class CoachBriefResponse(
    val brief: String? = null,
    val date: String? = null,
    val provider: String? = null,
    val disabled: Boolean = false,
)

@Serializable
data class CoachWeekReviewResponse(
    val review: String? = null,
    val week_start: String? = null,
    val provider: String? = null,
)

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

// What this deployment can do. A self-hosted backend without the hosted LLM
// secrets reports hosted_ai=false and the app never shows Pro UI.
@Serializable
data class ServerCapabilities(val hosted_ai: Boolean = false)

// Today's (or yesterday's) analyzed session, picked server-side for the Home
// "Session debrief" card. activity_id is null for strength sessions (their
// analyses key on date, not on a completed_activities row).
@Serializable
data class SessionDebrief(
    val kind: String = "activity",
    val activity_id: String? = null,
    val date: String = "",
    val type: String? = null,
    val score: Int? = null,
    val label: String? = null,
    val feedback: String? = null,
)

@Serializable
data class DailySummary(
    val date: String,
    val readiness: Readiness,
    val recovery: Recovery? = null,
    // Most recent date (≤ this summary's date) with any objective recovery signal
    // synced; null = none in the window. Drives the "last synced" freshness line.
    val recovery_synced_date: String? = null,
    val vo2max: Vo2Max? = null,
    val today_workout: PlannedWorkout? = null,
    val tsb_sparkline: List<TsbPoint> = emptyList(),
    val weekly_load: WeeklyLoad,
    val week_review: WeekReview? = null,
    val debrief: SessionDebrief? = null,
    val body_trend: BodyTrend? = null,
    val active_llm_provider: String,
    val goal: GoalProgress? = null,
    val server: ServerCapabilities? = null,
)

// --- Body composition (weight / body fat / lean mass over time) --------------

// Compact goal-aware trend computed server-side (_shared/body_trend.ts). The
// plots read the full history via bodyHistory(); this is just the verdict.
@Serializable
data class BodyMetricTrend(
    val latest: Double = 0.0,
    val latestDate: String = "",
    val slopePerWeek: Double? = null,
    val points: Int = 0,
)

@Serializable
data class BodyTrend(
    val focus: String = "general", // muscle | fat_loss | recomp | general
    val weight: BodyMetricTrend? = null,
    val bodyFat: BodyMetricTrend? = null,
    val leanMass: BodyMetricTrend? = null,
    val onTrack: Boolean? = null,
    val summary: String = "",
)

// One day's measured body metrics from wellness_checkins (nullable per column).
@Serializable
data class BodyHistoryPoint(
    val date: String,
    val weight_kg: Double? = null,
    val body_fat_pct: Double? = null,
    val lean_mass_kg: Double? = null,
)

// Upsert row for a scale sync or manual quick-log (server merge on user_id,date).
@Serializable
data class BodyMetricUpsert(
    val date: String,
    val weight_kg: Double? = null,
    val body_fat_pct: Double? = null,
    val lean_mass_kg: Double? = null,
    val source: String = "health_connect",
)

// Lean mass fallback when the scale only writes weight + fat. Mirrors the
// backend's derivation in body_trend.ts so both sides chart the same number.
fun deriveLeanKg(weightKg: Double?, bodyFatPct: Double?): Double? {
    if (weightKg == null || bodyFatPct == null) return null
    if (weightKg !in 30.0..250.0 || bodyFatPct !in 3.0..60.0) return null
    return Math.round(weightKg * (1 - bodyFatPct / 100) * 10) / 10.0
}

// Least-squares change per week over dated points, mirroring body_trend.ts:
// null unless >= 3 points spanning >= 14 days (day-to-day water weight is not
// a trend). Dates are ISO yyyy-MM-dd; values are already bounds-checked.
fun slopePerWeek(points: List<Pair<String, Double>>): Double? {
    val dated = points.mapNotNull { (d, v) ->
        runCatching { java.time.LocalDate.parse(d).toEpochDay() }.getOrNull()?.let { it to v }
    }.sortedBy { it.first }
    if (dated.size < 3 || dated.last().first - dated.first().first < 14) return null
    val xs = dated.map { (it.first - dated.first().first) / 7.0 }
    val ys = dated.map { it.second }
    val mx = xs.average()
    val my = ys.average()
    var num = 0.0
    var den = 0.0
    for (i in xs.indices) {
        num += (xs[i] - mx) * (ys[i] - my)
        den += (xs[i] - mx) * (xs[i] - mx)
    }
    if (den == 0.0) return null
    return Math.round(num / den * 100) / 100.0
}

// Billing state from user_profiles — plan columns are server-written only
// (verify-purchase / play-rtdn); use_hosted_ai is the user's own toggle.
data class PlanStatus(
    val plan: String = "free",
    val expiresAt: String? = null,
    val useHostedAi: Boolean = true,
) {
    // Mirrors isPro() in _shared/entitlement.ts: belt-and-braces expiry, because a
    // lost RTDN can leave plan='pro' on a subscription that already lapsed. No
    // expiry means no expiry. An unparseable date fails OPEN (still Pro) — this
    // only drives UI, and the server is the real gate for hosted AI.
    val isPro: Boolean get() {
        if (plan != "pro") return false
        val exp = expiresAt ?: return true
        return runCatching { java.time.OffsetDateTime.parse(exp).toInstant() }
            .map { it.isAfter(java.time.Instant.now()) }
            .getOrDefault(true)
    }
}

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
    // Today's busy windows from the device calendar (opt-in; times only, no titles).
    val calendar_busy: List<BusyDay>? = null,
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
data class PlanWeekRequest(
    val start_date: String? = null,
    val push: Boolean = true,
    // The week's busy windows from the device calendar (opt-in; times only, no
    // titles) so the planner puts hard/long sessions on the free days.
    val calendar_busy: List<BusyDay>? = null,
)

// One day's busy summary, derived on-device from the calendar. Only times and
// an all-day flag cross the wire, never event titles.
@Serializable
data class BusyDay(
    val date: String,
    val windows: List<String> = emptyList(), // "18:00-20:30"
    val all_day: Boolean = false,
)

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

// A Health Connect exercise session pushed as a fallback activity when
// intervals.icu isn't connected (intervals_id = "hc:<record uid>").
@Serializable
data class HcActivityInsert(
    val intervals_id: String,
    val type: String,
    val date: String,
    val duration_seconds: Int? = null,
    val distance_m: Double? = null,
    val avg_hr: Int? = null,
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

    // Swims are paced per 100 m everywhere (summary tiles, charts, splits).
    val isSwim: Boolean get() = (type ?: "").contains("swim", ignoreCase = true)

    /** Average swim pace in sec/100m. */
    val paceSecPer100m: Int?
        get() {
            val d = distance_m ?: return null
            val s = duration_seconds ?: return null
            if (d < 100) return null
            return (s / (d / 100.0)).toInt()
        }

    // --- typed pulls out of the raw Intervals object ---
    fun str(key: String): String? =
        (data_json?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    fun num(key: String): Double? =
        (data_json?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    fun numArray(key: String): List<Double>? {
        val arr = data_json?.get(key) as? kotlinx.serialization.json.JsonArray ?: return null
        return arr.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0 }
    }

    val avgPower: Int? get() = num("icu_average_watts")?.toInt() ?: num("average_watts")?.toInt()
    val maxHr: Int? get() = num("max_heartrate")?.toInt()
    val elevationGain: Int? get() = num("total_elevation_gain")?.toInt() ?: num("icu_elevation_gain")?.toInt()
    val calories: Int? get() = num("calories")?.toInt()
    val avgCadence: Int? get() = num("average_cadence")?.toInt()
    val maxCadence: Int? get() = num("max_cadence")?.toInt()

    // Elapsed (clock) time including pauses — only interesting when it meaningfully
    // exceeds moving time.
    val elapsedSeconds: Int? get() = num("elapsed_time")?.toInt()
    // Max pace from peak speed (m/s → sec/km); ignores GPS spikes under ~0.5 m/s.
    val maxPaceSecPerKm: Int? get() = num("max_speed")?.let { if (it > 0.5) (1000.0 / it).toInt() else null }
    // Max swim pace (m/s → sec/100m); a lower floor since swim speeds are lower.
    val maxPaceSecPer100m: Int? get() = num("max_speed")?.let { if (it > 0.25) (100.0 / it).toInt() else null }
    // Normalized/weighted power for runs/rides with a power source.
    val normalizedPower: Int? get() = num("icu_weighted_avg_watts")?.toInt()
    // Aerobic decoupling (Pa:HR / Pw:HR drift), as a percentage.
    val decouplingPct: Double? get() = num("icu_decoupling")
    // Average ambient temperature (°C), when the device recorded it.
    val avgTempC: Int? get() = num("average_temp")?.toInt()
    // Seconds spent in each HR zone (Z1..Zn), when HR zones are configured.
    val hrZoneTimes: List<Int>? get() =
        numArray("icu_hr_zone_times")?.map { it.toInt() }?.takeIf { secs -> secs.sum() > 0 }
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
    // Only set for the custom provider (its OpenAI-compatible endpoint).
    val base_url: String? = null,
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
    val feature: String? = null,
    val provider: String? = null,
    val model: String? = null,
    // Nullable ON PURPOSE: the server writes SQL null for tokens/cost when a
    // generation failed ("claim no cost, not $0"). kotlinx defaults only cover
    // ABSENT keys, so non-nullable fields here made one failure row poison the
    // whole decodeList and Diagnostics showed "No AI generations yet" forever.
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val parsed_ok: Boolean = false,
    val error: String? = null,
    val estimated_cost_usd: Double? = null,
)

// --- Post-workout execution analysis (analyze-activity) ---------------------
@Serializable
data class AnalysisComponent(val name: String, val score: Int = 0, val detail: String = "")

@Serializable
data class AnalysisSeries(
    val t: List<Double> = emptyList(),
    val pace: List<Double?> = emptyList(),  // sec/km, null while stopped
    val hr: List<Double?> = emptyList(),
    val cadence: List<Double?> = emptyList(),  // spm/rpm, null when not recorded
    val power: List<Double?> = emptyList(),    // watts, null when not recorded
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
data class AnalysisSplit(val km: Double, val sec: Int = 0, val avg_hr: Int? = null, val in_band: Boolean? = null)

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
    // "/km" (default) or "/100m" for swims; old cached analyses lack it.
    val pace_unit: String? = null,
    // Metres per split (1000, or 100 for swims).
    val split_m: Int? = null,
    val streams_error: String? = null,
    val error: String? = null,
) {
    val isPer100m: Boolean get() = pace_unit == "/100m"
}

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
    // HR trace from the paired watch recording, when available (for the chart).
    val series: AnalysisSeries? = null,
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
data class TestKeyRequest(
    val provider: String,
    val apiKey: String,
    val sampleGeneration: Boolean = false,
    // Custom provider only: OpenAI-compatible base URL + model id.
    val baseUrl: String? = null,
    val model: String? = null,
)

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

// A day's recovery signals for the trends screen (subset of wellness_checkins).
@Serializable
data class RecoveryHistoryPoint(
    val date: String,
    val hrv_rmssd: Double? = null,
    val resting_hr: Int? = null,
    val sleep_score: Int? = null,
    val zepp_sleep_minutes: Int? = null,
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
    // Incognito turn: the server answers with full context but persists nothing
    // (no conversation row, no knowledge/summary updates).
    val incognito: Boolean = false,
)

// A device setting the coach changed via the update_app_settings tool. Values
// arrive normalized as strings ("dark", "90", "true"); the app maps them onto
// AppPreferences through its own whitelist (ChatSettings.kt).
@Serializable
data class ChatSettingChange(val key: String, val value: String)

@Serializable
data class CoachReply(
    val reply: String? = null,
    val conversation_id: String? = null,
    val provider: String? = null,
    val estimated_cost_usd: Double = 0.0,
    val tools_used: List<String> = emptyList(),
    val settings_changes: List<ChatSettingChange> = emptyList(),
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
data class InjuryEntry(
    val area: String,
    val severity: String = "", // "" | "mild" | "moderate" | "serious"
    val note: String? = null,
)

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
    // Legacy free-text field, kept only so old profiles still round-trip; no
    // longer written by the app. New injuries go into `injuries` below —
    // backend readers go through injuriesOf() (_shared/profile.ts) which falls
    // back to parsing this string when `injuries` is empty.
    val injury_history: String? = null,
    val injuries: List<InjuryEntry> = emptyList(),
    val weekly_tss_target: Int? = null,
    // E1: thresholds for zone calculation. lthr=bpm, ftp=watts,
    // threshold_pace_per_km="m:ss" (your ~1h race pace).
    val lthr: Int? = null,
    val ftp: Int? = null,
    val threshold_pace_per_km: String? = null,
    // Preferred strength split (null/"Auto" → let the coach decide per day).
    val split_style: String? = null,
    // Sports the athlete actually does — gates which modalities get scheduled.
    val sports: List<String> = emptyList(),
    // Opt-in: progressive build weeks + an automatic deload every ~4 weeks.
    val periodized: Boolean = false,
    // The coach's proactive daily note on Home. On by default; turn off to avoid
    // the one-LLM-call-per-day it costs.
    val briefing: Boolean = true,
    // What the coach should call the athlete. Mirrored to the top-level
    // display_name column on save so the backend reads it with no schema change.
    val display_name: String? = null,
    // Manual demographics — normally read from Intervals.icu; anything set here
    // OVERRIDES the Intervals value (for when that profile is missing or stale).
    val sex: String? = null, // "male" | "female"
    val birth_year: Int? = null,
    val weight_kg: Int? = null,
    val height_cm: Int? = null,
    // Body fat %, from a Health Connect smart scale or typed in. Optional; when
    // present the strength generator gets bodyweight/BMI/body-fat context.
    val body_fat_pct: Double? = null,
    // --- Rich onboarding fields (additive) --------------------------------
    // These carry the fuller picture the onboarding flow now collects. The
    // single-value fields above are DERIVED from these (deriveLegacyFields) so
    // the deployed edge functions keep working unchanged; the enriched prompts
    // read the rich fields directly when present.
    // Flat list of every goal (derived from goals_by_sport on save; kept for the
    // backend's combined goal string).
    val goals: List<String> = emptyList(),
    // Goals per activity, e.g. {"run": ["Marathon"], "strength": ["Build muscle"]}.
    val goals_by_sport: Map<String, List<String>> = emptyMap(),
    // Experience per activity — keys are sport keys (strength/run/ride/swim).
    val experience_by_sport: Map<String, String> = emptyMap(),
    // What time the athlete can train on each day of the week.
    val day_availability: List<DayAvailability> = emptyList(),
    // Equipment they actually have, e.g. ["Barbell", "Squat rack", "Bench"].
    val equipment_list: List<String> = emptyList(),
    // Manual challenge bias: "easier" | "harder" (null = standard). The
    // automatic side (readiness caps, measured-execution feedback) keeps
    // working either way; this is the athlete's standing preference on top.
    val challenge: String? = null,
    // --- "Your numbers" (all optional; better numbers = better workouts) ---
    // Comfortable/critical 100m swim pace, "m:ss". The swim prompt's pace anchor.
    val css_per_100m: String? = null,
    // Self-reported top sets for the big lifts. Seeds the progression engine
    // for brand-new lifters until real strength logs exist (generate-workout
    // falls back to these only when it finds no history).
    val starting_lifts: List<StartingLift> = emptyList(),
)

@Serializable
data class StartingLift(
    val exercise: String,
    val weight_kg: Double,
    val reps: Int,
)

// A single day's training window: how long the athlete has, and (optionally)
// when. Lets the planner put the long run on Saturday and keep weekdays short.
@Serializable
data class DayAvailability(
    val day: String,      // "Mon".."Sun"
    val max_minutes: Int, // hard cap for that day
)

// Fill the single-value fields the current backend reads from the richer
// onboarding answers. Called on every full profile save (onboarding finish +
// Settings save) so the live edge functions keep working with no changes.
fun TrainingProfile.deriveLegacyFields(): TrainingProfile {
    // Flat goals come from the per-activity picks (fallback to any existing flat list).
    val flatGoals = if (goals_by_sport.isNotEmpty()) goals_by_sport.values.flatten().distinct() else goals
    val derivedGoal = if (flatGoals.isNotEmpty()) flatGoals.joinToString(" + ") else goal
    val derivedExperience = when {
        experience_by_sport.isEmpty() -> experience
        // Prefer strength, then the first sport actually done, then anything set.
        else -> experience_by_sport["strength"]
            ?: sports.firstNotNullOfOrNull { experience_by_sport[it] }
            ?: experience_by_sport.values.firstOrNull()
            ?: experience
    }
    val derivedDays = if (day_availability.isNotEmpty()) day_availability.map { it.day } else days
    val mins = day_availability.map { it.max_minutes }.filter { it > 0 }.sorted()
    // Typical length is a flexible budget (lower-middle day, weekday-representative);
    // max is the longest day (the long-run budget).
    val derivedSession = if (mins.isNotEmpty()) mins[(mins.size - 1) / 2] else session_duration
    val derivedSessionMax = if (mins.isNotEmpty()) mins.last() else session_duration_max
    val derivedEquipment = if (equipment_list.isNotEmpty()) deriveEquipmentTier(equipment_list) else equipment
    return copy(
        goal = derivedGoal,
        goals = flatGoals,
        experience = derivedExperience,
        days = derivedDays,
        session_duration = derivedSession,
        session_duration_max = derivedSessionMax,
        equipment = derivedEquipment,
    )
}

// Inverse of deriveLegacyFields: pre-populate the rich editors from an existing
// account's single-value profile so nothing looks empty on first open. Only
// fills a rich field when it is still empty (never clobbers real rich data).
fun TrainingProfile.hydrateRichFromLegacy(): TrainingProfile {
    var p = this
    if (p.goals.isEmpty() && !p.goal.isNullOrBlank()) {
        p = p.copy(goals = p.goal!!.split(" + ").map { it.trim() }.filter { it.isNotBlank() })
    }
    if (p.experience_by_sport.isEmpty() && !p.experience.isNullOrBlank()) {
        val target = p.sports.ifEmpty { listOf("strength") }
        p = p.copy(experience_by_sport = target.associateWith { p.experience!! })
    }
    if (p.day_availability.isEmpty() && p.days.isNotEmpty()) {
        val max = p.session_duration_max ?: p.session_duration ?: 60
        p = p.copy(day_availability = p.days.map { DayAvailability(day = it, max_minutes = max) })
    }
    if (p.equipment_list.isEmpty() && !p.equipment.isNullOrBlank()) {
        p = p.copy(equipment_list = legacyEquipmentToList(p.equipment!!))
    }
    return p
}

// Collapse a multi-select equipment list to one of the four tiers the backend's
// EQUIPMENT_CATEGORIES map understands (exercise_catalog.ts). Inclusive: the
// richest thing present wins.
fun deriveEquipmentTier(list: List<String>): String {
    val s = list.map { it.lowercase() }
    return when {
        s.any { it == "full gym" || it == "machines" } -> "Full gym"
        s.any { it == "barbell" || it == "squat rack" } -> "Barbell + rack"
        s.any { it == "dumbbells" || it == "kettlebells" } -> "Dumbbells"
        else -> "Bodyweight"
    }
}

// A representative equipment list for a stored legacy tier, so hydration shows
// something sensible in the multi-select.
fun legacyEquipmentToList(tier: String): List<String> = when (tier.lowercase()) {
    "full gym" -> listOf("Full gym")
    "barbell + rack" -> listOf("Barbell", "Squat rack")
    "dumbbells" -> listOf("Dumbbells")
    else -> emptyList()
}

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
