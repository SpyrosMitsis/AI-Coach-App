package com.workoutmaker.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp

// Tiny helpers so every Canvas line plot can carry axis values + units instead
// of being a naked line: faint horizontal gridlines and small text labels.

// Translucent neutral so the gridline reads as "faint" on BOTH the dark card and
// the light-mode paper (an opaque dark line would look heavy on light).
val ChartGridColor = Color(0x33888B86)
val ChartLabelColor = Color(0xFF8E938E)

// Vibrant, FIXED chart-series colors — these are data semantics, not brand, so
// they stay constant across every theme (like ChartGridColor). HR is ALWAYS red
// by requirement; the rest are punchy hues chosen to read on both dark + light.
val ChartHr = Color(0xFFFF3B30)      // heart rate — always red
val ChartPace = Color(0xFF30D158)    // pace — vivid green
val ChartCadence = Color(0xFFBF5AF2) // cadence — vivid violet
val ChartPower = Color(0xFFFF9F0A)   // power — vivid amber

/** A faint horizontal gridline at [y], optionally only across the plot area. */
fun DrawScope.hGridLine(y: Float, xStart: Float = 0f, xEnd: Float = size.width) {
    drawLine(
        ChartGridColor,
        Offset(xStart, y),
        Offset(xEnd, y),
        strokeWidth = 1.5f,
    )
}

/**
 * Paints a soft top-down gradient under a line: closes [linePath] down to
 * [baselineY] and fills it with [color] fading to transparent. Pass the line's
 * first/last x so the fill closes cleanly at the plot edges.
 */
fun DrawScope.fillUnderLine(
    linePath: Path,
    color: Color,
    baselineY: Float,
    topY: Float,
    firstX: Float,
    lastX: Float,
) {
    val fill = Path().apply {
        addPath(linePath)
        lineTo(lastX, baselineY)
        lineTo(firstX, baselineY)
        close()
    }
    drawPath(
        fill,
        Brush.verticalGradient(
            0f to color.copy(alpha = 0.28f),
            1f to color.copy(alpha = 0f),
            startY = topY,
            endY = baselineY,
        ),
    )
}

/** Small axis label drawn with the native canvas (Compose Canvas has no text). */
fun DrawScope.chartLabel(
    text: String,
    x: Float,
    y: Float,
    alignRight: Boolean = false,
    color: Color = ChartLabelColor,
) {
    val paint = android.graphics.Paint().apply {
        this.color = color.toArgb()
        textSize = 10.sp.toPx()
        isAntiAlias = true
        textAlign = if (alignRight) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}
