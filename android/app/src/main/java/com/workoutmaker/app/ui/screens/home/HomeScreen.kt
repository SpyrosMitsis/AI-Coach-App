package com.workoutmaker.app.ui.screens.home

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.workoutmaker.app.data.Workout
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.GhostButton
import com.workoutmaker.app.ui.components.InfoIcon
import com.workoutmaker.app.ui.components.Metrics
import com.workoutmaker.app.ui.components.SkeletonCard
import com.workoutmaker.app.ui.components.QuoteBlock
import com.workoutmaker.app.ui.components.ScreenScaffold
import com.workoutmaker.app.ui.components.SectionCard
import com.workoutmaker.app.ui.components.SectionLabel
import kotlinx.coroutines.launch
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.workoutmaker.app.ui.components.DetailOverlay
import com.workoutmaker.app.ui.components.EmptyState
import com.workoutmaker.app.ui.components.LocalAppSnackbar
import com.workoutmaker.app.util.friendlyError
import com.workoutmaker.app.ui.theme.amberAccent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import com.workoutmaker.app.ui.screens.history.ActivityDetailScreen
import com.workoutmaker.app.ui.screens.history.StrengthSessionDetailScreen
import com.workoutmaker.app.ui.screens.settings.DAYS
import com.workoutmaker.app.data.submitFeedback
import com.workoutmaker.app.data.undoSkip


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpenRecoveryHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
) {
    val summary by vm.summary.collectAsStateSafe()
    val fitness by vm.fitness.collectAsStateSafe()
    val loading by vm.loading.collectAsStateSafe()
    val refreshing by vm.refreshing.collectAsStateSafe()
    val generating by vm.generating.collectAsStateSafe()
    val adjusting by vm.adjusting.collectAsStateSafe()
    val feedbackStatus by vm.feedbackStatus.collectAsStateSafe()
    val submittingFeedback by vm.submittingFeedback.collectAsStateSafe()
    val error by vm.error.collectAsStateSafe()
    val wellnessToday by vm.wellnessToday.collectAsStateSafe()
    val wellnessLoaded by vm.wellnessLoaded.collectAsStateSafe()
    val wellnessBusy by vm.wellnessBusy.collectAsStateSafe()
    val brief by vm.brief.collectAsStateSafe()
    val weekReviewNote by vm.weekReviewNote.collectAsStateSafe()
    val profile by vm.profile.collectAsStateSafe()
    val haptics = LocalHapticFeedback.current

    // Transient confirmations ("✓ Marked done") and action errors surface through
    // the one app-wide snackbar instead of an easy-to-miss inline status line.
    val snackbar = LocalAppSnackbar.current
    LaunchedEffect(feedbackStatus) {
        val s = feedbackStatus ?: return@LaunchedEffect
        snackbar?.show(if (s.startsWith("✓") || s.startsWith("⟳")) s else friendlyError(s))
        vm.feedbackStatus.value = null
    }

    // Reload on every resume (delivered once on first composition too) so a
    // dashboard left open overnight doesn't keep showing yesterday's workout.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.load()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Ask once for notification permission (Android 13+) — without it the
    // morning check-in and evening feedback reminders can never appear.
    val context = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* reminders simply stay silent if denied */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Ask for coarse location only when first generating (for weather); proceed
    // regardless of the answer.
    val locLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.generate() }
    fun startGenerate() {
        if (vm.hasLocation()) vm.generate()
        else locLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    val selectedDate by vm.selectedDate.collectAsStateSafe()
    val isToday = selectedDate == LocalDate.now()
    val dateStr = selectedDate.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
    val lastSyncAt by vm.lastSyncAt.collectAsStateSafe()
    val offline by vm.offline.collectAsStateSafe()
    fun hhmm(epoch: Long) = Instant.ofEpochMilli(epoch)
        .atZone(ZoneId.systemDefault()).toLocalDateTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))
    val syncNote = when {
        !isToday -> "$dateStr · history"
        offline -> "$dateStr · offline" +
            (lastSyncAt?.let { ", data from ${hhmm(it)}" } ?: ", showing last data")
        lastSyncAt != null -> "$dateStr · synced ${hhmm(lastSyncAt!!)}"
        else -> dateStr
    }
    // "Today" / "Yesterday" / "N days ago" headline for the page.
    val daysBack = ChronoUnit.DAYS.between(selectedDate, LocalDate.now())
    val titleLabel = when (daysBack) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> "$daysBack days ago"
    }
    // Full-screen detail overlay when a recent activity is tapped — reuses the
    // same page as Calendar/History.
    val openedStrength by vm.openedStrength.collectAsStateSafe()
    openedStrength?.let { d ->
        BackHandler { vm.closeStrength() }
        DetailOverlay {
            StrengthSessionDetailScreen(
                w = d.workout,
                sets = d.sets,
                watch = d.watch,
                onBack = { vm.closeStrength() },
            )
        }
        return
    }

    val openedActivity by vm.openedActivity.collectAsStateSafe()
    openedActivity?.let { act ->
        BackHandler { vm.closeActivity() }
        DetailOverlay {
            ActivityDetailScreen(act, planned = null) { vm.closeActivity() }
        }
        return
    }

    // The date headline itself is the control: tap it to open the month calendar;
    // the ‹ › arrows in the top bar step a day. No separate date row.
    var showCalendar by remember { mutableStateOf(false) }
    val marked by vm.markedDates.collectAsStateSafe()

    ScreenScaffold(
        title = titleLabel,
        subtitle = syncNote,
        eyebrow = "DAILY READINESS",
        onTitleClick = { showCalendar = true },
        actions = {
            IconButton(onClick = { vm.prevDay() }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
            }
            IconButton(onClick = { vm.nextDay() }, enabled = !isToday) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Next day",
                    tint = if (isToday) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        isRefreshing = refreshing,
        onRefresh = { vm.refresh() },
    ) { mod ->
        if (showCalendar) {
            DayPickerDialog(
                selected = selectedDate,
                marked = marked,
                onPick = { vm.goToDay(it); showCalendar = false },
                onToday = { vm.goToToday(); showCalendar = false },
                onDismiss = { showCalendar = false },
            )
        }
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
                Text(
                    error?.let { friendlyError(it) }
                        ?: "Check your connection and try again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
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
        var showManualEntry by remember { mutableStateOf(false) }
        if (showManualEntry) {
            ManualRecoveryDialog(
                onDismiss = { showManualEntry = false },
                onSave = { hrv, rhr, sleepMin ->
                    vm.saveManualRecovery(hrv, rhr, sleepMin)
                    showManualEntry = false
                },
            )
        }
        // Watch-freshness check: distinguish "synced today, no HRV yet" from "the
        // watch hasn't reported in days" — the latter gets a loud banner since the
        // whole readiness read is then running blind on the objective signals.
        val staleDays = s.recovery_synced_date?.let {
            runCatching {
                ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.now())
            }.getOrNull()
        }
        val isStale = isToday && (s.recovery_synced_date == null || (staleDays != null && staleDays >= 2L))
        // Setup nudge for onboarding skippers: quiet, dismissable, disappears
        // for good the moment the profile is actually usable.
        val showSetupNudge by vm.showSetupNudge.collectAsStateSafe()
        if (showSetupNudge) {
            SectionCard(mod) {
                SectionLabel("FINISH SETUP")
                Text(
                    "Your coach is running on guesses. Two minutes of setup, your sports, week and goals, makes every workout yours.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("Set up now") }
                    TextButton(onClick = { vm.dismissSetupNudge() }) { Text("Later") }
                }
            }
        }

        // Nothing measured: no check-in, nothing synced. The server still returns
        // 50/amber (the neutral midpoint of its own defaults), and drawing that as
        // a ring told the athlete they were "moderately recovered" on the strength
        // of no evidence at all. Show the absence instead, and the way to fix it.
        val unmeasured = rec?.basis == "none"

        SectionCard(mod) {
            if (isStale && !unmeasured) RecoveryStaleBanner(s.recovery_synced_date)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unmeasured) ReadinessUnknownRing() else ReadinessRing(score, band)
                Column(
                    Modifier.padding(start = 16.dp).weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (unmeasured) "Readiness not measured" else readinessHeadline(band),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (unmeasured) MaterialTheme.colorScheme.onSurfaceVariant
                            else readinessColor(band),
                        )
                        InfoIcon("Recovery & readiness", Metrics.RECOVERY)
                    }
                    rec?.summary?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // The coach's proactive note for today — the human voice on top of the
            // numbers. Streams in after the dashboard; absent → the static headline
            // above already carries the readiness read, so nothing extra shows.
            brief?.takeIf { it.isNotBlank() }?.let { QuoteBlock(it) }

            // Drill-in toggle. Collapsed by default — the signals are one tap away.
            TextButton(
                onClick = { showDetails = !showDetails },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
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
                    // Why the score is what it is — compact chips ("HRV ↑", "Sleep ↓").
                    rec?.drivers?.takeIf { it.isNotEmpty() }?.let { ds ->
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) { ds.forEach { RecoveryDriverChip(it) } }
                    }
                    // Recovery signals + load — every field is the same width; the
                    // trailing 48dp slot holds a trend badge, an info ⓘ, or nothing.
                    // latest == null → today's reading hasn't synced from Intervals;
                    // say so explicitly rather than showing yesterday's number.
                    rec?.hrv?.let { h ->
                        if (h.latest != null) MetricRow("HRV", "${"%.0f".format(h.latest)} ms") { TrendBadge(h, higherIsBetter = true) }
                        else MetricRow("HRV", "No reading today")
                    }
                    rec?.rhr?.let { r ->
                        if (r.latest != null) MetricRow("Resting HR", "${"%.0f".format(r.latest)} bpm") { TrendBadge(r, higherIsBetter = false) }
                        else MetricRow("Resting HR", "No reading today")
                    }
                    rec?.sleep?.let { sl ->
                        val avg = sl.avgHours?.let { " · avg ${hoursToHm(it)}" } ?: ""
                        if (sl.hours != null) MetricRow("Sleep", "${hoursToHm(sl.hours)}$avg")
                        else MetricRow("Sleep", "No data today$avg")
                    }
                    rec?.sleep?.score?.let { sc ->
                        MetricRow("Sleep score", "${sc.toInt()} / 100") {
                            Text(
                                sleepScoreLabel(sc),
                                style = MaterialTheme.typography.labelMedium,
                                color = readinessColor(sleepScoreBand(sc)),
                            )
                        }
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
                    // Utility actions — manual entry (today only) + the trends screen.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (isToday) {
                            TextButton(
                                onClick = { showManualEntry = true },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            ) { Text("Log manually", style = MaterialTheme.typography.labelLarge) }
                        }
                        TextButton(
                            onClick = onOpenRecoveryHistory,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        ) { Text("Trends →", style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }

            // Data freshness — so a missing HRV/sleep reads as "watch hasn't
            // synced", not "nothing's wrong". Relative for today; dated for history.
            val syncedLabel = run {
                val synced = s.recovery_synced_date
                when {
                    synced == null -> "No recovery data synced"
                    !isToday -> "Recovery data through ${friendlyDate(synced)}"
                    else -> {
                        val days = runCatching {
                            ChronoUnit.DAYS.between(
                                LocalDate.parse(synced), LocalDate.now(),
                            )
                        }.getOrNull()
                        when {
                            days == null -> "Synced ${friendlyDate(synced)}"
                            days <= 0L -> "Recovery synced today"
                            days == 1L -> "Recovery last synced yesterday"
                            else -> "Recovery last synced $days days ago"
                        }
                    }
                }
            }
            Text(
                syncedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel("AI · ${s.active_llm_provider}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Daily wellness check-in — shown only once today's is still unanswered
        // (energy == null) AND last night's sleep has synced from Intervals.icu
        // (rec.sleep present), so it surfaces when you actually wake up rather
        // than at midnight. Fallback: if sleep still hasn't arrived by late
        // morning (watch not worn / didn't sync), show it anyway so the check-in
        // is never permanently locked out.
        // Gate on TODAY's sleep specifically (hours != null) — not just any sleep
        // object — so the card waits for this morning's sync instead of firing at
        // midnight on yesterday's data.
        val sleptToday = rec?.sleep?.hours != null
        val pastFallback = LocalTime.now() >= LocalTime.of(11, 0)
        if (isToday && wellnessLoaded && wellnessToday?.energy == null && (sleptToday || pastFallback)) {
            WellnessCheckinCard(mod, busy = wellnessBusy) { e, sore -> vm.saveWellness(e, sore) }
        }

        s.goal?.let { g -> GoalCard(mod, g) }

        s.week_review?.let { wr -> WeekReviewCard(mod, wr, if (isToday) weekReviewNote else null) }

        SectionCard(mod, title = if (isToday) "Today's Workout" else "Workout") {
            val tw = s.today_workout
            val w = tw?.workout_json
            val isRest = w?.type == "rest"
            if (w != null && tw.skipped && !tw.completed) {
                // Skipped: collapse to one line + Undo instead of the full card.
                Text(
                    w.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.LineThrough,
                )
                Text(
                    if (isToday) "Skipped, rest matters too. The plan will adapt and rebuild gradually."
                    else "Skipped that day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isToday) GhostButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.undoSkip()
                }) { Text("Undo skip") }
                return@SectionCard
            }
            if (w != null) WorkoutDetail(w, profile)
            else EmptyState(
                title = if (isToday) "No workout planned yet" else "Nothing was planned",
                subtitle = if (isToday) "Generate one below, or ask your coach to plan your day."
                else "No workout was on the plan for this day.",
                icon = Icons.Filled.FitnessCenter,
            )

            // The generate/tweak/rating controls only make sense for today — past
            // days are read-only history.
            if (isToday) {
                // Tweak field guides the (re)generation; the button sits below it and
                // regenerates WITH whatever you typed (no separate "Adjust").
                var instruction by rememberSaveable { mutableStateOf("") }
                if (w != null) {
                    OutlinedTextField(
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
            }

            if (w != null) {
                // #1: the rating appears only AFTER you say you did the workout.
                when {
                    s.today_workout?.completed == true ->
                        Text(
                            if (isRest) (if (isToday) "✓ Rested today" else "✓ Rested")
                            else (if (isToday) "✓ Completed today" else "✓ Completed"),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        )
                    !isToday ->
                        Text("Not logged as done.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Rest days don't need RPE/difficulty — one quiet tap to mark it.
                    isRest -> {
                        GhostButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.submitFeedback("just_right", null)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !submittingFeedback,
                        ) { Text("Mark rest taken") }
                    }
                    else -> {
                        var didIt by rememberSaveable(s.today_workout?.id) { mutableStateOf(false) }
                        if (!didIt) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        didIt = true
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("✓ I did this workout")
                                }
                                GhostButton(onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.skipToday()
                                }, enabled = !submittingFeedback) { Text("Skip") }
                            }
                        } else {
                            // RPE first (increasing-bars histogram), then the
                            // difficulty word — both feed the next generations.
                            var rpe by rememberSaveable(s.today_workout?.id) { mutableStateOf<Int?>(null) }
                            SectionLabel("How hard was it? (RPE)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            RpeBars(selected = rpe, onSelect = { rpe = it })
                            Text(
                                rpe?.let { "RPE $it, ${rpeWord(it)}" }
                                    ?: "Tap a bar: 1 = very easy, 10 = max effort (optional)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SectionLabel("How did it go?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("too_easy" to "Too easy", "just_right" to "Just right", "too_hard" to "Too hard").forEach { (k, label) ->
                                    GhostButton(
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            vm.submitFeedback(k, rpe)
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = !submittingFeedback,
                                    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // How the latest session (today's or yesterday's) actually went — the
        // analyzer's verdict, one tap from the full breakdown. Server-picked;
        // absent whenever nothing recent has an analysis.
        s.debrief?.let { d -> SessionDebriefCard(mod, d) { vm.openDebrief(d) } }

        fitness?.let { f -> FitnessSection(mod, f, onOpenActivity = { vm.openActivity(it) }) }
    }
}






















@Composable
fun ReadinessRing(score: Int, band: String) {
    val color = when (band) {
        "green" -> MaterialTheme.colorScheme.primary
        "amber" -> amberAccent()
        else -> MaterialTheme.colorScheme.error
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(88.dp), Alignment.Center) {
        Canvas(Modifier.size(88.dp)) {
            drawArc(track, -90f, 360f, false, style = Stroke(width = 18f))
            drawArc(color, -90f, 360f * (score / 100f), false, style = Stroke(width = 18f, cap = StrokeCap.Round))
        }
        Text("$score", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

// The same ring with no arc and no number: the shape of the card is preserved,
// but nothing is claimed. A score here would be the placeholder 50.
@Composable
fun ReadinessUnknownRing() {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(88.dp), Alignment.Center) {
        Canvas(Modifier.size(88.dp)) {
            drawArc(track, -90f, 360f, false, style = Stroke(width = 18f))
        }
        Text(
            "?",
            fontSize = 24.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun readinessHeadline(band: String) = when (band) {
    "green" -> "Ready to train"
    "amber" -> "Train with care"
    else -> "Prioritise recovery"
}







@Composable
internal fun readinessColor(band: String) = when (band) {
    "green" -> MaterialTheme.colorScheme.primary
    "amber" -> amberAccent()
    else -> MaterialTheme.colorScheme.error
}
