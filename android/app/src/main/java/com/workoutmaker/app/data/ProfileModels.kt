package com.workoutmaker.app.data

import kotlinx.serialization.Serializable

// Kotlin mirror of /shared/types.ts. Keep field names in sync with the schema.
//
// Who the athlete is: the training profile the whole engine is driven from,
// its legacy-field migrations, and the goals and threshold tests around it.

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
