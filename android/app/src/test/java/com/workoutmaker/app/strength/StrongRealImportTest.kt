package com.workoutmaker.app.strength

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Regression: the real Strong export the user provided must parse fully.
class StrongRealImportTest {
    private fun load() = this::class.java.classLoader!!
        .getResourceAsStream("strong_real.csv")!!.bufferedReader().readText()

    @Test fun parsesRealStrongExport() {
        val r = StrengthCsvImport.parse(load())
        assertEquals("Strong", r.format)
        // 72 distinct training days in the file.
        assertEquals(72, r.workoutCount)
        assertTrue("expected lots of sets, got ${r.setCount}", r.setCount > 1200)
        // Cardio rows (Walking with distance/seconds, no reps) are skipped, not failed.
        assertTrue("cardio rows should be counted", r.cardioRows > 0)
        // Every workout has at least one exercise with at least one set.
        assertTrue(r.workouts.all { w -> w.exercises.isNotEmpty() && w.exercises.all { it.sets.isNotEmpty() } })
    }

    @Test fun perRowUnitConvertsLbToKg() {
        val csv = """
            Date;Workout Name;Exercise Name;Set Order;Weight;Weight Unit;Reps
            2025-01-01 10:00:00;"Test";"Bench Press (Barbell)";1;100;lbs;5
        """.trimIndent()
        val r = StrengthCsvImport.parse(csv)
        val w = r.workouts.single().exercises.single().sets.single()
        // 100 lb ≈ 45.36 kg
        assertEquals(45.36, w.weightKg, 0.1)
    }
}
