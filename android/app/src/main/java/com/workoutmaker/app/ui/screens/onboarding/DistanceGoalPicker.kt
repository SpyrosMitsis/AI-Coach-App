package com.workoutmaker.app.ui.screens.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.EnduranceGoals
import com.workoutmaker.app.ui.components.rememberAnimationsEnabled
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// ===========================================================================
// The distance goal, as a thing you drag rather than a chip you tick.
//
// A line, the classic distances standing on it as posts, a flag you drag to
// your target, and a figure that travels with the flag and moves at the pace
// you set. The pace is not decoration: it is half the goal ("42.2 km" and
// "42.2 km in 3:30" are different training plans), and coupling the cadence to
// it makes an abstract number something you can watch.
//
// All the arithmetic lives in data/EnduranceGoals.kt; this file is drawing.
// ===========================================================================

private val TRACK_HEIGHT = 172.dp
private val TRACK_INSET = 22.dp   // room for the first and last post labels
private val BASELINE_UP = 44.dp   // baseline height above the labels

@Composable
internal fun DistanceGoalPicker(
    sport: String,
    fraction: Float,
    paceSec: Int,
    onFraction: (Float) -> Unit,
    onRelease: () -> Unit,
    onPace: (Int) -> Unit,
) {
    val posts = remember(sport) { EnduranceGoals.posts[sport].orEmpty() }
    val km = EnduranceGoals.kmForFraction(sport, fraction)
    val standingOn = EnduranceGoals.postAt(sport, fraction)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The readout: how far, and what that distance is CALLED, which is the
        // reassurance that a dragged number is still a real event.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                EnduranceGoals.formatKmValue(km),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                EnduranceGoals.kmUnit(km),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 5.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                standingOn?.name ?: "custom distance",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        DistanceTrack(sport, posts.map { it.label }, fraction, paceSec, standingOn != null, onFraction, onRelease)

        PacePanel(sport, paceSec, onPace)

        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f))
                .padding(horizontal = 15.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "About ${EnduranceGoals.formatDuration(EnduranceGoals.estimateMinutes(km, paceSec))} " +
                    "at ${EnduranceGoals.formatPace(sport, paceSec)}. The figure moves at the pace you set.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DistanceTrack(
    sport: String,
    labels: List<String>,
    fraction: Float,
    paceSec: Int,
    onPost: Boolean,
    onFraction: (Float) -> Unit,
    onRelease: () -> Unit,
) {
    val n = (labels.size - 1).coerceAtLeast(1)
    Box(Modifier.fillMaxWidth().height(TRACK_HEIGHT).padding(horizontal = TRACK_INSET)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(TRACK_HEIGHT)) {
            val span = maxWidth
            val spanPx = with(LocalDensity.current) { span.toPx() }

            // Pointer down anywhere on the line jumps the flag there and starts
            // the drag: aiming for a 42dp figure with a thumb is not a control.
            val gestures = Modifier
                .pointerInput(sport, spanPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { p -> onFraction((p.x / spanPx).coerceIn(0f, 1f)) },
                        onDragEnd = onRelease,
                        onDragCancel = onRelease,
                    ) { change, _ ->
                        change.consume()
                        onFraction((change.position.x / spanPx).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(sport, spanPx) {
                    detectTapGestures { p -> onFraction((p.x / spanPx).coerceIn(0f, 1f)); onRelease() }
                }

            Box(
                Modifier.fillMaxWidth().height(TRACK_HEIGHT).then(gestures).semantics {
                    contentDescription = "Distance goal, ${EnduranceGoals.formatKm(EnduranceGoals.kmForFraction(sport, fraction))}"
                },
            ) {
                if (sport == "swim") {
                    Waterline(paceSec, Modifier.align(Alignment.BottomStart).padding(bottom = BASELINE_UP - 14.dp))
                }
                // Baseline, and the part of it already covered.
                Box(
                    Modifier.align(Alignment.BottomStart).padding(bottom = BASELINE_UP)
                        .fillMaxWidth().height(2.dp).clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
                Box(
                    Modifier.align(Alignment.BottomStart).padding(bottom = BASELINE_UP)
                        .width(span * fraction).height(2.dp).clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
                )

                labels.forEachIndexed { i, label ->
                    val f = i.toFloat() / n
                    val here = onPost && kotlin.math.abs(fraction - f) < 0.02f
                    val passed = f <= fraction + 0.001f
                    val tint = when {
                        here -> MaterialTheme.colorScheme.primary
                        passed -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                    Column(
                        Modifier.align(Alignment.BottomStart)
                            .offset(x = span * f - POST_WIDTH / 2)
                            .width(POST_WIDTH),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Box(
                            Modifier.width(2.dp).height(if (here) 16.dp else 10.dp)
                                .clip(RoundedCornerShape(50)).background(tint),
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (here) FontWeight.Bold else FontWeight.Normal,
                            color = tint,
                            maxLines = 1,
                        )
                    }
                }

                // The travelling handle: the sport's figure, then the flag that
                // marks the spot. Centred on the fraction, like the design.
                Row(
                    Modifier.align(Alignment.BottomStart)
                        .padding(bottom = BASELINE_UP + 2.dp)
                        .offset(x = span * fraction - HANDLE_WIDTH / 2)
                        .width(HANDLE_WIDTH),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    SportFigure(sport, paceSec)
                    GoalFlag()
                }
            }
        }
    }
}

private val POST_WIDTH = 44.dp
private val HANDLE_WIDTH = 96.dp

@Composable
private fun PacePanel(sport: String, paceSec: Int, onPace: (Int) -> Unit) {
    val (leftLabel, rightLabel) = EnduranceGoals.paceEndLabels(sport)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                EnduranceGoals.paceCaption(sport),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // Minus is always slower and plus always faster, whichever way the
            // displayed unit happens to count.
            StepperButton(Icons.Filled.Remove, "Slower") { onPace(EnduranceGoals.stepPace(sport, paceSec, faster = false)) }
            Row(
                Modifier.width(96.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    EnduranceGoals.paceValue(sport, paceSec),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    EnduranceGoals.paceUnit(sport),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                )
            }
            StepperButton(Icons.Filled.Add, "Faster") { onPace(EnduranceGoals.stepPace(sport, paceSec, faster = true)) }
        }
        Slider(
            value = EnduranceGoals.paceFraction(sport, paceSec),
            onValueChange = { onPace(EnduranceGoals.secForPaceFraction(sport, it)) },
            colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(leftLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(rightLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun StepperButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

// --- The figures -----------------------------------------------------------
//
// Each figure is a jointed skeleton, not a set of rotating sticks. Limbs have
// TWO segments (thigh + shin, upper arm + forearm) and the joint between them is
// solved by inverse kinematics from where the hand or foot needs to be. That is
// the whole difference between "a line pivoting at the hip" and something that
// reads as running: a knee that bends on the swing and straightens on the drive
// is what the eye is actually looking for.
//
// So each figure is defined by the PATH ITS EXTREMITIES TRACE, which is the real
// description of the action:
//   run   a gait cycle, the foot swinging forward through the air and driving
//         back along the ground, half a cycle apart per leg
//   ride  the pedal on the chainring circle, both legs following it exactly
//   swim  the freestyle catch/pull/exit/recovery loop, arms half a cycle apart
//
// Cadence follows the chosen pace (EnduranceGoals.animationRate, capped at both
// ends). The phase is integrated per frame rather than driven by a keyframe
// animation, so changing the pace SPEEDS THE FIGURE UP rather than restarting
// its loop, which is what made the old version stutter while you dragged.

/**
 * A 0..1 phase advanced once per frame, at one turn every [periodMs].
 *
 * Deliberately not an infiniteRepeatable: that is keyed on its duration, so
 * every pace change tore the animation back to its first frame. Here the period
 * is read fresh each frame and the phase simply carries on from where it was.
 */
@Composable
private fun rememberPhase(periodMs: Float): Float {
    val animate = rememberAnimationsEnabled()
    var phase by remember { mutableFloatStateOf(0f) }
    val period = rememberUpdatedState(periodMs)
    LaunchedEffect(animate) {
        if (!animate) {
            phase = 0f
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dtMs = (now - last) / 1_000_000f
                    phase = (phase + dtMs / period.value.coerceAtLeast(120f)) % 1f
                }
                last = now
            }
        }
    }
    return phase
}

/**
 * Where the joint of a two-segment limb sits, given both ends.
 *
 * Standard two-link IK: the joint lies on a circle around the midpoint of the
 * base-to-target line. [flip] picks which of the two solutions to take, which is
 * simply which way the knee or elbow bends. The reach is clamped so an
 * out-of-range target straightens the limb instead of producing NaN.
 */
private fun joint(base: Offset, target: Offset, l1: Float, l2: Float, flip: Float): Offset {
    val dx = target.x - base.x
    val dy = target.y - base.y
    val raw = hypot(dx, dy)
    if (raw < 0.0001f) return Offset(base.x, base.y + l1)
    val ux = dx / raw
    val uy = dy / raw
    val d = raw.coerceIn(abs(l1 - l2) + 0.02f, l1 + l2 - 0.02f)
    val a = (l1 * l1 - l2 * l2 + d * d) / (2f * d)
    val h = sqrt(max(0f, l1 * l1 - a * a))
    return Offset(base.x + ux * a - uy * h * flip, base.y + uy * a + ux * h * flip)
}

private class Skeleton(val scope: DrawScope, val unit: Float, val originY: Float) {
    fun bone(a: Offset, b: Offset, color: Color, width: Float) {
        scope.drawLine(
            color,
            Offset(a.x * unit, (a.y + originY) * unit),
            Offset(b.x * unit, (b.y + originY) * unit),
            strokeWidth = width * unit,
            cap = StrokeCap.Round,
        )
    }

    /** A two-segment limb drawn through its solved joint, e.g. hip → knee → foot. */
    fun limb(base: Offset, target: Offset, l1: Float, l2: Float, flip: Float, color: Color, width: Float) {
        val j = joint(base, target, l1, l2, flip)
        bone(base, j, color, width)
        bone(j, target, color, width)
    }

    fun head(center: Offset, radius: Float, color: Color, width: Float) {
        scope.drawCircle(
            color,
            radius = radius * unit,
            center = Offset(center.x * unit, (center.y + originY) * unit),
            style = Stroke(width = width * unit),
        )
    }

    fun ring(center: Offset, radius: Float, color: Color, width: Float) = head(center, radius, color, width)
}

private fun at(x: Float, y: Float) = Offset(x, y)

private const val TAU = (2.0 * Math.PI).toFloat()

@Composable
private fun SportFigure(sport: String, paceSec: Int) {
    val rate = EnduranceGoals.animationRate(sport, paceSec)
    val stroke = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    when (sport) {
        "ride" -> {
            // One phase drives everything: the pedals ARE the clock, and the
            // wheels turn at a fixed ratio to them, like a real drivetrain.
            val crank = rememberPhase(900f * rate)
            Canvas(Modifier.size(62.dp, 58.dp)) { drawRider(crank, stroke, muted, accent) }
        }
        "swim" -> {
            val strokeCycle = rememberPhase(1500f * rate)
            val kick = rememberPhase(1500f * rate / 3f) // six-beat kick, three per arm cycle
            Canvas(Modifier.size(66.dp, 46.dp)) { drawSwimmer(strokeCycle, kick, stroke, muted) }
        }
        else -> {
            val stride = rememberPhase(700f * rate)
            Canvas(Modifier.size(44.dp, 58.dp)) { drawRunner(stride, stroke, muted) }
        }
    }
}

// --- Run -------------------------------------------------------------------
//
// A gait cycle per leg, half a turn apart. The foot traces the classic
// procedural path: forward and UP through the air for half the cycle (swing),
// then back along the ground for the other half (stance, the drive). The knee
// falls out of the IK, so it lifts high on the swing and extends on the drive
// without either being animated directly.

private fun footPath(t: Float, hipX: Float, ground: Float, stride: Float, lift: Float): Offset {
    val a = TAU * t
    val airborne = sin(a).coerceAtLeast(0f) // only the swing half lifts
    return at(hipX - stride * cos(a), ground - lift * airborne)
}

private fun DrawScope.drawRunner(t: Float, stroke: Color, muted: Color) {
    val u = size.width / 44f
    val a = TAU * t
    // Two foot strikes per cycle, so the body drops twice: lowest on contact,
    // highest at mid-swing. This is what stops a running figure looking like it
    // is skating.
    val bob = -1.8f * (0.5f - 0.5f * cos(2f * a))
    val s = Skeleton(this, u, bob)
    val w = 2.3f

    val hip = at(20f, 33f)
    val shoulder = at(22.5f, 18.5f)
    val ground = 50.5f

    // Trailing leg first so the leading one draws over it.
    listOf(0.5f to muted, 0f to stroke).forEach { (offset, color) ->
        val foot = footPath((t + offset) % 1f, hip.x, ground, stride = 7f, lift = 6.5f)
        // Knee forward: the near-side leg bends the way a right-facing runner's does.
        s.limb(hip, foot, l1 = 9.5f, l2 = 9f, flip = -1f, color = color, width = w)
        // A short foot, angled with the swing. Small, but its absence is what
        // made the old figure read as a pair of scissors.
        val toe = at(foot.x + 3.2f, foot.y - if (foot.y < ground - 0.5f) 1.4f else 0f)
        s.bone(foot, toe, color, w * 0.85f)
    }

    // Arms drive opposite the legs, elbows locked near a right angle by aiming
    // the hand at a short, mostly fore-and-aft path.
    listOf(0.5f to muted, 0f to stroke).forEach { (offset, color) ->
        val ha = TAU * ((t + offset) % 1f)
        val hand = at(shoulder.x + 6.2f * cos(ha), shoulder.y + 8.5f + 2.2f * sin(ha))
        s.limb(shoulder, hand, l1 = 6.5f, l2 = 6.2f, flip = 1f, color = color, width = w * 0.95f)
    }

    // Torso leans into the run; the head sits forward of the hips, not above them.
    s.bone(shoulder, hip, stroke, w)
    s.head(at(24f, 11f), radius = 5f, color = stroke, width = w)
    s.bone(at(23.4f, 15.8f), shoulder, stroke, w * 0.9f)
}

// --- Ride ------------------------------------------------------------------
//
// The pedal is the only thing animated: it goes round the chainring, and both
// legs are solved to reach it. That single constraint is what makes a cycling
// figure look right, and it is exactly what the real machine does.

private fun DrawScope.drawRider(t: Float, stroke: Color, muted: Color, accent: Color) {
    val u = size.width / 62f
    val s = Skeleton(this, u, 0f)
    val w = 2.1f

    val rearHub = at(11f, 44f)
    val frontHub = at(50f, 44f)
    val bb = at(30f, 44f)          // bottom bracket, where the cranks turn
    val saddle = at(23f, 24f)
    val bars = at(47f, 26f)
    val hip = at(24.5f, 25f)
    val shoulder = at(35f, 19f)
    val crankR = 5.2f
    val wheelR = 9.6f

    // Wheels. The spokes turn at the wheel's own rate, geared off the cranks,
    // so cadence and road speed stay visibly related.
    val wheelAngle = TAU * ((t * 2.6f) % 1f)
    listOf(rearHub, frontHub).forEach { hub ->
        s.ring(hub, wheelR, muted, 1.5f)
        repeat(3) { k ->
            val ang = wheelAngle + k * TAU / 3f
            val dx = (wheelR - 1f) * cos(ang)
            val dy = (wheelR - 1f) * sin(ang)
            s.bone(at(hub.x - dx, hub.y - dy), at(hub.x + dx, hub.y + dy), muted.copy(alpha = 0.4f), 1f)
        }
    }

    // Frame: a real diamond, so it reads as a bike rather than a scribble.
    listOf(
        bb to rearHub,          // chainstay
        rearHub to saddle,      // seat stay
        bb to saddle,           // seat tube
        bb to at(45f, 28f),     // down tube
        saddle to at(45f, 28f), // top tube
        at(45f, 28f) to frontHub, // fork
        at(45f, 28f) to bars,   // stem and bars
    ).forEach { (a, b) -> s.bone(a, b, stroke, w) }

    // Both legs chase the pedals, half a turn apart on the same crank.
    listOf(0.5f to muted, 0f to stroke).forEach { (offset, color) ->
        val ang = TAU * ((t + offset) % 1f)
        val pedal = at(bb.x + crankR * cos(ang), bb.y + crankR * sin(ang))
        s.bone(bb, pedal, if (offset == 0f) accent else muted, 1.6f)
        s.limb(hip, pedal, l1 = 9.2f, l2 = 10.4f, flip = -1f, color = color, width = w)
        s.bone(at(pedal.x - 2f, pedal.y), at(pedal.x + 2f, pedal.y), color, w * 0.8f)
    }
    s.ring(bb, 2.4f, accent, 1.2f)

    // Rider: hips back over the saddle, shoulders forward, arms out to the bars.
    s.bone(hip, shoulder, stroke, w)
    s.limb(shoulder, bars, l1 = 6.5f, l2 = 6.5f, flip = -1f, color = stroke, width = w * 0.9f)
    s.bone(shoulder, at(39f, 15.5f), stroke, w * 0.9f)
    s.head(at(42.5f, 12.5f), radius = 4.6f, color = stroke, width = w)
}

// --- Swim ------------------------------------------------------------------
//
// Freestyle from the side. Each hand runs the real stroke loop: it enters ahead
// of the head, pulls back UNDER the body, exits at the hip and recovers back
// over the top. Half a cycle apart, so one arm is always pulling. The elbow
// bends out of the IK, which gives the high-elbow catch for free.

private fun DrawScope.drawSwimmer(t: Float, kickT: Float, stroke: Color, muted: Color) {
    val u = size.width / 66f
    // The body rolls with the stroke, exactly as a real swimmer's does.
    val roll = 1.6f * sin(TAU * t)
    val s = Skeleton(this, u, roll)
    val w = 2.3f

    val shoulder = at(44f, 27.5f)
    val hip = at(20f, 30f)
    // Centre of the stroke loop, slightly ahead of and below the shoulder.
    val loop = at(45.5f, 28f)

    s.bone(shoulder, hip, stroke, w)

    listOf(0.5f to muted, 0f to stroke).forEach { (offset, color) ->
        val a = TAU * ((t + offset) % 1f)
        val hand = at(loop.x + 11.5f * cos(a), loop.y + 8f * sin(a))
        // Elbow above the hand through the pull, which is the shape coaches
        // spend years asking for.
        s.limb(shoulder, hand, l1 = 7.2f, l2 = 7f, flip = -1f, color = color, width = w * 0.95f)
    }

    // Six-beat flutter: small, fast, from the hip, with the knee barely bending.
    listOf(0.5f to muted, 0f to stroke).forEach { (offset, color) ->
        val k = TAU * ((kickT + offset) % 1f)
        val foot = at(hip.x - 11.5f, hip.y + 3.6f * sin(k))
        s.limb(hip, foot, l1 = 6.2f, l2 = 6f, flip = -1f, color = color, width = w * 0.95f)
        s.bone(foot, at(foot.x - 2.6f, foot.y + 0.9f * sin(k)), color, w * 0.8f)
    }

    s.head(at(50.5f, 26f), radius = 5f, color = stroke, width = w)
    s.bone(at(46.5f, 27f), shoulder, stroke, w * 0.9f)
}

/**
 * The marker itself: a pole on the line and a pennant that flutters. Not coupled
 * to pace, deliberately: the wind does not care how fast you are running.
 */
@Composable
private fun GoalFlag() {
    val phase = rememberPhase(1100f)
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(26.dp, 60.dp)) {
        val u = size.width / 26f
        val flutter = 2f * cos(2.0 * Math.PI * phase).toFloat()
        drawLine(accent, Offset(4f * u, 58f * u), Offset(4f * u, 4f * u), strokeWidth = 2.4f * u, cap = StrokeCap.Round)
        val pennant = Path().apply {
            moveTo(5f * u, 5f * u)
            lineTo((21f + flutter) * u, 10f * u)
            lineTo(5f * u, 16f * u)
            close()
        }
        drawPath(pennant, accent)
    }
}

/** Water for the swimmer to be in, drifting past under the line. */
@Composable
private fun Waterline(paceSec: Int, modifier: Modifier = Modifier) {
    val phase = rememberPhase(1600f * EnduranceGoals.animationRate("swim", paceSec))
    val water = MaterialTheme.colorScheme.outline
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val u = size.height / 14f
        val wavelength = 24f * u
        // Sampled rather than drawn with beziers: one loop, no curve API, and the
        // shift is exactly one wavelength per cycle so the seam never shows.
        val path = Path()
        val step = wavelength / 8f
        var x = -wavelength - phase * wavelength
        path.moveTo(x, 7f * u)
        while (x < size.width + wavelength) {
            x += step
            val y = (7f + 3.5f * sin(2.0 * Math.PI * x / wavelength).toFloat()) * u
            path.lineTo(x, y)
        }
        drawPath(path, water.copy(alpha = 0.5f), style = Stroke(width = 1.6f * u, cap = StrokeCap.Round))
    }
}
