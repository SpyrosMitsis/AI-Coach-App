package com.workoutmaker.app.ui.screens

import com.workoutmaker.app.data.Periodization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceStepTest {

    @Test
    fun `time parsing handles both race formats`() {
        assertEquals(25 * 60 + 30, parseTimeSeconds("25:30"))
        assertEquals(1 * 3600 + 45 * 60, parseTimeSeconds("1:45:00"))
        assertNull(parseTimeSeconds("fast"))
        assertNull(parseTimeSeconds("25"))
    }

    @Test
    fun `a 25min 5K derives a plausible threshold pace`() {
        // 5:06/km race pace, threshold ~5% slower -> ~5:21/km.
        assertEquals("5:21", thresholdPaceFromRace(5.0, 25 * 60 + 30))
    }

    @Test
    fun `a 10K result maps threshold to race pace`() {
        assertEquals("5:00", thresholdPaceFromRace(10.0, 50 * 60))
    }

    @Test
    fun `a half marathon derives slightly faster than race pace`() {
        // 1:45 half = 4:58/km race pace; threshold ~3% faster -> ~4:49.
        assertEquals("4:49", thresholdPaceFromRace(21.0975, 105 * 60))
    }

    @Test
    fun `typo times produce no pace instead of garbage zones`() {
        assertNull(thresholdPaceFromRace(5.0, 2 * 60)) // 2min 5K
        assertNull(thresholdPaceFromRace(5.0, 4 * 3600)) // 4h 5K
    }

    @Test
    fun `effort chips price from the athlete's own week and stay under the ceiling`() {
        // Sam's 405-min week: ceiling 356 (mirrors plan_checks availabilityTssCeiling).
        val ceiling = Periodization.availabilityCeiling(405)!!
        assertEquals(356, ceiling)
        for (e in Periodization.Effort.entries) {
            val t = e.targetFor(ceiling)
            assertEquals(0, t % 10)
            assert(t <= ceiling) { "${e.label} target $t exceeds the ceiling $ceiling" }
        }
        assertEquals(210, Periodization.Effort.LIGHT.targetFor(ceiling))
        assertNull(Periodization.availabilityCeiling(0))
    }
}

class InjuryCycleTest {
    @Test
    fun `tapping cycles through severities and off`() {
        var s: String? = null
        s = toggleInjury(s, "Knee");      assertEquals("Knee", s)
        s = toggleInjury(s, "Knee");      assertEquals("Knee (mild)", s)
        s = toggleInjury(s, "Knee");      assertEquals("Knee (moderate)", s)
        s = toggleInjury(s, "Knee");      assertEquals("Knee (serious)", s)
        s = toggleInjury(s, "Knee");      assertNull(s)
    }

    @Test
    fun `other areas and free text survive the cycle`() {
        val s = toggleInjury("Knee (mild), old ankle sprain from 2020", "Knee")
        assertEquals("old ankle sprain from 2020, Knee (moderate)", s)
    }

    @Test
    fun `severity reads back for the chips`() {
        assertEquals("", injurySeverity("Knee", "Knee"))
        assertEquals("moderate", injurySeverity("Lower back (moderate)", "Lower back"))
        assertNull(injurySeverity("Knee", "Shoulder"))
    }
}

class AvailabilityBuildTest {
    @Test
    fun `two long days both get the long budget and the week keeps its size`() {
        val days = buildAvailability(daysPerWeek = 5, typical = 60, longDays = listOf("Sat", "Sun"), longMin = 120)
        assertEquals(5, days.size)
        assertEquals(120, days.first { it.day == "Sat" }.max_minutes)
        assertEquals(120, days.first { it.day == "Sun" }.max_minutes)
        assertEquals(60, days.first { it.day != "Sat" && it.day != "Sun" }.max_minutes)
    }

    @Test
    fun `long days round-trip through the questions model`() {
        val days = buildAvailability(4, 60, listOf("Sat", "Sun"), 120)
        val q = availabilityToQuestions(days)
        assertEquals(listOf("Sat", "Sun"), q.longDays.sorted())
        assertEquals(120, q.longMin)
    }
}
