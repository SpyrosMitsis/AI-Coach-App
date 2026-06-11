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
import com.workoutmaker.app.ui.components.GhostButton
import com.workoutmaker.app.ui.components.InsetStat
import com.workoutmaker.app.ui.components.ScreenScaffold
import com.workoutmaker.app.ui.components.SectionCard
import com.workoutmaker.app.ui.components.SectionLabel
import com.workoutmaker.app.ui.theme.BandRed
import com.workoutmaker.app.ui.theme.Moss
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

        SectionCard {
            SectionLabel("Summary", color = Sage)
            activity.distanceKm?.let { if (it > 0) InsetStat("Distance", "%.2f km".format(it)) }
            activity.durationMin?.let { if (it > 0) InsetStat("Duration", "$it min") }
            activity.paceSecPerKm?.let { InsetStat("Avg pace", "${fmtPaceSec(it)} /km") }
            activity.avg_hr?.let { InsetStat("Avg HR", "$it bpm") }
            activity.maxHr?.let { InsetStat("Max HR", "$it bpm") }
            activity.avgPower?.let { InsetStat("Avg power", "$it W") }
            activity.avgCadence?.let { InsetStat("Avg cadence", "$it") }
            activity.elevationGain?.let { InsetStat("Elevation", "$it m") }
            activity.calories?.let { InsetStat("Calories", "$it kcal") }
            activity.tss?.let { if (it > 0) InsetStat("Training load (TSS)", "${it.toInt()}") }
        }

        // E3-style load context: what this did to your fitness.
        if (activity.ctl != null || activity.atl != null) {
            SectionCard {
                SectionLabel("Fitness after this", color = Sand)
                activity.ctl?.let { InsetStat("Fitness (CTL)", "%.0f".format(it)) }
                activity.atl?.let { InsetStat("Fatigue (ATL)", "%.0f".format(it)) }
                if (activity.ctl != null && activity.atl != null) {
                    InsetStat("Form (TSB)", "%.0f".format(activity.ctl!! - activity.atl!!))
                }
            }
        }

        // Planned vs actual on this date.
        planned?.let { p ->
            SectionCard {
                SectionLabel("On the plan that day", color = Moss)
                Text(p.workout_json.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (looksLike(p.type, activity.type)) "✓ You did your planned ${p.type}."
                    else "You had a ${p.type} planned but did this instead.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (looksLike(p.type, activity.type)) Sage else Sand,
                )
            }
        }

        // Garmin-style execution analysis: score, target-vs-actual charts,
        // splits, and AI coach feedback.
        if (!activity.isManual) AnalysisSection(activity)
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
}

@Composable
internal fun AnalysisSection(
    activity: CompletedActivity,
    vm: ActivityAnalysisViewModel = hiltViewModel(),
) {
    val results by vm.results.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    val error by vm.error.collectAsStateSafe()
    val a = results[activity.id]

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Workout analysis", color = Sage)
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
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            return@SectionCard
        }

        // --- Execution score ------------------------------------------------
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
            Text(a.label ?: "No plan to compare against.", style = MaterialTheme.typography.bodyMedium)
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

    // --- Pace chart with target band ----------------------------------------
    val series = a?.series
    if (a != null && series != null && series.pace.any { it != null }) {
        SectionCard {
            SectionLabel(
                "Pace" + (a.target?.takeIf { it.pace_lo != null }?.let { t ->
                    " · target ${t.zones} ${fmtPaceSec(t.pace_lo!!.toInt())}–${fmtPaceSec(t.pace_hi!!.toInt())} /km"
                } ?: ""),
                color = Sage,
            )
            PaceChart(series, a.target)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDotSmall("Actual pace", Sage)
                if (a.target?.pace_lo != null) LegendDotSmall("Target band", Sand)
            }
        }
    }
    if (a != null && series != null && series.hr.any { it != null }) {
        SectionCard {
            SectionLabel(
                "Heart rate" + (a.target?.takeIf { it.hr_lo != null }?.let { " · target ${it.hr_lo}–${it.hr_hi} bpm" } ?: ""),
                color = Sand,
            )
            HrChart(series, a.target)
        }
    }

    // --- Splits ---------------------------------------------------------------
    if (a != null && a.splits.isNotEmpty()) {
        SectionCard {
            SectionLabel("Splits", color = Moss)
            a.splits.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("km ${if (s.km % 1.0 == 0.0) s.km.toInt().toString() else s.km.toString()}",
                        Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${fmtPaceSec(s.sec)} /km", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    s.avg_hr?.let { Text("♥ $it", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }

    // --- AI feedback ------------------------------------------------------------
    if (a != null && !a.feedback.isNullOrBlank()) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp), tint = Sage)
                Spacer(Modifier.width(6.dp))
                SectionLabel("Coach feedback" + (a.feedback_provider?.let { " · $it" } ?: ""), color = Sage)
            }
            Text(a.feedback!!, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun scoreColor(score: Int) = when {
    score >= 75 -> com.workoutmaker.app.ui.theme.BandGreen
    score >= 55 -> com.workoutmaker.app.ui.theme.BandAmber
    else -> BandRed
}

@Composable
internal fun ExecutionRing(score: Int) {
    val color = scoreColor(score)
    Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(72.dp)) {
            drawArc(androidx.compose.ui.graphics.Color(0xFF333535), -90f, 360f, false,
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

// Actual pace line over time with the planned pace band shaded behind it.
// Y axis is inverted (faster = higher), as runners expect.
@Composable
private fun PaceChart(
    series: com.workoutmaker.app.data.AnalysisSeries,
    target: com.workoutmaker.app.data.AnalysisTarget?,
) {
    val line = Sage
    val band = Sand
    val paces = series.pace.filterNotNull()
    if (paces.isEmpty()) return
    // Clamp the scale to a sensible window so one GPS blip doesn't flatten it.
    val pLo = (paces.min()).coerceAtLeast(120.0)
    val pHi = (paces.max()).coerceAtMost(720.0)
    val lo = minOf(pLo, target?.pace_lo ?: pLo) - 10
    val hi = maxOf(pHi, target?.pace_hi ?: pHi) + 10
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val w = size.width
        val h = size.height
        fun y(p: Double) = (((p - lo) / (hi - lo)) * h).toFloat() // slower → lower
        // Target band
        if (target?.pace_lo != null && target.pace_hi != null) {
            val top = y(target.pace_lo!!)
            val bottom = y(target.pace_hi!!)
            drawRect(
                band.copy(alpha = 0.18f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(w, (bottom - top).coerceAtLeast(2f)),
            )
        }
        val n = series.t.size
        if (n < 2) return@Canvas
        val tMax = series.t.last().coerceAtLeast(1.0)
        var pen: androidx.compose.ui.geometry.Offset? = null
        for (i in 0 until n) {
            val p = series.pace.getOrNull(i)
            if (p == null) { pen = null; continue }
            val pt = androidx.compose.ui.geometry.Offset(
                (series.t[i] / tMax * w).toFloat(), y(p.coerceIn(lo, hi)),
            )
            pen?.let { drawLine(line, it, pt, strokeWidth = 4f) }
            pen = pt
        }
    }
}

@Composable
private fun HrChart(
    series: com.workoutmaker.app.data.AnalysisSeries,
    target: com.workoutmaker.app.data.AnalysisTarget?,
) {
    val line = Sand
    val hrs = series.hr.filterNotNull()
    if (hrs.isEmpty()) return
    val lo = minOf(hrs.min(), (target?.hr_lo ?: Int.MAX_VALUE).toDouble()) - 5
    val hi = maxOf(hrs.max(), (target?.hr_hi ?: 0).toDouble()) + 5
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val w = size.width
        val h = size.height
        fun y(v: Double) = (h - ((v - lo) / (hi - lo)) * h).toFloat()
        if (target?.hr_lo != null && target.hr_hi != null) {
            val top = y(target.hr_hi!!.toDouble())
            val bottom = y(target.hr_lo!!.toDouble())
            drawRect(
                Sage.copy(alpha = 0.15f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(w, (bottom - top).coerceAtLeast(2f)),
            )
        }
        val n = series.t.size
        if (n < 2) return@Canvas
        val tMax = series.t.last().coerceAtLeast(1.0)
        var pen: androidx.compose.ui.geometry.Offset? = null
        for (i in 0 until n) {
            val v = series.hr.getOrNull(i)
            if (v == null) { pen = null; continue }
            val pt = androidx.compose.ui.geometry.Offset((series.t[i] / tMax * w).toFloat(), y(v))
            pen?.let { drawLine(line, it, pt, strokeWidth = 4f) }
            pen = pt
        }
    }
}
