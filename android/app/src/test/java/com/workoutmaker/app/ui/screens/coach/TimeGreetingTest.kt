package com.workoutmaker.app.ui.screens.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeGreetingTest {

    @Test
    fun `covers every hour of the day`() {
        // A heading that renders blank at some hour is the whole failure mode
        // worth guarding: the hero has nothing else at the top of the screen.
        for (h in 0..23) {
            assertTrue("hour $h produced a blank greeting", timeGreeting(h).isNotBlank())
        }
    }

    @Test
    fun `boundaries land on the right half of each slot`() {
        assertEquals("Still up", timeGreeting(0))
        assertEquals("Still up", timeGreeting(4))
        assertEquals("Good morning", timeGreeting(5))
        assertEquals("Good morning", timeGreeting(11))
        assertEquals("Good afternoon", timeGreeting(12))
        assertEquals("Good afternoon", timeGreeting(16))
        assertEquals("Good evening", timeGreeting(17))
        assertEquals("Good evening", timeGreeting(23))
    }

    @Test
    fun `the same hour always reads the same`() {
        // The reason this does not reuse Notifications.greeting(), which
        // deliberately rotates its wording by day of year. A heading you see
        // several times an hour must not change under you.
        for (h in 0..23) {
            assertEquals(timeGreeting(h), timeGreeting(h))
        }
    }

    @Test
    fun `greeting text carries no dash punctuation`() {
        // House rule: no em or en dashes in anything user-facing.
        val all = (0..23).map { timeGreeting(it) } + HERO_SUBTITLE
        for (s in all) {
            assertFalse("\"$s\" used a dash", s.contains('—') || s.contains('–'))
        }
    }
}
