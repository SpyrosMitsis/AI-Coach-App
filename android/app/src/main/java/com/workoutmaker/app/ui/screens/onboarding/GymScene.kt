package com.workoutmaker.app.ui.screens.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.ui.components.rememberAnimationsEnabled

// ===========================================================================
// The gym you are describing, drawn as you describe it.
//
// A figure stands on a floor line, and every piece of kit the athlete ticks
// puts a piece of equipment in the room around it. The point is not decoration:
// a chip row can tell you that "Squat rack" is selected, but it cannot tell you
// that the gym you have just described is one dumbbell in an empty room. The
// scene answers "what did I actually just build" at a glance.
//
// Drawn against a fixed 360x200 design grid and scaled to whatever width it
// gets, so the props keep their relative positions on every screen size.
// ===========================================================================

private const val GRID_W = 360f
private const val GRID_H = 200f

/** One piece of kit, and whether the room currently contains it. */
internal enum class GymProp(val equipment: String) {
    MACHINES("Machines"),
    PULLUP("Pull-up bar"),
    RACK("Squat rack"),
    BENCH("Bench"),
    BANDS("Resistance bands"),
    KETTLEBELLS("Kettlebells"),
    DUMBBELLS("Dumbbells"),
    BARBELL("Barbell"),
}

/**
 * What the room contains. "Full gym" means all of it, which is the honest
 * reading: someone with a gym membership is not going to tick eight chips.
 */
internal fun gymProps(equipment: List<String>): Set<GymProp> =
    if (equipment.contains(FULL_GYM)) GymProp.entries.toSet()
    else GymProp.entries.filter { it.equipment in equipment }.toSet()

internal const val FULL_GYM = "Full gym"

/**
 * The scene, plus the one-line summary of what has been chosen so far.
 *
 * [caption] is what the room adds up to in words ("Get stronger · Intermediate
 * · Full gym"). It is the accessible version of the picture, and the thing that
 * actually gets read on a screen the athlete is scrolling past.
 */
@Composable
internal fun GymSceneCard(
    equipment: List<String>,
    modifier: Modifier = Modifier,
    caption: String? = null,
    height: androidx.compose.ui.unit.Dp = 178.dp,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        GymScene(equipment, Modifier.fillMaxWidth().height(height))
        if (!caption.isNullOrBlank()) {
            Text(
                caption,
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun GymScene(equipment: List<String>, modifier: Modifier = Modifier) {
    val present = gymProps(equipment)
    val animate = rememberAnimationsEnabled()

    // One growth value per prop, so a piece that has just been ticked springs in
    // from the floor rather than blinking into existence. Kept per-prop (not one
    // shared transition) so removing the bench does not re-animate the barbell.
    val grow = GymProp.entries.associateWith { prop ->
        animateFloatAsState(
            targetValue = if (prop in present) 1f else 0f,
            animationSpec = if (animate) {
                // Overshoot, so it lands with a little weight to it.
                tween(300, easing = CubicBezierEasing(0.34f, 1.4f, 0.64f, 1f))
            } else {
                tween(0)
            },
            label = prop.name,
        ).value
    }

    // The figure breathes. Two pixels, slowly: enough that the screen is alive
    // while you read it, not enough to pull the eye off the question.
    val breathAnim by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    val breath = if (animate) breathAnim else 0f

    val figure = MaterialTheme.colorScheme.primary
    // Two prop tones, both derived from primary so the room reads as one
    // material and stays legible in light mode (raw brand greys wash out).
    val propBack = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val propFront = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    val propFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val floor = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier) {
        val s = minOf(size.width / GRID_W, size.height / GRID_H)
        val dx = (size.width - GRID_W * s) / 2f
        val dy = (size.height - GRID_H * s) / 2f
        translate(dx, dy) {
            scale(s, s, pivot = Offset.Zero) {
                fun prop(p: GymProp, body: DrawScope.() -> Unit) {
                    val g = grow[p] ?: 0f
                    if (g <= 0.001f) return
                    // Grown from the floor, not from the middle: kit stands on
                    // the ground, so that is where it comes up from.
                    scale(g.coerceAtMost(1.2f), g.coerceAtMost(1.2f), pivot = Offset(GRID_W / 2f, 171f)) {
                        body()
                    }
                }

                prop(GymProp.MACHINES) { drawMachines(propBack, propFill) }
                prop(GymProp.PULLUP) { drawPullupBar(propBack, propFront) }
                prop(GymProp.RACK) { drawRack(propBack, propFront) }
                prop(GymProp.BENCH) { drawBench(propBack, propFront) }
                prop(GymProp.BANDS) { drawBands(propBack, propFront) }

                // The floor, drawn between the background kit and the figure, so
                // the room has depth without any of it needing a z-index.
                drawLine(floor, Offset(14f, 169.5f), Offset(346f, 169.5f), 3f, StrokeCap.Round)

                translate(0f, -1.5f * breath) { drawFigure(figure) }

                prop(GymProp.KETTLEBELLS) { drawKettlebells(propBack, propFront) }
                prop(GymProp.DUMBBELLS) { drawDumbbells(propFront) }
                prop(GymProp.BARBELL) { drawBarbell(propBack, propFront) }
            }
        }
    }
}

// --- The figure ------------------------------------------------------------

private fun DrawScope.drawFigure(c: Color) {
    drawCircle(c, radius = 12f, center = Offset(180f, 74f))
    drawLine(c, Offset(180f, 88f), Offset(180f, 124f), 10f, StrokeCap.Round)
    drawLine(c, Offset(180f, 97f), Offset(160f, 119f), 8f, StrokeCap.Round)
    drawLine(c, Offset(180f, 97f), Offset(200f, 119f), 8f, StrokeCap.Round)
    drawLine(c, Offset(180f, 123f), Offset(167f, 166f), 9f, StrokeCap.Round)
    drawLine(c, Offset(180f, 123f), Offset(193f, 166f), 9f, StrokeCap.Round)
}

// --- The kit ---------------------------------------------------------------
//
// Each prop occupies its own patch of the grid, so any combination of them
// composes without overlapping: machines far left, rack and pull-up bar
// overhead and centre, bench right, bands far right, the loose weights on the
// floor. That layout is what lets "everything on" still read as a room.

private fun DrawScope.drawMachines(stroke: Color, fill: Color) {
    drawRoundRect(fill, Offset(22f, 74f), Size(54f, 94f), radius(6f))
    drawRoundRect(stroke, Offset(22f, 74f), Size(54f, 94f), radius(6f), style = Stroke(3f))
    drawCircle(stroke, radius = 7f, center = Offset(49f, 90f), style = Stroke(3f))
    // The weight stack, which is what makes it read as a machine and not a box.
    listOf(112f, 124f, 136f).forEach { y ->
        drawRoundRect(stroke, Offset(33f, y), Size(32f, 7f), radius(3f))
    }
}

private fun DrawScope.drawPullupBar(post: Color, bar: Color) {
    drawRoundRect(post, Offset(116f, 20f), Size(9f, 20f), radius(3f))
    drawRoundRect(post, Offset(235f, 20f), Size(9f, 20f), radius(3f))
    drawRoundRect(bar, Offset(116f, 30f), Size(128f, 7f), radius(3.5f))
}

private fun DrawScope.drawRack(post: Color, bar: Color) {
    drawRoundRect(post, Offset(124f, 58f), Size(9f, 110f), radius(3f))
    drawRoundRect(post, Offset(227f, 58f), Size(9f, 110f), radius(3f))
    drawRoundRect(bar, Offset(124f, 70f), Size(112f, 7f), radius(3.5f))
}

private fun DrawScope.drawBench(leg: Color, pad: Color) {
    drawRoundRect(pad, Offset(250f, 136f), Size(78f, 9f), radius(4.5f))
    drawRoundRect(leg, Offset(257f, 145f), Size(7f, 23f), radius(3f))
    drawRoundRect(leg, Offset(314f, 145f), Size(7f, 23f), radius(3f))
}

private fun DrawScope.drawBands(anchor: Color, band: Color) {
    drawRoundRect(anchor, Offset(330f, 54f), Size(14f, 7f), radius(3.5f))
    drawOval(band, Offset(327f, 60f), Size(20f, 56f), style = Stroke(4f))
}

private fun DrawScope.drawKettlebells(small: Color, big: Color) {
    drawCircle(big, radius = 10f, center = Offset(96f, 158f))
    drawArc(big, 180f, 180f, false, Offset(90f, 143f), Size(12f, 12f), style = Stroke(3.5f))
    drawCircle(small, radius = 7.5f, center = Offset(118f, 161f))
    drawArc(small, 180f, 180f, false, Offset(113f, 149f), Size(10f, 10f), style = Stroke(3f))
}

private fun DrawScope.drawDumbbells(c: Color) {
    drawRoundRect(c, Offset(208f, 159f), Size(28f, 5f), radius(2.5f))
    drawRoundRect(c, Offset(203f, 152f), Size(7f, 19f), radius(3f))
    drawRoundRect(c, Offset(234f, 152f), Size(7f, 19f), radius(3f))
}

private fun DrawScope.drawBarbell(plate: Color, bar: Color) {
    drawRoundRect(bar, Offset(72f, 178f), Size(216f, 6f), radius(3f))
    drawCircle(plate, radius = 12f, center = Offset(82f, 181f))
    drawCircle(plate, radius = 12f, center = Offset(278f, 181f))
}

private fun radius(r: Float) = androidx.compose.ui.geometry.CornerRadius(r, r)
