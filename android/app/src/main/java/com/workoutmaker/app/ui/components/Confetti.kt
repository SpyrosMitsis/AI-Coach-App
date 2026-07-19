package com.workoutmaker.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.ui.theme.amberAccent
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

// A one-shot confetti celebration for the end of onboarding. Hand-drawn on a
// Canvas to match the rest of the app (everything here is drawn, not imported)
// and to keep the foss flavor free of any new third-party UI dependency.
//
// Two cannons in the bottom corners fire particles up and inward; gravity takes
// over so each piece arcs, stalls, and tumbles back down. A second wave fires
// mid-run so the burst feels continuous rather than a single puff.
//
// Colors come from the active ColorScheme rather than the brand constants: six
// palettes ship, so literal Sage/Sand would clash in five of them. The
// theme-invariant rule in ChartUtil is about DATA colors; this is decoration.

private const val PARTICLE_COUNT = 140

// Must stay under OnboardingViewModel.CELEBRATION_MS: that's how long onboarding
// is held open before the app swaps in. Overrun and the burst is guillotined
// mid-fall at full opacity; finishing just early means every flake has faded to
// nothing by the time the screen changes, so the cut is invisible.
private const val DURATION_MS = 2_200

private enum class Shape { RECT, DOT, STREAMER }

private data class Flake(
    val xFrac: Float,        // launch x, 0..1 of width (a bottom corner, jittered)
    val vx: Float,           // horizontal launch velocity, widths per run
    val vy: Float,           // vertical launch velocity, heights per run (upward)
    val delay: Float,        // 0..1 of the run; the second wave starts ~0.35
    val drift: Float,        // horizontal sway amplitude, fraction of width
    val swayPhase: Float,
    val swaySpeed: Float,
    val spin: Float,         // radians per unit of progress
    val size: Float,         // dp
    val colorIndex: Int,
    val shape: Shape,
    val ratio: Float,        // height/width for RECT, length multiplier for STREAMER
)

// Gravity in heights per run². Tuned so a median launch stalls around the upper
// third of the screen and lands just past the bottom edge as the run ends.
private const val GRAVITY = 3.6f

/**
 * Draws a burst of celebration confetti while [playing] is true.
 *
 * Honors the system "remove animations" setting exactly like [BreathingBackdrop]:
 * when animations are off this draws nothing at all. Purely decorative and never
 * interactive, so it takes no pointer input.
 */
@Composable
fun Confetti(playing: Boolean, modifier: Modifier = Modifier) {
    if (!rememberAnimationsEnabled()) return

    val scheme = MaterialTheme.colorScheme
    val colors = listOf(
        scheme.primary,
        scheme.secondary,
        scheme.tertiary,
        amberAccent(),
        scheme.primaryContainer,
    )

    // Fixed seed: the layout is arbitrary but should not reshuffle on recomposition.
    val flakes = remember {
        val rnd = Random(0x5EED)
        List(PARTICLE_COUNT) { i ->
            val fromLeft = i % 2 == 0
            val secondWave = i % 3 == 2 // every third piece holds for the refill burst
            // Cannons sit in the bottom corners and fire up and inward: the left
            // one leans right, the right one leans left, so the arcs cross.
            val inward = 0.12f + rnd.nextFloat() * 0.5f
            Flake(
                xFrac = if (fromLeft) rnd.nextFloat() * 0.18f else 1f - rnd.nextFloat() * 0.18f,
                vx = if (fromLeft) inward else -inward,
                vy = 1.6f + rnd.nextFloat() * 1.5f,
                delay = (if (secondWave) 0.35f else 0f) + rnd.nextFloat() * 0.12f,
                drift = 0.015f + rnd.nextFloat() * 0.05f,
                swayPhase = rnd.nextFloat() * (2 * PI).toFloat(),
                swaySpeed = 3f + rnd.nextFloat() * 5f,
                spin = (rnd.nextFloat() - 0.5f) * 34f,
                size = 5f + rnd.nextFloat() * 6f,
                colorIndex = rnd.nextInt(5),
                shape = when (rnd.nextInt(5)) {
                    0 -> Shape.DOT
                    1 -> Shape.STREAMER
                    else -> Shape.RECT // rectangles stay the majority, like real confetti
                },
                ratio = 0.4f + rnd.nextFloat() * 0.9f,
            )
        }
    }

    val progress by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = tween(durationMillis = if (playing) DURATION_MS else 0, easing = LinearEasing),
        label = "confetti",
    )
    if (progress <= 0f) return

    Canvas(modifier.fillMaxSize()) {
        flakes.forEach { f ->
            // Stagger: each flake runs its own 0..1 over the tail of the window.
            val t = ((progress - f.delay) / (1f - f.delay)).coerceIn(0f, 1f)
            if (t <= 0f) return@forEach

            // Ballistic arc from the bottom edge: launch up, gravity pulls back.
            val y = size.height * (1.02f - f.vy * t + 0.5f * GRAVITY * t * t)
            val x = (f.xFrac + f.vx * t) * size.width +
                sin(f.swayPhase + t * f.swaySpeed) * f.drift * size.width * t

            // Fade out over the last quarter so the burst dissolves rather than cuts.
            val alpha = ((1f - t) / 0.25f).coerceIn(0f, 1f)
            val w = f.size.dp.toPx()
            val color = colors[f.colorIndex].copy(alpha = alpha)

            rotateRad(f.spin * t, pivot = Offset(x, y)) {
                when (f.shape) {
                    Shape.DOT -> drawCircle(color, radius = w * 0.42f, center = Offset(x, y))
                    Shape.STREAMER -> drawRect(
                        color = color,
                        topLeft = Offset(x - w * 0.14f, y - w * (1f + f.ratio)),
                        size = Size(w * 0.28f, w * (2f + f.ratio * 2f)),
                    )
                    Shape.RECT -> {
                        val h = w * f.ratio
                        drawRect(color, topLeft = Offset(x - w / 2f, y - h / 2f), size = Size(w, h))
                    }
                }
            }
        }
    }
}
