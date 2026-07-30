package com.workoutmaker.app.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {

    @Test
    fun `a fresh install shows every card, readiness and the workout first`() {
        val l = defaultHomeLayout()
        assertEquals(HomeCard.entries, l.visible)
        assertEquals(HomeCard.READINESS, l.visible[0])
        assertEquals(HomeCard.WORKOUT, l.visible[1])
    }

    @Test
    fun `hiding a card drops it from home but keeps its place in the order`() {
        val l = defaultHomeLayout().toggledHidden(HomeCard.GOAL)
        assertTrue(HomeCard.GOAL !in l.visible)
        assertTrue(HomeCard.GOAL in l.order)
        // And it comes back exactly where it was, not at the bottom.
        val back = l.toggledHidden(HomeCard.GOAL)
        assertEquals(defaultHomeLayout().visible, back.visible)
    }

    // The screen is FOR readiness and today's session. A layout that can hide
    // both is a blank page, so the two pinned cards ignore every edit.
    @Test
    fun `pinned cards cannot be hidden or moved`() {
        val l = defaultHomeLayout()
        assertEquals(l, l.toggledHidden(HomeCard.READINESS))
        assertEquals(l, l.toggledHidden(HomeCard.WORKOUT))
        assertEquals(l.order, l.moved(HomeCard.READINESS, up = false).order)
        assertEquals(l.order, l.moved(HomeCard.WORKOUT, up = true).order)
    }

    @Test
    fun `a movable card cannot be pushed above the pinned block`() {
        val l = defaultHomeLayout()
        // WEEK sits directly under the two pinned cards, so up is a no-op.
        assertEquals(HomeCard.WEEK, l.order[2])
        assertEquals(l.order, l.moved(HomeCard.WEEK, up = true).order)
    }

    @Test
    fun `moving swaps with the neighbour and stops at the ends`() {
        val l = defaultHomeLayout()
        val moved = l.moved(HomeCard.GOAL, up = true)
        assertEquals(HomeCard.GOAL, moved.order[2])
        assertEquals(HomeCard.WEEK, moved.order[3])
        // The last card has nowhere to go.
        val last = l.order.last()
        assertEquals(l.order, l.moved(last, up = false).order)
    }

    @Test
    fun `a layout round-trips through the two strings it is stored as`() {
        val l = defaultHomeLayout().moved(HomeCard.FITNESS, up = true).toggledHidden(HomeCard.WELLNESS)
        val back = homeLayoutFrom(l.orderCsv, l.hiddenCsv)
        assertEquals(l.order, back.order)
        assertEquals(l.hidden, back.hidden)
    }

    // Forgiving in both directions: an athlete who updates the app must not lose
    // their order to a card they have never seen, or to one we stopped shipping.
    @Test
    fun `a card added in a later release is appended, not lost`() {
        val partial = homeLayoutFrom("readiness,workout", "")
        assertEquals(HomeCard.entries.size, partial.order.size)
        assertEquals(HomeCard.READINESS, partial.order[0])
        assertEquals(HomeCard.WORKOUT, partial.order[1])
    }

    @Test
    fun `unknown and duplicate keys are ignored rather than breaking the layout`() {
        val l = homeLayoutFrom("goal,nonsense,goal,workout", "nonsense,goal")
        assertEquals(HomeCard.entries.size, l.order.size)
        assertEquals(HomeCard.GOAL, l.order[0])
        assertEquals(setOf(HomeCard.GOAL), l.hidden)
    }

    @Test
    fun `a stale preference that hides a pinned card is not honoured`() {
        // "readiness" was hideable before it was pinned; the old value must not
        // be able to blank the screen after an update.
        val l = homeLayoutFrom("", "readiness,workout,goal")
        assertEquals(setOf(HomeCard.GOAL), l.hidden)
        assertTrue(HomeCard.READINESS in l.visible)
        assertTrue(HomeCard.WORKOUT in l.visible)
    }

    @Test
    fun `nothing stored at all still gives a usable home`() {
        val l = homeLayoutFrom(null, null)
        assertEquals(defaultHomeLayout().order, l.order)
        assertTrue(l.hidden.isEmpty())
    }
}
