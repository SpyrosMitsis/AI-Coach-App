package com.workoutmaker.app.calendar

import com.workoutmaker.app.data.Workout
import com.workoutmaker.app.data.WorkoutExercise

// Pure builders for the calendar event a planned workout becomes — kept out of
// DeviceCalendarManager so JVM tests cover them without a ContentResolver.
//
// Title: glanceable on the month/agenda view ("🏃 Threshold repeats · 45 min").
// Description: the whole session at a glance, so checking the calendar answers
// "what am I actually doing today" without opening the app.

private fun typeEmoji(type: String): String = when {
    type.contains("run", ignoreCase = true) -> "🏃"
    type.contains("ride", ignoreCase = true) || type.contains("bike", ignoreCase = true) -> "🚴"
    type.contains("swim", ignoreCase = true) -> "🏊"
    type.contains("strength", ignoreCase = true) || type.contains("gym", ignoreCase = true) -> "🏋️"
    type.contains("row", ignoreCase = true) -> "🚣"
    else -> "🗓️"
}

fun calendarEventTitle(w: Workout, type: String): String {
    val name = w.title.ifBlank { type.replaceFirstChar { it.uppercase() } }
    val mins = w.duration_minutes.toInt()
    return "${typeEmoji(type)} $name" + (if (mins > 0) " · $mins min" else "")
}

private fun exerciseLine(e: WorkoutExercise): String {
    val parts = StringBuilder(e.name)
    if (e.sets > 0 && e.reps.isNotBlank()) parts.append(" ${e.sets}x${e.reps}")
    else if (e.reps.isNotBlank()) parts.append(" ${e.reps}")
    e.weight_kg?.takeIf { it > 0 }?.let {
        val v = if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
        parts.append(" @ ${v}kg")
    }
    (e.pace_zone ?: e.hr_zone)?.takeIf { it.isNotBlank() }?.let { parts.append(" @ $it") }
    return parts.toString()
}

/**
 * Multi-line event description: intensity, per-section structure (capped so a
 * long gym session doesn't become a scroll), coach note, and how to open the
 * app. MARKER is appended by the caller — never include it here.
 */
fun calendarEventDetail(w: Workout, type: String, maxExercisesPerSection: Int = 5): String {
    val out = StringBuilder()
    val headline = mutableListOf<String>()
    headline += type.replaceFirstChar { it.uppercase() }
    if (w.rpe_target > 0) headline += "effort ${w.rpe_target.toInt()}/10"
    if (w.tss_estimate > 0) headline += "~${w.tss_estimate.toInt()} TSS"
    out.append(headline.joinToString(" · "))

    for (s in w.sections) {
        if (s.exercises.isEmpty() && s.duration_minutes <= 0) continue
        out.append("\n\n").append(s.name)
        if (s.duration_minutes > 0) out.append(" (${s.duration_minutes.toInt()} min)")
        val shown = s.exercises.take(maxExercisesPerSection)
        for (e in shown) out.append("\n• ").append(exerciseLine(e))
        val more = s.exercises.size - shown.size
        if (more > 0) out.append("\n• +$more more")
    }

    if (w.coach_note.isNotBlank()) out.append("\n\nCoach: ").append(w.coach_note.take(200))
    out.append("\n\nPlanned by Metis. Open the app to start the session.")
    return out.toString()
}
