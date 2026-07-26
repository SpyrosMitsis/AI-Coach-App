package com.workoutmaker.app.ui.screens.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workoutmaker.app.data.DailySummary
import com.workoutmaker.app.data.GenerateRequest
import com.workoutmaker.app.data.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.workoutmaker.app.data.AppPreferences
import com.workoutmaker.app.data.CompletedActivity
import com.workoutmaker.app.data.IntervalsActivity
import com.workoutmaker.app.data.IntervalsStats
import com.workoutmaker.app.data.LocationProvider
import com.workoutmaker.app.data.NotificationDeepLinks
import com.workoutmaker.app.data.PlanChangeBus
import com.workoutmaker.app.data.SessionDebrief
import com.workoutmaker.app.data.TrainingProfile
import com.workoutmaker.app.data.WellnessCheckin
import com.workoutmaker.app.data.WorkoutFeedback
import com.workoutmaker.app.strength.SetEntity
import com.workoutmaker.app.strength.StrengthRepository
import com.workoutmaker.app.strength.WorkoutEntity
import com.workoutmaker.app.util.AppLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonPrimitive
import com.workoutmaker.app.ui.screens.calendar.looksLike
import com.workoutmaker.app.data.adjustWorkout
import com.workoutmaker.app.data.cachedDailySummary
import com.workoutmaker.app.data.coachBrief
import com.workoutmaker.app.data.completedActivities
import com.workoutmaker.app.data.dailySummary
import com.workoutmaker.app.data.generateWorkout
import com.workoutmaker.app.data.hasCachedBrief
import com.workoutmaker.app.data.intervalsStats
import com.workoutmaker.app.data.loadProfile
import com.workoutmaker.app.data.markPlannedComplete
import com.workoutmaker.app.data.refreshMemory
import com.workoutmaker.app.data.submitFeedback
import com.workoutmaker.app.data.syncHealth
import com.workoutmaker.app.data.syncIntervals
import com.workoutmaker.app.data.undoSkip
import com.workoutmaker.app.data.upsertManualRecovery
import com.workoutmaker.app.data.upsertWellness
import com.workoutmaker.app.data.weekReview
import com.workoutmaker.app.data.wellnessCheckin

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: WorkoutRepository,
    private val strength: StrengthRepository,
    private val location: LocationProvider,
    private val prefs: AppPreferences,
    planChanges: PlanChangeBus,
) : ViewModel() {
    // Onboarding skippers land here with an empty profile and every workout is
    // generated from guesses. Nudge until the profile is minimally usable
    // (sports + availability); dismissal snoozes for 14 days.
    val showSetupNudge = MutableStateFlow(false)
    private suspend fun refreshSetupNudge() {
        val p = profile.value ?: return
        val unusable = p.sports.isEmpty() || p.day_availability.isEmpty()
        if (!unusable) { showSetupNudge.value = false; return }
        val dismissed = runCatching { prefs.setupNudgeDismissedAt() }.getOrNull()
        showSetupNudge.value = dismissed == null ||
            System.currentTimeMillis() - dismissed > 14L * 24 * 60 * 60 * 1000
    }
    fun dismissSetupNudge() {
        showSetupNudge.value = false
        viewModelScope.launch { runCatching { prefs.dismissSetupNudge() } }
    }
    // Reload the dashboard when the coach (or any other screen) mutates the
    // plan: this VM survives tab switches, so today's workout went stale
    // otherwise. load(), not refresh(): no need to force an Intervals re-sync.
    init {
        viewModelScope.launch { planChanges.changes.collect { load() } }
        // Debrief notification tapped: resolve the activity and open its detail
        // overlay (MainScaffold has already navigated to the Home tab).
        viewModelScope.launch {
            NotificationDeepLinks.openActivity.collect { link ->
                val (id, date) = link ?: return@collect
                NotificationDeepLinks.openActivity.value = null
                runCatching { repo.completedActivities(date) }.getOrNull()
                    ?.firstOrNull { it.id == id }
                    ?.let { openedActivity.value = it }
            }
        }
    }

    val summary = MutableStateFlow<DailySummary?>(null)
    val fitness = MutableStateFlow<IntervalsStats?>(null)
    // Thresholds for turning a planned step's zone into a concrete target range.
    val profile = MutableStateFlow<TrainingProfile?>(null)
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
    val wellnessToday = MutableStateFlow<WellnessCheckin?>(null)
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
    val selectedDate = MutableStateFlow(LocalDate.now())
    val isViewingToday get() = selectedDate.value == LocalDate.now()

    fun goToDay(date: LocalDate) {
        val capped = if (date.isAfter(LocalDate.now())) LocalDate.now() else date
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
    fun goToToday() = goToDay(LocalDate.now())

    // Dates that have a completed activity — drawn as dots in the calendar picker
    // so it's obvious which past days have something to look at. Loaded once.
    val markedDates = MutableStateFlow<Set<LocalDate>>(emptySet())
    private fun loadMarks() = viewModelScope.launch {
        val from = LocalDate.now().minusDays(400).toString()
        runCatching { repo.completedActivities(from) }.onSuccess { acts ->
            markedDates.value = acts
                .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                .toSet()
        }
    }

    fun load() = viewModelScope.launch {
        loading.value = true
        val date = selectedDate.value
        val viewingToday = date == LocalDate.now()
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
        refreshSetupNudge()
        runCatching { repo.intervalsStats() }.onSuccess { fitness.value = it }
        loading.value = false
        // The briefing is today-only — it's a live coaching note, and we never
        // spend an LLM call generating one for a historical day. Streams in
        // separately so it never holds up the dashboard; cached per-day.
        if (viewingToday) {
            viewModelScope.launch {
                // First brief of the day = the day's ONE LLM generation. Push last
                // night's Health Connect data first (and refresh the summary so
                // the ring matches what the brief saw); a cached brief skips all
                // of this and the open stays as cheap as before.
                if (!repo.hasCachedBrief()) {
                    runCatching { repo.syncHealth() }
                    runCatching { repo.dailySummary(date) }.onSuccess { summary.value = it }
                }
                runCatching { repo.coachBrief() }
                    .onSuccess { brief.value = it }
                    .onFailure { AppLog.w("home", "coachBrief failed", it) }
            }
            viewModelScope.launch {
                val now = LocalDate.now()
                val monday = now.minusDays((now.dayOfWeek.value - 1).toLong())
                runCatching { repo.weekReview(monday.toString()) }
                    .onSuccess { weekReviewNote.value = it }
                    .onFailure { AppLog.w("home", "weekReview failed", it) }
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
        // Health Connect too — wellness always, plus watch workouts as fallback
        // activities when intervals isn't connected (the "sync your watch" copy).
        runCatching { repo.syncHealth() }
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
        val checkin = WellnessCheckin(
            date = LocalDate.now().toString(),
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
    val openedActivity = MutableStateFlow<CompletedActivity?>(null)

    // A strength session is logged in-app AND (usually) recorded on the watch. When
    // one is tapped, we open the SAME unified detail page the Calendar uses, which
    // merges the logged sets with the watch recording — instead of the watch-only
    // endurance page that ignores everything that was lifted.
    data class StrengthDetail(
        val workout: WorkoutEntity,
        val sets: List<SetEntity>,
        val watch: CompletedActivity?,
    )
    val openedStrength = MutableStateFlow<StrengthDetail?>(null)

    fun openActivity(a: IntervalsActivity) = viewModelScope.launch {
        val from = LocalDate.now().minusDays(120).toString()
        val completed = runCatching { repo.completedActivities(from) }.getOrNull().orEmpty()

        // Strength path: if an in-app strength session was logged on this date, open
        // the merged detail (logged + watch) rather than the watch-only one.
        val zone = ZoneId.systemDefault()
        val logged = runCatching { strength.recentWorkouts(500) }.getOrNull()?.firstOrNull { w ->
            Instant.ofEpochMilli(w.startedAt).atZone(zone).toLocalDate().toString() == a.date
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

    // Tapping the Home "Session debrief" card opens the SAME detail page as the
    // recent-activities list: full analysis + coach feedback for endurance, the
    // merged logged+watch page for strength.
    fun openDebrief(d: SessionDebrief) = viewModelScope.launch {
        if (d.kind == "strength") {
            val zone = ZoneId.systemDefault()
            val logged = runCatching { strength.recentWorkouts(500) }.getOrNull()?.firstOrNull { w ->
                Instant.ofEpochMilli(w.startedAt).atZone(zone).toLocalDate().toString() == d.date
            } ?: return@launch
            val sets = runCatching { strength.setsForWorkout(logged.id) }.getOrNull().orEmpty()
            val watch = runCatching { repo.completedActivities(d.date) }.getOrNull().orEmpty()
                .firstOrNull { c -> c.date == d.date && looksLike("strength", c.type) }
            openedStrength.value = StrengthDetail(logged, sets, watch)
            return@launch
        }
        val id = d.activity_id ?: return@launch
        runCatching { repo.completedActivities(d.date) }.getOrNull()
            ?.firstOrNull { it.id == id }
            ?.let { openedActivity.value = it }
    }

    private fun IntervalsActivity.toCompleted() =
        CompletedActivity(
            id = "digest:$date:$name",
            intervals_id = "digest",
            type = type.ifBlank { null },
            date = date,
            duration_seconds = duration_min?.let { it * 60 },
            distance_m = distance_km?.let { it * 1000.0 },
            avg_hr = avg_hr,
            tss = tss,
            data_json = if (name.isNotBlank()) {
                kotlinx.serialization.json.JsonObject(mapOf("name" to JsonPrimitive(name)))
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
            repo.adjustWorkout(base, instruction, LocalDate.now().toString())
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
                repo.adjustWorkout(base, tweak, LocalDate.now().toString())
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
        val date = LocalDate.now().toString()
        runCatching {
            if (today?.id != null) {
                repo.markPlannedComplete(today.id, date, completed = true, difficulty = difficulty, rpe = rpe)
            } else {
                repo.submitFeedback(
                    WorkoutFeedback(date = date, completed = true, actual_rpe = rpe, difficulty = difficulty),
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
        val date = LocalDate.now().toString()
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
