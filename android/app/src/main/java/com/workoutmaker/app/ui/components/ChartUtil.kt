package com.workoutmaker.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp

// Tiny helpers so every Canvas line plot can carry axis values + units instead
// of being a naked line: faint horizontal gridlines and small text labels.

val ChartGridColor = Color(0xFF333535)
val ChartLabelColor = Color(0xFF8E938E)

/** A faint horizontal gridline across the full width at [y]. */
fun DrawScope.hGridLine(y: Float) {
    drawLine(
        ChartGridColor,
        androidx.compose.ui.geometry.Offset(0f, y),
        androidx.compose.ui.geometry.Offset(size.width, y),
        strokeWidth = 1.5f,
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
