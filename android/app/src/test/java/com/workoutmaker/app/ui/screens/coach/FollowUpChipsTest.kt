package com.workoutmaker.app.ui.screens.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpChipsTest {

    @Test
    fun `a plain answer gets no chips`() {
        // Reads only: chips after every message would be noise.
        assertEquals(emptyList<CoachStarter>(), followUpChips(listOf("get_fitness", "get_planned_week")))
    }

    @Test
    fun `a generated workout offers easier, move and why`() {
        val labels = followUpChips(listOf("get_readiness", "generate_workout")).map { it.label }
        assertEquals(listOf("Make it easier", "Move it", "Why this session?"), labels)
    }

    @Test
    fun `a planned week offers the week follow-ups`() {
        val labels = followUpChips(listOf("plan_week")).map { it.label }
        assertTrue("Explain the week" in labels)
    }

    @Test
    fun `plan_week wins over generate_workout when both ran`() {
        // A week plan is the bigger action; its follow-ups cover the day too.
        val labels = followUpChips(listOf("generate_workout", "plan_week")).map { it.label }
        assertTrue("Explain the week" in labels)
    }

    @Test
    fun `every chip prompt is directive, not a question back`() {
        // House philosophy: chips finish the job in one turn.
        val all = listOf("generate_workout", "plan_week", "set_goal_race", "move_workout")
            .flatMap { followUpChips(listOf(it)) }
        for (c in all) assertTrue("${c.label}: prompt too short to be directive", c.prompt.length > 60)
    }
}
