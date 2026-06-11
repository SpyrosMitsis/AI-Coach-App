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

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repo: WorkoutRepository,
    private val strength: com.workoutmaker.app.strength.StrengthRepository,
    private val strengthHandoff: com.workoutmaker.app.strength.StrengthHandoff,
) : ViewModel() {
    val workouts = MutableStateFlow<List<PlannedWorkout>>(emptyList())
    val templates = MutableStateFlow<List<WorkoutTemplate>>(emptyList())
    val banner = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)
    val planning = MutableStateFlow(false)
    val weekPlan = MutableStateFlow<com.workoutmaker.app.data.WeekPlanRow?>(null)
    // Q14: logged strength sessions, grouped by yyyy-MM-dd for the calendar.
    val strengthByDate = MutableStateFlow<Map<String, List<com.workoutmaker.app.strength.WorkoutEntity>>>(emptyMap())
    // Past activities from Intervals.icu, grouped by date for the calendar + detail.
    val activitiesByDate = MutableStateFlow<Map<String, List<com.workoutmaker.app.data.CompletedActivity>>>(emptyMap())
    val adapting = MutableStateFlow(false)

    // Sets for a strength session opened from the calendar, keyed by workout id.
    val strengthSets = MutableStateFlow<Map<String, List<com.workoutmaker.app.strength.SetEntity>>>(emptyMap())
    fun loadStrengthSets(id: String) = viewModelScope.launch {
        if (strengthSets.value.containsKey(id)) return@launch
        runCatching { strength.setsForWorkout(id) }.onSuccess { strengthSets.value = strengthSets.value + (id to it) }
    }

    fun loadWeekPlan(start: LocalDate) = viewModelScope.launch {
        weekPlan.value = repo.weekPlan(start.toString())
    }

    fun load() = viewModelScope.launch {
        loading.value = true
        val from = LocalDate.now().minusMonths(2).toString()
        runCatching { repo.plannedWorkouts(from) }
            .onSuccess { workouts.value = it }
            .onFailure {
                // Offline (incl. cold start): render the last cached plan.
                // Logged strength sessions below are local Room data anyway.
                val cached = repo.cachedPlannedWorkouts()
                if (cached.isNotEmpty() && workouts.value.isEmpty()) {
                    workouts.value = cached
                    banner.value = "Offline — showing your last synced plan."
                }
            }
        runCatching { repo.templates() }.onSuccess { templates.value = it }
        runCatching { repo.completedActivities(from) }
            .onSuccess { acts -> activitiesByDate.value = acts.filter { it.date != null }.groupBy { it.date!! } }
        runCatching {
            val zone = java.time.ZoneId.systemDefault()
            strength.recentWorkouts(500).groupBy {
                java.time.Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate().toString()
            }
        }.onSuccess { strengthByDate.value = it }
        loading.value = false
    }

    // Pull the latest activities from Intervals.icu so actuals are current
    // before adapting / reviewing the week.
    fun syncNow() = viewModelScope.launch {
        loading.value = true
        banner.value = "Syncing your latest activities from Intervals.icu…"
        runCatching { repo.syncIntervals() }
            .onSuccess { banner.value = "✓ Synced — your recent activities are up to date."; load() }
            .onFailure { banner.value = "Sync failed: ${it.message}" }
        loading.value = false
    }

    // Adaptive re-plan: reconcile this week with what was actually done on
    // Intervals.icu, then re-plan the rest of the week around it.
    fun adapt(weekStart: LocalDate) = viewModelScope.launch {
        adapting.value = true
        banner.value = "Syncing Intervals.icu, then adapting your plan to what you did…"
        runCatching { repo.syncIntervals() } // best-effort: work from the freshest actuals
        runCatching { repo.adaptWeek(weekStart.toString(), LocalDate.now().toString()) }
            .onSuccess { r ->
                banner.value = r.error?.let { "Couldn't fully adapt: $it" } ?: "✓ ${r.message}"
                load()
                loadWeekPlan(weekStart)
            }
            .onFailure { banner.value = "Couldn't adapt: ${it.message}" }
        adapting.value = false
    }

    fun markComplete(plannedId: String, date: LocalDate, completed: Boolean) = viewModelScope.launch {
        runCatching { repo.markPlannedComplete(plannedId, date.toString(), completed, if (completed) "just_right" else null, null) }
            .onSuccess { banner.value = if (completed) "✓ Marked done" else "Marked skipped"; load() }
            .onFailure { banner.value = it.message }
    }

    // Undo a completion (in case of a mistake) — flips the flag without logging
    // feedback.
    fun markUndone(plannedId: String) = viewModelScope.launch {
        runCatching { strength.markPlannedWorkoutDone(plannedId, false) }
            .onSuccess { banner.value = "Marked as not done"; load() }
            .onFailure { banner.value = "Couldn't update: ${it.message}" }
    }

    // Open a planned strength session in the Strength logger, pre-filled and
    // linked so finishing it marks this plan complete.
    fun logPlannedStrength(w: PlannedWorkout) {
        strengthHandoff.request(w.workout_json, w.id, w.date)
    }

    fun deletePlanned(plannedId: String) = viewModelScope.launch {
        runCatching { repo.deletePlannedWorkout(plannedId) }
            .onSuccess { banner.value = "Workout deleted"; load() }
            .onFailure { banner.value = "Couldn't delete: ${it.message}" }
    }

    fun logActivity(date: LocalDate, type: String, durationMin: Int, distanceKm: Double?, rpe: Int?) = viewModelScope.launch {
        runCatching { repo.logManualActivity(date.toString(), type, durationMin, distanceKm, rpe) }
            .onSuccess { banner.value = "✓ Logged $type session"; load() }
            .onFailure { banner.value = "Failed to log: ${it.message}" }
    }

    // P2: plan a full periodized block to the race.
    fun planBlock() = viewModelScope.launch {
        planning.value = true
        banner.value = "Planning your full block to race… this calls the AI once per week."
        runCatching { repo.planBlock(com.workoutmaker.app.data.PlanBlockRequest()) }
            .onSuccess { r ->
                banner.value = r.error
                    ?: "✓ Planned ${r.weeks_planned}/${r.weeks} weeks · pushed the next ${r.pushed_weeks} to your watch"
                load()
            }
            .onFailure { banner.value = "Failed to plan block: ${it.message}" }
        planning.value = false
    }

    // #3: lock/unlock a session so the weekly re-planner leaves it fixed.
    fun toggleLock(w: PlannedWorkout) = viewModelScope.launch {
        runCatching { repo.setLocked(w.id, !w.locked) }
            .onSuccess { banner.value = if (!w.locked) "🔒 Locked — re-planning won't touch it" else "Unlocked"; load() }
            .onFailure { banner.value = "Couldn't update: ${it.message}" }
    }

    // #3: ask the AI for a fixed, locked-in session on a specific date from a
    // free-text request ("social 10k with friends").
    fun requestSession(date: LocalDate, request: String, type: String) = viewModelScope.launch {
        planning.value = true
        banner.value = "Building your locked-in session for $date…"
        runCatching { repo.requestSession(date.toString(), request, type) }
            .onSuccess { r ->
                banner.value = if (r.workout_id != null) "✓ Locked-in “${r.workout?.title ?: "session"}” on $date"
                else "AI couldn't build it — check your AI provider key in Settings."
                load()
            }
            .onFailure { banner.value = "Failed: ${it.message}" }
        planning.value = false
    }

    // E5: persist a manually-built structured session into the plan.
    fun saveBuiltWorkout(date: LocalDate, workout: com.workoutmaker.app.data.Workout, push: Boolean) = viewModelScope.launch {
        banner.value = "Saving session…"
        runCatching {
            val id = repo.savePlannedWorkout(date.toString(), workout)
            if (push) runCatching { repo.pushWorkout(id) }
        }.onSuccess {
            banner.value = "✓ Added “${workout.title}” to $date" + (if (push) " · pushed to watch" else "")
            load()
        }.onFailure { banner.value = "Couldn't save: ${it.message}" }
    }

    // P7: move a planned workout to a new date.
    fun reschedule(plannedId: String, newDate: LocalDate) = viewModelScope.launch {
        runCatching { repo.reschedulePlanned(plannedId, newDate.toString()) }
            .onSuccess { banner.value = "Moved to $newDate"; load() }
            .onFailure { banner.value = "Couldn't move: ${it.message}" }
    }

    fun planWeek(start: LocalDate) = viewModelScope.launch {
        planning.value = true
        banner.value = "Planning your week from $start…"
        runCatching { repo.planWeek(com.workoutmaker.app.data.PlanWeekRequest(start_date = start.toString())) }
            .onSuccess { r ->
                banner.value = r.error
                    ?: "✓ Planned ${r.scheduled}-day week (${r.week_focus ?: "block"})" +
                    (if (r.pushed > 0) " · pushed ${r.pushed} to watch" else "")
                load()
                loadWeekPlan(start)
            }
            .onFailure { banner.value = "Failed to plan week: ${it.message}" }
        planning.value = false
    }

    fun schedule(template: WorkoutTemplate, start: LocalDate) = viewModelScope.launch {
        banner.value = "Scheduling “${template.name}”…"
        runCatching { repo.scheduleTemplate(ScheduleRequest(template_id = template.id, start_date = start.toString())) }
            .onSuccess { r ->
                banner.value = (r.error ?: "✓ Scheduled ${r.scheduled} session(s)") +
                    (if (r.pushed > 0) " · pushed ${r.pushed} to Intervals" else "")
                load()
            }
            .onFailure { banner.value = "Failed: ${it.message}" }
    }
}
