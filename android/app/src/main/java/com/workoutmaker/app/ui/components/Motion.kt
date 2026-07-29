package com.workoutmaker.app.ui.components

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// True unless the user has turned animations off system-wide (Settings >
// Accessibility > Remove animations, or Developer options). Decorative motion
// sits this preference out instead of fighting it; functional transitions can
// keep running. Read once per composition: changing it restarts the process.
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }
}
