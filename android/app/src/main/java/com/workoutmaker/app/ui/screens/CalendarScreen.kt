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

internal fun typeColor(type: String) = when {
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
            // A watch-recorded weight-training activity on a day with an app-logged
            // strength session is the SAME workout — fold the watch data into the
            // strength card instead of showing two entries.
            val pairedWatch = remember(dayActivities, dayStrength) {
                val pool = dayActivities.filter { looksLike("strength", it.type) }.toMutableList()
                dayStrength.associate { sw -> sw.id to (if (pool.isNotEmpty()) pool.removeAt(0) else null) }
            }
            val mergedIds = pairedWatch.values.filterNotNull().map { it.id }.toSet()

            // Remaining completed Intervals.icu activities on this day — tap for detail.
            dayActivities.filter { it.id !in mergedIds }.forEach { act ->
                ActivityCard(act, planned = daySessions.firstOrNull()) { activityDetail = act }
            }

            // Q14: strength sessions logged on this day (now unified with their
            // watch recording, when one exists) — tap to open detail.
            dayStrength.forEach { sw ->
                val watch = pairedWatch[sw.id]
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
                        watch?.avg_hr?.let { add("❤️ $it bpm") }
                        watch?.tss?.let { if (it > 0) add("TSS ${it.toInt()}") }
                    })
                    if (watch != null) {
                        Text("⌚ Merged with your watch recording", style = MaterialTheme.typography.labelSmall, color = Sage)
                    }
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
