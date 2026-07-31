package com.workoutmaker.app.ui.screens.onboarding

import com.workoutmaker.app.data.TrainingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gym's four questions: what they leave behind, and what the scene draws
 * from it. Pure logic, so the room can be checked without rendering it.
 */
class GymSetupTest {

    @Test
    fun `an empty room draws nothing but the figure`() {
        assertTrue(gymProps(emptyList()).isEmpty())
    }

    @Test
    fun `each ticked item puts its own prop in the room`() {
        assertEquals(setOf(GymProp.BARBELL), gymProps(listOf("Barbell")))
        assertEquals(
            setOf(GymProp.BENCH, GymProp.RACK),
            gymProps(listOf("Bench", "Squat rack")),
        )
    }

    // Someone with a gym membership is not going to tick eight chips, so the
    // switch has to mean all of it rather than being a ninth chip.
    @Test
    fun `full gym fills the room without ticking anything`() {
        assertEquals(GymProp.entries.toSet(), gymProps(listOf("Full gym")))
    }

    @Test
    fun `an unknown item from another release is ignored, not drawn`() {
        assertEquals(setOf(GymProp.BARBELL), gymProps(listOf("Barbell", "Sled")))
    }

    // Full gym and a hand-picked list are alternatives, and the rule has to hold
    // in both directions without ever losing what the athlete typed in.
    @Test
    fun `turning full gym on keeps the chips underneath it`() {
        val kit = listOf("Barbell", "Bench")
        val on = toggledGymKit(kit, "Full gym")
        assertTrue("Full gym" in on)
        // Off again and the original two are still there, not an empty room.
        assertEquals(kit, toggledGymKit(on, "Full gym"))
    }

    @Test
    fun `ticking a chip while full gym is on means a specific room`() {
        val on = listOf("Full gym")
        val picked = toggledGymKit(on, "Dumbbells")
        assertEquals(listOf("Dumbbells"), picked)
    }

    @Test
    fun `unticking the last chip does not silently mean a full gym`() {
        assertEquals(emptyList<String>(), toggledGymKit(listOf("Barbell"), "Barbell"))
    }

    @Test
    fun `the summary reads as a sentence about the athlete, in answer order`() {
        val p = TrainingProfile(
            goals_by_sport = mapOf(GYM to listOf("Get stronger")),
            experience_by_sport = mapOf(GYM to "Intermediate"),
            split_style = "Upper / lower",
            equipment_list = listOf("Full gym"),
        )
        assertEquals("Get stronger  ·  Intermediate  ·  Upper / lower  ·  Full gym", gymSummary(p))
    }

    @Test
    fun `an unanswered gym summarizes to nothing rather than to empty separators`() {
        assertEquals("", gymSummary(TrainingProfile()))
    }

    // "Auto" is the absence of a preference, so saying it back would be noise.
    @Test
    fun `the summary counts loose kit and stays quiet about auto`() {
        val p = TrainingProfile(
            experience_by_sport = mapOf(GYM to "Beginner"),
            split_style = null,
            equipment_list = listOf("Barbell", "Bench"),
        )
        assertEquals("Beginner  ·  2 pieces of kit", gymSummary(p))
        assertEquals(
            "Beginner  ·  1 piece of kit",
            gymSummary(p.copy(equipment_list = listOf("Barbell"))),
        )
    }

    @Test
    fun `every goal, split and level carries the sentence that explains it`() {
        listOf("Build muscle", "Get stronger", "Lose fat", "Body recomposition", "General fitness")
            .forEach { assertTrue("$it needs a blurb", gymGoalBlurb(it).isNotBlank()) }
        listOf("Auto", "Full body", "Upper / lower", "Push / pull / legs")
            .forEach { assertTrue("$it needs a blurb", gymSplitBlurb(it).isNotBlank()) }
        gymLevels().forEach { assertTrue("$it needs a blurb", gymLevelBlurb(it).isNotBlank()) }
    }

    // "Auto" is what the profile stores as null; the athlete is offered a phrase
    // that says what it does rather than a word that sounds like a setting.
    @Test
    fun `auto is offered as a sentence, the rest keep their own names`() {
        assertEquals("Let me choose", gymSplitName("Auto"))
        assertEquals("Full body", gymSplitName("Full body"))
    }
}

/** The gym block's place in the flow. */
class GymStepsTest {

    private fun kinds(p: TrainingProfile) = visibleSteps(p).map { it.kind }

    @Test
    fun `picking the gym adds its four questions, in order and together`() {
        val steps = kinds(TrainingProfile(sports = listOf("strength")))
        val gym = steps.filter { it.name.startsWith("GYM_") || it == ObStep.EQUIPMENT }
        assertEquals(
            listOf(ObStep.GYM_GOALS, ObStep.GYM_LEVEL, ObStep.GYM_SPLIT, ObStep.EQUIPMENT),
            gym,
        )
        // Contiguous: the kit used to sit after the whole week, which made the
        // gym two conversations with everything else in between.
        val first = steps.indexOf(ObStep.GYM_GOALS)
        assertEquals(gym, steps.subList(first, first + 4))
    }

    @Test
    fun `an athlete who does not lift is never asked about kit`() {
        val steps = kinds(TrainingProfile(sports = listOf("run")))
        assertTrue(steps.none { it == ObStep.EQUIPMENT || it.name.startsWith("GYM_") })
        // And still gets the one-screen distance question for the sport they do.
        assertTrue(ObStep.ACTIVITY in steps)
    }

    @Test
    fun `the gym replaces its ACTIVITY step rather than adding to it`() {
        val steps = visibleSteps(TrainingProfile(sports = listOf("run", "strength")))
        assertEquals(listOf("run"), steps.filter { it.kind == ObStep.ACTIVITY }.map { it.sport })
    }

    @Test
    fun `every step in the flow has a screen behind it`() {
        val steps = kinds(TrainingProfile(sports = listOf("run", "strength")))
        assertEquals(steps.distinct().size, steps.distinct().size)
        assertTrue(steps.first() == ObStep.WELCOME)
        assertTrue(steps.last() == ObStep.REVIEW)
    }
}
