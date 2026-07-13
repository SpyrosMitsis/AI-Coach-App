package com.workoutmaker.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workoutmaker.app.data.PlannedWorkout
import com.workoutmaker.app.data.ScheduleRequest
import com.workoutmaker.app.data.WorkoutRepository
import com.workoutmaker.app.data.WorkoutTemplate
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.data.CompletedActivity
import com.workoutmaker.app.ui.components.ChipRow
import com.workoutmaker.app.ui.components.chartLabel
import com.workoutmaker.app.ui.components.fmtClock
import com.workoutmaker.app.ui.components.hGridLine
import com.workoutmaker.app.ui.components.GhostButton
import com.workoutmaker.app.ui.components.StatTileGrid
import com.workoutmaker.app.ui.components.ScreenScaffold
import com.workoutmaker.app.ui.components.SectionCard
import com.workoutmaker.app.ui.components.SectionLabel
import com.workoutmaker.app.ui.components.ChartCadence
import com.workoutmaker.app.ui.components.ChartHr
import com.workoutmaker.app.ui.components.ChartPace
import com.workoutmaker.app.ui.components.ChartPower
import com.workoutmaker.app.ui.theme.BandRed
import com.workoutmaker.app.ui.theme.mossAccent
import com.workoutmaker.app.ui.theme.Sage
import com.workoutmaker.app.ui.theme.Sand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

// Full detail page for a past workout/run/ride — rich data from Intervals.icu.
// Non-private so the dedicated Workout History screen can reuse it.
@Composable
fun ActivityDetailScreen(activity: CompletedActivity, planned: PlannedWorkout?, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.padding(start = 4.dp)) {
                Text(activity.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${activity.type ?: "Activity"} · ${activity.date ?: ""}" + if (activity.isManual) " · logged manually" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Order: headline first (how it went), then the key numbers and the
        // coach's take, then the graphs, then the deeper analytical breakdowns.

        // 1. Execution verdict vs the plan — the headline.
        if (!activity.isManual) AnalysisScoreCard(activity)

        // 2. Key metrics at a glance.
        SectionCard {
            SectionLabel("Summary", color = MaterialTheme.colorScheme.primary)
            StatTileGrid(
                buildList {
                    activity.distanceKm?.let { if (it > 0) add("Distance" to "%.2f km".format(it)) }
                    activity.durationMin?.let { if (it > 0) add("Duration" to "$it min") }
                    // Elapsed only when it differs meaningfully from moving time (≥1 min of pauses).
                    activity.elapsedSeconds?.let { el ->
                        val mv = activity.duration_seconds ?: 0
                        if (el - mv >= 60) add("Elapsed" to "${el / 60} min")
                    }
                    activity.paceSecPerKm?.let { add("Avg pace" to "${fmtPaceSec(it)} /km") }
                    activity.maxPaceSecPerKm?.let { add("Best pace" to "${fmtPaceSec(it)} /km") }
                    activity.avg_hr?.let { add("Avg HR" to "$it bpm") }
                    activity.maxHr?.let { add("Max HR" to "$it bpm") }
                    activity.avgPower?.let { add("Avg power" to "$it W") }
                    activity.normalizedPower?.let { add("Norm. power" to "$it W") }
                    activity.avgCadence?.let { add("Avg cadence" to "$it spm") }
                    activity.maxCadence?.let { add("Max cadence" to "$it spm") }
                    activity.decouplingPct?.let { add("HR drift" to "%.1f%%".format(it)) }
                    activity.elevationGain?.let { add("Elevation" to "$it m") }
                    activity.avgTempC?.let { add("Avg temp" to "$it°C") }
                    activity.calories?.let { add("Calories" to "$it kcal") }
                    activity.tss?.let { if (it > 0) add("Training load" to "${it.toInt()} TSS") }
                },
            )
        }

        // 3. The coach's narrative takeaway.
        if (!activity.isManual) CoachFeedbackCard(activity)

        // 4. Graphs over time: pace, heart rate, cadence, power.
        if (!activity.isManual) ActivityChartsSection(activity)

        // --- deeper analytical detail below -----------------------------------

        // 5. How the session's intensity was distributed.
        activity.hrZoneTimes?.let { HrZoneCard(it) }

        // 6. Per-kilometre splits.
        if (!activity.isManual) SplitsCard(activity)

        // 7. Planned vs actual on this date.
        planned?.let { p ->
            SectionCard {
                SectionLabel("On the plan that day", color = mossAccent())
                Text(p.workout_json.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (looksLike(p.type, activity.type)) "✓ You did your planned ${p.type}."
                    else "You had a ${p.type} planned but did this instead.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (looksLike(p.type, activity.type)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                )
            }
        }

        // 8. E3-style load context: what this did to your fitness.
        if (activity.ctl != null || activity.atl != null) {
            SectionCard {
                SectionLabel("Fitness after this", color = MaterialTheme.colorScheme.secondary)
                StatTileGrid(
                    buildList {
                        activity.ctl?.let { add("Fitness (CTL)" to "%.0f".format(it)) }
                        activity.atl?.let { add("Fatigue (ATL)" to "%.0f".format(it)) }
                        if (activity.ctl != null && activity.atl != null) {
                            add("Form (TSB)" to "%.0f".format(activity.ctl!! - activity.atl!!))
                        }
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Post-workout analysis (analyze-activity edge function)
// ---------------------------------------------------------------------------
@HiltViewModel
class ActivityAnalysisViewModel @Inject constructor(
    private val repo: WorkoutRepository,
) : ViewModel() {
    val results = MutableStateFlow<Map<String, com.workoutmaker.app.data.ActivityAnalysis>>(emptyMap())
    val busy = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    fun analyze(activityId: String, force: Boolean = false) = viewModelScope.launch {
        busy.value = activityId
        error.value = null
        runCatching { repo.analyzeActivity(activityId, force) }
            .onSuccess { results.value = results.value + (activityId to it) }
            .onFailure { error.value = it.message }
        busy.value = null
    }

    // Auto-load an analysis that already exists (e.g. computed in the background
    // after a sync) without ever triggering a fresh LLM run.
    private val peeked = mutableSetOf<String>()
    fun peek(activityId: String) = viewModelScope.launch {
        if (activityId in peeked || results.value.containsKey(activityId)) return@launch
        peeked += activityId
        runCatching { repo.analyzeActivity(activityId, peek = true) }
            .onSuccess { if (it.ok) results.value = results.value + (activityId to it) }
    }
}

// The execution-verdict card: score vs the plan, or the prompt to analyze when
// it hasn't run yet. Owns the peek so the sibling analysis cards below can just
// read the cached result (they share this same ViewModel instance).
@Composable
internal fun AnalysisScoreCard(
    activity: CompletedActivity,
    vm: ActivityAnalysisViewModel = hiltViewModel(),
) {
    val results by vm.results.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    val error by vm.error.collectAsStateSafe()
    val a = results[activity.id]

    // Display a background-computed analysis immediately, no button press needed.
    LaunchedEffect(activity.id) { vm.peek(activity.id) }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Workout analysis", color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            if (a != null) {
                TextButton(onClick = { vm.analyze(activity.id, force = true) }, enabled = busy == null) {
                    Text("Re-analyze")
                }
            }
        }

        if (a == null) {
            Text(
                "See how well you stuck to the plan: an execution score, target vs actual pace, splits, and AI coach feedback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GhostButton(
                onClick = { vm.analyze(activity.id) },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                Text(if (busy == activity.id) "  Analyzing…" else "  Analyze this workout")
            }
            error?.let {
                Text(com.workoutmaker.app.ui.components.friendlyError(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            return@SectionCard
        }

        if (a.score != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExecutionRing(a.score!!)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(a.label ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    a.planned_title?.let {
                        Text("vs “$it”", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            Text(a.label ?: "No planned session to compare against.", style = MaterialTheme.typography.bodyMedium)
        }
        a.components.forEach { c ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${c.score}/100", style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, color = scoreColor(c.score))
                }
                ScoreBar(c.score)
                Text(c.detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// The coach's narrative takeaway for this session.
@Composable
private fun CoachFeedbackCard(
    activity: CompletedActivity,
    vm: ActivityAnalysisViewModel = hiltViewModel(),
) {
    val results by vm.results.collectAsStateSafe()
    val a = results[activity.id] ?: return
    if (a.feedback.isNullOrBlank()) return
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            SectionLabel("Coach feedback" + (a.feedback_provider?.let { " · $it" } ?: ""), color = MaterialTheme.colorScheme.primary)
        }
        Text(a.feedback!!, style = MaterialTheme.typography.bodyMedium)
    }
}

// Time-series graphs from the recording: pace + HR (with their planned target
// bands), then cadence and power when the device recorded them.
@Composable
private fun ActivityChartsSection(
    activity: CompletedActivity,
    vm: ActivityAnalysisViewModel = hiltViewModel(),
) {
    val results by vm.results.collectAsStateSafe()
    val a = results[activity.id] ?: return
    val series = a.series ?: return

    if (series.pace.any { it != null }) {
        SectionCard {
            SectionLabel(
                "Pace · /km" + (a.target?.takeIf { it.pace_lo != null && it.pace_hi != null }?.let { t ->
                    " · target ${t.zones} ${fmtPaceSec(t.pace_lo!!.toInt())}-${fmtPaceSec(t.pace_hi!!.toInt())}"
                } ?: ""),
                color = ChartPace,
            )
            PaceChart(series, a.target)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDotSmall("Actual pace", ChartPace)
                if (a.target?.pace_lo != null) LegendDotSmall("Target band", MaterialTheme.colorScheme.secondary)
            }
        }
    }
    if (series.hr.any { it != null }) {
        SectionCard {
            SectionLabel(
                "Heart rate · bpm" + (a.target?.takeIf { it.hr_lo != null && it.hr_hi != null }?.let { " · target ${it.hr_lo}-${it.hr_hi}" } ?: ""),
                color = ChartHr,
            )
            HrChart(series, a.target)
        }
    }
    if (series.cadence.any { it != null }) {
        SectionCard {
            SectionLabel("Cadence · spm", color = ChartCadence)
            com.workoutmaker.app.ui.components.LineChart(
                t = series.t, values = series.cadence, color = ChartCadence,
                formatY = { "${it.toInt()}" }, xLabels = timeAxis(series.t),
                formatX = { fmtClock(it.toInt()) }, height = 120.dp,
            )
        }
    }
    if (series.power.any { it != null }) {
        SectionCard {
            SectionLabel("Power · W", color = ChartPower)
            com.workoutmaker.app.ui.components.LineChart(
                t = series.t, values = series.power, color = ChartPower,
                formatY = { "${it.toInt()}" }, xLabels = timeAxis(series.t),
                formatX = { fmtClock(it.toInt()) }, height = 120.dp,
            )
        }
    }
}

// "(0, "N min")" time-axis labels from a seconds series.
private fun timeAxis(t: List<Double>): Pair<String, String> =
    "0" to "${((t.lastOrNull() ?: 0.0) / 60).toInt()} min"

// Per-kilometre splits with pace and average HR.
@Composable
private fun SplitsCard(
    activity: CompletedActivity,
    vm: ActivityAnalysisViewModel = hiltViewModel(),
) {
    val results by vm.results.collectAsStateSafe()
    val a = results[activity.id] ?: return
    if (a.splits.isEmpty()) return
    val banded = a.splits.count { it.in_band != null }
    val onTarget = a.splits.count { it.in_band == true }
    SectionCard {
        SectionLabel(
            "Splits" + if (banded > 0) " · $onTarget/$banded on target" else "",
            color = mossAccent(),
        )
        a.splits.forEach { s ->
            // Tint the pace by whether the km landed in the planned target band.
            val paceColor = when (s.in_band) {
                true -> com.workoutmaker.app.ui.theme.BandGreen
                false -> com.workoutmaker.app.ui.theme.BandAmber
                null -> MaterialTheme.colorScheme.onSurface
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("km ${if (s.km % 1.0 == 0.0) s.km.toInt().toString() else s.km.toString()}",
                    Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${fmtPaceSec(s.sec)} /km", style = MaterialTheme.typography.bodyMedium, color = paceColor)
                s.in_band?.let { Text(if (it) "  ✓" else "  ✗", style = MaterialTheme.typography.bodySmall, color = paceColor) }
                Spacer(Modifier.weight(1f))
                s.avg_hr?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Favorite, contentDescription = "Heart rate",
                            modifier = Modifier.size(13.dp), tint = ChartHr)
                        Text(" $it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun scoreColor(score: Int) = when {
    score >= 75 -> MaterialTheme.colorScheme.primary
    score >= 55 -> com.workoutmaker.app.ui.theme.amberAccent()
    else -> MaterialTheme.colorScheme.error
}

@Composable
internal fun ExecutionRing(score: Int) {
    val color = scoreColor(score)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(72.dp)) {
            drawArc(track, -90f, 360f, false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 14f))
            drawArc(color, -90f, 360f * (score / 100f), false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 14f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text("$score", style = MaterialTheme.typography.titleLarge, color = color)
    }
}

@Composable
internal fun ScoreBar(score: Int) {
    Box(
        Modifier.fillMaxWidth().height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            Modifier.fillMaxWidth((score / 100f).coerceIn(0.02f, 1f)).height(6.dp)
                .background(scoreColor(score), RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun LegendDotSmall(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Text("  $label", style = MaterialTheme.typography.labelSmall)
    }
}

// Time-in-zone breakdown: one labelled bar per HR zone (Z1 easy → Zn max), with
// the time spent and its share of the session. Trailing empty zones are dropped.
@Composable
private fun HrZoneCard(zoneTimes: List<Int>) {
    val zones = zoneTimes.dropLastWhile { it == 0 }
    if (zones.isEmpty()) return
    val total = zones.sum().coerceAtLeast(1)
    val zoneColors = listOf(mossAccent(), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, com.workoutmaker.app.ui.theme.amberAccent(), MaterialTheme.colorScheme.error)
    SectionCard {
        SectionLabel("Time in HR zones", color = MaterialTheme.colorScheme.secondary)
        zones.forEachIndexed { i, secs ->
            val frac = secs / total.toFloat()
            val color = zoneColors.getOrElse(i) { zoneColors.last() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Z${i + 1}",
                    Modifier.width(28.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Box(
                        Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(10.dp)
                            .background(color, RoundedCornerShape(5.dp)),
                    )
                }
                Text(
                    "${fmtClock(secs)} · ${(frac * 100 + 0.5f).toInt()}%",
                    Modifier.padding(start = 10.dp).width(88.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

// Actual pace line over time with the planned pace band shaded behind it.
// Y axis is inverted (faster = higher), as runners expect.
@Composable
private fun PaceChart(
    series: com.workoutmaker.app.data.AnalysisSeries,
    target: com.workoutmaker.app.data.AnalysisTarget?,
) {
    val paces = series.pace.filterNotNull()
    if (paces.isEmpty()) return
    // Clamp the scale to a sensible window so one GPS blip doesn't flatten it.
    val lo = paces.min().coerceAtLeast(120.0).let { minOf(it, target?.pace_lo ?: it) } - 10
    val hi = paces.max().coerceAtMost(720.0).let { maxOf(it, target?.pace_hi ?: it) } + 10
    com.workoutmaker.app.ui.components.LineChart(
        t = series.t,
        values = series.pace,
        color = ChartPace,
        formatY = { com.workoutmaker.app.data.Zones.formatPace(it.toInt().coerceAtLeast(0)) },
        xLabels = timeAxis(series.t),
        formatX = { fmtClock(it.toInt()) },
        inverted = true,
        band = target?.takeIf { it.pace_lo != null && it.pace_hi != null }?.let { it.pace_lo!! to it.pace_hi!! },
        bandColor = MaterialTheme.colorScheme.secondary,
        minOverride = lo,
        maxOverride = hi,
        height = 150.dp,
    )
}

@Composable
internal fun HrChart(
    series: com.workoutmaker.app.data.AnalysisSeries,
    target: com.workoutmaker.app.data.AnalysisTarget?,
) {
    val hrs = series.hr.filterNotNull()
    if (hrs.isEmpty()) return
    com.workoutmaker.app.ui.components.LineChart(
        t = series.t,
        values = series.hr,
        // Hard requirement: the HR line is ALWAYS red, regardless of theme.
        color = ChartHr,
        formatY = { "${it.toInt()}" },
        xLabels = timeAxis(series.t),
        formatX = { fmtClock(it.toInt()) },
        band = target?.takeIf { it.hr_lo != null && it.hr_hi != null }?.let { it.hr_lo!!.toDouble() to it.hr_hi!!.toDouble() },
        bandColor = MaterialTheme.colorScheme.primary,
        height = 130.dp,
    )
}
