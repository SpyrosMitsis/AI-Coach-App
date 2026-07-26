package com.workoutmaker.app.calendar

import com.workoutmaker.app.data.Workout
import com.workoutmaker.app.data.WorkoutExercise
import com.workoutmaker.app.data.WorkoutSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEventContentTest {
    private val strength = Workout(
        type = "strength",
        title = "Upper push",
        duration_minutes = 55.0,
        rpe_target = 7.0,
        tss_estimate = 45.0,
        coach_note = "Keep two reps in reserve on the bench.",
        sections = listOf(
            WorkoutSection(
                name = "Main",
                duration_minutes = 40.0,
                exercises = listOf(
                    WorkoutExercise(name = "Barbell Bench Press", sets = 4, reps = "6", weight_kg = 77.5),
                    WorkoutExercise(name = "Overhead Press", sets = 3, reps = "8", weight_kg = 40.0),
                ),
            ),
        ),
    )

    @Test
    fun `title carries emoji, name and duration`() {
        assertEquals("🏋️ Upper push · 55 min", calendarEventTitle(strength, "strength"))
        assertEquals("🏃 Threshold repeats · 45 min", calendarEventTitle(
            Workout(type = "run", title = "Threshold repeats", duration_minutes = 45.0), "run",
        ))
    }

    @Test
    fun `title falls back to the type when untitled`() {
        assertEquals("🏃 Run", calendarEventTitle(Workout(type = "run", title = ""), "run"))
    }

    @Test
    fun `detail lists effort, sections, exercises with loads, and coach note`() {
        val d = calendarEventDetail(strength, "strength")
        assertTrue(d.startsWith("Strength · effort 7/10 · ~45 TSS"))
        assertTrue(d.contains("Main (40 min)"))
        assertTrue(d.contains("• Barbell Bench Press 4x6 @ 77.5kg"))
        assertTrue(d.contains("• Overhead Press 3x8 @ 40kg"))
        assertTrue(d.contains("Coach: Keep two reps in reserve"))
        assertTrue(d.contains("Open the app"))
        assertFalse(d.contains(DeviceCalendarManager.MARKER)) // caller appends it
    }

    @Test
    fun `long sections are capped with a +N more line`() {
        val many = strength.copy(
            sections = listOf(
                WorkoutSection(
                    name = "Main",
                    exercises = (1..8).map { WorkoutExercise(name = "Exercise $it", sets = 3, reps = "10") },
                ),
            ),
        )
        val d = calendarEventDetail(many, "strength")
        assertTrue(d.contains("• Exercise 5"))
        assertFalse(d.contains("• Exercise 6"))
        assertTrue(d.contains("• +3 more"))
    }

    @Test
    fun `endurance intervals show their zone`() {
        val run = Workout(
            type = "run",
            title = "Threshold",
            duration_minutes = 45.0,
            rpe_target = 8.0,
            sections = listOf(
                WorkoutSection(
                    name = "Work",
                    duration_minutes = 25.0,
                    exercises = listOf(WorkoutExercise(name = "5 min repeats", sets = 4, reps = "1", hr_zone = "Z4")),
                ),
            ),
        )
        val d = calendarEventDetail(run, "run")
        assertTrue(d.contains("@ Z4"))
    }
}
