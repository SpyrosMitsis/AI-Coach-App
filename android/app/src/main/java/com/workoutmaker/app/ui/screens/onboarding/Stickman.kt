package com.workoutmaker.app.ui.screens.onboarding

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// ===========================================================================
// The app's stickman, and the rules that make one.
//
// Every figure the app draws is the same body: the runner on the distance
// picker, the cyclist, the swimmer, and the lifter standing in the gym scene.
// They live in different files and animate to different constraints, but they
// are built from these primitives and to these proportions, because a figure
// that is visibly heavier or lighter than its siblings reads as a different app
// having wandered in.
//
// The proportions are all fractions of ONE number, the figure's height, so a
// figure drawn 62dp tall next to a track and one drawn inside a 200-unit scene
// grid come out identical in every ratio that matters.
//
// Two things make these read as bodies rather than pivoting sticks: limbs have
// TWO bones, so a knee can flex; and near-side limbs are drawn at full strength
// over dimmed far-side ones, which is what gives a flat line drawing depth.
//
// Colour always comes from the theme, never a fixed palette: these have to work
// on all six of the app's schemes, in light and dark (CLAUDE.md).
// ===========================================================================

internal const val TAU = (2.0 * Math.PI).toFloat()

/** Line weight. Thin: the figures are drawings, not icons. */
internal fun strokeFor(height: Float) = height * 0.035f

/** Head radius. Drawn as an outline, never a filled blob. */
internal fun headRadiusFor(height: Float) = height * 0.085f

/** How much a far-side limb is dimmed to sit behind the body. */
internal const val FAR_SIDE_ALPHA = 0.32f

/** Angle measured from straight-down, positive = forward (+x). Natural for legs. */
internal fun Offset.fromVertical(angle: Float, len: Float) =
    Offset(x + len * sin(angle), y + len * cos(angle))

/** Standard polar: 0 = +x, π/2 = +y (down on screen). */
internal fun Offset.polar(angle: Float, len: Float) =
    Offset(x + len * cos(angle), y + len * sin(angle))

internal fun DrawScope.limb(a: Offset, b: Offset, color: Color, width: Float, alpha: Float = 1f) =
    drawLine(
        color = if (alpha == 1f) color else color.copy(alpha = color.alpha * alpha),
        start = a,
        end = b,
        strokeWidth = width,
        cap = StrokeCap.Round,
    )

/** The head, in the one style every figure uses. */
internal fun DrawScope.stickHead(center: Offset, height: Float, color: Color) =
    drawCircle(color, headRadiusFor(height), center, style = Stroke(strokeFor(height)))

/**
 * Two-bone IK: given hip and foot, find the knee that keeps both bone lengths.
 * Used for the pedal stroke, where the foot has to follow the crank circle.
 */
internal fun solveKnee(hip: Offset, foot: Offset, thigh: Float, shin: Float): Offset {
    val delta = foot - hip
    val raw = delta.getDistance().coerceAtLeast(0.001f)
    val d = raw.coerceIn(abs(thigh - shin) + 0.01f, thigh + shin - 0.01f)
    val u = Offset(delta.x / raw, delta.y / raw)
    val a = (thigh * thigh - shin * shin + d * d) / (2f * d)
    val height = sqrt(max(0f, thigh * thigh - a * a))
    val base = Offset(hip.x + u.x * a, hip.y + u.y * a)
    val perp = Offset(u.y, -u.x) // bend the knee forward
    return Offset(base.x + perp.x * height, base.y + perp.y * height)
}

/**
 * The lifter: the same body as the runner, standing still and breathing.
 *
 * Facing the viewer rather than in profile, since it is standing in a room
 * being furnished rather than travelling along a line, which is the one thing
 * that legitimately differs from its siblings. Everything else, the stroke
 * weight, the outlined head, the two-bone limbs, the dimmed far side, is
 * exactly what [RunnerStickman] and the others use.
 *
 * [breath] is 0..1. It lifts the chest, straightens the knees a touch and
 * settles the arms, all by fractions of a stroke width. Motion at this scale is
 * only meant to say the screen is alive while you read the question.
 *
 * [feet] is where the figure stands, on the floor line, and [height] is the
 * whole body from crown to sole.
 */
internal fun DrawScope.standingStickman(
    feet: Offset,
    height: Float,
    color: Color,
    breath: Float = 0f,
) {
    val h = height
    val sw = strokeFor(h)
    // The runner's own skeleton, read as fractions of its canvas height, with
    // the whole thing hung off the feet instead of off the top of a box.
    val top = feet.y - h * 0.96f
    fun y(f: Float) = top + h * f

    val lift = -h * 0.008f * breath
    val hip = Offset(feet.x, y(0.55f) + lift)
    val neck = Offset(feet.x, y(0.26f) + lift)
    val shoulder = Offset(feet.x, y(0.30f) + lift)
    val head = Offset(feet.x, y(0.165f) + lift)

    val thigh = h * 0.21f
    val shin = h * 0.20f
    val footLen = h * 0.07f
    val upperArm = h * 0.15f
    val foreArm = h * 0.14f

    // Standing, so the legs are near-vertical: a small stance angle apart and a
    // knee that softens as the chest rises, which is what breathing looks like
    // in a body that is holding still rather than one that is moving.
    val soften = 0.05f + 0.03f * breath
    fun leg(side: Float, alpha: Float) {
        val stance = 0.14f * side
        val knee = hip.fromVertical(stance, thigh)
        val ankle = knee.fromVertical(stance + soften, shin)
        val toe = ankle.fromVertical(stance + soften + 1.35f, footLen)
        limb(hip, knee, color, sw, alpha)
        limb(knee, ankle, color, sw, alpha)
        limb(ankle, toe, color, sw * 0.85f, alpha)
    }

    // Arms hang, elbows barely bent, drifting out a hair as the chest fills.
    fun arm(side: Float, alpha: Float) {
        val hang = 0.30f * side
        val elbowBend = (0.16f + 0.05f * breath) * side
        val elbow = shoulder.fromVertical(hang, upperArm)
        val hand = elbow.fromVertical(hang + elbowBend, foreArm)
        limb(shoulder, elbow, color, sw, alpha)
        limb(elbow, hand, color, sw, alpha)
    }

    // Far side first and dimmed, so the figure reads as three-dimensional.
    leg(-1f, FAR_SIDE_ALPHA)
    arm(-1f, FAR_SIDE_ALPHA)

    limb(hip, neck, color, sw)
    stickHead(head, h, color)

    leg(1f, 1f)
    arm(1f, 1f)
}
