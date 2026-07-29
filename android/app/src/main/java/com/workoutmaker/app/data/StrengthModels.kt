package com.workoutmaker.app.data

import kotlinx.serialization.Serializable

// Kotlin mirror of /shared/types.ts. Keep field names in sync with the schema.
//
// Strength logging: a logged set on its way to the cloud, and the shapes used
// to push a finished session to Intervals.icu.

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
