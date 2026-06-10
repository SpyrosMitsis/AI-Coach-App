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

internal fun fmtClock(totalSec: Long): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

internal fun trimKg(v: Double): String =
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
internal fun StrengthHomeView(vm: StrengthViewModel, onOpenHistory: () -> Unit = {}) {
    val history by vm.history.collectAsStateSafe()
    val routines by vm.routines.collectAsStateSafe()
    val logged by vm.loggedExercises.collectAsStateSafe()
    val loading by vm.loading.collectAsStateSafe()
    val status by vm.status.collectAsStateSafe()
    val report by vm.weeklyReport.collectAsStateSafe()
    val prs by vm.lastPrs.collectAsStateSafe()
    val pendingSync by vm.pendingSync.collectAsStateSafe()
    val editingRoutine by vm.editingRoutine.collectAsStateSafe()
    var showStatsPicker by remember { mutableStateOf(false) }

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
    if (showStatsPicker) {
        ExerciseStatsPickerDialog(
            exercises = logged,
            onPick = { showStatsPicker = false; vm.openStats(it) },
            onDismiss = { showStatsPicker = false },
        )
    }

    ScreenScaffold(
        title = "Strength",
        subtitle = "${history.size} workouts logged",
        actions = {
            IconButton(onClick = { showStatsPicker = true }, enabled = logged.isNotEmpty()) {
                Icon(Icons.Filled.BarChart, contentDescription = "Exercise stats")
            }
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
    }
}

// Searchable picker behind the top-bar 📊 button — replaces the old
// "Exercise stats" chip card. Tap an exercise to open its stats page.
@Composable
internal fun ExerciseStatsPickerDialog(
    exercises: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(exercises, query) {
        if (query.isBlank()) exercises else exercises.filter { it.contains(query.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Exercise stats") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Search exercises") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                    },
                )
                LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    if (filtered.isEmpty()) {
                        item {
                            Text("No logged exercises match.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(filtered) { ex ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(ex) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.BarChart, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Text("  $ex", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
    )
}

@Composable
internal fun DeloadBanner(mod: Modifier, reason: String) {
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
internal fun WeeklyVolumeCard(mod: Modifier, report: WeeklyReport) {
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
internal fun VolumeBar(mv: MuscleVolume) {
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
internal fun ProgramsCard(mod: Modifier, programs: List<StrengthProgram>, onAdd: (StrengthProgram) -> Unit) {
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

internal fun prEmoji(type: String): String = when (type) {
    "session_volume" -> "🏆"
    "volume" -> "💪"
    "weight" -> "🏋️"
    "e1rm" -> "📈"
    "rep" -> "🔁"
    else -> "⭐"
}
