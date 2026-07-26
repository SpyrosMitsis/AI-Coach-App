package com.workoutmaker.app.health

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyDaysTest {
    private val zone: ZoneId = ZoneId.of("Europe/Athens")

    private fun at(date: String, hour: Int): Long =
        LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `latest reading per local day wins`() {
        val out = bodyDaysFromReadings(
            weights = listOf(
                at("2026-07-20", 7) to 78.4,
                at("2026-07-20", 21) to 77.9, // evening re-weigh wins
                at("2026-07-21", 7) to 78.1,
            ),
            fats = emptyList(),
            leans = emptyList(),
            zone = zone,
        )
        assertEquals(listOf("2026-07-20", "2026-07-21"), out.map { it.date })
        assertEquals(77.9, out[0].weightKg!!, 1e-9)
    }

    @Test
    fun `metrics from different days merge into a sorted sparse series`() {
        val out = bodyDaysFromReadings(
            weights = listOf(at("2026-07-21", 7) to 78.0),
            fats = listOf(at("2026-07-19", 7) to 15.55),
            leans = listOf(at("2026-07-21", 7) to 62.0),
            zone = zone,
        )
        assertEquals(listOf("2026-07-19", "2026-07-21"), out.map { it.date })
        assertEquals(15.6, out[0].bodyFatPct!!, 1e-9) // rounded to 1 decimal
        assertNull(out[0].weightKg)
        assertEquals(62.0, out[1].leanMassKg!!, 1e-9)
    }

    @Test
    fun `implausible readings are dropped`() {
        val out = bodyDaysFromReadings(
            weights = listOf(at("2026-07-20", 7) to 900.0),
            fats = listOf(at("2026-07-20", 7) to 99.0),
            leans = listOf(at("2026-07-20", 7) to 5.0),
            zone = zone,
        )
        assertTrue(out.isEmpty())
    }
}
