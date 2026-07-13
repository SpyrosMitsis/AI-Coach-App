package com.workoutmaker.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The brand mark: two open concentric arcs (a rep in progress) around a pulse
// dot that breathes. Drawn in the active palette's primary so it adapts to all
// six palettes and both modes. Mirrored statically in res/drawable/ic_splash_logo.xml.
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

    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = s * 0.075f
        val center = Offset(s / 2f, s / 2f)

        // Outer arc: open at the top-right, sweeping most of the circle.
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(s - stroke, s - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // Inner arc: opens the other way, offset phase.
        val inset = s * 0.22f
        drawArc(
            color = color.copy(alpha = 0.55f),
            startAngle = 140f,
            sweepAngle = 250f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(s - inset * 2f, s - inset * 2f),
            style = Stroke(width = stroke * 0.8f, cap = StrokeCap.Round),
        )
        // The pulse dot, breathing between 55% and 100% of its size.
        drawCircle(
            color = color,
            radius = s * 0.075f * (0.55f + 0.45f * pulse),
            center = center,
        )
    }
}
