package com.workoutmaker.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.workoutmaker.app.ui.components.chartLabel
import com.workoutmaker.app.ui.components.fillUnderLine
import com.workoutmaker.app.ui.components.hGridLine
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
    private val strength: com.workoutmaker.app.strength.StrengthRepository,
    private val location: com.workoutmaker.app.data.LocationProvider,
) : ViewModel() {
    val summary = MutableStateFlow<DailySummary?>(null)
    val fitness = MutableStateFlow<com.workoutmaker.app.data.IntervalsStats?>(null)
    // Thresholds for turning a planned step's zone into a concrete target range.
    val profile = MutableStateFlow<com.workoutmaker.app.data.TrainingProfile?>(null)
    val loading = MutableStateFlow(true)
    // A pull-to-refresh in progress — distinct from `loading` because it first
    // forces an Intervals.icu re-sync (which can take a few seconds) before the
    // dashboard reload, and we want the refresh spinner up for that whole time.
    val refreshing = MutableStateFlow(false)
    val generating = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    // When the data on screen was last fetched successfully (null until the
    // first load) — lets the header distinguish fresh from stale/offline data.
    val lastSyncAt = MutableStateFlow<Long?>(null)
    val offline = MutableStateFlow(false)

    // Today's wellness check-in (null until loaded; energy == null → unanswered).
    val wellnessToday = MutableStateFlow<com.workoutmaker.app.data.WellnessCheckin?>(null)
    val wellnessLoaded = MutableStateFlow(false)
    val wellnessBusy = MutableStateFlow(false)

    // The coach's proactive daily note. Null until it streams in (it's a separate,
    // possibly-slow generation) or when the briefing is disabled / offline — Home
    // falls back to its static readiness headline in that case.
    val brief = MutableStateFlow<String?>(null)

    // The coach's weekly recap (one LLM call/week, cached). Today-only, streams in
    // after the dashboard like the brief; null → the card shows just its stats.
    val weekReviewNote = MutableStateFlow<String?>(null)

    // Home can page back through past days to see that day's dashboard as it was
    // (readiness, planned/completed workout, load). Capped at today — no future.
    // Today-only controls (wellness check-in, generate, RPE) hide on past days.
    val selectedDate = MutableStateFlow(java.time.LocalDate.now())
    val isViewingToday get() = selectedDate.value == java.time.LocalDate.now()

    fun goToDay(date: java.time.LocalDate) {
        val capped = if (date.isAfter(java.time.LocalDate.now())) java.time.LocalDate.now() else date
        if (capped == selectedDate.value) return
        selectedDate.value = capped
        // A different day's data is unrelated to what's on screen — clear it so we
        // don't flash the previous day's cards while the new day loads.
        summary.value = null
        brief.value = null
        weekReviewNote.value = null
        wellnessToday.value = null
        wellnessLoaded.value = false
        load()
    }
    fun prevDay() = goToDay(selectedDate.value.minusDays(1))
    fun nextDay() = goToDay(selectedDate.value.plusDays(1))
    fun goToToday() = goToDay(java.time.LocalDate.now())

    // Dates that have a completed activity — drawn as dots in the calendar picker
    // so it's obvious which past days have something to look at. Loaded once.
    val markedDates = MutableStateFlow<Set<java.time.LocalDate>>(emptySet())
    private fun loadMarks() = viewModelScope.launch {
        val from = java.time.LocalDate.now().minusDays(400).toString()
        runCatching { repo.completedActivities(from) }.onSuccess { acts ->
            markedDates.value = acts
                .mapNotNull { runCatching { java.time.LocalDate.parse(it.date) }.getOrNull() }
                .toSet()
        }
    }

    fun load() = viewModelScope.launch {
        loading.value = true
        val date = selectedDate.value
        val viewingToday = date == java.time.LocalDate.now()
        runCatching { repo.dailySummary(date) }
            .onSuccess {
                summary.value = it
                lastSyncAt.value = System.currentTimeMillis()
                offline.value = false
            }
            .onFailure { e ->
                error.value = e.message
                // Offline (incl. cold start): serve the last cached dashboard
                // with the time it was fetched, instead of a bare error. Only for
                // today — the cache holds the latest day, not arbitrary history.
                if (viewingToday && summary.value == null) {
                    repo.cachedDailySummary()?.let { (cached, fetchedAt) ->
                        summary.value = cached
                        lastSyncAt.value = fetchedAt.takeIf { it > 0 }
                    }
                }
                offline.value = summary.value != null
            }
        runCatching { repo.wellnessCheckin(date.toString()) }
            .onSuccess { wellnessToday.value = it; wellnessLoaded.value = true }
        if (profile.value == null) runCatching { repo.loadProfile() }.onSuccess { profile.value = it }
        runCatching { repo.intervalsStats() }.onSuccess { fitness.value = it }
        loading.value = false
        // The briefing is today-only — it's a live coaching note, and we never
        // spend an LLM call generating one for a historical day. Streams in
        // separately so it never holds up the dashboard; cached per-day.
        if (viewingToday) {
            viewModelScope.launch {
                runCatching { repo.coachBrief() }
                    .onSuccess { brief.value = it }
                    .onFailure { com.workoutmaker.app.util.AppLog.w("home", "coachBrief failed", it) }
            }
            viewModelScope.launch {
                val now = java.time.LocalDate.now()
                val monday = now.minusDays((now.dayOfWeek.value - 1).toLong())
                runCatching { repo.weekReview(monday.toString()) }
                    .onSuccess { weekReviewNote.value = it }
                    .onFailure { com.workoutmaker.app.util.AppLog.w("home", "weekReview failed", it) }
            }
        }
        if (markedDates.value.isEmpty()) loadMarks()
    }

    // Pull-to-refresh: actually re-pull from Intervals.icu (HRV/sleep/fitness) and
    // THEN reload the dashboard — so a day showing "No reading today" has a way to
    // fix itself, rather than just re-reading the same stale server cache. Only on
    // today; history days have nothing new to sync. sync failures are swallowed —
    // the reload still runs so the user at least gets the latest server state.
    fun refresh() = viewModelScope.launch {
        if (!isViewingToday) { load(); return@launch }
        refreshing.value = true
        runCatching { repo.syncIntervals() }
        load().join()
        refreshing.value = false
    }

    // Manual HRV / resting-HR / sleep entry for a day the watch didn't sync.
    // Writes onto today's wellness row and refreshes so the score/drivers update.
    fun saveManualRecovery(hrvMs: Double?, restingHr: Int?, sleepMinutes: Int?) = viewModelScope.launch {
        runCatching { repo.upsertManualRecovery(selectedDate.value.toString(), hrvMs, restingHr, sleepMinutes) }
            .onSuccess { load() }
            .onFailure { error.value = it.message }
    }

    fun saveWellness(energy: Int, soreness: Int) = viewModelScope.launch {
        wellnessBusy.value = true
        val checkin = com.workoutmaker.app.data.WellnessCheckin(
            date = java.time.LocalDate.now().toString(),
            energy = energy, soreness = soreness,
        )
        runCatching { repo.upsertWellness(checkin) }
            .onSuccess { wellnessToday.value = checkin; load() } // readiness uses it
            .onFailure { error.value = it.message }
        wellnessBusy.value = false
    }

    val adjusting = MutableStateFlow(false)
    val feedbackStatus = MutableStateFlow<String?>(null)
    // In-flight guard: the done/skip buttons stay tappable until load() flips the
    // completed state, so without this a fast double-tap double-submits feedback.
    val submittingFeedback = MutableStateFlow(false)

    // Tapping a Home "recent activity" digest resolves it to the full
    // CompletedActivity (rich Intervals data) so it opens the SAME detail page
    // used by Calendar/History. Falls back to the thin digest if the synced row
    // isn't found (e.g. very recent activity not yet in completed_activities).
    val openedActivity = MutableStateFlow<com.workoutmaker.app.data.CompletedActivity?>(null)

    // A strength session is logged in-app AND (usually) recorded on the watch. When
    // one is tapped, we open the SAME unified detail page the Calendar uses, which
    // merges the logged sets with the watch recording — instead of the watch-only
    // endurance page that ignores everything that was lifted.
    data class StrengthDetail(
        val workout: com.workoutmaker.app.strength.WorkoutEntity,
        val sets: List<com.workoutmaker.app.strength.SetEntity>,
        val watch: com.workoutmaker.app.data.CompletedActivity?,
    )
    val openedStrength = MutableStateFlow<StrengthDetail?>(null)

    fun openActivity(a: com.workoutmaker.app.data.IntervalsActivity) = viewModelScope.launch {
        val from = java.time.LocalDate.now().minusDays(120).toString()
        val completed = runCatching { repo.completedActivities(from) }.getOrNull().orEmpty()

        // Strength path: if an in-app strength session was logged on this date, open
        // the merged detail (logged + watch) rather than the watch-only one.
        val zone = java.time.ZoneId.systemDefault()
        val logged = runCatching { strength.recentWorkouts(500) }.getOrNull()?.firstOrNull { w ->
            java.time.Instant.ofEpochMilli(w.startedAt).atZone(zone).toLocalDate().toString() == a.date
        }
        if (logged != null) {
            val sets = runCatching { strength.setsForWorkout(logged.id) }.getOrNull().orEmpty()
            val watch = completed.firstOrNull { c -> c.date == a.date && looksLike("strength", c.type) }
            openedStrength.value = StrengthDetail(logged, sets, watch)
            return@launch
        }

        val match = completed.firstOrNull { c ->
            c.date == a.date &&
                (c.displayName.equals(a.name, ignoreCase = true) ||
                    (a.name.isBlank() && (c.type ?: "").equals(a.type, ignoreCase = true)))
        }
        openedActivity.value = match ?: a.toCompleted()
    }
    fun closeActivity() { openedActivity.value = null }
    fun closeStrength() { openedStrength.value = null }

    private fun com.workoutmaker.app.data.IntervalsActivity.toCompleted() =
        com.workoutmaker.app.data.CompletedActivity(
            id = "digest:$date:$name",
            intervals_id = "digest",
            type = type.ifBlank { null },
            date = date,
            duration_seconds = duration_min?.let { it * 60 },
            distance_m = distance_km?.let { it * 1000.0 },
            avg_hr = avg_hr,
            tss = tss,
            data_json = if (name.isNotBlank()) {
                kotlinx.serialization.json.JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(name)))
            } else {
                null
            },
        )

    fun hasLocation(): Boolean = location.hasPermission()

    fun generate() = viewModelScope.launch {
        generating.value = true
        val loc = location.lastKnown()
        runCatching {
            repo.generateWorkout(GenerateRequest(type = "auto", lat = loc?.first, lon = loc?.second))
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
                repo.generateWorkout(GenerateRequest(type = "auto", lat = loc?.first, lon = loc?.second))
            }
        }.onFailure { error.value = it.message }
        generating.value = false
        load()
        repo.refreshMemory()
    }

    fun submitFeedback(difficulty: String, rpe: Int?) = viewModelScope.launch {
        if (!submittingFeedback.compareAndSet(false, true)) return@launch
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
            feedbackStatus.value = "✓ Marked done, your next workout will adapt."
            repo.refreshMemory()
            load()
        }.onFailure { feedbackStatus.value = it.message }
        submittingFeedback.value = false
    }

    fun skipToday() = viewModelScope.launch {
        if (!submittingFeedback.compareAndSet(false, true)) return@launch
        val today = summary.value?.today_workout ?: run { submittingFeedback.value = false; return@launch }
        val date = java.time.LocalDate.now().toString()
        runCatching { repo.markPlannedComplete(today.id, date, completed = false, difficulty = null, rpe = null) }
            .onSuccess { feedbackStatus.value = null; repo.refreshMemory(); load() }
            .onFailure { feedbackStatus.value = it.message }
        submittingFeedback.value = false
    }

    fun undoSkip() = viewModelScope.launch {
        val today = summary.value?.today_workout ?: return@launch
        runCatching { repo.undoSkip(today.id) }
            .onSuccess { feedbackStatus.value = null; load() }
            .onFailure { feedbackStatus.value = it.message }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpenRecoveryHistory: () -> Unit = {},
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
    val snackbar = com.workoutmaker.app.ui.components.LocalAppSnackbar.current
    androidx.compose.runtime.LaunchedEffect(feedbackStatus) {
        val s = feedbackStatus ?: return@LaunchedEffect
        snackbar?.show(if (s.startsWith("✓") || s.startsWith("⟳")) s else com.workoutmaker.app.ui.components.friendlyError(s))
        vm.feedbackStatus.value = null
    }

    // Reload on every resume (delivered once on first composition too) so a
    // dashboard left open overnight doesn't keep showing yesterday's workout.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.load()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Ask once for notification permission (Android 13+) — without it the
    // morning check-in and evening feedback reminders can never appear.
    val context = androidx.compose.ui.platform.LocalContext.current
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { /* reminders simply stay silent if denied */ }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Ask for coarse location only when first generating (for weather); proceed
    // regardless of the answer.
    val locLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { vm.generate() }
    fun startGenerate() {
        if (vm.hasLocation()) vm.generate()
        else locLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    val selectedDate by vm.selectedDate.collectAsStateSafe()
    val isToday = selectedDate == java.time.LocalDate.now()
    val dateStr = selectedDate.format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM"))
    val lastSyncAt by vm.lastSyncAt.collectAsStateSafe()
    val offline by vm.offline.collectAsStateSafe()
    fun hhmm(epoch: Long) = java.time.Instant.ofEpochMilli(epoch)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    val syncNote = when {
        !isToday -> "$dateStr · history"
        offline -> "$dateStr · offline" +
            (lastSyncAt?.let { ", data from ${hhmm(it)}" } ?: ", showing last data")
        lastSyncAt != null -> "$dateStr · synced ${hhmm(lastSyncAt!!)}"
        else -> dateStr
    }
    // "Today" / "Yesterday" / "N days ago" headline for the page.
    val daysBack = java.time.temporal.ChronoUnit.DAYS.between(selectedDate, java.time.LocalDate.now())
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
        com.workoutmaker.app.ui.components.DetailOverlay {
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
        com.workoutmaker.app.ui.components.DetailOverlay {
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
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
            }
            IconButton(onClick = { vm.nextDay() }, enabled = !isToday) {
                Icon(
                    Icons.Filled.KeyboardArrowRight,
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
                    error?.let { com.workoutmaker.app.ui.components.friendlyError(it) }
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
                java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(it), java.time.LocalDate.now())
            }.getOrNull()
        }
        val isStale = isToday && (s.recovery_synced_date == null || (staleDays != null && staleDays >= 2L))
        SectionCard(mod) {
            if (isStale) RecoveryStaleBanner(s.recovery_synced_date)
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

            // The coach's proactive note for today — the human voice on top of the
            // numbers. Streams in after the dashboard; absent → the static headline
            // above already carries the readiness read, so nothing extra shows.
            brief?.takeIf { it.isNotBlank() }?.let { QuoteBlock(it) }

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
                    // Why the score is what it is — compact chips ("HRV ↑", "Sleep ↓").
                    rec?.drivers?.takeIf { it.isNotEmpty() }?.let { ds ->
                        androidx.compose.foundation.layout.FlowRow(
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
                            androidx.compose.material3.TextButton(
                                onClick = { showManualEntry = true },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            ) { Text("Log manually", style = MaterialTheme.typography.labelLarge) }
                        }
                        androidx.compose.material3.TextButton(
                            onClick = onOpenRecoveryHistory,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp),
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
                            java.time.temporal.ChronoUnit.DAYS.between(
                                java.time.LocalDate.parse(synced), java.time.LocalDate.now(),
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
        val pastFallback = java.time.LocalTime.now() >= java.time.LocalTime.of(11, 0)
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
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
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
            else com.workoutmaker.app.ui.components.EmptyState(
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

        fitness?.let { f -> FitnessSection(mod, f, onOpenActivity = { vm.openActivity(it) }) }
    }
}

// Manual recovery entry for a day the watch didn't sync. All three fields are
// optional — saving any one writes it onto today's wellness row.
@Composable
private fun ManualRecoveryDialog(onDismiss: () -> Unit, onSave: (Double?, Int?, Int?) -> Unit) {
    var hrv by rememberSaveable { mutableStateOf("") }
    var rhr by rememberSaveable { mutableStateOf("") }
    var sleepH by rememberSaveable { mutableStateOf("") }
    val hrvVal = hrv.trim().toDoubleOrNull()
    val rhrVal = rhr.trim().toIntOrNull()
    val sleepMin = sleepH.trim().toDoubleOrNull()?.let { (it * 60).roundToInt() }
    val canSave = hrvVal != null || rhrVal != null || sleepMin != null
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log recovery manually") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Fill in what you know, leave the rest blank. Saved for today.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = hrv, onValueChange = { hrv = it }, label = { Text("HRV (ms)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = rhr, onValueChange = { rhr = it }, label = { Text("Resting HR (bpm)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = sleepH, onValueChange = { sleepH = it }, label = { Text("Sleep (hours, e.g. 7.5)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(hrvVal, rhrVal, sleepMin) }, enabled = canSave) { Text("Save") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// Morning wellness check-in: two 1–5 scales (energy, soreness) that feed today's
// readiness score. Sleep is pulled automatically from Intervals.icu, so it's no
// longer asked here. Appears only when today is unanswered.
@Composable
private fun WellnessCheckinCard(mod: Modifier, busy: Boolean, onSave: (Int, Int) -> Unit) {
    var energy by rememberSaveable { mutableStateOf<Int?>(null) }
    var soreness by rememberSaveable { mutableStateOf<Int?>(null) }
    SectionCard(mod, title = "How do you feel today?") {
        Text(
            "A quick morning check tunes today's readiness and training. Sleep is pulled from Intervals.icu automatically.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WellnessScale("Energy", "drained", "fresh", energy) { energy = it }
        WellnessScale("Soreness", "none", "very sore", soreness) { soreness = it }
        Button(
            onClick = { onSave(energy ?: 3, soreness ?: 3) },
            enabled = !busy && (energy != null || soreness != null),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Saving…" else "Save check-in") }
    }
}

// One 1–5 row: label, low/high anchor words, and five tap targets.
@Composable
private fun WellnessScale(label: String, low: String, high: String, selected: Int?, onSelect: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..5).forEach { n ->
                val active = selected == n
                val bg by animateColorAsState(
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    label = "wellnessTile",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .size(width = 0.dp, height = 48.dp)
                        .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "Set $label to $n of 5") {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelect(n)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$n",
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(low, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(high, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FitnessSection(
    mod: Modifier,
    f: com.workoutmaker.app.data.IntervalsStats,
    onOpenActivity: (com.workoutmaker.app.data.IntervalsActivity) -> Unit = {},
) {
    if (!f.connected) {
        SectionCard(mod, title = "Fitness (Intervals.icu)") {
            Text("Connect Intervals.icu in Settings to see your fitness curve, HR zones and recent activities here.",
                style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    if (f.error != null) {
        SectionCard(mod, title = "Fitness (Intervals.icu)") {
            Text(com.workoutmaker.app.ui.components.friendlyError(f.error), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        return
    }

    SectionCard(mod, title = "Fitness" + (f.athlete_name?.let { " · $it" } ?: "")) {
        f.summary?.let { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                    FitnessStat("Fitness", "%.0f".format(s.ctl), "CTL", MaterialTheme.colorScheme.primary)
                    FitnessStat("Fatigue", "%.0f".format(s.atl), "ATL", MaterialTheme.colorScheme.secondary)
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
            val latest = f.fitness.last()
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDot("Fitness (CTL) ${"%.0f".format(latest.ctl)}", MaterialTheme.colorScheme.primary)
                LegendDot("Fatigue (ATL) ${"%.0f".format(latest.atl)}", MaterialTheme.colorScheme.secondary)
            }

            // Form (TSB) over time on the Intervals.icu zone backdrop.
            SectionLabel("Form (TSB) · now ${"%+.0f".format(latest.tsb)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FormChart(f.fitness, Modifier.fillMaxWidth())
            SectionLabel("Tap a point for its date & value", color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FORM_ZONES.forEach { z -> LegendDot(z.label, z.color) }
            }
        }
    }

    // Heart-rate zones intentionally omitted from Home — they're reference data,
    // not an at-a-glance dashboard signal. (Still available in Intervals.icu.)

    if (f.activities.isNotEmpty()) {
        SectionCard(mod, title = "Recent activities") {
            f.activities.take(8).forEach { a ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenActivity(a) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
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
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = "Open details",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            MaterialTheme.colorScheme.error, "High overload risk",
            "Fatigue is well above your fitness (ratio %.2f). Take easy days, an injury/illness spike zone.".format(ratio))
        ratio >= 1.3 || s.ramp >= 8 -> Triple(
            com.workoutmaker.app.ui.theme.amberAccent(), "Ramping fast",
            "Building quickly (ratio %.2f, ramp %+.1f). Fine short-term; don't hold it for many weeks.".format(ratio, s.ramp))
        ratio < 0.8 && s.ramp < 0 -> Triple(
            com.workoutmaker.app.ui.theme.amberAccent(), "Detraining / very fresh",
            "Load is low relative to fitness (ratio %.2f). Good for a taper; otherwise add volume.".format(ratio))
        else -> Triple(
            MaterialTheme.colorScheme.primary, "Load well managed",
            "Fatigue:fitness ratio %.2f sits in the productive 0.8-1.3 range.".format(ratio))
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

@Composable
private fun tsbColor(tsb: Double): Color = when {
    tsb > 5 -> MaterialTheme.colorScheme.primary
    tsb < -20 -> MaterialTheme.colorScheme.error
    tsb < -10 -> com.workoutmaker.app.ui.theme.amberAccent()
    else -> MaterialTheme.colorScheme.primary
}

private fun tsbLabel(tsb: Double): String = when {
    tsb > 15 -> "fresh"
    tsb > 5 -> "ready"
    tsb < -20 -> "high fatigue"
    tsb < -10 -> "building"
    else -> "neutral"
}

// Device sleep score (0..100) → short word + readiness band colour.
private fun sleepScoreLabel(score: Double): String = when {
    score >= 85 -> "excellent"
    score >= 70 -> "good"
    score >= 50 -> "fair"
    else -> "poor"
}
private fun sleepScoreBand(score: Double): String = when {
    score >= 70 -> "green"
    score >= 50 -> "amber"
    else -> "red"
}

// Intervals.icu "Form" (TSB) zones, top→bottom. Each is (lower bound, label,
// colour); a band fills from its bound up to the next one.
private data class FormZone(val min: Double, val label: String, val color: Color)
// Vivid fixed zone hues (matching the ChartHr/Pace/Power palette) so the Form
// backdrop and its legend dots read boldly across every theme.
private val FORM_ZONES = listOf(
    FormZone(25.0, "Transition", Color(0xFFFF9F0A)),  // amber
    FormZone(5.0, "Fresh", Color(0xFF0A84FF)),        // blue
    FormZone(-10.0, "Grey zone", Color(0xFF8E8E93)),  // grey
    FormZone(-30.0, "Optimal", Color(0xFF30D158)),    // green
    FormZone(-100.0, "High risk", Color(0xFFFF453A)), // red
)

// Form chart: the TSB line over time on a fixed zone backdrop (mirrors the
// bottom panel of Intervals.icu's fitness page). Bands are translucent so the
// line reads clearly on top.
@Composable
private fun FormChart(points: List<com.workoutmaker.app.data.FitnessPoint>, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.surfaceVariant
    val marker = MaterialTheme.colorScheme.onSurface
    val last = points.last()
    // Tap a point to pin its date + TSB; tap it again (or another) to move/clear.
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val gutterDp = 30.dp
    androidx.compose.foundation.Canvas(
        modifier
            .size(width = 0.dp, height = 130.dp)
            .fillMaxWidth()
            .pointerInput(points) {
                val gl = gutterDp.toPx()
                val plotW = (size.width - gl).coerceAtLeast(1f)
                val step = if (points.size > 1) plotW / (points.size - 1) else plotW
                detectTapGestures { off ->
                    val idx = ((off.x - gl) / step).roundToInt().coerceIn(0, points.size - 1)
                    selected = if (selected == idx) null else idx
                }
            },
    ) {
        val dataMax = points.maxOf { it.tsb }
        val dataMin = points.minOf { it.tsb }
        val top = maxOf(30.0, dataMax + 4)
        val bottom = minOf(-40.0, dataMin - 4)
        val span = (top - bottom).coerceAtLeast(1.0)
        val w = size.width
        val gutterLeft = 30.dp.toPx()
        val labelPad = 14.sp.toPx()
        val h = size.height - labelPad
        val plotW = (w - gutterLeft).coerceAtLeast(1f)
        fun y(v: Double) = (h * (top - v) / span).toFloat().coerceIn(0f, h)
        val stepX = if (points.size > 1) plotW / (points.size - 1) else plotW
        fun x(i: Int) = gutterLeft + stepX * i

        // Zone bands: each fills from the zone above (or the top) down to its min.
        FORM_ZONES.forEachIndexed { i, z ->
            val upper = if (i == 0) top else FORM_ZONES[i - 1].min
            val yU = y(upper.coerceIn(bottom, top))
            val yL = y(z.min.coerceIn(bottom, top))
            if (yL > yU) {
                drawRect(
                    z.color.copy(alpha = 0.30f),
                    topLeft = androidx.compose.ui.geometry.Offset(gutterLeft, yU),
                    size = androidx.compose.ui.geometry.Size(plotW, yL - yU),
                )
            }
        }
        // Zero baseline + axis numbers (in the left gutter).
        drawLine(grid, androidx.compose.ui.geometry.Offset(gutterLeft, y(0.0)), androidx.compose.ui.geometry.Offset(w, y(0.0)), strokeWidth = 2f)
        chartLabel("${top.toInt()}", gutterLeft - 6f, y(top) + 9.sp.toPx() * 0.6f, alignRight = true)
        chartLabel("0", gutterLeft - 6f, y(0.0) - 3f, alignRight = true)
        chartLabel("${bottom.toInt()}", gutterLeft - 6f, h - 2f, alignRight = true)
        chartLabel(points.first().date.takeLast(5), gutterLeft, size.height - 2f)
        chartLabel(last.date.takeLast(5), w, size.height - 2f, alignRight = true)

        // TSB line, coloured by the zone each part falls in — the stretch inside
        // the green band is green, inside red is red, and so on. Each segment is
        // split exactly at the zone boundaries it crosses so the colour flips on
        // the band edge rather than at a data point.
        fun zoneColorOf(v: Double): Color =
            FORM_ZONES.firstOrNull { v >= it.min }?.color ?: FORM_ZONES.last().color
        val boundaries = FORM_ZONES.dropLast(1).map { it.min } // 25, 5, -10, -30
        for (i in 0 until points.size - 1) {
            val v0 = points[i].tsb
            val v1 = points[i + 1].tsb
            val x0 = x(i)
            val x1 = x(i + 1)
            // Fractions (0..1) along this segment where it crosses a zone edge.
            val cuts = boundaries.mapNotNull { b ->
                if ((v0 < b && v1 > b) || (v0 > b && v1 < b)) (b - v0) / (v1 - v0) else null
            }.sorted()
            val ts = listOf(0.0) + cuts + listOf(1.0)
            for (k in 0 until ts.size - 1) {
                val ta = ts[k]
                val tb = ts[k + 1]
                if (tb <= ta) continue
                val mid = v0 + (v1 - v0) * (ta + tb) / 2
                drawLine(
                    zoneColorOf(mid),
                    androidx.compose.ui.geometry.Offset((x0 + (x1 - x0) * ta).toFloat(), y(v0 + (v1 - v0) * ta)),
                    androidx.compose.ui.geometry.Offset((x0 + (x1 - x0) * tb).toFloat(), y(v0 + (v1 - v0) * tb)),
                    strokeWidth = 3.5f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }

        // Pinned point: a vertical guide, a dot, and a "MM-DD · +5" callout. Drawn
        // last so it sits on top of the bands and the line.
        selected?.let { sel ->
            val p = points[sel]
            val px = x(sel)
            val py = y(p.tsb)
            drawLine(marker.copy(alpha = 0.4f), androidx.compose.ui.geometry.Offset(px, 0f), androidx.compose.ui.geometry.Offset(px, h), strokeWidth = 1.5f)
            drawCircle(marker, radius = 4f, center = androidx.compose.ui.geometry.Offset(px, py))
            val txt = "${p.date.takeLast(5)} · ${"%+.0f".format(p.tsb)}"
            val nearRight = px > size.width * 0.6f
            chartLabel(txt, if (nearRight) px - 6f else px + 6f, (y(top) + 9.sp.toPx()).coerceAtLeast(11.sp.toPx()), alignRight = nearRight, color = marker)
        }
    }
}

@Composable
private fun FitnessChart(points: List<com.workoutmaker.app.data.FitnessPoint>, modifier: Modifier) {
    val ctlColor = MaterialTheme.colorScheme.primary
    val atlColor = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.surfaceVariant
    val last = points.last()
    androidx.compose.foundation.Canvas(modifier.size(width = 0.dp, height = 150.dp).fillMaxWidth()) {
        val maxV = (points.maxOf { maxOf(it.ctl, it.atl) }).coerceAtLeast(1.0)
        val w = size.width
        val gutterLeft = 34.dp.toPx() // room for the value axis on the left
        val labelPad = 14.sp.toPx()   // room for the date axis at the bottom
        val h = size.height - labelPad
        val plotW = (w - gutterLeft).coerceAtLeast(1f)
        val stepX = if (points.size > 1) plotW / (points.size - 1) else plotW
        fun x(i: Int) = gutterLeft + stepX * i
        fun y(v: Double) = h - (v / maxV * h).toFloat()

        // Value scale: gridlines + numbers at max / half / 0 (TSS/day load).
        hGridLine(y(maxV), gutterLeft, w)
        hGridLine(y(maxV / 2), gutterLeft, w)
        drawLine(grid, androidx.compose.ui.geometry.Offset(gutterLeft, h), androidx.compose.ui.geometry.Offset(w, h), strokeWidth = 2f)
        chartLabel("${maxV.toInt()}", gutterLeft - 6f, y(maxV) + 9.sp.toPx() * 0.5f, alignRight = true)
        chartLabel("${(maxV / 2).toInt()}", gutterLeft - 6f, y(maxV / 2) + 9.sp.toPx() * 0.35f, alignRight = true)
        chartLabel("0", gutterLeft - 6f, h - 2f, alignRight = true)
        // Date range on the x axis (MM-DD).
        chartLabel(points.first().date.takeLast(5), gutterLeft, size.height - 2f)
        chartLabel(last.date.takeLast(5), w, size.height - 2f, alignRight = true)

        fun line(sel: (com.workoutmaker.app.data.FitnessPoint) -> Double, color: Color) {
            val path = androidx.compose.ui.graphics.Path()
            points.forEachIndexed { i, p ->
                val px = x(i)
                val py = y(sel(p))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            fillUnderLine(path, color, h, 0f, x(0), x(points.size - 1))
            drawPath(
                path, color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 3.5f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
        }
        line({ it.atl }, atlColor)
        line({ it.ctl }, ctlColor)
    }
}

// Month calendar picker: jump to any past day. Future days are disabled; days
// with a completed activity get a dot; today and the selected day are marked.
@Composable
private fun DayPickerDialog(
    selected: java.time.LocalDate,
    marked: Set<java.time.LocalDate>,
    onPick: (java.time.LocalDate) -> Unit,
    onToday: () -> Unit,
    onDismiss: () -> Unit,
) {
    val today = java.time.LocalDate.now()
    var month by remember { mutableStateOf(java.time.YearMonth.from(selected)) }
    val canGoNextMonth = month.isBefore(java.time.YearMonth.from(today))
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Header: Today shortcut · month nav · close.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.TextButton(onClick = onToday) {
                        Text("TODAY", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                    }
                    Text(
                        month.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")).uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    IconButton(onClick = { if (canGoNextMonth) month = month.plusMonths(1) }, enabled = canGoNextMonth) {
                        Icon(
                            Icons.Filled.KeyboardArrowRight, contentDescription = "Next month",
                            tint = if (canGoNextMonth) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                // Weekday header (Sunday-first, matching the grid).
                Row(Modifier.fillMaxWidth()) {
                    listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT").forEach { d ->
                        Text(d, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 6×7 grid. First cell = the Sunday on/before the 1st.
                val first = month.atDay(1)
                val lead = first.dayOfWeek.value % 7 // Mon=1..Sun=7 → Sun=0 offset
                val firstCell = first.minusDays(lead.toLong())
                for (week in 0 until 6) {
                    Row(Modifier.fillMaxWidth()) {
                        for (dow in 0 until 7) {
                            val d = firstCell.plusDays((week * 7 + dow).toLong())
                            DayCell(
                                day = d,
                                inMonth = d.monthValue == month.monthValue,
                                isToday = d == today,
                                isSelected = d == selected,
                                isFuture = d.isAfter(today),
                                marked = d in marked,
                                onClick = { if (!d.isAfter(today)) onPick(d) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: java.time.LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    isFuture: Boolean,
    marked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .aspectRatio(1f)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(enabled = !isFuture, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${day.dayOfMonth}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    isFuture || !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    isToday -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        // Activity dot under days with a completed session (hidden on the
        // selected day, where it'd clash with the filled circle).
        Box(
            Modifier.size(5.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (marked && !isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

// Deterministic last-7-day recap: adherence, load vs target with a trend arrow
// vs last week, the sport split, and the standout session. The coach-voice take
// lives in the briefing at the top; this is the at-a-glance scoreboard.
@Composable
private fun WeekReviewCard(mod: Modifier, wr: com.workoutmaker.app.data.WeekReview, note: String? = null) {
    fun sportLabel(s: String) = when (s) {
        "run" -> "Run"; "ride" -> "Ride"; "swim" -> "Swim"; "strength" -> "Strength"; else -> "Other"
    }
    SectionCard(mod, title = "This week") {
        // The coach's voice on the week, above the deterministic scoreboard.
        note?.takeIf { it.isNotBlank() }?.let { QuoteBlock(it) }
        if (wr.sessions == 0 && wr.adherence.planned == 0) {
            Text(
                "No sessions logged yet this week, it fills in as you train.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        if (wr.adherence.planned > 0) {
            InsetStat(
                "Adherence",
                "${wr.adherence.done}/${wr.adherence.planned} sessions" +
                    (wr.adherence.pct?.let { " · $it%" } ?: ""),
            )
        }
        // Load vs target, with the week-over-week trend arrow (Quick win 5).
        val trend = wr.load.delta_pct?.let { d ->
            val arrow = if (d > 0) "↑" else if (d < 0) "↓" else "→"
            "  $arrow${kotlin.math.abs(d)}% vs last wk"
        } ?: ""
        InsetStat("Load", "${wr.load.tss} / ${wr.load.target} TSS$trend")
        // Sport split — where the load went this week.
        if (wr.by_sport.isNotEmpty()) {
            ChipRow(wr.by_sport.filter { it.tss > 0 }.map { "${sportLabel(it.sport)} ${it.tss}" })
        }
        wr.standout?.let { st ->
            Text(
                "Biggest session: ${sportLabel(st.sport)} on ${st.date} · ${st.tss} TSS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        Text(g.on_track, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
                done -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surfaceVariant
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

internal fun rpeWord(n: Int): String = when {
    n <= 2 -> "very easy"
    n <= 4 -> "easy"
    n <= 6 -> "moderate"
    n <= 8 -> "hard"
    n == 9 -> "very hard"
    else -> "max effort"
}

// Increasing-bars RPE picker: bars 1-10 grow in height; tapping bar n lights
// bars 1..n (green → amber → red).
@Composable
internal fun RpeBars(selected: Int?, onSelect: (Int) -> Unit) {
    // Capture theme colors here (a local fun can't invoke composables).
    val easy = MaterialTheme.colorScheme.primary
    val mid = com.workoutmaker.app.ui.theme.amberAccent()
    val hard = MaterialTheme.colorScheme.error
    fun barColor(n: Int) = when {
        n <= 5 -> easy
        n <= 8 -> mid
        else -> hard
    }
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        (1..10).forEach { n ->
            val active = selected != null && n <= selected
            val bg by animateColorAsState(
                if (active) barColor(n) else MaterialTheme.colorScheme.surfaceContainerHigh,
                label = "rpeBar",
            )
            Box(
                Modifier
                    .weight(1f)
                    .size(width = 0.dp, height = (10 + n * 3).dp)
                    .background(bg, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .clickable(onClickLabel = "RPE $n, ${rpeWord(n)}") {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSelect(n)
                    },
            )
        }
    }
}

@Composable
fun ReadinessRing(score: Int, band: String) {
    val color = when (band) {
        "green" -> MaterialTheme.colorScheme.primary
        "amber" -> com.workoutmaker.app.ui.theme.amberAccent()
        else -> MaterialTheme.colorScheme.error
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(88.dp), Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(88.dp)) {
            drawArc(track, -90f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 18f))
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

// Loud banner when the watch hasn't reported in a while — the readiness read is
// then running blind on objective signals, so it shouldn't look business-as-usual.
@Composable
private fun RecoveryStaleBanner(syncedDate: String?) {
    val color = com.workoutmaker.app.ui.theme.amberAccent()
    val msg = if (syncedDate == null) {
        "No recovery data has synced yet. Pull down to sync your watch."
    } else {
        "Watch hasn't synced since ${friendlyDate(syncedDate)}, today's HRV, resting HR " +
            "and sleep may be missing. Pull down to refresh."
    }
    Row(
        Modifier.fillMaxWidth()
            .background(color.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Text(
            msg,
            Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// One readiness driver as a tinted pill ("HRV ↑", "Sleep ↓"); colour = tone.
@Composable
private fun RecoveryDriverChip(d: com.workoutmaker.app.data.RecoveryDriver) {
    val color = when (d.tone) {
        "good" -> MaterialTheme.colorScheme.primary
        "bad" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val arrow = when (d.dir) { "up" -> "↑"; "down" -> "↓"; else -> "→" }
    Box(
        Modifier
            .background(color.copy(alpha = 0.13f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text("${d.label} $arrow", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

// "2026-06-28" → "28 Jun"; falls back to the raw string if unparseable.
private fun friendlyDate(iso: String): String =
    runCatching {
        java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
    }.getOrDefault(iso)

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

@Composable
private fun readinessColor(band: String) = when (band) {
    "green" -> MaterialTheme.colorScheme.primary
    "amber" -> com.workoutmaker.app.ui.theme.amberAccent()
    else -> MaterialTheme.colorScheme.error
}

@Composable
fun WorkoutDetail(w: Workout, profile: com.workoutmaker.app.data.TrainingProfile? = null) {
    val thresholdSecPerKm = profile?.threshold_pace_per_km?.let { com.workoutmaker.app.data.Zones.parsePace(it) }
    val lthr = profile?.lthr
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
        w.sections.forEach { section -> WorkoutSectionItem(section, thresholdSecPerKm, lthr) }

        // "Your zones" peek — only for endurance days where thresholds are set.
        val isEndurance = listOf("run", "ride", "bike", "cycl").any { w.type.contains(it, ignoreCase = true) }
        val hasThresholds = profile != null && (profile.lthr != null || profile.threshold_pace_per_km != null || profile.ftp != null)
        if (isEndurance && hasThresholds) {
            var zonesOpen by remember { mutableStateOf(false) }
            Text(
                (if (zonesOpen) "▾ Your zones" else "▸ Your zones"),
                Modifier.clickable { zonesOpen = !zonesOpen },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (zonesOpen) ZoneTables(profile!!.lthr, profile.threshold_pace_per_km, profile.ftp)
        }
    }
}

@Composable
private fun WorkoutSectionItem(
    section: com.workoutmaker.app.data.WorkoutSection,
    thresholdSecPerKm: Int? = null,
    lthr: Int? = null,
) {
    // Total work time for the section (duration-based steps × their repeats).
    val sectionSec = section.exercises.sumOf { ex ->
        (com.workoutmaker.app.data.Zones.parseDurationSec(ex.reps) ?: 0) * ex.sets.coerceAtLeast(1)
    }
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
            Text(
                section.name + if (sectionSec > 0) "  ·  ${com.workoutmaker.app.data.Zones.fmtDurationShort(sectionSec)}" else "",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            section.exercises.forEach { ex ->
                val durSec = com.workoutmaker.app.data.Zones.parseDurationSec(ex.reps)
                val zoneLabel = (ex.pace_zone ?: ex.hr_zone)?.let { z ->
                    com.workoutmaker.app.data.Zones.zoneNum(z)?.let { "Z$it" }
                }
                if (durSec != null || zoneLabel != null) {
                    // Endurance step: "5× · 3 min · Z4 · 4:15–4:30 /km".
                    val target = com.workoutmaker.app.data.Zones.targetRange(ex.pace_zone, ex.hr_zone, thresholdSecPerKm, lthr)
                    val meta = listOfNotNull(
                        "${ex.sets}×".takeIf { ex.sets > 1 },
                        durSec?.let { com.workoutmaker.app.data.Zones.fmtDurationShort(it) },
                        zoneLabel,
                        target,
                    ).joinToString(" · ")
                    StepLine(ex.name, meta)
                } else {
                    // Strength / other step: keep the sets×reps · kg rendering.
                    val meta = buildString {
                        if (ex.sets > 0 && ex.reps.isNotEmpty()) append("${ex.sets}×${ex.reps}")
                        ex.weight_kg?.let { append(" · ${it}kg") }
                    }
                    StepLine(ex.name, meta)
                }
            }
        }
    }
}

@Composable
private fun StepLine(name: String, meta: String) {
    Text(
        "$name${if (meta.isNotBlank()) "  ·  $meta" else ""}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
