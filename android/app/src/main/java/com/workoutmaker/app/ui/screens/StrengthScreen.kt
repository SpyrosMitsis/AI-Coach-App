package com.workoutmaker.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.filled.Timer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.workoutmaker.app.strength.ExerciseCatalog
import com.workoutmaker.app.strength.RoutineWithItems
import com.workoutmaker.app.strength.StrengthNav
import com.workoutmaker.app.strength.StrengthViewModel
import com.workoutmaker.app.strength.UiExercise
import com.workoutmaker.app.strength.UiSet
import com.workoutmaker.app.strength.WorkoutEntity
import com.workoutmaker.app.strength.PlateMath
import com.workoutmaker.app.strength.ExerciseStats
import com.workoutmaker.app.data.format
import com.workoutmaker.app.strength.MuscleVolume
import com.workoutmaker.app.strength.OneRepMax
import com.workoutmaker.app.strength.StrengthProgram
import com.workoutmaker.app.strength.WeeklyReport
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.ChipRow
import com.workoutmaker.app.ui.components.EmptyState
import com.workoutmaker.app.ui.components.GhostButton
import com.workoutmaker.app.ui.components.InsetStat
import com.workoutmaker.app.ui.components.MetaChip
import com.workoutmaker.app.ui.components.ScreenScaffold
import com.workoutmaker.app.ui.components.SectionCard
import com.workoutmaker.app.ui.components.SectionLabel
import com.workoutmaker.app.ui.components.SkeletonCard
import com.workoutmaker.app.ui.theme.BandAmber
import com.workoutmaker.app.ui.theme.BandRed
import com.workoutmaker.app.ui.theme.Sage

private fun fmtClock(totalSec: Long): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun trimKg(v: Double): String =
    if (kotlin.math.abs(v - v.toLong()) < 0.05) v.toLong().toString() else ((v * 10).toLong() / 10.0).toString()

@Composable
fun StrengthScreen(vm: StrengthViewModel = hiltViewModel(), onOpenHistory: () -> Unit = {}) {
    val nav by vm.nav.collectAsStateSafe()
    val pendingPlanned by vm.pendingPlannedStart.collectAsStateSafe()
    LaunchedEffect(Unit) { vm.loadHome() }
    when (val n = nav) {
        is StrengthNav.Home -> StrengthHomeView(vm, onOpenHistory)
        is StrengthNav.Active -> ActiveWorkoutView(vm)
        is StrengthNav.Picker -> ExercisePickerView(vm)
        is StrengthNav.Stats -> ExerciseStatsView(vm, n.exercise)
        is StrengthNav.WorkoutDetail -> WorkoutDetailView(vm)
    }

    // Guard: a planned session was requested while one is in progress.
    pendingPlanned?.let { req ->
        AlertDialog(
            onDismissRequest = { vm.keepCurrentSession() },
            confirmButton = {
                TextButton(onClick = { vm.confirmReplaceWithPlanned() }) { Text("Discard & load", color = BandRed) }
            },
            dismissButton = { TextButton(onClick = { vm.keepCurrentSession() }) { Text("Keep current") } },
            title = { Text("Unsaved session in progress") },
            text = {
                Text(
                    "You're in the middle of logging a workout. Load the planned session " +
                        "“${req.workout.title}” instead? Your current, unsaved sets will be discarded.",
                )
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Home: start workout, routines, history, per-exercise stats entry
// ---------------------------------------------------------------------------
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StrengthHomeView(vm: StrengthViewModel, onOpenHistory: () -> Unit = {}) {
    val history by vm.history.collectAsStateSafe()
    val routines by vm.routines.collectAsStateSafe()
    val logged by vm.loggedExercises.collectAsStateSafe()
    val loading by vm.loading.collectAsStateSafe()
    val status by vm.status.collectAsStateSafe()
    val report by vm.weeklyReport.collectAsStateSafe()
    val prs by vm.lastPrs.collectAsStateSafe()
    val pendingSync by vm.pendingSync.collectAsStateSafe()
    val editingRoutine by vm.editingRoutine.collectAsStateSafe()
    var confirmDelete by remember { mutableStateOf<WorkoutEntity?>(null) }
    var query by remember { mutableStateOf("") }
    val filteredHistory = remember(history, query) {
        if (query.isBlank()) history else history.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val filteredLogged = remember(logged, query) {
        if (query.isBlank()) logged else logged.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    if (prs.isNotEmpty()) {
        val haptics = LocalHapticFeedback.current
        LaunchedEffect(prs) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        PrCelebrationDialog(prs) { vm.dismissPrs() }
    }
    editingRoutine?.let { r ->
        RoutineEditorDialog(
            routine = r,
            onSave = { name, items -> vm.saveEditedRoutine(name, items) },
            onCancel = { vm.cancelEditRoutine() },
        )
    }
    confirmDelete?.let { w ->
        ConfirmDeleteDialog(
            what = "“${w.name}”",
            detail = "This removes the workout and its sets from your history.",
            onConfirm = { vm.deleteWorkout(w.id); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }

    ScreenScaffold(
        title = "Strength",
        subtitle = "${history.size} workouts logged",
        navigationIcon = {
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Filled.History, contentDescription = "Workout history")
            }
        },
        isRefreshing = loading,
        onRefresh = { vm.loadHome() },
    ) { mod ->
        status?.let { Text(it, mod, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        if (pendingSync > 0) {
            Text("⟳ $pendingSync change${if (pendingSync > 1) "s" else ""} saved offline — will sync when you're back online.",
                mod, style = MaterialTheme.typography.bodySmall, color = BandAmber)
        }

        // Deload banner (B2)
        report?.deload?.takeIf { it.recommended }?.let { DeloadBanner(mod, it.reason) }

        // Start actions: empty (primary) / repeat last / AI generate (ghost)
        Row(mod, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.startEmpty() }, modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Filled.Add, null); Text("  Empty")
            }
            GhostButton(onClick = { vm.startFromLastWorkout() }, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Filled.Refresh, null); Text("  Repeat")
            }
        }
        GhostButton(
            onClick = { vm.generateAiLift() },
            enabled = !loading,
            modifier = mod.height(48.dp),
        ) { Icon(Icons.Filled.AutoAwesome, null); Text("  Generate today's lift with AI") }

        // Weekly volume + balance (B5)
        report?.let { WeeklyVolumeCard(mod, it) }

        SectionCard(mod, title = "Routines") {
            Text(
                "A reusable template for a single session — your exercises pre-loaded so you can start a workout in one tap (e.g. “Push Day”).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (routines.isEmpty()) {
                Text("No routines yet. Start a workout and “Save as routine”, or pick a program below.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (routines.isNotEmpty()) {
                Text(
                    "Tip: long-press a routine to edit its name and exercises.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            routines.forEach { r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { vm.startFromRoutine(r) },
                            onLongClick = { vm.beginEditRoutine(r) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.routine.name, style = MaterialTheme.typography.titleSmall)
                        Text(r.items.joinToString(", ") { it.exerciseName }.ifBlank { "empty" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1)
                    }
                    TextButton(onClick = { vm.startFromRoutine(r) }) { Text("Start") }
                    IconButton(onClick = { vm.beginEditRoutine(r) }) {
                        Icon(Icons.Filled.Edit, "Edit routine")
                    }
                    IconButton(onClick = { vm.deleteRoutine(r.routine.id) }) {
                        Icon(Icons.Filled.Delete, "Delete routine")
                    }
                }
            }
        }

        // Program builder (B4)
        ProgramsCard(mod, vm.programs) { vm.createProgram(it) }

        // Q5: search across history & exercises.
        if (history.isNotEmpty() || logged.isNotEmpty()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = mod, singleLine = true,
                label = { Text("Search workouts & exercises") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                },
            )
        }

        if (filteredLogged.isNotEmpty()) {
            SectionCard(mod, title = "Exercise stats") {
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        filteredLogged.forEach { ex ->
                            AssistChip(onClick = { vm.openStats(ex) }, label = { Text(ex) },
                                leadingIcon = { Icon(Icons.Filled.BarChart, null, Modifier.size(16.dp)) })
                        }
                    }
                }
            }
        }

        SectionCard(mod, title = "History") {
            if (history.isEmpty()) {
                EmptyState(
                    title = "No workouts yet",
                    subtitle = "Your finished sessions land here. Import from Strong/Hevy in Settings → Import data, or start a workout above.",
                    icon = Icons.Filled.BarChart,
                )
            } else if (filteredHistory.isEmpty()) {
                Text("No workouts match “$query”.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            filteredHistory.forEach { w -> HistoryRow(w, onOpen = { vm.openWorkout(w.id) }, onDelete = { confirmDelete = w }) }
        }
    }
}

@Composable
private fun DeloadBanner(mod: Modifier, reason: String) {
    Box(mod.background(BandAmber.copy(alpha = 0.18f), RoundedCornerShape(14.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠️", Modifier.padding(end = 10.dp))
            Column {
                Text("Deload suggested", style = MaterialTheme.typography.titleSmall, color = BandAmber, fontWeight = FontWeight.Bold)
                Text(reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WeeklyVolumeCard(mod: Modifier, report: WeeklyReport) {
    SectionCard(mod, title = "This week's volume") {
        Text("${report.totalHardSets} hard sets · target 10–20 per muscle",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (report.muscleVolume.isEmpty()) {
            Text("Log a session to see per-muscle volume.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        report.muscleVolume.forEach { mv -> VolumeBar(mv) }
        report.balance.forEach { w ->
            Text("• ${w.text}", style = MaterialTheme.typography.bodySmall, color = BandAmber)
        }
    }
}

@Composable
private fun VolumeBar(mv: MuscleVolume) {
    val color = when (mv.status) { "under" -> BandAmber; "over" -> BandRed; else -> Sage }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(mv.muscle, Modifier.width(86.dp), style = MaterialTheme.typography.bodySmall)
        Box(
            Modifier.weight(1f).height(10.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(5.dp)),
        ) {
            Box(
                Modifier.fillMaxWidth((mv.sets / 20f).coerceIn(0.04f, 1f)).height(10.dp)
                    .background(color, RoundedCornerShape(5.dp)),
            )
        }
        Text("  ${mv.sets}", Modifier.width(32.dp), style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End, color = color)
    }
}

@Composable
private fun ProgramsCard(mod: Modifier, programs: List<StrengthProgram>, onAdd: (StrengthProgram) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(mod, title = "Programs") {
        Text(
            "A multi-week training plan (e.g. a 3-day full-body block). Adding one creates a routine for " +
                "each training day, and auto-progression bumps the loads week to week — so a program is " +
                "really a set of routines that work together over time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!expanded) {
            TextButton(onClick = { expanded = true }) { Text("Browse ${programs.size} programs") }
        } else {
            programs.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Text(p.schedule, style = MaterialTheme.typography.labelSmall, color = Sage)
                        Text(p.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onAdd(p) }) { Text("Add") }
                }
            }
        }
    }
}

private fun prEmoji(type: String): String = when (type) {
    "session_volume" -> "🏆"
    "volume" -> "💪"
    "weight" -> "🏋️"
    "e1rm" -> "📈"
    "rep" -> "🔁"
    else -> "⭐"
}

@Composable
private fun PrCelebrationDialog(prs: List<com.workoutmaker.app.strength.PrHit>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Nice!") } },
        title = { Text("🎉 New personal record${if (prs.size > 1) "s" else ""}!") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                prs.take(8).forEach { pr ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(prEmoji(pr.type), modifier = Modifier.padding(end = 10.dp))
                        Text(pr.detail, color = Sage, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (prs.size > 8) {
                    Text(
                        "+${prs.size - 8} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

// --- routine editor (long-press a routine) ----------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineEditorDialog(
    routine: RoutineWithItems,
    onSave: (String, List<com.workoutmaker.app.strength.RoutineItemEntity>) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(routine.routine.name) }
    val items = remember {
        mutableStateListOf<com.workoutmaker.app.strength.RoutineItemEntity>().apply {
            addAll(routine.items.sortedBy { it.position })
        }
    }
    var showAdd by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Edit routine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Routine name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (items.isEmpty()) {
                    Text("No exercises yet — add one below.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(
                    Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items.forEachIndexed { i, item ->
                        RoutineEditItemRow(
                            item = item,
                            canUp = i > 0,
                            canDown = i < items.lastIndex,
                            onSets = { items[i] = item.copy(targetSets = it) },
                            onReps = { items[i] = item.copy(targetReps = it) },
                            onUp = { if (i > 0) { val t = items[i - 1]; items[i - 1] = items[i]; items[i] = t } },
                            onDown = { if (i < items.lastIndex) { val t = items[i + 1]; items[i + 1] = items[i]; items[i] = t } },
                            onRemove = { items.removeAt(i) },
                        )
                    }
                }
                OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null); Text("  Add exercise")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = { onSave(name, items.toList()) }, enabled = name.isNotBlank()) { Text("Save") }
                }
            }
        }
    }

    if (showAdd) {
        ExerciseAddDialog(
            onPick = { picked ->
                items.add(
                    com.workoutmaker.app.strength.RoutineItemEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        routineId = routine.routine.id,
                        exerciseName = picked,
                        position = items.size,
                        targetSets = 3,
                        targetReps = "8-12",
                        restSec = ExerciseCatalog.restOf(picked),
                    ),
                )
                showAdd = false
            },
            onClose = { showAdd = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineEditItemRow(
    item: com.workoutmaker.app.strength.RoutineItemEntity,
    canUp: Boolean,
    canDown: Boolean,
    onSets: (Int) -> Unit,
    onReps: (String) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.exerciseName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onUp, enabled = canUp) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
            IconButton(onClick = onDown, enabled = canDown) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove", tint = BandRed) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sets", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { onSets((item.targetSets - 1).coerceAtLeast(1)) }) {
                Text("−", style = MaterialTheme.typography.titleMedium)
            }
            Text("${item.targetSets}", style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { onSets((item.targetSets + 1).coerceAtMost(12)) }) {
                Text("+", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedTextField(
                value = item.targetReps, onValueChange = onReps,
                label = { Text("Reps") }, singleLine = true,
                modifier = Modifier.width(120.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseAddDialog(onPick: (String) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val names = remember {
        (ExerciseCatalog.all.map { it.name } + ExerciseCatalog.custom().map { it.name }).distinct().sorted()
    }
    val filtered = remember(q) {
        if (q.isBlank()) names else names.filter { it.contains(q.trim(), ignoreCase = true) }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add exercise", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = q, onValueChange = { q = it },
                    label = { Text("Search") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered) { n ->
                        Text(
                            n,
                            Modifier.fillMaxWidth().clickable { onPick(n) }.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun HistoryRow(w: WorkoutEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    val date = java.time.Instant.ofEpochMilli(w.startedAt)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(w.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            ChipRow(
                listOfNotNull(
                    date,
                    fmtClock(w.durationSec.toLong()).takeIf { w.durationSec > 0 },
                    "${w.totalVolumeKg.toInt()} kg",
                ),
            )
        }
        Icon(Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete workout", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ConfirmDeleteDialog(what: String, detail: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = BandRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Delete $what?") },
        text = { Text(detail) },
    )
}

// ---------------------------------------------------------------------------
// Workout detail — drill into one logged session: every exercise and set.
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutDetailView(vm: StrengthViewModel) {
    val detail by vm.workoutDetail.collectAsStateSafe()
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val d = detail
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(d?.workout?.name ?: "Workout") },
                navigationIcon = { IconButton(onClick = { vm.goHome() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (d != null) {
                        IconButton(onClick = { vm.editWorkout(d.workout.id) }) { Icon(Icons.Filled.Edit, "Edit workout") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Save as routine") },
                                onClick = { menu = false; vm.saveWorkoutAsRoutine(d.workout.id) })
                            DropdownMenuItem(text = { Text("Delete workout") },
                                onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (d == null) {
            Box(Modifier.padding(padding).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        if (confirmDelete) ConfirmDeleteDialog(
            what = "“${d.workout.name}”",
            detail = "This removes the workout and its ${d.totalSets} sets from your history.",
            onConfirm = { confirmDelete = false; vm.deleteWorkout(d.workout.id) },
            onDismiss = { confirmDelete = false },
        )
        val date = java.time.Instant.ofEpochMilli(d.workout.startedAt)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        LazyColumn(
            Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Pr("Volume", "${d.workout.totalVolumeKg.toInt()} kg")
                    Pr("Sets", "${d.totalSets}")
                    Pr("Exercises", "${d.exercises.size}")
                    if (d.workout.durationSec > 0) Pr("Time", fmtClock(d.workout.durationSec.toLong()))
                }
                Text(date, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (d.workout.note.isNotBlank()) {
                    Text("“${d.workout.note}”", Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium, color = Sage)
                }
            }
            items(d.exercises, key = { it.first }) { (name, sets) ->
                WorkoutDetailExercise(name, sets, onStats = { vm.openStats(name) })
            }
        }
    }
}

@Composable
private fun WorkoutDetailExercise(name: String, sets: List<com.workoutmaker.app.strength.SetEntity>, onStats: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val vol = sets.filter { !it.isWarmup }.sumOf { it.weightKg * it.reps }
                Text("${ExerciseCatalog.muscleOf(name)} · ${sets.size} sets · ${vol.toInt()} kg",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onStats) { Icon(Icons.Filled.BarChart, "Exercise stats") }
        }
        sets.forEach { s ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
                        Text(if (s.isWarmup) "W" else "${s.idx}",
                            color = if (s.isWarmup) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${trimKg(s.weightKg)} kg × ${s.reps}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    s.rpe?.let { Text("RPE $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (s.note.isNotBlank()) {
                    Text(s.note, Modifier.padding(start = 34.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Active workout
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveWorkoutView(vm: StrengthViewModel) {
    val elapsed by vm.elapsedSec.collectAsStateSafe()
    val rest by vm.restRemaining.collectAsStateSafe()
    val status by vm.status.collectAsStateSafe()
    val settings by vm.settings.collectAsStateSafe()
    var menu by remember { mutableStateOf(false) }
    var showPlate by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }

    // Keep the screen awake during a workout when the user opts in.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Persist the session whenever the app is backgrounded (captures in-progress
    // text edits to weights/reps/name) so a lock or kill never loses it.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            // Synchronous write — the process may be killed right after backgrounding.
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.persistSessionNow()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(fmtClock(elapsed), fontWeight = FontWeight.Bold)
                        Text("${vm.exercises.size} exercises", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    Button(onClick = { confirmFinish = true }) { Text(if (vm.isEditing) "Save" else "Finish") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Plate calculator") }, onClick = { menu = false; showPlate = true })
                        DropdownMenuItem(text = { Text("Save as routine") }, onClick = { menu = false; vm.saveAsRoutine() })
                        DropdownMenuItem(text = { Text("Cancel workout") }, onClick = { menu = false; vm.cancelWorkout() })
                    }
                },
            )
        },
        bottomBar = {
            Column {
                rest?.let { RestTimerBar(it, vm) }
                status?.let {
                    Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.openPicker() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Add, null); Text(" Add exercise")
                    }
                    OutlinedButton(onClick = { vm.startManualRest() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Timer, null); Text(" Rest")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(vm.workoutName, { vm.workoutName = it; vm.persistSession() }, label = { Text("Workout name") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            item {
                OutlinedTextField(vm.workoutNote, { vm.workoutNote = it; vm.persistSession() }, label = { Text("Session note (optional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = false,
                    textStyle = MaterialTheme.typography.bodySmall)
            }
            items(vm.exercises, key = { it.name + System.identityHashCode(it) }) { ux ->
                ExerciseCard(vm, ux)
            }
            if (vm.exercises.isEmpty()) {
                item { Text("No exercises yet — tap “Add exercise”.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    if (showPlate) PlateCalcDialog(settings, onClose = { showPlate = false })
    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text(if (vm.isEditing) "Save changes?" else "Finish workout?") },
            text = { Text(if (vm.isEditing) "Your edits replace the saved workout." else "Completed sets are saved and synced for your AI coach.") },
            confirmButton = { TextButton(onClick = { confirmFinish = false; vm.finish() }) { Text(if (vm.isEditing) "Save" else "Finish") } },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun ExerciseCard(vm: StrengthViewModel, ux: UiExercise) {
    var menu by remember { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ux.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(ux.muscle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Q4: reorder within the session.
            IconButton(onClick = { vm.moveExercise(ux, up = true) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, "Move up", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.moveExercise(ux, up = false) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, "Move down", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RestPicker(ux.restSec) { vm.setRest(ux, it) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, "Exercise options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove exercise", color = BandRed) },
                        onClick = { menu = false; vm.removeExercise(ux) },
                    )
                }
            }
        }
        // B1 auto-progression target
        ux.suggestion?.let { s ->
            Text("🎯 Target ${trimKg(s.weightKg)}kg × ${s.reps} · ${s.note}",
                style = MaterialTheme.typography.labelSmall, color = Sage)
        }
        // column headers
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            HCell("SET", 34.dp); HCell("PREV", 72.dp); HCell("KG", 76.dp); HCell("REPS", 64.dp)
            Spacer(Modifier.weight(1f)); HCell("✓", 40.dp)
        }
        // Working-set numbering: warm-ups show "W" and don't consume a number.
        var working = 0
        ux.sets.forEachIndexed { i, s ->
            val label = if (s.warmup) "W" else { working += 1; working.toString() }
            // Key by the set's identity so swipe-dismiss state follows the row it
            // belongs to (otherwise deleting one freezes the dismissed background
            // onto the row that shifts into its slot).
            androidx.compose.runtime.key(System.identityHashCode(s)) {
                SetRow(
                    label, s, ux.previous.getOrNull(i),
                    onToggle = { vm.toggleDone(ux, s) },
                    onRemove = { vm.removeSet(ux, s) },
                    onEdit = { vm.persistSession() },
                )
            }
        }
        TextButton(onClick = { vm.addSet(ux) }) { Icon(Icons.Filled.Add, null); Text(" Add set") }
    }
}

@Composable
private fun HCell(text: String, w: androidx.compose.ui.unit.Dp) {
    Text(text, Modifier.width(w), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetRow(
    label: String,
    s: UiSet,
    prev: com.workoutmaker.app.strength.SetEntity?,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit = {},
) {
    var showNote by remember { mutableStateOf(s.note.isNotBlank()) }
    val haptics = LocalHapticFeedback.current
    // Opaque so the red delete background only shows while swiping.
    val rowBg = if (s.done) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            .compositeOver(MaterialTheme.colorScheme.surface)
    } else {
        MaterialTheme.colorScheme.surface
    }
    // Swipe the row left to delete the set (red trash revealed on the right).
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.EndToStart) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onRemove()
                true
            } else {
                false
            }
        },
        positionalThreshold = { it * 0.45f },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                    .background(BandRed.copy(alpha = 0.9f)).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, "Delete set", tint = androidx.compose.ui.graphics.Color.White)
            }
        },
    ) {
    Column(Modifier.fillMaxWidth().background(rowBg, RoundedCornerShape(8.dp)).padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // working-set number / warmup toggle
            Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
                Text(
                    label,
                    Modifier.clickable { s.warmup = !s.warmup },
                    color = if (s.warmup) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                prev?.let { "${it.weightKg.toInt()}×${it.reps}" } ?: "—",
                Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            CompactField(s.weight, { s.weight = it; onEdit() }, 72.dp, decimal = true, placeholder = s.suggestedWeight)
            CompactField(s.reps, { s.reps = it; onEdit() }, 60.dp, decimal = false, placeholder = s.suggestedReps)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showNote = !showNote }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.AutoMirrored.Filled.NoteAdd, "Note",
                    tint = if (s.note.isNotBlank()) Sage else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = {
                    val becomingDone = !s.done
                    onToggle()
                    if (becomingDone) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                modifier = Modifier.size(38.dp),
            ) {
                Icon(Icons.Filled.Check, "Done", tint = if (s.done) Sage else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showNote) {
            OutlinedTextField(
                value = s.note, onValueChange = { s.note = it; onEdit() },
                modifier = Modifier.fillMaxWidth().padding(start = 34.dp, end = 8.dp, bottom = 4.dp),
                placeholder = { Text("Note (e.g. felt easy, left knee)") },
                singleLine = true, textStyle = MaterialTheme.typography.bodySmall,
            )
        }
    }
    }
}

@Composable
private fun CompactField(
    value: String,
    onChange: (String) -> Unit,
    w: androidx.compose.ui.unit.Dp,
    decimal: Boolean,
    placeholder: String = "",
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.width(w),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
        // Greyed AI/last-time suggestion; vanishes the moment the user types.
        placeholder = if (placeholder.isBlank()) null else {
            {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

@Composable
private fun RestPicker(restSec: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = if (restSec <= 0) "Rest off" else "Rest ${fmtClock(restSec.toLong())}"
    Box {
        AssistChip(onClick = { open = true }, label = { Text(label) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(0, 60, 90, 120, 150, 180, 210, 240).forEach { sec ->
                DropdownMenuItem(
                    text = { Text(if (sec == 0) "Off" else fmtClock(sec.toLong())) },
                    onClick = { onPick(sec); open = false },
                )
            }
        }
    }
}

@Composable
private fun RestTimerBar(remaining: Int, vm: StrengthViewModel) {
    val total by vm.restTotal.collectAsStateSafe()
    val target = (if (total > 0) remaining.toFloat() / total else 0f).coerceIn(0f, 1f)
    val progress by animateFloatAsState(targetValue = target, label = "rest-progress")
    val nearDone = remaining in 1..5
    val accent = if (nearDone) BandAmber else Sage
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        androidx.compose.material3.LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(5.dp),
            color = accent,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "REST",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.5.sp,
                )
                Text(
                    fmtClock(remaining.toLong()),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
            TextButton(onClick = { vm.adjustRest(-15) }) { Text("−15s") }
            TextButton(onClick = { vm.adjustRest(15) }) { Text("+15s") }
            FilledTonalButton(onClick = { vm.skipRest() }) { Text("Skip") }
        }
    }
}

// ---------------------------------------------------------------------------
// Exercise picker
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExercisePickerView(vm: StrengthViewModel) {
    var query by remember { mutableStateOf("") }
    var muscle by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var confirmDeleteCustom by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    val favorites by vm.favorites.collectAsStateSafe()
    val recents by vm.recentExercises.collectAsStateSafe()
    val custom by vm.customExercises.collectAsStateSafe()
    // Recompute when custom list or filters change.
    val results = remember(query, muscle, category, custom) { ExerciseCatalog.search(query, muscle, category) }

    if (showCreate) CreateExerciseDialog(onClose = { showCreate = false }) { n, m, c, comp ->
        vm.addCustomExercise(n, m, c, comp); showCreate = false
    }

    confirmDeleteCustom?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDeleteCustom = null },
            confirmButton = {
                TextButton(onClick = { vm.deleteCustomExercise(name); selected.remove(name); confirmDeleteCustom = null }) {
                    Text("Delete", color = BandRed)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteCustom = null }) { Text("Cancel") } },
            title = { Text("Delete “$name”?") },
            text = { Text("This removes your custom exercise. Past sessions that used it are unaffected.") },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Add exercises") },
                navigationIcon = { IconButton(onClick = { vm.backToActive() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { showCreate = true }) { Icon(Icons.Filled.Add, null); Text(" New") } },
            )
        },
        bottomBar = {
            Button(
                onClick = { vm.addExercises(selected.toList()) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) { Text("Add ${selected.size} exercise(s)") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxWidth()) {
            OutlinedTextField(query, { query = it }, label = { Text("Search") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true)

            // D5: quick-add favorites & recents
            if (query.isBlank() && muscle == null && category == null) {
                QuickAddRow("★ Favorites", favorites, selected)
                QuickAddRow("Recent", recents, selected)
            }

            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = muscle == null, onClick = { muscle = null }, label = { Text("All") })
                    ExerciseCatalog.muscles.forEach { m ->
                        FilterChip(selected = muscle == m, onClick = { muscle = if (muscle == m) null else m }, label = { Text(m) })
                    }
                }
            }
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExerciseCatalog.categories.forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = if (category == c) null else c }, label = { Text(c) })
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(results, key = { it.name }) { ex ->
                    val isSel = selected.contains(ex.name)
                    val isFav = favorites.contains(ex.name)
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (isSel) selected.remove(ex.name) else selected.add(ex.name)
                        }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(ex.name + if (ExerciseCatalog.isCustom(ex.name)) "  ·  custom" else "",
                                style = MaterialTheme.typography.bodyLarge)
                            Text("${ex.muscle} · ${ex.category}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (ExerciseCatalog.isCustom(ex.name)) {
                            IconButton(onClick = { confirmDeleteCustom = ex.name }) {
                                Icon(Icons.Filled.Delete, "Delete custom exercise",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { vm.toggleFavorite(ex.name) }) {
                            Icon(if (isFav) Icons.Filled.Star else Icons.Filled.StarBorder, "Favorite",
                                tint = if (isFav) Sage else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked = isSel, onCheckedChange = {
                            if (isSel) selected.remove(ex.name) else selected.add(ex.name)
                        })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAddRow(label: String, names: List<String>, selected: MutableList<String>) {
    if (names.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEach { n ->
                    val isSel = selected.contains(n)
                    FilterChip(selected = isSel, onClick = { if (isSel) selected.remove(n) else selected.add(n) }, label = { Text(n) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateExerciseDialog(onClose: () -> Unit, onCreate: (String, String, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var muscle by remember { mutableStateOf(ExerciseCatalog.muscles.first()) }
    var category by remember { mutableStateOf(ExerciseCatalog.categories.first()) }
    var compound by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = { onCreate(name, muscle, category, compound) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        title = { Text("New exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Muscle", style = MaterialTheme.typography.labelSmall)
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExerciseCatalog.muscles.forEach { m -> FilterChip(selected = muscle == m, onClick = { muscle = m }, label = { Text(m) }) }
                    }
                }
                Text("Equipment", style = MaterialTheme.typography.labelSmall)
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExerciseCatalog.categories.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = compound, onCheckedChange = { compound = it })
                    Text("Compound lift (longer default rest)")
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Per-exercise stats
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseStatsView(vm: StrengthViewModel, exercise: String) {
    val stats by vm.currentStats.collectAsStateSafe()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(exercise) },
                navigationIcon = { IconButton(onClick = { vm.goHome() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val s = stats
        Column(Modifier.padding(padding).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s == null || !s.hasData) {
                Text("No history yet. Log this exercise to see e1RM and PRs.")
                return@Column
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Pr("Best e1RM", "${s.bestE1rm.toInt()} kg")
                Pr("Best set", "${s.bestWeight.toInt()} kg")
                Pr("Best volume", "${s.bestVolume.toInt()} kg")
            }

            // C1: progression chart with a metric toggle.
            var metric by remember { mutableStateOf("e1RM") }
            SectionCard(title = "Progression") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("e1RM", "Top weight", "Volume").forEach { m ->
                        FilterChip(selected = metric == m, onClick = { metric = m }, label = { Text(m) })
                    }
                }
                val series = s.points.map {
                    when (metric) {
                        "Top weight" -> it.bestWeight
                        "Volume" -> it.volume
                        else -> it.e1rm
                    }
                }
                MetricChart(series)
                Text(
                    "${s.points.size} sessions · latest ${series.lastOrNull()?.toInt() ?: 0}" +
                        if (metric == "Volume") " kg vol" else " kg",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // C6: %-of-1RM training table off the best estimated 1RM.
            SectionCard(title = "Training loads (% of ${s.bestE1rm.toInt()}kg 1RM)") {
                OneRepMax.table(s.bestE1rm).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${row.pct}% · ~${row.reps} reps", style = MaterialTheme.typography.bodySmall)
                        Text("${trimKg(row.weightKg)} kg", style = MaterialTheme.typography.bodyMedium, color = Sage)
                    }
                }
            }

            SectionCard(title = "Sessions") {
                s.points.reversed().forEach { p ->
                    val d = java.time.Instant.ofEpochMilli(p.dateMillis)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d, style = MaterialTheme.typography.bodySmall)
                        Text("e1RM ${p.e1rm.toInt()}kg · ${p.volume.toInt()}kg vol",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Pr(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = Sage)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricChart(values: List<Double>) {
    if (values.isEmpty()) {
        Text("Not enough data yet.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val maxV = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    val minV = (values.minOrNull() ?: 0.0)
    val span = (maxV - minV).coerceAtLeast(1.0)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(140.dp).padding(vertical = 6.dp)) {
        val pad = 8f
        val h = size.height - pad * 2
        fun yOf(v: Double) = pad + (h - ((v - minV) / span * h)).toFloat()
        // baseline
        drawLine(Sage.copy(alpha = 0.25f), androidx.compose.ui.geometry.Offset(0f, size.height - pad),
            androidx.compose.ui.geometry.Offset(size.width, size.height - pad), strokeWidth = 2f)
        if (values.size < 2) {
            drawCircle(Sage, radius = 7f, center = androidx.compose.ui.geometry.Offset(size.width / 2, yOf(values.first())))
            return@Canvas
        }
        val stepX = size.width / (values.size - 1)
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { i, v ->
            val x = stepX * i
            val y = yOf(v)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Sage, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f))
        values.forEachIndexed { i, v -> drawCircle(Sage, radius = 4f, center = androidx.compose.ui.geometry.Offset(stepX * i, yOf(v))) }
    }
}

@Composable
private fun PlateCalcDialog(settings: com.workoutmaker.app.data.AppSettings, onClose: () -> Unit) {
    val unit = settings.units
    var target by remember { mutableStateOf(unit.format(100.0)) }
    // Interpret the input in the user's unit, then load in real (kg) plates.
    val targetKg = com.workoutmaker.app.data.WeightUnit.displayToKg(target.toDoubleOrNull() ?: 0.0, unit)
    val plates = PlateMath.perSide(targetKg, settings.barbellKg)
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("Plate calculator") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(target, { target = it }, label = { Text("Target total (${unit.suffix})") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("Barbell ${unit.format(settings.barbellKg)} ${unit.suffix} + per side:", style = MaterialTheme.typography.bodySmall)
                if (plates.isEmpty()) Text("— just the bar", style = MaterialTheme.typography.bodyMedium)
                plates.forEach { p ->
                    Text("${p.count} × ${unit.format(p.plate)} ${unit.suffix}", style = MaterialTheme.typography.bodyLarge, color = Sage)
                }
            }
        },
    )
}
