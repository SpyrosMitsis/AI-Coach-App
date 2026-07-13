package com.workoutmaker.app.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App-wide transient confirmation surface. One [SnackbarHostState] lives in
 * MainScaffold; any screen under it grabs [LocalAppSnackbar] and calls [show] to
 * confirm an action ("✓ Marked done") instead of leaving an easy-to-miss inline
 * status line. A new message replaces whatever is on screen so confirmations
 * don't pile up.
 */
class AppSnackbar(
    private val host: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    fun show(message: String) {
        if (message.isBlank()) return
        scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
}

val LocalAppSnackbar = staticCompositionLocalOf<AppSnackbar?> { null }
