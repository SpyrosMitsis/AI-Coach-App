package com.workoutmaker.app.ui.screens.settings

import com.workoutmaker.app.data.Periodization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The phase strip on the goal sheet's last step is a promise about what the
 * coach will plan, so the only thing worth testing is that it cannot promise
 * a different shape than `Periodization.phaseFor` will actually name.
 */
class AddGoalSheetTest {

    @Test
    fun `the weeks add up to the countdown`() {
        (0..40).forEach { w ->
            assertEquals("$w weeks", w, goalPhaseWeeks(w).sumOf { it.second })
        }
    }

    // The strip is drawn as weighted bars, and a zero-width bar is a sliver of
    // colour with a label nobody can read.
    @Test
    fun `phases with no weeks are left out entirely`() {
        assertTrue(goalPhaseWeeks(0).isEmpty())
        assertEquals(listOf("TAPER" to 2), goalPhaseWeeks(2))
        assertEquals(listOf("PEAK" to 1, "TAPER" to 2), goalPhaseWeeks(3))
        assertTrue(goalPhaseWeeks(10).none { it.first == "BASE" })
    }

    @Test
    fun `a full block is base, build, peak and taper in that order`() {
        assertEquals(
            listOf("BASE" to 6, "BUILD" to 8, "PEAK" to 4, "TAPER" to 2),
            goalPhaseWeeks(20),
        )
    }

    // The real invariant: whatever the strip calls the FIRST phase is the phase
    // Periodization (and, through it, the server's trainingPhase) names for a
    // goal that far out. If either side's bands move, this fails.
    @Test
    fun `the first slice is the phase the coach would name today`() {
        val today = LocalDate.of(2026, 1, 5)
        (1..30).forEach { w ->
            val named = Periodization.phaseFor(today.plusWeeks(w.toLong()), today).name
            val drawn = goalPhaseWeeks(w).first().first
            assertEquals("$w weeks out", named.uppercase(), drawn)
        }
    }

    // Four questions for everyone: the sheet says "STEP n OF 4" and a sport
    // that quietly had three would be lying on every screen.
    @Test
    fun `every sport is asked four questions`() {
        (SPORTS + "other").forEach { sport ->
            val steps = goalStepsFor(sport)
            assertEquals("$sport", 4, steps.size)
            assertEquals(GoalStep.EVENT, steps.first())
            assertEquals(GoalStep.DATE, steps.last())
        }
    }

    // Which second question depends on whether the sport covers ground. The gym
    // has no line to drag; a marathon has no top set.
    @Test
    fun `the second question is the one the sport can answer`() {
        assertEquals(GoalStep.DISTANCE, goalStepsFor("run")[1])
        assertEquals(GoalStep.DISTANCE, goalStepsFor("swim")[1])
        assertEquals(GoalStep.TARGET, goalStepsFor("strength")[1])
        assertEquals(GoalStep.TARGET, goalStepsFor("other")[1])
    }

    // The target step is only honest if every kind it offers has an example of
    // the answer it wants, since the words typed there are the whole of what
    // the coach learns about a non-endurance goal.
    @Test
    fun `every event kind has a target example`() {
        listOf("strength", "other").forEach { sport ->
            val kinds = goalEventKinds(sport)
            assertTrue("$sport has no kinds", kinds.isNotEmpty())
            assertTrue("$sport needs an escape hatch", kinds.contains("Something else"))
            kinds.forEach { k ->
                assertTrue("$sport / $k", goalTargetPlaceholder(sport, k).isNotBlank())
            }
        }
    }
}
