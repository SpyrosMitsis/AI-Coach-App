package com.workoutmaker.app.ui.screens.onboarding

import com.workoutmaker.app.data.InjuryEntry
import com.workoutmaker.app.data.Periodization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import com.workoutmaker.app.ui.screens.settings.availabilityToQuestions
import com.workoutmaker.app.ui.screens.settings.buildAvailability
import com.workoutmaker.app.ui.screens.settings.parseTimeSeconds
import com.workoutmaker.app.ui.screens.settings.thresholdPaceFromRace

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
    fun `area chip adds unqualified and removes regardless of severity`() {
        var s = emptyList<InjuryEntry>()
        s = toggleInjury(s, "Knee");      assertEquals(listOf(InjuryEntry(area = "Knee")), s)
        s = toggleInjury(s, "Knee");      assertEquals(emptyList<InjuryEntry>(), s)
        assertEquals(emptyList<InjuryEntry>(), toggleInjury(listOf(InjuryEntry("Knee", "serious")), "Knee"))
    }

    @Test
    fun `severity chips set, change and clear the qualifier`() {
        var s = listOf(InjuryEntry(area = "Knee"))
        s = setInjurySeverity(s, "Knee", "mild");     assertEquals("mild", injurySeverity(s, "Knee"))
        s = setInjurySeverity(s, "Knee", "serious");  assertEquals("serious", injurySeverity(s, "Knee"))
        s = setInjurySeverity(s, "Knee", "");         assertEquals("", injurySeverity(s, "Knee"))
    }

    @Test
    fun `other areas and free notes survive edits`() {
        val base = listOf(InjuryEntry("Knee", "mild"), InjuryEntry(area = "", note = "old ankle sprain from 2020"))
        val s = setInjurySeverity(base, "Knee", "moderate")
        assertEquals("old ankle sprain from 2020", injuryNote(s))
        assertEquals("moderate", injurySeverity(s, "Knee"))
        val afterRemove = toggleInjury(base, "Knee")
        assertEquals("old ankle sprain from 2020", injuryNote(afterRemove))
        assertNull(injurySeverity(afterRemove, "Knee"))
    }

    @Test
    fun `severity reads back for the chips`() {
        assertEquals("", injurySeverity(listOf(InjuryEntry(area = "Knee")), "Knee"))
        assertEquals("moderate", injurySeverity(listOf(InjuryEntry("Lower back", "moderate")), "Lower back"))
        assertNull(injurySeverity(listOf(InjuryEntry(area = "Knee")), "Shoulder"))
    }

    @Test
    fun `notes round-trip through withNote and injuryNote`() {
        var s = listOf(InjuryEntry(area = "Knee"))
        s = withNote(s, "twisted it hiking")
        assertEquals("twisted it hiking", injuryNote(s))
        assertEquals("Knee; twisted it hiking", injuriesSummary(s))
        s = withNote(s, "")
        assertEquals("", injuryNote(s))
        assertEquals(1, s.size)
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

    // Trello #78: athlete can pin the exact weekdays instead of the auto-spread.
    @Test
    fun `an explicit day pick with the right count is honored over the auto-spread`() {
        val days = buildAvailability(
            daysPerWeek = 3, typical = 60, longDays = emptyList(), longMin = 120,
            explicitDays = listOf("Tue", "Thu", "Sat"),
        )
        assertEquals(listOf("Sat", "Thu", "Tue"), days.map { it.day }.sorted())
    }

    @Test
    fun `a long day is still forced in even with an explicit pick`() {
        val days = buildAvailability(
            daysPerWeek = 3, typical = 60, longDays = listOf("Sun"), longMin = 120,
            explicitDays = listOf("Tue", "Thu", "Sat"), // doesn't include the long day
        )
        assertEquals(3, days.size)
        assertEquals(120, days.first { it.day == "Sun" }.max_minutes)
    }

    @Test
    fun `an explicit pick with the wrong count falls back to auto-spread`() {
        val withPick = buildAvailability(
            daysPerWeek = 4, typical = 60, longDays = emptyList(), longMin = 120,
            explicitDays = listOf("Tue", "Thu"), // only 2, not 4
        )
        val withoutPick = buildAvailability(daysPerWeek = 4, typical = 60, longDays = emptyList(), longMin = 120)
        assertEquals(withoutPick.map { it.day }, withPick.map { it.day })
    }

    // THE REGRESSION TEST. WeeklyAvailabilityEditor opens every chip
    // pre-selected from availabilityToQuestions(emptyList()), but only pushed
    // on a chip tap, so agreeing with the defaults left day_availability empty.
    // availabilityCeiling(0) is null by design, which made the next onboarding
    // step ("How hard to go") render an empty page. The editor now commits the
    // seed on first composition; this pins the invariant that seed has to
    // satisfy, independently of the composable.
    @Test
    fun `the editor's default answers describe a real, plannable week`() {
        val seed = availabilityToQuestions(emptyList())
        val days = buildAvailability(seed.daysPerWeek, seed.typicalMin, seed.longDays, seed.longMin)

        assert(days.isNotEmpty()) { "the pre-selected defaults must produce a week" }
        assertEquals(seed.daysPerWeek, days.size)

        val minutes = days.sumOf { it.max_minutes }
        val ceiling = Periodization.availabilityCeiling(minutes)
        assertNotNull("effort options must be priceable from the defaults", ceiling)
        // Non-null is the load-bearing part; this pins the actual week too, so a
        // change to the defaults is a deliberate edit rather than a surprise.
        assertEquals(4 * 60, minutes)
        assertEquals(211, ceiling)
    }
}
