package com.workoutmaker.app.ui.screens.home

// ===========================================================================
// Which cards Home shows, and in what order.
//
// Home was a fixed stack: everyone got the same seven cards in the same order,
// whether or not they cared about any given one. An athlete with no race has a
// dead Goal card forever; someone who never checks CTL scrolls past Fitness
// every morning. So the order is the athlete's, and anything that is not
// load-bearing can be switched off.
//
// Two questions, not one. "Can it move?" and "can it be switched off?" used to
// be a single `pinned` flag, which forced today's workout to hold second place
// forever just because it must never disappear. So they are separate now:
//
//   READINESS is pinned to the top and always on. It is the thing the screen
//   is for, and the reading everything below it is judged against.
//   WORKOUT is always on but moves freely. Someone who wants their week or
//   their goal above the session can have it; a Home with no session at all is
//   still a blank page.
//
// Everything here is pure and stored as two strings, so the whole layout can be
// tested without a phone and a corrupt or stale preference degrades to the
// default rather than to an empty screen.
// ===========================================================================

enum class HomeCard(
    val key: String,
    val title: String,
    val blurb: String,
    /** Can the athlete reorder it? False = welded to its slot. */
    val movable: Boolean = true,
    /** Load-bearing: the switch that would hide it is not offered. */
    val alwaysOn: Boolean = false,
) {
    READINESS("readiness", "Readiness", "Ring, drivers, HRV, sleep and load", movable = false, alwaysOn = true),
    WORKOUT("workout", "Today's workout", "Session, coach note, log and skip", alwaysOn = true),
    WEEK("week", "This week", "Planned against done, and the coach's read"),
    GOAL("goal", "Goal", "Race countdown and phase"),
    WELLNESS("wellness", "Wellness check-in", "Energy and soreness, mornings only"),
    DEBRIEF("debrief", "Session debrief", "How the last session actually went"),
    FITNESS("fitness", "Fitness", "CTL, ATL and form, plus recent activities"),
    ;

    companion object {
        fun byKey(key: String): HomeCard? = entries.firstOrNull { it.key == key }
    }
}

/** The stored layout: an order, and the set of keys switched off. */
data class HomeLayout(
    val order: List<HomeCard> = HomeCard.entries.toList(),
    val hidden: Set<HomeCard> = emptySet(),
) {
    /** What Home actually draws, top to bottom. Always-on cards never fall out. */
    val visible: List<HomeCard> get() = order.filter { it.alwaysOn || it !in hidden }

    val orderCsv: String get() = order.joinToString(",") { it.key }
    val hiddenCsv: String get() = hidden.joinToString(",") { it.key }
}

/**
 * Rebuild a layout from what was stored.
 *
 * Deliberately forgiving in both directions: a key we no longer ship is
 * dropped, and a card added in a later release is appended rather than
 * silently missing. An athlete who updates the app should find the new card at
 * the bottom of their own order, not lose their order to a reset.
 */
fun homeLayoutFrom(orderCsv: String?, hiddenCsv: String?): HomeLayout {
    val stored = orderCsv.orEmpty().split(",").mapNotNull { HomeCard.byKey(it.trim()) }.distinct()
    val order = stored + HomeCard.entries.filterNot { it in stored }
    val hidden = hiddenCsv.orEmpty().split(",").mapNotNull { HomeCard.byKey(it.trim()) }
        // An always-on card in the hidden set is a stale preference from before
        // it was load-bearing, or a corrupt one. Either way it is not honoured.
        .filterNot { it.alwaysOn }
        .toSet()
    return HomeLayout(order = order, hidden = hidden)
}

/**
 * Move a card one slot up or down.
 *
 * A pinned card neither moves nor gets displaced, so nothing can be swapped
 * above readiness: the swap simply stops there.
 */
fun HomeLayout.moved(card: HomeCard, up: Boolean): HomeLayout {
    if (!card.movable) return this
    val list = order.toMutableList()
    val from = list.indexOf(card)
    if (from < 0) return this
    val to = if (up) from - 1 else from + 1
    if (to !in list.indices) return this
    if (!list[to].movable) return this
    list[from] = list[to]
    list[to] = card
    return copy(order = list)
}

/** Switch a card off, or back on. Always-on cards ignore this entirely. */
fun HomeLayout.toggledHidden(card: HomeCard): HomeLayout {
    if (card.alwaysOn) return this
    return copy(hidden = if (card in hidden) hidden - card else hidden + card)
}

/** Back to the order and visibility a fresh install has. */
fun defaultHomeLayout(): HomeLayout = HomeLayout()
