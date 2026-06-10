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
        runCatching { repo.plannedWorkouts(from) }.onSuccess { workouts.value = it }
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

private fun typeColor(type: String) = when {
    type.contains("run", true) || type.contains("ride", true) || type.contains("bike", true) -> Sage
    type.contains("strength", true) || type.contains("gym", true) || type.contains("weight", true) -> Sand
    else -> Moss
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: CalendarViewModel = hiltViewModel(), onOpenStrength: () -> Unit = {}) {
    val workouts by vm.workouts.collectAsStateSafe()
    val templates by vm.templates.collectAsStateSafe()
    val banner by vm.banner.collectAsStateSafe()
    val loading by vm.loading.collectAsStateSafe()
    val planning by vm.planning.collectAsStateSafe()
    val weekPlan by vm.weekPlan.collectAsStateSafe()
    val strengthByDate by vm.strengthByDate.collectAsStateSafe()
    val activitiesByDate by vm.activitiesByDate.collectAsStateSafe()
    val adapting by vm.adapting.collectAsStateSafe()

    var visibleMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var scheduleFor by remember { mutableStateOf<LocalDate?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var showBuilder by remember { mutableStateOf(false) }
    var showRequest by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<PlannedWorkout?>(null) }
    var moveTarget by remember { mutableStateOf<PlannedWorkout?>(null) }
    var activityDetail by remember { mutableStateOf<com.workoutmaker.app.data.CompletedActivity?>(null) }
    var strengthDetail by remember { mutableStateOf<com.workoutmaker.app.strength.WorkoutEntity?>(null) }
    val strengthSets by vm.strengthSets.collectAsStateSafe()

    LaunchedEffect(Unit) { vm.load() }

    val byDate = remember(workouts) { workouts.groupBy { it.date } }
    val daySessions = byDate[selectedDate.toString()].orEmpty()
    val dayStrength = strengthByDate[selectedDate.toString()].orEmpty()
    val dayActivities = activitiesByDate[selectedDate.toString()].orEmpty()
    val weekStart = selectedDate.minusDays((selectedDate.dayOfWeek.value - 1).toLong())
    LaunchedEffect(weekStart) { vm.loadWeekPlan(weekStart) }

    // Activity detail is a full sub-screen.
    activityDetail?.let { act ->
        BackHandler { activityDetail = null }
        ActivityDetailScreen(
            activity = act,
            planned = byDate[act.date]?.firstOrNull(),
            onBack = { activityDetail = null },
        )
        return
    }

    // Strength session detail — reuses the unified detail page from history.
    strengthDetail?.let { sw ->
        BackHandler { strengthDetail = null }
        LaunchedEffect(sw.id) { vm.loadStrengthSets(sw.id) }
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.Instant.ofEpochMilli(sw.startedAt).atZone(zone).toLocalDate().toString()
        val watch = vm.activitiesByDate.value[day]?.firstOrNull { a ->
            looksLike("strength", a.type)
        }
        StrengthSessionDetailScreen(
            w = sw,
            sets = strengthSets[sw.id].orEmpty(),
            watch = watch,
            onBack = { strengthDetail = null },
        )
        return
    }

    // Adaptive prompt: did a past planned session this real week go unlogged?
    val realWeekStart = LocalDate.now().with(java.time.DayOfWeek.MONDAY)
    val divergence = remember(workouts) {
        val ws = realWeekStart.toString()
        val td = LocalDate.now().toString()
        workouts.any { it.date in ws..td && it.date < td && !it.completed && !it.locked && it.type != "rest" }
    }

    ScreenScaffold(
        title = "Calendar",
        subtitle = "${workouts.size} planned",
        isRefreshing = loading,
        onRefresh = { vm.load() },
        actions = {
            IconButton(onClick = { vm.syncNow() }, enabled = !loading) {
                Icon(Icons.Filled.Sync, contentDescription = "Sync activities from Intervals.icu")
            }
            IconButton(onClick = { vm.planWeek(weekStart) }, enabled = !planning) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = "Plan my week")
            }
            IconButton(onClick = { scheduleFor = selectedDate }) {
                Icon(Icons.Filled.Add, contentDescription = "Schedule a template")
            }
        },
    ) { mod ->
        Column(mod, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            banner?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

            // Adaptive re-plan prompt — surfaces when the week diverged from plan.
            if (divergence) {
                SectionCard {
                    SectionLabel("Plan vs reality", color = Sand)
                    Text(
                        "You've got planned sessions this week you haven't done yet. I can check what you actually did on Intervals.icu and rebuild the rest of your week around it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    GhostButton(
                        onClick = { vm.adapt(realWeekStart) },
                        enabled = !adapting && !planning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
                        Text(if (adapting) "  Adapting…" else "  Adapt my plan to what I did")
                    }
                }
            }

            WeekSummaryCard(weekStart, workouts, planning, weekPlan) { vm.planWeek(weekStart) }

            // P2: plan a whole periodized block to the race in one go.
            GhostButton(
                onClick = { vm.planBlock() },
                enabled = !planning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(18.dp))
                Text(if (planning) "  Planning…" else "  Plan full block to race (AI)")
            }

            // Month switcher
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { visibleMonth = visibleMonth.minusMonths(1) }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous month")
                }
                Text(
                    "${visibleMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${visibleMonth.year}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { visibleMonth = visibleMonth.plusMonths(1) }) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Next month")
                }
            }

            MonthGrid(
                month = visibleMonth,
                selected = selectedDate,
                byDate = byDate,
                strengthDates = strengthByDate.keys,
                activityDates = activitiesByDate.keys,
                onSelect = { selectedDate = it },
            )

            // Selected-day sessions
            Text(
                selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " · $selectedDate",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // Completed Intervals.icu activities on this day — tap for detail.
            dayActivities.forEach { act ->
                ActivityCard(act, planned = daySessions.firstOrNull()) { activityDetail = act }
            }

            // Q14: strength sessions logged on this day — tap to open detail.
            dayStrength.forEach { sw ->
                SectionCard(Modifier.clickable { strengthDetail = sw }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Strength", color = Sand)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Filled.ChevronRight, "Open details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(sw.name, style = MaterialTheme.typography.titleMedium)
                    ChipRow(buildList {
                        add("${sw.totalVolumeKg.toInt()} kg")
                        if (sw.durationSec > 0) add("${sw.durationSec / 60} min")
                    })
                }
            }

            if (daySessions.isEmpty() && dayStrength.isEmpty() && dayActivities.isEmpty()) {
                Text(
                    "Nothing planned. Tap ＋ to schedule a template on this day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (daySessions.isNotEmpty()) {
                daySessions.forEach { w ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel(
                                w.type + if (w.locked) " · locked" else "",
                                color = if (w.locked) Sage else typeColor(w.type),
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { vm.toggleLock(w) }, modifier = Modifier.size(28.dp)) {
                                Icon(if (w.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                    if (w.locked) "Unlock" else "Lock so re-planning won't change it",
                                    tint = if (w.locked) Sage else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { moveTarget = w }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.EditCalendar, "Move to another day", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { confirmDelete = w }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Delete, "Delete workout", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        WorkoutDetail(w.workout_json)
                        if (w.type != "rest") {
                            if (w.completed) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("✓ Completed", style = MaterialTheme.typography.labelMedium, color = Sage)
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { vm.markUndone(w.id) }) { Text("Mark as not done") }
                                }
                            } else {
                                // Strength plans open in the logger pre-filled; finishing
                                // there marks this plan done automatically.
                                if (w.type == "strength") {
                                    GhostButton(
                                        onClick = { vm.logPlannedStrength(w); onOpenStrength() },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Filled.FitnessCenter, null, modifier = Modifier.size(18.dp))
                                        Text("  Log this session")
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    GhostButton(onClick = { vm.markComplete(w.id, selectedDate, true) }, modifier = Modifier.weight(1f)) {
                                        Text("✓ Done")
                                    }
                                    GhostButton(onClick = { vm.markComplete(w.id, selectedDate, false) }) { Text("Skip") }
                                }
                            }
                        }
                    }
                }
            }

            Column {
                TextButton(onClick = { showRequest = true }) { Text("＋ Ask AI for a session on this day") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showLog = true }) { Text("＋ Log past") }
                    TextButton(onClick = { showBuilder = true }) { Text("＋ Build intervals") }
                }
            }
        }
    }

    if (showRequest) {
        RequestSessionDialog(
            date = selectedDate,
            onDismiss = { showRequest = false },
            onSubmit = { d, text, type -> vm.requestSession(d, text, type); showRequest = false },
        )
    }

    if (showBuilder) {
        IntervalBuilderDialog(
            date = selectedDate,
            onDismiss = { showBuilder = false },
            onSave = { w, push -> vm.saveBuiltWorkout(selectedDate, w, push); showBuilder = false },
        )
    }

    if (showLog) {
        LogActivityDialog(
            date = selectedDate,
            onDismiss = { showLog = false },
            onConfirm = { type, dur, dist, rpe -> vm.logActivity(selectedDate, type, dur, dist, rpe); showLog = false },
        )
    }

    confirmDelete?.let { w ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = { TextButton(onClick = { vm.deletePlanned(w.id); confirmDelete = null }) { Text("Delete", color = BandRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            title = { Text("Delete this workout?") },
            text = {
                Text(
                    "“${w.workout_json.title}” will be removed from your plan" +
                        (if (w.intervals_event_id != null) " and your watch calendar." else "."),
                )
            },
        )
    }

    moveTarget?.let { w ->
        val initial = runCatching {
            LocalDate.parse(w.date).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { moveTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        vm.reschedule(w.id, d)
                    }
                    moveTarget = null
                }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { moveTarget = null }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    scheduleFor?.let { day ->
        AlertDialog(
            onDismissRequest = { scheduleFor = null },
            confirmButton = { TextButton(onClick = { scheduleFor = null }) { Text("Close") } },
            title = { Text("Schedule on $day") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (templates.isEmpty()) {
                        Text("No templates yet. Create one from the Coach tab.", style = MaterialTheme.typography.bodyMedium)
                    }
                    templates.forEach { t ->
                        SectionCard(title = t.name) {
                            t.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            OutlinedButton(
                                onClick = { vm.schedule(t, day); scheduleFor = null },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Schedule from $day") }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun LogActivityDialog(
    date: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (type: String, durationMin: Int, distanceKm: Double?, rpe: Int?) -> Unit,
) {
    var type by remember { mutableStateOf("Run") }
    var duration by remember { mutableStateOf("45") }
    var distance by remember { mutableStateOf("") }
    var rpe by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(type, duration.toIntOrNull() ?: 0, distance.toDoubleOrNull(), rpe.toIntOrNull()) },
                enabled = (duration.toIntOrNull() ?: 0) > 0,
            ) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Log a session · $date") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Run", "WeightTraining", "Other").forEach { t ->
                        androidx.compose.material3.FilterChip(
                            selected = type == t, onClick = { type = t },
                            label = { Text(if (t == "WeightTraining") "Strength" else t) },
                        )
                    }
                }
                androidx.compose.material3.OutlinedTextField(duration, { duration = it }, label = { Text("Duration (min)") })
                androidx.compose.material3.OutlinedTextField(distance, { distance = it }, label = { Text("Distance km (optional)") })
                androidx.compose.material3.OutlinedTextField(rpe, { rpe = it }, label = { Text("RPE 1-10 (optional)") })
            }
        },
    )
}

// #3 — ask the AI for a fixed, locked-in session on a specific day from a
// free-text request. The result is locked so the weekly re-planner works around
// it rather than replacing it.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestSessionDialog(date: LocalDate, onDismiss: () -> Unit, onSubmit: (LocalDate, String, String) -> Unit) {
    var day by remember { mutableStateOf(date) }
    var text by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("auto") }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { day = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() }
                showPicker = false
            }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSubmit(day, text.trim(), type) }) { Text("Create & lock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("AI session for a day") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.EditCalendar, null); Text("  $day")
                }
                androidx.compose.material3.OutlinedTextField(
                    text, { text = it },
                    label = { Text("What's the plan?") },
                    placeholder = { Text("e.g. social 10k run with friends, keep it easy") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to "Auto", "run" to "Run", "strength" to "Strength").forEach { (k, label) ->
                        androidx.compose.material3.FilterChip(selected = type == k, onClick = { type = k }, label = { Text(label) })
                    }
                }
                Text("It'll be 🔒 locked — your weekly AI re-plan will schedule around it, not over it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

// E5 — structured interval builder. Each step has a zone, minutes and a repeat
// count; the list compiles into a Workout (one "Main" section) with a duration
// and a TSS estimate from per-zone intensity factors.
private class BuilderStep(kind: String = "Work", zone: String = "Z4", minutes: String = "3", reps: String = "1") {
    var kind by mutableStateOf(kind)
    var zone by mutableStateOf(zone)
    var minutes by mutableStateOf(minutes)
    var reps by mutableStateOf(reps)
}

private val ZONE_IF = mapOf("Z1" to 0.55, "Z2" to 0.70, "Z3" to 0.83, "Z4" to 0.95, "Z5" to 1.10)
private val STEP_KINDS = listOf("Warm-up", "Work", "Recovery", "Steady", "Cool-down")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalBuilderDialog(date: LocalDate, onDismiss: () -> Unit, onSave: (com.workoutmaker.app.data.Workout, Boolean) -> Unit) {
    var title by remember { mutableStateOf("Interval session") }
    var type by remember { mutableStateOf("run") }
    var push by remember { mutableStateOf(true) }
    val steps = remember {
        androidx.compose.runtime.mutableStateListOf(
            BuilderStep("Warm-up", "Z1", "10", "1"),
            BuilderStep("Work", "Z4", "3", "5"),
            BuilderStep("Recovery", "Z1", "2", "5"),
            BuilderStep("Cool-down", "Z1", "10", "1"),
        )
    }

    fun stepMinutes(s: BuilderStep) = (s.minutes.toDoubleOrNull() ?: 0.0) * (s.reps.toIntOrNull() ?: 1)
    val totalMin = steps.sumOf { stepMinutes(it) }
    val tss = steps.sumOf { s ->
        val mins = stepMinutes(s); val iff = ZONE_IF[s.zone] ?: 0.7
        mins / 60.0 * iff * iff * 100.0
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = steps.isNotEmpty() && totalMin > 0,
                onClick = {
                    val exercises = steps.map { s ->
                        val r = s.reps.toIntOrNull() ?: 1
                        com.workoutmaker.app.data.WorkoutExercise(
                            name = (if (r > 1) "$r× " else "") + s.kind,
                            reps = "${s.minutes} min", hr_zone = s.zone,
                        )
                    }
                    val w = com.workoutmaker.app.data.Workout(
                        type = type, title = title.ifBlank { "Interval session" },
                        duration_minutes = totalMin, tss_estimate = tss, rpe_target = 7.0,
                        sections = listOf(com.workoutmaker.app.data.WorkoutSection("Main", totalMin, exercises)),
                        coach_note = "Built manually.",
                    )
                    onSave(w, push)
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Build session · $date") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.OutlinedTextField(title, { title = it }, label = { Text("Title") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("run" to "Run", "ride" to "Ride").forEach { (k, label) ->
                        androidx.compose.material3.FilterChip(selected = type == k, onClick = { type = k }, label = { Text(label) })
                    }
                }
                Text("${totalMin.toInt()} min · ~${tss.toInt()} TSS", style = MaterialTheme.typography.labelMedium, color = Sage)

                steps.forEachIndexed { i, s ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", Modifier.width(20.dp), style = MaterialTheme.typography.labelMedium)
                            ZoneDropdown(s.kind, STEP_KINDS) { s.kind = it }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { steps.removeAt(i) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Delete, "Remove step", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ZoneDropdown(s.zone, listOf("Z1", "Z2", "Z3", "Z4", "Z5")) { s.zone = it }
                            androidx.compose.material3.OutlinedTextField(
                                s.minutes, { s.minutes = it }, label = { Text("min") },
                                singleLine = true, modifier = Modifier.width(84.dp),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            )
                            androidx.compose.material3.OutlinedTextField(
                                s.reps, { s.reps = it }, label = { Text("× reps") },
                                singleLine = true, modifier = Modifier.width(84.dp),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            )
                        }
                    }
                }
                TextButton(onClick = { steps.add(BuilderStep()) }) { Icon(Icons.Filled.Add, null); Text(" Add step") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = push, onCheckedChange = { push = it })
                    Text("Push to Intervals.icu watch calendar", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZoneDropdown(value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.AssistChip(onClick = { open = true }, label = { Text(value) })
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false })
            }
        }
    }
}

@Composable
private fun WeekSummaryCard(
    weekStart: LocalDate,
    workouts: List<PlannedWorkout>,
    planning: Boolean,
    weekPlan: com.workoutmaker.app.data.WeekPlanRow?,
    onPlan: () -> Unit,
) {
    val weekDates = (0..6).map { weekStart.plusDays(it.toLong()).toString() }.toSet()
    val week = workouts.filter { it.date in weekDates }
    val nonRest = week.filter { it.type != "rest" }
    val sessions = nonRest.size
    val tss = week.sumOf { it.workout_json.tss_estimate.toInt() }
    val rest = week.count { it.type == "rest" }

    // Intensity distribution by target RPE.
    val easy = nonRest.count { it.workout_json.rpe_target <= 4 }
    val moderate = nonRest.count { it.workout_json.rpe_target in 5.0..6.9 }
    val hard = nonRest.count { it.workout_json.rpe_target >= 7 }
    val easyPct = if (sessions > 0) (easy + moderate) * 100 / sessions else 0

    var expanded by remember { mutableStateOf(false) }

    // P6: plan-vs-actual adherence for the week.
    val doneSessions = nonRest.count { it.completed }
    val doneTss = nonRest.filter { it.completed }.sumOf { it.workout_json.tss_estimate.toInt() }
    val adherencePct = if (sessions > 0) doneSessions * 100 / sessions else 0

    SectionCard(title = "This week · $weekStart → ${weekStart.plusDays(6)}") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                WeekStat("Sessions", "$sessions")
                WeekStat("Planned TSS", "$tss")
                WeekStat("Hard", "$hard")
                WeekStat("Rest", "$rest")
            }
            com.workoutmaker.app.ui.components.InfoIcon("Your week at a glance", com.workoutmaker.app.ui.components.Metrics.WEEK_CARD)
        }

        if (sessions > 0) {
            val adhColor = if (adherencePct >= 80) Sage else if (adherencePct >= 50) Sand else BandRed
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Box(Modifier.fillMaxWidth(adherencePct / 100f).fillMaxHeight().background(adhColor))
                }
                Text("  $doneSessions/$sessions done · $doneTss/$tss TSS",
                    style = MaterialTheme.typography.labelSmall, color = adhColor)
            }
        }

        if (sessions > 0) {
            // Easy/moderate/hard stacked bar (target ~80% easy).
            Row(
                Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
            ) {
                if (easy > 0) Box(Modifier.weight(easy.toFloat()).fillMaxHeight().background(Sage))
                if (moderate > 0) Box(Modifier.weight(moderate.toFloat()).fillMaxHeight().background(Sand))
                if (hard > 0) Box(Modifier.weight(hard.toFloat()).fillMaxHeight().background(Moss))
            }
            Text(
                "$easyPct% easy/aerobic · target ~80%",
                style = MaterialTheme.typography.labelSmall,
                color = if (easyPct >= 75) Sage else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        androidx.compose.material3.Button(
            onClick = onPlan,
            enabled = !planning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.AutoAwesome, null)
            Text(if (planning) "  Planning…" else if (week.isEmpty()) "  Plan my week (AI)" else "  Re-plan this week (AI)")
        }

        if (weekPlan?.rationale != null || weekPlan?.focus != null) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide explanation ▴" else "Explain this week ▾")
            }
            if (expanded) {
                weekPlan.focus?.let {
                    Text("Focus: $it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                weekPlan.rationale?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                nonRest.sortedBy { it.date }.forEach { w ->
                    val note = w.workout_json.coach_note
                    Column(Modifier.padding(top = 4.dp)) {
                        Text("${w.date} · ${w.workout_json.title}", style = MaterialTheme.typography.labelMedium)
                        if (note.isNotBlank()) {
                            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = Sage)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    byDate: Map<String, List<PlannedWorkout>>,
    strengthDates: Set<String>,
    activityDates: Set<String>,
    onSelect: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    val leading = month.atDay(1).dayOfWeek.value - 1 // Monday = 0
    val daysInMonth = month.lengthOfMonth()
    val totalCells = ((leading + daysInMonth + 6) / 7) * 7

    SectionCard {
        Row(Modifier.fillMaxWidth()) {
            listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su").forEach { d ->
                Text(
                    d,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        var cell = 0
        while (cell < totalCells) {
            Row(Modifier.fillMaxWidth()) {
                repeat(7) {
                    val dayNum = cell - leading + 1
                    if (dayNum in 1..daysInMonth) {
                        val date = month.atDay(dayNum)
                        DayCell(
                            date = date,
                            isToday = date == today,
                            isSelected = date == selected,
                            sessions = byDate[date.toString()].orEmpty(),
                            hasStrength = date.toString() in strengthDates,
                            hasActivity = date.toString() in activityDates,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(date) },
                        )
                    } else {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    }
                    cell++
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    sessions: List<PlannedWorkout>,
    hasStrength: Boolean,
    hasActivity: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(
                if (isToday && !isSelected)
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${date.dayOfMonth}", style = MaterialTheme.typography.bodySmall, color = fg)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                sessions.take(3).forEach { s ->
                    Box(Modifier.size(5.dp).background(typeColor(s.type), CircleShape))
                }
                if (hasStrength) Box(Modifier.size(5.dp).background(Sand, CircleShape))
                // A completed Intervals activity — hollow ring to read as "actually done".
                if (hasActivity) Box(
                    Modifier.size(5.dp).clip(CircleShape)
                        .border(1.2.dp, if (isSelected) fg else Sage, CircleShape),
                )
            }
        }
    }
}

private fun fmtPaceSec(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

private fun activityMeta(act: CompletedActivity): List<String> = buildList {
    act.distanceKm?.let { if (it > 0) add("%.1f km".format(it)) }
    act.durationMin?.let { if (it > 0) add("$it min") }
    act.paceSecPerKm?.let { add("${fmtPaceSec(it)} /km") }
    act.avg_hr?.let { add("♥ $it bpm") }
    act.tss?.let { if (it > 0) add("TSS ${it.toInt()}") }
}

// Compact card for a completed activity in the day list — taps into the detail.
@Composable
private fun ActivityCard(act: CompletedActivity, planned: PlannedWorkout?, onClick: () -> Unit) {
    SectionCard(Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Done · ${act.type ?: "activity"}", color = Sage)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.ChevronRight, "Open details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(act.displayName, style = MaterialTheme.typography.titleMedium)
        val meta = activityMeta(act)
        if (meta.isNotEmpty()) ChipRow(meta)
        // Hint when this isn't what was on the plan that day.
        planned?.let { p ->
            if (p.type != "rest" && !p.completed && !looksLike(p.type, act.type)) {
                Text("Off-plan — ${p.type} was scheduled", style = MaterialTheme.typography.labelSmall, color = Sand)
            }
        }
    }
}

private fun looksLike(plannedType: String, actualType: String?): Boolean {
    val a = (actualType ?: "").lowercase()
    return when (plannedType.lowercase()) {
        "run" -> a.contains("run") || a.contains("walk")
        "strength" -> a.contains("weight") || a.contains("strength") || a.contains("workout") || a.contains("gym")
        else -> a.isNotEmpty()
    }
}

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
    }
}
