package com.workoutmaker.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BodyMathTest {
    @Test
    fun `lean mass derives from weight and fat, matching the backend formula`() {
        assertEquals(66.8, deriveLeanKg(80.0, 16.5)!!, 1e-9)
        assertNull(deriveLeanKg(null, 16.5))
        assertNull(deriveLeanKg(80.0, null))
        assertNull(deriveLeanKg(900.0, 16.5)) // implausible weight
        assertNull(deriveLeanKg(80.0, 80.0)) // implausible fat
    }

    @Test
    fun `slope recovers a linear weekly change`() {
        val series = (0 until 8).map { week ->
            java.time.LocalDate.parse("2026-06-01").plusWeeks(week.toLong()).toString() to (78.0 - 0.3 * week)
        }
        assertEquals(-0.3, slopePerWeek(series)!!, 1e-9)
    }

    @Test
    fun `slope refuses sparse or short-span data`() {
        assertNull(slopePerWeek(listOf("2026-07-01" to 78.0, "2026-07-20" to 77.0)))
        assertNull(
            slopePerWeek(
                listOf("2026-07-18" to 78.0, "2026-07-19" to 77.5, "2026-07-20" to 77.0),
            ),
        )
        assertNull(slopePerWeek(emptyList()))
        assertNull(slopePerWeek(listOf("garbage" to 78.0, "also-bad" to 77.0)))
    }

    @Test
    fun `focus mapping mirrors the backend`() {
        // bodyFocusOf lives in ui.screens; imported via its package below.
        assertEquals("muscle", com.workoutmaker.app.ui.screens.bodyFocusOf(listOf("Build muscle")))
        assertEquals("fat_loss", com.workoutmaker.app.ui.screens.bodyFocusOf(listOf("Lose fat")))
        assertEquals("recomp", com.workoutmaker.app.ui.screens.bodyFocusOf(listOf("Body recomposition")))
        assertEquals("recomp", com.workoutmaker.app.ui.screens.bodyFocusOf(listOf("Build muscle", "Lose fat")))
        assertEquals("general", com.workoutmaker.app.ui.screens.bodyFocusOf(listOf("Get stronger")))
        assertEquals("general", com.workoutmaker.app.ui.screens.bodyFocusOf(emptyList()))
    }
}
