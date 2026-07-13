package com.workoutmaker.app.data

import kotlinx.coroutines.flow.MutableStateFlow

// Bridge between MainActivity's intent handling and the Compose UI for the
// auth email deep links (workoutmaker://auth/...). Process-scoped state is
// fine here: the links only matter for the current foreground session.
object AuthDeepLinks {
    // A recovery link landed and its session was imported: show the
    // set-new-password dialog until the user saves or dismisses.
    val recoveryPending = MutableStateFlow(false)

    // One-shot user-facing note (e.g. an expired link), shown on the login
    // screen; consumers clear it after display.
    val message = MutableStateFlow<String?>(null)
}
