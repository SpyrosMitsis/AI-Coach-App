package com.workoutmaker.app.strength

// ============================================================================
// B4 — templated multi-week strength programs. Choosing one creates a Routine
// per training day (reusing the existing routine infra); week-to-week
// progression is handled at session start by the [Progression] engine. Exercise
// names map to ExerciseCatalog so muscle/rest resolve correctly.
// ============================================================================

data class ProgramDay(val name: String, val exercises: List<Pair<String, Int>>) // exercise -> target sets

data class StrengthProgram(
    val key: String,
    val name: String,
    val description: String,
    val schedule: String,        // human-readable weekly split
    val days: List<ProgramDay>,
)

object StrengthPrograms {
    val all: List<StrengthProgram> = listOf(
        StrengthProgram(
            key = "fullbody3",
            name = "Full Body 3×/week",
            description = "Beginner-friendly. Three full-body sessions (A/B/A) with the big compounds every session, fast strength gains.",
            schedule = "Mon · Wed · Fri (alternate A/B)",
            days = listOf(
                ProgramDay("Full Body A", listOf("Back Squat" to 3, "Barbell Bench Press" to 3, "Barbell Row" to 3, "Overhead Press" to 2, "Plank" to 3)),
                ProgramDay("Full Body B", listOf("Deadlift" to 2, "Overhead Press" to 3, "Lat Pulldown" to 3, "Leg Press" to 3, "Hanging Leg Raise" to 3)),
            ),
        ),
        StrengthProgram(
            key = "upperlower4",
            name = "Upper / Lower (4×/week)",
            description = "Intermediate. Two upper + two lower days lets you push more weekly volume per muscle with good recovery.",
            schedule = "Mon Upper · Tue Lower · Thu Upper · Fri Lower",
            days = listOf(
                ProgramDay("Upper A", listOf("Barbell Bench Press" to 4, "Barbell Row" to 4, "Overhead Press" to 3, "Lat Pulldown" to 3, "Dumbbell Curl" to 3, "Triceps Pushdown" to 3)),
                ProgramDay("Lower A", listOf("Back Squat" to 4, "Romanian Deadlift" to 3, "Leg Press" to 3, "Lying Leg Curl" to 3, "Standing Calf Raise" to 4)),
                ProgramDay("Upper B", listOf("Overhead Press" to 4, "Pull-Up" to 4, "Incline Dumbbell Bench Press" to 3, "Seated Cable Row" to 3, "Lateral Raise" to 3, "Hammer Curl" to 3)),
                ProgramDay("Lower B", listOf("Deadlift" to 3, "Front Squat" to 3, "Bulgarian Split Squat" to 3, "Seated Leg Curl" to 3, "Seated Calf Raise" to 4)),
            ),
        ),
        StrengthProgram(
            key = "ppl6",
            name = "Push / Pull / Legs (6×/week)",
            description = "Advanced / high volume. Each muscle trained twice a week across six focused sessions.",
            schedule = "Mon Push · Tue Pull · Wed Legs · Thu Push · Fri Pull · Sat Legs",
            days = listOf(
                ProgramDay("Push", listOf("Barbell Bench Press" to 4, "Overhead Press" to 3, "Incline Dumbbell Bench Press" to 3, "Lateral Raise" to 4, "Triceps Pushdown" to 3, "Overhead Cable Extension" to 3)),
                ProgramDay("Pull", listOf("Deadlift" to 3, "Pull-Up" to 4, "Barbell Row" to 4, "Lat Pulldown" to 3, "Face Pull" to 3, "Barbell Curl" to 4)),
                ProgramDay("Legs", listOf("Back Squat" to 4, "Romanian Deadlift" to 4, "Leg Press" to 3, "Lying Leg Curl" to 3, "Leg Extension" to 3, "Standing Calf Raise" to 4)),
            ),
        ),
        StrengthProgram(
            key = "strength4",
            name = "Strength (4×/week, main-lift focus)",
            description = "Powerbuilding. Heavy primary lift each day in low reps, then hypertrophy accessories, drive squat/bench/dead/press up.",
            schedule = "Squat · Bench · Deadlift · Press days",
            days = listOf(
                ProgramDay("Squat Day", listOf("Back Squat" to 5, "Romanian Deadlift" to 3, "Leg Press" to 3, "Standing Calf Raise" to 4)),
                ProgramDay("Bench Day", listOf("Barbell Bench Press" to 5, "Incline Dumbbell Bench Press" to 3, "Dips (Chest)" to 3, "Triceps Pushdown" to 3)),
                ProgramDay("Deadlift Day", listOf("Deadlift" to 5, "Barbell Row" to 4, "Lat Pulldown" to 3, "Barbell Curl" to 3)),
                ProgramDay("Press Day", listOf("Overhead Press" to 5, "Seated Dumbbell Press" to 3, "Lateral Raise" to 4, "Face Pull" to 3)),
            ),
        ),
    )

    fun find(key: String): StrengthProgram? = all.firstOrNull { it.key == key }
}
