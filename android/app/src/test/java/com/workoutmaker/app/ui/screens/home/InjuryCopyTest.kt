package com.workoutmaker.app.ui.screens.home

import com.workoutmaker.app.data.InjuryBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// The backoff banner is the only place the athlete finds out WHY their training
// changed shape, so its wording is worth pinning: it has to name the area, name
// the date, and say which of the two things is happening.
class InjuryCopyTest {

    @Test
    fun `avoid and ease say different things`() {
        assertEquals(
            "Keeping the load off your knee until 8 Aug.",
            backoffLine(InjuryBackoff(area = "Knee", level = "avoid", until = "2026-08-08")),
        )
        assertEquals(
            "Going easy on your knee until 8 Aug.",
            backoffLine(InjuryBackoff(area = "Knee", level = "ease", until = "2026-08-08")),
        )
    }

    @Test
    fun `a multi word area reads naturally`() {
        assertEquals(
            "Going easy on your lower back until 1 Sep.",
            backoffLine(InjuryBackoff(area = "  Lower back ", level = "ease", until = "2026-09-01")),
        )
    }

    @Test
    fun `an unparseable date falls back to the raw value instead of crashing`() {
        // The column is jsonb, so a hand-edited row can hold anything. A banner
        // that throws would take the whole Home screen with it.
        val line = backoffLine(InjuryBackoff(area = "Knee", level = "ease", until = "soon"))
        assertEquals("Going easy on your knee until soon.", line)
    }

    @Test
    fun `no em dashes in the copy`() {
        val lines = listOf(
            backoffLine(InjuryBackoff(area = "Knee", level = "avoid", until = "2026-08-08")),
            backoffLine(InjuryBackoff(area = "Achilles", level = "ease", until = "2026-08-08")),
        )
        lines.forEach { assertFalse(it, it.contains("—") || it.contains("–")) }
    }
}
