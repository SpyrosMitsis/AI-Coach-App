package com.workoutmaker.app.ui.screens.calendar

import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.Periodization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PhaseMarkerTest {

    private val strip = 300.dp

    @Test
    fun `a goal 20-plus weeks out does not produce a negative padding`() {
        // The actual crash. Phase.progress is 1 - min(weeks,20)/20, so it is
        // exactly 0f for anything a season away, and the old centring
        // subtraction made the padding -4.dp. Compose throws on that, taking
        // the whole Calendar tab down on open.
        val weekStart = LocalDate.of(2026, 7, 27)
        for (weeks in 20..60) {
            val phase = Periodization.phaseFor(weekStart.plusWeeks(weeks.toLong()), weekStart)
            val offset = markerOffset(strip, phase.progress)
            assertTrue(
                "a goal $weeks weeks out gave padding $offset",
                offset.value >= 0f,
            )
        }
    }

    @Test
    fun `every real weeks-to-goal stays inside the strip`() {
        // Sweep what phaseFor can actually produce rather than picked floats:
        // both ends are clamped, so the marker never overhangs either edge.
        val weekStart = LocalDate.of(2026, 7, 27)
        for (weeks in 0..60) {
            val phase = Periodization.phaseFor(weekStart.plusWeeks(weeks.toLong()), weekStart)
            val offset = markerOffset(strip, phase.progress)
            assertTrue("$weeks weeks: $offset below 0", offset.value >= 0f)
            assertTrue(
                "$weeks weeks: $offset overhangs the right edge",
                offset <= strip - MARKER,
            )
        }
    }

    @Test
    fun `race week pins to the right edge, not past it`() {
        assertEquals(strip - MARKER, markerOffset(strip, 1f))
    }

    @Test
    fun `the marker is centred on its position in the middle of the arc`() {
        // Half the strip, less half the marker: the centring the original was
        // trying to do (it subtracted 4.dp for a 10.dp marker).
        assertEquals(strip / 2 - MARKER / 2, markerOffset(strip, 0.5f))
    }

    @Test
    fun `a strip narrower than the marker degrades instead of throwing`() {
        // coerceIn requires min <= max; without the span guard this is an
        // IllegalArgumentException of its own during an early layout pass.
        assertEquals(0.dp, markerOffset(4.dp, 1f))
        assertEquals(0.dp, markerOffset(0.dp, 0.5f))
    }

    @Test
    fun `out-of-range progress is survived rather than trusted`() {
        assertTrue(markerOffset(strip, -1f).value >= 0f)
        assertTrue(markerOffset(strip, 2f) <= strip - MARKER)
    }
}
