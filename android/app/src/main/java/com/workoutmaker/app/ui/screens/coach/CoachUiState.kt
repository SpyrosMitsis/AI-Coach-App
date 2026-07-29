package com.workoutmaker.app.ui.screens.coach

import com.workoutmaker.app.data.ChatMessage
import com.workoutmaker.app.data.CoachConversation
import com.workoutmaker.app.data.PlannedWorkout

// The seam between the coach screen and its view model.
//
// It exists so the screen can be COMPOSED IN A TEST. Every recent regression
// here was a rendering decision, not a data one: starter chips that took no
// taps because the message list covered them, a "Coach proposed this but didn't
// apply it" banner on an answer that had merely described the week. Nothing
// could reach any of it while the screen read 17 flows off a Hilt view model,
// so it was all caught by hand on the phone, twice.
//
// CoachScreen collects and wires. CoachContent renders CoachState and calls
// CoachActions, and knows nothing else. Every field defaults, so a test names
// only the two or three that its case is actually about.

data class CoachState(
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    /** The typewriter is still revealing the last reply. */
    val revealing: Boolean = false,
    val banner: String? = null,
    val liveStatus: String? = null,
    /** The single tool line shown while the coach works, or null for the dots. */
    val currentStep: CoachViewModel.ToolStep? = null,
    val actionWeek: List<PlannedWorkout>? = null,
    /** Index into [messages] the calendar result card is anchored to, or null. */
    val actionWeekAnchor: Int? = null,
    val lastAction: String? = null,
    val showReplan: Boolean = false,
    /**
     * Tools used by the CURRENT turn, or null when no turn has run in this
     * session (fresh thread, or one reopened from history). The proposal banner
     * reads both meanings: see the gate in CoachContent.
     */
    val turnTools: List<String>? = null,
    val suggestions: List<CoachStarter> = emptyList(),
    val followUps: List<CoachStarter> = emptyList(),
    val displayName: String? = null,
    val incognito: Boolean = false,
    val showHistory: Boolean = false,
    val conversations: List<CoachConversation> = emptyList(),
    /** Text from a failed send, to put back in the composer. */
    val draftRestore: String? = null,
)

data class CoachActions(
    val send: (String) -> Unit = {},
    val retryLast: () -> Unit = {},
    val dismissFollowUps: () -> Unit = {},
    val dismissActionCard: () -> Unit = {},
    val rePlanWeek: () -> Unit = {},
    val focusCalendar: (String) -> Unit = {},
    val finalize: (String) -> Unit = {},
    val toggleIncognito: () -> Unit = {},
    val openHistory: () -> Unit = {},
    val closeHistory: () -> Unit = {},
    val newChat: () -> Unit = {},
    val openConversation: (CoachConversation) -> Unit = {},
    val togglePin: (CoachConversation) -> Unit = {},
    val deleteConversation: (CoachConversation) -> Unit = {},
    /** The restored draft has been put back in the box; clear it. */
    val draftConsumed: () -> Unit = {},
    val openCalendar: () -> Unit = {},
)
