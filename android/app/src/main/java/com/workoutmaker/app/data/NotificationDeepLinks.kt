package com.workoutmaker.app.data

import kotlinx.coroutines.flow.MutableStateFlow

// Bridge between MainActivity's notification-intent handling and the Compose
// UI (same pattern as AuthDeepLinks). Process-scoped one-shot state: consumers
// clear the flow after acting on it.
object NotificationDeepLinks {
    // "Open this activity's detail page" — (completed_activities id, date),
    // set when the evening debrief notification is tapped. HomeViewModel
    // resolves it and opens the detail overlay.
    val openActivity = MutableStateFlow<Pair<String, String>?>(null)
}
