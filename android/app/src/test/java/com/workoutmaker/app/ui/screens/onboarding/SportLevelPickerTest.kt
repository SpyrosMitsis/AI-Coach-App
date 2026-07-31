package com.workoutmaker.app.ui.screens.onboarding

import com.workoutmaker.app.ui.screens.settings.SPORTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The level question, now asked the same way for every sport. The slider is
 * only honest if every rung it can land on has a sentence explaining it, so
 * that is what is checked here: the table and the rungs cannot drift apart.
 */
class SportLevelPickerTest {

    @Test
    fun `every sport's every rung has a blurb`() {
        SPORTS.forEach { sport ->
            val levels = sportLevels(sport)
            assertTrue("$sport has no rungs", levels.isNotEmpty())
            levels.forEach { lvl ->
                assertTrue("$sport / $lvl needs a blurb", sportLevelBlurb(sport, lvl).isNotBlank())
            }
        }
    }

    // Running starts a rung lower than the rest ("Never ran"), which is the
    // whole reason the rungs are per sport rather than one shared list.
    @Test
    fun `the rungs are the sport's own`() {
        assertEquals("Never ran", sportLevels("run").first())
        assertEquals(4, sportLevels("strength").size)
        assertEquals(3, sportLevels("swim").size)
    }

    // A sport with no table of its own still gets a working slider, just
    // without the explanation, rather than crashing on an empty list.
    @Test
    fun `an unknown sport falls back to the generic rungs`() {
        assertEquals(listOf("Beginner", "Intermediate", "Advanced"), sportLevels("rowing"))
        assertEquals("", sportLevelBlurb("rowing", "Beginner"))
    }

    // The gym's own wording is unchanged: this was a move, not a rewrite.
    @Test
    fun `the gym keeps the blurbs it already had`() {
        gymLevels().forEach { assertEquals(gymLevelBlurb(it), sportLevelBlurb(GYM, it)) }
    }
}
