package com.workoutmaker.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawBehind

// The login/onboarding signature: three enormous soft color washes that
// inhale and exhale on mismatched slow periods, like breath. Radial-gradient
// falloff does the "blur", so it renders identically from API 26 up (no
// RenderEffect). Colors come from the active palette's containers, which sit
// mid-tone in both light and dark mode of all six palettes.
@Composable
fun BreathingBackdrop(modifier: Modifier = Modifier, intensity: Float = 1f) {
    val scheme = MaterialTheme.colorScheme
    // Derive dark/light from the actual scheme, not the system flag, so the
    // in-app theme override is respected.
    val dark = scheme.background.luminance() < 0.5f
    val base = (if (dark) 0.30f else 0.45f) * intensity

    val context = LocalContext.current
    val animationsOn = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    // Co-prime periods so the composition never visibly repeats.
    val transition = rememberInfiniteTransition(label = "breath")
    val b1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "blob1",
    )
    val b2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "blob2",
    )
    val b3 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(23_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "blob3",
    )

    fun DrawScope.blob(color: Color, center: Offset, radius: Float, phase: Float) {
        val p = if (animationsOn) phase else 0.5f
        val r = radius * (0.92f + 0.16f * p)
        val a = base * (0.6f + 0.4f * p)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = a), color.copy(alpha = 0f)),
                center = center,
                radius = r,
            ),
            radius = r,
            center = center,
        )
    }

    androidx.compose.foundation.layout.Box(
        modifier.drawBehind {
            val w = size.width
            val h = size.height
            // Off-edge placement keeps the center column legible.
            blob(scheme.primaryContainer, Offset(w * 0.10f, h * 0.12f), w * 0.85f, b1)
            blob(scheme.secondaryContainer, Offset(w * 1.05f, h * 0.45f), w * 0.65f, b2)
            blob(scheme.tertiaryContainer, Offset(w * 0.25f, h * 1.02f), w * 0.55f, b3)
        },
    )
}
