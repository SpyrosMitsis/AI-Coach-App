package com.workoutmaker.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.R

// The brand mark: the monochrome logo asset, tinted to the active palette's
// primary so it adapts to all six palettes and both modes, with a gentle
// breathing scale. Mirrored statically in res/drawable/ic_splash_logo.xml and
// res/drawable/ic_launcher_foreground.xml.
@Composable
fun LogoMark(modifier: Modifier = Modifier, size: Dp = 72.dp, animate: Boolean = true) {
    val color = MaterialTheme.colorScheme.primary
    val pulse = if (animate) {
        val transition = rememberInfiniteTransition(label = "logoPulse")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(4_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulse",
        )
        p
    } else 0.5f

    Image(
        painter = painterResource(R.drawable.ic_logo_monochrome),
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val scale = 0.92f + 0.08f * pulse
                scaleX = scale
                scaleY = scale
            },
    )
}
