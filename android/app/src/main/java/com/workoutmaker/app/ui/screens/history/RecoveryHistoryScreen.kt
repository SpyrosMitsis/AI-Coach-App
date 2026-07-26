package com.workoutmaker.app.ui.screens.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workoutmaker.app.data.RecoveryHistoryPoint
import com.workoutmaker.app.data.WorkoutRepository
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.SkeletonCard
import com.workoutmaker.app.ui.components.SectionCard
import com.workoutmaker.app.ui.components.chartLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import com.workoutmaker.app.ui.components.EmptyState
import com.workoutmaker.app.ui.components.ScreenScaffold
import com.workoutmaker.app.ui.theme.amberAccent
import java.time.LocalDate

@HiltViewModel
class RecoveryHistoryViewModel @Inject constructor(
    private val repo: WorkoutRepository,
) : ViewModel() {
    val points = MutableStateFlow<List<RecoveryHistoryPoint>>(emptyList())
    val loading = MutableStateFlow(true)

    init { load() }

    fun load() = viewModelScope.launch {
        loading.value = true
        // Always fetch the widest window (3 months); the screen filters it down to
        // the selected 7D / 1M / 3M view client-side, so switching range is instant.
        val from = LocalDate.now().minusDays(90).toString()
        runCatching { repo.recoveryHistory(from) }.onSuccess { points.value = it }
        loading.value = false
    }
}

private data class RangeOption(val label: String, val days: Long)
private val RANGES = listOf(RangeOption("7D", 7), RangeOption("1M", 30), RangeOption("3M", 90))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryHistoryScreen(onBack: () -> Unit, vm: RecoveryHistoryViewModel = hiltViewModel()) {
    val points by vm.points.collectAsStateSafe()
    val loading by vm.loading.collectAsStateSafe()
    var range by remember { mutableStateOf(RANGES[1]) } // 1M

    ScreenScaffold(
        title = "Recovery trends",
        subtitle = "Last ${range.label}",
        eyebrow = "BIO-METRICS",
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        isRefreshing = loading,
        onRefresh = { vm.load() },
    ) { mod ->
        if (loading && points.isEmpty()) {
            Column(mod, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonCard(lines = 3); SkeletonCard(lines = 3); SkeletonCard(lines = 3)
            }
            return@ScreenScaffold
        }

        // Filter to the selected window, then pull each metric out as dated points.
        val cutoff = LocalDate.now().minusDays(range.days).toString()
        val inRange = points.filter { it.date >= cutoff }
        val hrv = inRange.mapNotNull { p -> p.hrv_rmssd?.let { p.date to it } }
        val rhr = inRange.mapNotNull { p -> p.resting_hr?.let { p.date to it.toDouble() } }
        val sleep = inRange.mapNotNull { p -> p.zepp_sleep_minutes?.let { p.date to it / 60.0 } }
        val sleepScore = inRange.mapNotNull { p -> p.sleep_score?.let { p.date to it.toDouble() } }

        Column(mod, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Range selector — 7D / 1M / 3M, filtered from the loaded 3-month window.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RANGES.forEach { r ->
                    FilterChip(
                        selected = range == r,
                        onClick = { range = r },
                        label = { Text(r.label) },
                    )
                }
            }
            if (points.isEmpty()) {
                EmptyState(
                    title = "No recovery data yet",
                    subtitle = "HRV, resting HR and sleep will chart here as your watch (or manual entries) build up.",
                    icon = Icons.Filled.Favorite,
                )
                return@Column
            }
            MetricTrendCard("HRV", "ms", hrv, MaterialTheme.colorScheme.primary, higherIsBetter = true) { "%.0f".format(it) }
            MetricTrendCard("Resting HR", "bpm", rhr, MaterialTheme.colorScheme.error, higherIsBetter = false) { "%.0f".format(it) }
            MetricTrendCard("Sleep", "h", sleep, MaterialTheme.colorScheme.secondary, higherIsBetter = true) { hhmm(it) }
            MetricTrendCard("Sleep score", "/100", sleepScore, amberAccent(), higherIsBetter = true) { "%.0f".format(it) }
        }
    }
}

private fun hhmm(hours: Double): String {
    val m = (hours * 60).toInt()
    return "${m / 60}h${"%02d".format(m % 60)}"
}

private fun median(xs: List<Double>): Double {
    val s = xs.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

private fun stdev(xs: List<Double>): Double {
    if (xs.size < 2) return 0.0
    val mean = xs.average()
    return kotlin.math.sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size)
}

// A smooth curve through the points via Catmull-Rom → cubic Bézier, so the trend
// line flows instead of zig-zagging between every reading. Endpoints are duplicated
// so the first/last segments don't kink.
private fun smoothPath(pts: List<Offset>): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    path.moveTo(pts[0].x, pts[0].y)
    if (pts.size < 3) {
        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        return path
    }
    for (i in 0 until pts.size - 1) {
        val p0 = pts[(i - 1).coerceAtLeast(0)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[(i + 2).coerceAtMost(pts.size - 1)]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    return path
}

@Composable
private fun MetricTrendCard(
    label: String,
    unit: String,
    series: List<Pair<String, Double>>,
    color: Color,
    higherIsBetter: Boolean,
    fmt: (Double) -> String,
) {
    SectionCard(title = label) {
        if (series.size < 2) {
            Text(
                "Not enough readings yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val values = series.map { it.second }
        // A personal baseline (the window median) and a "normal range" of ±1 SD.
        // Recovery is read as drift from your own normal, not as an absolute number,
        // so this is the context that makes the latest reading mean anything.
        val baseline = median(values)
        val sd = stdev(values)
        val bandLo = baseline - sd
        val bandHi = baseline + sd
        val latest = values.last()
        val inRange = latest in bandLo..bandHi
        val above = latest >= baseline
        val pct = if (baseline != 0.0) ((latest - baseline) / baseline * 100).roundToInt() else 0
        // A move away from baseline is "good" only in the direction that helps this
        // metric (HRV up = good, resting HR up = bad). In-range reads neutral/calm.
        val statusColor = when {
            inRange -> MaterialTheme.colorScheme.onSurfaceVariant
            above == higherIsBetter -> color
            else -> amberAccent()
        }
        val status = if (inRange) "in your normal range"
            else "${kotlin.math.abs(pct)}% ${if (above) "above" else "below"} baseline ${if (above) "↑" else "↓"}"

        Row(verticalAlignment = Alignment.Bottom) {
            Text("${fmt(latest)} $unit", style = MaterialTheme.typography.titleMedium, color = color)
            Spacer(Modifier.width(8.dp))
            Text("· $status", style = MaterialTheme.typography.bodySmall, color = statusColor)
        }
        Spacer(Modifier.height(10.dp))
        BaselineBand(series, baseline, bandLo, bandHi, color, fmt, Modifier.fillMaxWidth())
    }
}

// A calm "normal range" band with the daily readings riding through it. The shaded
// band is your own ±1 SD, the dashed line your median baseline; points inside read
// quiet, points outside earn emphasis, and today is the hero dot. Not zero-based —
// the band supplies honest context, so we zoom to where the readings actually live.
// Tap a point to pin its date + value.
@Composable
private fun BaselineBand(
    series: List<Pair<String, Double>>,
    baseline: Double,
    bandLo: Double,
    bandHi: Double,
    color: Color,
    fmt: (Double) -> String,
    modifier: Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val marker = MaterialTheme.colorScheme.onSurface
    val gutterDp = 40.dp
    var selected by remember(series) { mutableStateOf<Int?>(null) }
    Canvas(
        modifier
            .height(150.dp)
            .fillMaxWidth()
            // Scrubbable: press and drag a finger across the chart and the crosshair
            // snaps to the nearest reading, updating live. The selection persists after
            // you lift so you can read the value.
            .pointerInput(series) {
                val gl = gutterDp.toPx()
                val plotW = (size.width - gl).coerceAtLeast(1f)
                val stepX = if (series.size > 1) plotW / (series.size - 1) else plotW
                fun nearest(px: Float) = ((px - gl) / stepX).roundToInt().coerceIn(0, series.lastIndex)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    selected = nearest(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        selected = nearest(change.position.x)
                        change.consume()
                    }
                }
            },
    ) {
        val values = series.map { it.second }
        val gutterLeft = gutterDp.toPx()
        val labelPad = 14.sp.toPx()
        val h = size.height - labelPad
        val plotW = (size.width - gutterLeft).coerceAtLeast(1f)
        val stepX = if (series.size > 1) plotW / (series.size - 1) else plotW

        val lo = minOf(values.minOrNull() ?: return@Canvas, bandLo)
        val hi = maxOf(values.maxOrNull() ?: return@Canvas, bandHi)
        val pad = (hi - lo).coerceAtLeast(1e-6) * 0.18
        val yLo = lo - pad
        val yHi = hi + pad
        fun y(v: Double) = (h * (1.0 - (v - yLo) / (yHi - yLo))).toFloat().coerceIn(0f, h)
        fun x(i: Int) = gutterLeft + stepX * i

        val pts = series.mapIndexed { i, p -> Offset(x(i), y(p.second)) }

        // 1. Normal-range band — a soft rounded zone, not a hard box.
        val bandTop = y(bandHi)
        drawRoundRect(
            color.copy(alpha = 0.10f),
            topLeft = Offset(gutterLeft, bandTop),
            size = Size(plotW, (y(bandLo) - bandTop).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(10.dp.toPx()),
        )
        // 2. Baseline (median) — a quiet dashed line + its value in the gutter.
        val by = y(baseline)
        drawLine(
            baselineColor.copy(alpha = 0.45f),
            Offset(gutterLeft, by), Offset(size.width, by),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 7f)),
        )
        chartLabel(fmt(baseline), gutterLeft - 6f, by + 9.sp.toPx() * 0.5f, alignRight = true, color = baselineColor)

        // 3. Smooth trend curve + a soft area fill fading toward the floor, so the
        // line reads as one calm gesture instead of a jagged saw-tooth of dots.
        val curve = smoothPath(pts)
        val fill = Path().apply {
            addPath(curve)
            lineTo(pts.last().x, h)
            lineTo(pts.first().x, h)
            close()
        }
        drawPath(
            fill,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.16f), color.copy(alpha = 0f)),
                startY = 0f,
                endY = h,
            ),
        )
        drawPath(curve, color, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // 4. Scrub crosshair: a perpendicular line + a ring on the curve at the point
        // under the finger, with the date + value pinned at the top of the plot.
        selected?.let { sel ->
            val cx = x(sel)
            drawLine(
                marker.copy(alpha = 0.55f),
                Offset(cx, 0f), Offset(cx, h),
                strokeWidth = 1.5f,
            )
            drawCircle(surface, 6.dp.toPx(), pts[sel])
            drawCircle(color, 5.dp.toPx(), pts[sel])
            drawCircle(surface, 2.dp.toPx(), pts[sel])
        }

        // Today as a hero "donut" — the curve itself carries the day-to-day shape.
        drawCircle(color, 6.5.dp.toPx(), pts.last())
        drawCircle(surface, 3.dp.toPx(), pts.last())

        // Date axis.
        chartLabel(series.first().first.takeLast(5), gutterLeft, size.height - 2f)
        chartLabel(series.last().first.takeLast(5), size.width, size.height - 2f, alignRight = true)

        // Scrub readout pinned at the top, so it stays steady while you drag.
        selected?.let { sel ->
            val p = series[sel]
            val txt = "${p.first.takeLast(5)} · ${fmt(p.second)}"
            val cx = x(sel)
            val nearRight = cx > size.width * 0.55f
            chartLabel(
                txt,
                if (nearRight) cx - 8f else cx + 8f,
                11.sp.toPx(),
                alignRight = nearRight,
                color = marker,
            )
        }
    }
}
