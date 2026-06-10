package com.workoutmaker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workoutmaker.app.data.DailySummary
import com.workoutmaker.app.data.GenerateRequest
import com.workoutmaker.app.data.Workout
import com.workoutmaker.app.data.WorkoutRepository
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.ChipRow
import com.workoutmaker.app.ui.components.GhostButton
import com.workoutmaker.app.ui.components.InfoIcon
import com.workoutmaker.app.ui.components.InsetStat
import com.workoutmaker.app.ui.components.Metrics
import com.workoutmaker.app.ui.components.SkeletonCard
import com.workoutmaker.app.ui.components.QuoteBlock
import com.workoutmaker.app.ui.components.ScreenScaffold
import com.workoutmaker.app.ui.components.SectionCard
import com.workoutmaker.app.ui.components.SectionLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: WorkoutRepository,
    private val location: com.workoutmaker.app.data.LocationProvider,
) : ViewModel() {
    val summary = MutableStateFlow<DailySummary?>(null)
    val fitness = MutableStateFlow<com.workoutmaker.app.data.IntervalsStats?>(null)
    val loading = MutableStateFlow(true)
    val generating = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    // When the data on screen was last fetched successfully (null until the
    // first load) — lets the header distinguish fresh from stale/offline data.
    val lastSyncAt = MutableStateFlow<Long?>(null)
    val offline = MutableStateFlow(false)

    fun load() = viewModelScope.launch {
        loading.value = true
        runCatching { repo.dailySummary() }
            .onSuccess {
                summary.value = it
                lastSyncAt.value = System.currentTimeMillis()
                offline.value = false
            }
            .onFailure {
                error.value = it.message
                offline.value = summary.value != null
            }
        runCatching { repo.intervalsStats() }.onSuccess { fitness.value = it }
        loading.value = false
    }

    val adjusting = MutableStateFlow(false)
    val feedbackStatus = MutableStateFlow<String?>(null)

    fun hasLocation(): Boolean = location.hasPermission()

    fun generate() = viewModelScope.launch {
        generating.value = true
        val loc = location.lastKnown()
        runCatching {
            repo.generateWorkout(GenerateRequest(type = "auto", duration = 60, lat = loc?.first, lon = loc?.second))
        }.onFailure { error.value = it.message }
        generating.value = false
        load()
        repo.refreshMemory() // fire-and-forget; updates rolling athlete notes
    }

    fun adjust(instruction: String) = viewModelScope.launch {
        val base = summary.value?.today_workout?.workout_json ?: return@launch
        adjusting.value = true
        runCatching {
            repo.adjustWorkout(base, instruction, java.time.LocalDate.now().toString())
        }.onFailure { error.value = it.message }
        adjusting.value = false
        load()
    }

    // Unified regenerate: if a workout exists and the user typed a tweak, revise it
    // with that instruction; otherwise generate a fresh one (weather-aware).
    fun regenerate(tweak: String) = viewModelScope.launch {
        val base = summary.value?.today_workout?.workout_json
        generating.value = true
        runCatching {
            if (base != null && tweak.isNotBlank()) {
                repo.adjustWorkout(base, tweak, java.time.LocalDate.now().toString())
            } else {
                val loc = location.lastKnown()
                repo.generateWorkout(GenerateRequest(type = "auto", duration = 60, lat = loc?.first, lon = loc?.second))
            }
        }.onFailure { error.value = it.message }
        generating.value = false
        load()
        repo.refreshMemory()
    }

    fun submitFeedback(difficulty: String, rpe: Int?) = viewModelScope.launch {
        val today = summary.value?.today_workout
        val date = java.time.LocalDate.now().toString()
        runCatching {
            if (today?.id != null) {
                repo.markPlannedComplete(today.id, date, completed = true, difficulty = difficulty, rpe = rpe)
            } else {
                repo.submitFeedback(
                    com.workoutmaker.app.data.WorkoutFeedback(date = date, completed = true, actual_rpe = rpe, difficulty = difficulty),
                )
            }
        }.onSuccess {
            feedbackStatus.value = "✓ Marked done — your next workout will adapt."
            repo.refreshMemory()
            load()
        }.onFailure { feedbackStatus.value = it.message }
    }

    fun skipToday() = viewModelScope.launch {
        val today = summary.value?.today_workout ?: return@launch
        val date = java.time.LocalDate.now().toString()
        runCatching { repo.markPlannedComplete(today.id, date, completed = false, difficulty = null, rpe = null) }
            .onSuccess { feedbackStatus.value = "Marked skipped — the plan will rebuild gradually."; repo.refreshMemory(); load() }
            .onFailure { feedbackStatus.value = it.message }
    }
}

@Composable
fun HomeScreen(vm: HomeViewModel = hiltViewModel()) {
    val summary by vm.summary.collectAsStateSafe()
    val fitness by vm.fitness.collectAsStateSafe()
    val loading by vm.loading.collectAsStateSafe()
    val generating by vm.generating.collectAsStateSafe()
    val adjusting by vm.adjusting.collectAsStateSafe()
    val feedbackStatus by vm.feedbackStatus.collectAsStateSafe()
    val error by vm.error.collectAsStateSafe()

    LaunchedEffect(Unit) { vm.load() }

    // Ask for coarse location only when first generating (for weather); proceed
    // regardless of the answer.
    val locLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { vm.generate() }
    fun startGenerate() {
        if (vm.hasLocation()) vm.generate()
        else locLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    val today = java.time.LocalDate.now().toString()
    val lastSyncAt by vm.lastSyncAt.collectAsStateSafe()
    val offline by vm.offline.collectAsStateSafe()
    val syncNote = when {
        offline -> "$today · offline — showing last data"
        lastSyncAt != null -> {
            val t = java.time.Instant.ofEpochMilli(lastSyncAt!!)
                .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            "$today · synced ${t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
        }
        else -> today
    }
    ScreenScaffold(
        title = "Today",
        subtitle = syncNote,
        isRefreshing = loading,
        onRefresh = { vm.load() },
    ) { mod ->
        if (loading && summary == null) {
            Column(mod, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonCard(lines = 3)
                SkeletonCard(lines = 2)
                SkeletonCard(lines = 4)
            }
            return@ScreenScaffold
        }
        val s = summary
        if (s == null) {
            SectionCard(mod, title = "Couldn't load today") {
                Text(error ?: "Check your connection and try again.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { vm.load() }, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
            }
            return@ScreenScaffold
        }

        // Readiness — a high-level summary by default (ring + headline + one-line
        // takeaway). The underlying signals (HRV, resting HR, sleep, wellness,
        // load, VO₂) live behind a "Details" drill-in so the dashboard stays calm.
        val rec = s.recovery
        val band = rec?.band ?: s.readiness.band
        val score = rec?.score ?: s.readiness.score
        val wellnessVal = rec?.wellness ?: s.readiness.components.wellness
        var showDetails by remember { mutableStateOf(false) }
        SectionCard(mod) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReadinessRing(score, band)
                Column(
                    Modifier.padding(start = 16.dp).weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            readinessHeadline(band),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = readinessColor(band),
                        )
                        InfoIcon("Recovery & readiness", Metrics.RECOVERY)
                    }
                    rec?.summary?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Drill-in toggle. Collapsed by default — the signals are one tap away.
            androidx.compose.material3.TextButton(
                onClick = { showDetails = !showDetails },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(if (showDetails) "Hide details" else "Details", style = MaterialTheme.typography.labelLarge)
                Icon(
                    if (showDetails) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }

            androidx.compose.animation.AnimatedVisibility(visible = showDetails) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Recovery signals + load — every field is the same width; the
                    // trailing 48dp slot holds a trend badge, an info ⓘ, or nothing.
                    rec?.hrv?.let { MetricRow("HRV", "${"%.0f".format(it.latest)} ms") { TrendBadge(it, higherIsBetter = true) } }
                    rec?.rhr?.let { MetricRow("Resting HR", "${"%.0f".format(it.latest)} bpm") { TrendBadge(it, higherIsBetter = false) } }
                    rec?.sleep?.let { sl ->
                        val avg = sl.avgHours?.let { " · avg ${hoursToHm(it)}" } ?: ""
                        MetricRow("Sleep", "${hoursToHm(sl.hours)}$avg")
                    }
                    MetricRow("Wellness", "${"%.1f".format(wellnessVal)} / 5") { InfoIcon("Wellness", Metrics.WELLNESS) }
                    MetricRow("Weekly load", "${s.weekly_load.tss} / ${s.weekly_load.target} TSS") {
                        InfoIcon("Training Stress Score (TSS)", Metrics.TSS)
                    }
                    s.vo2max?.let { v ->
                        MetricRow("VO₂ max", "${"%.1f".format(v.value)} ml/kg/min") {
                            v.change?.takeIf { kotlin.math.abs(it) >= 0.1 }?.let { c ->
                                Text(
                                    "${if (c > 0) "↑" else "↓"}${"%.1f".format(kotlin.math.abs(c))}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = readinessColor(if (c >= 0) "green" else "red"),
                                )
                            }
                        }
                    }
                }
            }
            SectionLabel("AI · ${s.active_llm_provider}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        s.goal?.let { g -> GoalCard(mod, g) }

        SectionCard(mod, title = "Today's Workout") {
            val w = s.today_workout?.workout_json
            if (w != null) WorkoutDetail(w)
            else Text("No workout planned yet.", style = MaterialTheme.typography.bodyMedium)

            // Tweak field guides the (re)generation; the button sits below it and
            // regenerates WITH whatever you typed (no separate "Adjust").
            var instruction by remember { mutableStateOf("") }
            if (w != null) {
                androidx.compose.material3.OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tweak the regenerate (optional)") },
                    placeholder = { Text("e.g. shorter, I'm sore, add hills, make it easy") },
                )
            }
            Button(
                onClick = {
                    if (w == null) startGenerate()
                    else { vm.regenerate(instruction.trim()); instruction = "" }
                },
                enabled = !generating,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (generating) "Generating…" else if (w != null) "Regenerate" else "Generate workout") }

            if (w != null) {
                // #1: the rating appears only AFTER you say you did the workout.
                when {
                    s.today_workout?.completed == true ->
                        Text("✓ Completed today", style = MaterialTheme.typography.titleSmall, color = com.workoutmaker.app.ui.theme.Sage)
                    else -> {
                        var didIt by remember(s.today_workout?.id) { mutableStateOf(false) }
                        if (!didIt) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { didIt = true }, modifier = Modifier.weight(1f)) {
                                    Text("✓ I did this workout")
                                }
                                GhostButton(onClick = { vm.skipToday() }) { Text("Skip") }
                            }
                        } else {
                            SectionLabel("How did it go?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("too_easy" to "Too easy", "just_right" to "Just right", "too_hard" to "Too hard").forEach { (k, label) ->
                                    GhostButton(
                                        onClick = { vm.submitFeedback(k, null) },
                                        modifier = Modifier.weight(1f),
                                    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                }
                feedbackStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }

        fitness?.let { f -> FitnessSection(mod, f) }
    }
}

@Composable
private fun FitnessSection(mod: Modifier, f: com.workoutmaker.app.data.IntervalsStats) {
    if (!f.connected) {
        SectionCard(mod, title = "Fitness (Intervals.icu)") {
            Text("Connect Intervals.icu in Settings to see your fitness curve, HR zones and recent activities here.",
                style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    if (f.error != null) {
        SectionCard(mod, title = "Fitness (Intervals.icu)") {
            Text("Couldn't load Intervals data: ${f.error}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        return
    }

    SectionCard(mod, title = "Fitness" + (f.athlete_name?.let { " · $it" } ?: "")) {
        f.summary?.let { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                    FitnessStat("Fitness", "%.0f".format(s.ctl), "CTL", com.workoutmaker.app.ui.theme.Sage)
                    FitnessStat("Fatigue", "%.0f".format(s.atl), "ATL", com.workoutmaker.app.ui.theme.Sand)
                    FitnessStat("Form", "%+.0f".format(s.tsb), tsbLabel(s.tsb), tsbColor(s.tsb))
                    FitnessStat("Ramp", "%+.1f".format(s.ramp), "7d CTL", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                InfoIcon("Your fitness curve", Metrics.FITNESS)
            }
        }
        // E3: load guardrail derived from fatigue:fitness ratio + CTL ramp.
        f.summary?.let { s -> LoadGuard(s) }
        if (f.fitness.size >= 2) {
            FitnessChart(f.fitness, Modifier.fillMaxWidth().padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDot("Fitness (CTL)", com.workoutmaker.app.ui.theme.Sage)
                LegendDot("Fatigue (ATL)", com.workoutmaker.app.ui.theme.Sand)
            }
        }
    }

    // Heart-rate zones intentionally omitted from Home — they're reference data,
    // not an at-a-glance dashboard signal. (Still available in Intervals.icu.)

    if (f.activities.isNotEmpty()) {
        SectionCard(mod, title = "Recent activities") {
            f.activities.take(8).forEach { a ->
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("${a.date} · ${a.name}", style = MaterialTheme.typography.labelMedium)
                    val meta = buildString {
                        a.distance_km?.let { append("%.1f km".format(it)) }
                        a.duration_min?.let { if (isNotEmpty()) append(" · "); append("${it} min") }
                        a.avg_hr?.let { if (isNotEmpty()) append(" · "); append("${it} bpm") }
                        a.tss?.let { if (isNotEmpty()) append(" · "); append("${it.toInt()} TSS") }
                    }
                    if (meta.isNotBlank()) {
                        Text(meta, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// E3 — acute:chronic load guardrail. Uses the fatigue:fitness ratio (ATL/CTL),
// a well-established overload proxy, plus the 7-day CTL ramp. Flags when you're
// building too fast (injury risk) or detraining.
@Composable
private fun LoadGuard(s: com.workoutmaker.app.data.FitnessSummary) {
    if (s.ctl < 1.0) return // not enough data to judge
    val ratio = s.atl / s.ctl
    val (color, headline, detail) = when {
        ratio >= 1.5 -> Triple(
            com.workoutmaker.app.ui.theme.BandRed, "High overload risk",
            "Fatigue is well above your fitness (ratio %.2f). Take easy days — an injury/illness spike zone.".format(ratio))
        ratio >= 1.3 || s.ramp >= 8 -> Triple(
            com.workoutmaker.app.ui.theme.BandAmber, "Ramping fast",
            "Building quickly (ratio %.2f, ramp %+.1f). Fine short-term; don't hold it for many weeks.".format(ratio, s.ramp))
        ratio < 0.8 && s.ramp < 0 -> Triple(
            com.workoutmaker.app.ui.theme.BandAmber, "Detraining / very fresh",
            "Load is low relative to fitness (ratio %.2f). Good for a taper; otherwise add volume.".format(ratio))
        else -> Triple(
            com.workoutmaker.app.ui.theme.BandGreen, "Load well managed",
            "Fatigue:fitness ratio %.2f sits in the productive 0.8–1.3 range.".format(ratio))
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .background(color.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Column(Modifier.padding(start = 10.dp)) {
            Text(headline, style = MaterialTheme.typography.titleSmall, color = color)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FitnessStat(label: String, value: String, sub: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Text("  $label", style = MaterialTheme.typography.labelSmall)
    }
}

private fun tsbColor(tsb: Double): Color = when {
    tsb > 5 -> com.workoutmaker.app.ui.theme.BandGreen
    tsb < -20 -> com.workoutmaker.app.ui.theme.BandRed
    tsb < -10 -> com.workoutmaker.app.ui.theme.BandAmber
    else -> com.workoutmaker.app.ui.theme.Sage
}

private fun tsbLabel(tsb: Double): String = when {
    tsb > 15 -> "fresh"
    tsb > 5 -> "ready"
    tsb < -20 -> "high fatigue"
    tsb < -10 -> "building"
    else -> "neutral"
}

@Composable
private fun FitnessChart(points: List<com.workoutmaker.app.data.FitnessPoint>, modifier: Modifier) {
    val ctlColor = com.workoutmaker.app.ui.theme.Sage
    val atlColor = com.workoutmaker.app.ui.theme.Sand
    val grid = Color(0xFF333535)
    androidx.compose.foundation.Canvas(modifier.size(width = 0.dp, height = 140.dp).fillMaxWidth()) {
        val maxV = (points.maxOf { maxOf(it.ctl, it.atl) }).coerceAtLeast(1.0)
        val w = size.width
        val h = size.height
        val stepX = if (points.size > 1) w / (points.size - 1) else w
        fun y(v: Double) = h - (v / maxV * h).toFloat()

        // baseline grid
        drawLine(grid, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(w, h), strokeWidth = 2f)

        fun line(sel: (com.workoutmaker.app.data.FitnessPoint) -> Double, color: Color) {
            val path = androidx.compose.ui.graphics.Path()
            points.forEachIndexed { i, p ->
                val px = stepX * i
                val py = y(sel(p))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
        }
        line({ it.atl }, atlColor)
        line({ it.ctl }, ctlColor)
    }
}

@Composable
private fun GoalCard(mod: Modifier, g: com.workoutmaker.app.data.GoalProgress) {
    SectionCard(mod, title = "Goal · ${g.goal}") {
        // Prefer an exact day countdown when we have the race date.
        val days = g.goal_date?.let {
            runCatching { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), java.time.LocalDate.parse(it)) }.getOrNull()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    when {
                        days != null && days > 0 -> "$days days to go"
                        days != null && days == 0L -> "Race day! 🏁"
                        g.weeks_to_goal != null -> "${g.weeks_to_goal} weeks to go"
                        else -> "No date set"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                g.goal_date?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(g.phase, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("CTL ${if (g.ctl_trend >= 0) "+" else ""}${"%.1f".format(g.ctl_trend)}/28d",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Periodization phase timeline — highlights the block you're in now.
        if (g.weeks_to_goal != null) PhaseStrip(g.phase)
        Text(g.on_track, style = MaterialTheme.typography.bodyMedium, color = com.workoutmaker.app.ui.theme.Sage)
    }
}

@Composable
private fun PhaseStrip(current: String) {
    val phases = listOf("Base", "Build", "Peak", "Taper")
    val curIdx = phases.indexOfFirst { it.equals(current, ignoreCase = true) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        phases.forEachIndexed { i, p ->
            val active = i == curIdx
            val done = curIdx >= 0 && i < curIdx
            val c = when {
                active -> MaterialTheme.colorScheme.primary
                done -> com.workoutmaker.app.ui.theme.Sage.copy(alpha = 0.5f)
                else -> Color(0xFF333535)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().size(width = 0.dp, height = 6.dp)
                    .background(c, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
                Text(p, style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ReadinessRing(score: Int, band: String) {
    val color = when (band) {
        "green" -> com.workoutmaker.app.ui.theme.BandGreen
        "amber" -> com.workoutmaker.app.ui.theme.BandAmber
        else -> com.workoutmaker.app.ui.theme.BandRed
    }
    Box(Modifier.size(88.dp), Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(88.dp)) {
            drawArc(Color(0xFF333535), -90f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 18f))
            drawArc(color, -90f, 360f * (score / 100f), false, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 18f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text("$score", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun readinessHeadline(band: String) = when (band) {
    "green" -> "Ready to train"
    "amber" -> "Train with care"
    else -> "Prioritise recovery"
}

// A uniform metric row: a full-width inset field + a fixed 48dp trailing slot
// (trend badge, info ⓘ, or empty) so every field box is exactly the same length.
@Composable
private fun MetricRow(label: String, value: String, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        InsetStat(label, value, Modifier.weight(1f))
        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) { trailing() }
    }
}

// 7.5 → "7h 30m", 8.083 → "8h 05m".
private fun hoursToHm(hours: Double): String {
    val totalMin = (hours * 60).roundToInt()
    return "${totalMin / 60}h ${"%02d".format(totalMin % 60)}m"
}

@Composable
private fun TrendBadge(t: com.workoutmaker.app.data.RecoveryTrend, higherIsBetter: Boolean) {
    val pct = (t.deltaPct * 100).roundToInt()
    val good = if (higherIsBetter) pct >= 0 else pct <= 0
    val arrow = if (pct > 0) "↑" else if (pct < 0) "↓" else "→"
    Text(
        "$arrow${kotlin.math.abs(pct)}%",
        style = MaterialTheme.typography.labelMedium,
        color = readinessColor(if (good) "green" else "red"),
    )
}

private fun readinessColor(band: String) = when (band) {
    "green" -> com.workoutmaker.app.ui.theme.BandGreen
    "amber" -> com.workoutmaker.app.ui.theme.BandAmber
    else -> com.workoutmaker.app.ui.theme.BandRed
}

@Composable
fun WorkoutDetail(w: Workout) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(w.title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        ChipRow(
            listOfNotNull(
                w.type.takeIf { it.isNotBlank() },
                "${w.duration_minutes.toInt()} min".takeIf { w.duration_minutes > 0 },
                "RPE ${w.rpe_target.toInt()}".takeIf { w.rpe_target > 0 },
                "~${w.tss_estimate.toInt()} TSS".takeIf { w.tss_estimate > 0 },
            ),
        )
        if (w.coach_note.isNotBlank()) QuoteBlock(w.coach_note)
        w.sections.forEach { section -> WorkoutSectionItem(section) }
    }
}

@Composable
private fun WorkoutSectionItem(section: com.workoutmaker.app.data.WorkoutSection) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(30.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                section.name.trim().firstOrNull()?.uppercase() ?: "•",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(section.name, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            section.exercises.forEach { ex ->
                val meta = buildString {
                    if (ex.sets > 0 && ex.reps.isNotEmpty()) append("${ex.sets}×${ex.reps}")
                    ex.weight_kg?.let { append(" · ${it}kg") }
                    ex.pace_zone?.let { append(" · pace $it") }
                    ex.hr_zone?.let { append(" · HR $it") }
                }
                Text(
                    "${ex.name}${if (meta.isNotBlank()) "  ·  $meta" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
