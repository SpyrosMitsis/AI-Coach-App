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

    // The screen is FOR readiness and today's session, so neither can be
    // switched off: a layout that can hide both is a blank page.
    @Test
    fun `always-on cards cannot be hidden`() {
        val l = defaultHomeLayout()
        assertEquals(l, l.toggledHidden(HomeCard.READINESS))
        assertEquals(l, l.toggledHidden(HomeCard.WORKOUT))
    }

    // Always on is not the same as welded in place.
    @Test
    fun `today's workout moves even though it cannot be hidden`() {
        val l = defaultHomeLayout().moved(HomeCard.WORKOUT, up = false)
        assertEquals(HomeCard.WEEK, l.order[1])
        assertEquals(HomeCard.WORKOUT, l.order[2])
        assertTrue(HomeCard.WORKOUT in l.visible)
    }

    @Test
    fun `readiness is pinned to the top`() {
        val l = defaultHomeLayout()
        assertEquals(l.order, l.moved(HomeCard.READINESS, up = false).order)
        // And nothing can be swapped over it, so the top slot stays readiness.
        assertEquals(l.order, l.moved(HomeCard.WORKOUT, up = true).order)
        val week = l.moved(HomeCard.WEEK, up = true).moved(HomeCard.WEEK, up = true)
        assertEquals(HomeCard.READINESS, week.order[0])
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
