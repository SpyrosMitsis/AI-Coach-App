package com.workoutmaker.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

// Precomputed plot geometry + screen positions, shared by drawing and the
// touch hit-test so they always agree.
private class ChartGeo(
    val plotLeft: Float,
    val plotRight: Float,
    val plotTop: Float,
    val plotBottom: Float,
    val loV: Double,
    val hiV: Double,
    val inverted: Boolean,
    val points: List<Pair<Int, Offset>>, // (data index, screen position)
) {
    val midY get() = (plotTop + plotBottom) / 2f
    fun yOf(v: Double): Float {
        val f = (((v - loV) / (hiV - loV)).toFloat()).coerceIn(0f, 1f)
        return if (inverted) plotTop + f * (plotBottom - plotTop) else plotBottom - f * (plotBottom - plotTop)
    }
    fun offsetFor(idx: Int): Offset? = points.firstOrNull { it.first == idx }?.second
    fun nearest(x: Float): Int? = points.minByOrNull { abs(it.second.x - x) }?.first
}

private fun computeGeo(
    t: List<Double>,
    values: List<Double?>,
    wPx: Float,
    hPx: Float,
    density: Density,
    inverted: Boolean,
    minOverride: Double?,
    maxOverride: Double?,
    band: Pair<Double, Double>?,
    hasXLabels: Boolean,
): ChartGeo {
    val valid = values.filterNotNull()
    val gutterLeft = with(density) { 44.dp.toPx() }
    val gutterBottom = with(density) { (if (hasXLabels) 16.dp else 4.dp).toPx() }
    val padTop = with(density) { 8.dp.toPx() }
    val plotLeft = gutterLeft
    val plotRight = wPx
    val plotTop = padTop
    val plotBottom = hPx - gutterBottom
    val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
    val plotW = (plotRight - plotLeft).coerceAtLeast(1f)

    var lo = minOverride ?: valid.min()
    var hi = maxOverride ?: valid.max()
    if (band != null) { lo = minOf(lo, band.first); hi = maxOf(hi, band.second) }
    if (hi <= lo) hi = lo + 1
    if (minOverride == null && maxOverride == null) {
        val pad = (hi - lo) * 0.08
        lo -= pad; hi += pad
    }
    fun yOf(v: Double): Float {
        val f = (((v - lo) / (hi - lo)).toFloat()).coerceIn(0f, 1f)
        return if (inverted) plotTop + f * plotH else plotBottom - f * plotH
    }
    val tMin = t.first()
    val tMax = t.last()
    val tSpan = tMax - tMin
    fun xOf(i: Int): Float =
        if (tSpan > 0) plotLeft + ((t[i] - tMin) / tSpan).toFloat() * plotW
        else if (values.size > 1) plotLeft + i.toFloat() / (values.size - 1) * plotW
        else plotLeft + plotW / 2f

    val pts = ArrayList<Pair<Int, Offset>>(values.size)
    for (i in values.indices) {
        val v = values[i] ?: continue
        pts.add(i to Offset(xOf(i), yOf(v)))
    }
    return ChartGeo(plotLeft, plotRight, plotTop, plotBottom, lo, hi, inverted, pts)
}

/**
 * A single-series line chart with the app's shared look: a rounded line over a
 * soft top-down gradient fill, faint gridlines, and all axis text in the left/
 * bottom margins so nothing is ever drawn over the line.
 *
 * Touch-and-drag anywhere on the chart to scrub: a crosshair follows your finger
 * and a callout shows the value (and time, when [formatX] is given) at that point.
 *
 * @param formatY compact Y-axis tick text (no unit — put units in the header).
 * @param formatX optional formatter for the scrub callout's x value (e.g. time).
 * @param xLabels (left,right) labels for the time axis; null hides them.
 * @param inverted flips Y so smaller values sit at the top (pace: faster = higher).
 * @param band optional (lo,hi) target band shaded behind the line.
 * @param showPoints draws a dot at each sample (for sparse progression charts).
 */
@Composable
fun LineChart(
    t: List<Double>,
    values: List<Double?>,
    color: Color,
    formatY: (Double) -> String,
    modifier: Modifier = Modifier,
    xLabels: Pair<String, String>? = null,
    formatX: ((Double) -> String)? = null,
    inverted: Boolean = false,
    band: Pair<Double, Double>? = null,
    bandColor: Color = ChartLabelColor,
    minOverride: Double? = null,
    maxOverride: Double? = null,
    showPoints: Boolean = false,
    height: Dp = 140.dp,
) {
    val valid = values.filterNotNull()
    if (valid.isEmpty() || t.size != values.size) return
    val density = LocalDensity.current
    val calloutBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val calloutFg = MaterialTheme.colorScheme.onSurface
    var activeIdx by remember(values, t) { mutableStateOf<Int?>(null) }

    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { this@BoxWithConstraints.maxHeight.toPx() }
        val geo = remember(values, t, wPx, hPx, inverted, minOverride, maxOverride, band, xLabels != null) {
            computeGeo(t, values, wPx, hPx, density, inverted, minOverride, maxOverride, band, xLabels != null)
        }

        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(geo) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        activeIdx = geo.nearest(down.position.x)
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            activeIdx = geo.nearest(change.position.x)
                            change.consume()
                        }
                        activeIdx = null
                    }
                },
        ) {
            val plotW = geo.plotRight - geo.plotLeft

            // gridlines
            hGridLine(geo.plotTop, geo.plotLeft, geo.plotRight)
            hGridLine(geo.midY, geo.plotLeft, geo.plotRight)
            hGridLine(geo.plotBottom, geo.plotLeft, geo.plotRight)

            // target band
            if (band != null) {
                val yA = geo.yOf(band.first)
                val yB = geo.yOf(band.second)
                drawRect(
                    bandColor.copy(alpha = 0.16f),
                    topLeft = Offset(geo.plotLeft, minOf(yA, yB)),
                    size = Size(plotW, abs(yB - yA).coerceAtLeast(2f)),
                )
            }

            // line + gradient fill
            val pts = geo.points.map { it.second }
            if (pts.size == 1) {
                drawCircle(color, radius = 5f, center = pts[0])
            } else if (pts.isNotEmpty()) {
                val line = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (k in 1 until pts.size) lineTo(pts[k].x, pts[k].y)
                }
                fillUnderLine(line, color, geo.plotBottom, geo.plotTop, pts.first().x, pts.last().x)
                drawPath(line, color, style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (showPoints) pts.forEach { drawCircle(color, radius = 3.5f, center = it) }
            }

            // axis labels — all in the gutters, never over the line
            val labelX = geo.plotLeft - 6f
            val topVal = if (inverted) geo.loV else geo.hiV
            val botVal = if (inverted) geo.hiV else geo.loV
            chartLabel(formatY(topVal), labelX, geo.plotTop + 9.sp.toPx() * 0.5f, alignRight = true)
            chartLabel(formatY((geo.loV + geo.hiV) / 2), labelX, geo.midY + 9.sp.toPx() * 0.35f, alignRight = true)
            chartLabel(formatY(botVal), labelX, geo.plotBottom - 1f, alignRight = true)
            if (xLabels != null) {
                val baseY = size.height - 3f
                chartLabel(xLabels.first, geo.plotLeft, baseY)
                chartLabel(xLabels.second, size.width - 2f, baseY, alignRight = true)
            }

            // --- scrub crosshair + value callout ------------------------------
            val idx = activeIdx
            val pt = idx?.let { geo.offsetFor(it) }
            if (idx != null && pt != null) {
                drawLine(
                    ChartLabelColor.copy(alpha = 0.55f),
                    Offset(pt.x, geo.plotTop), Offset(pt.x, geo.plotBottom), strokeWidth = 2f,
                )
                drawCircle(color, radius = 6f, center = pt)
                drawCircle(calloutBg, radius = 2.5f, center = pt)

                val v = values[idx]
                if (v != null) {
                    val text = formatY(v) + (formatX?.let { " · ${it(t[idx])}" } ?: "")
                    val paint = android.graphics.Paint().apply {
                        this.color = calloutFg.toArgb()
                        textSize = 11.sp.toPx()
                        isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val tw = paint.measureText(text)
                    val padX = 9f
                    val boxW = tw + padX * 2
                    val boxH = 11.sp.toPx() + 9f
                    val cx = pt.x.coerceIn(geo.plotLeft + boxW / 2f, size.width - boxW / 2f)
                    drawRoundRect(
                        calloutBg,
                        topLeft = Offset(cx - boxW / 2f, 0f),
                        size = Size(boxW, boxH),
                        cornerRadius = CornerRadius(7f, 7f),
                    )
                    drawContext.canvas.nativeCanvas.drawText(text, cx, boxH - 8f, paint)
                }
            }
        }
    }
}
