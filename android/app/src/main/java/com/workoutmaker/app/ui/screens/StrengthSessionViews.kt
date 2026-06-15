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

// ---------------------------------------------------------------------------
// Workout detail — drill into one logged session: every exercise and set.
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutDetailView(vm: StrengthViewModel) {
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
internal fun WorkoutDetailExercise(name: String, sets: List<com.workoutmaker.app.strength.SetEntity>, onStats: () -> Unit) {
    val cardio = ExerciseCatalog.find(name)?.category == "Cardio"
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val meta = if (cardio) {
                    val mins = sets.sumOf { it.reps }
                    "Cardio · ${sets.size} interval(s) · $mins min"
                } else {
                    val vol = sets.filter { !it.isWarmup }.sumOf { it.weightKg * it.reps }
                    "${ExerciseCatalog.muscleOf(name)} · ${sets.size} sets · ${vol.toInt()} kg"
                }
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(if (cardio) "${s.reps} min" else "${trimKg(s.weightKg)} kg × ${s.reps}",
                        style = MaterialTheme.typography.bodyMedium)
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
internal fun ActiveWorkoutView(vm: StrengthViewModel) {
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
internal fun ExerciseCard(vm: StrengthViewModel, ux: UiExercise) {
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
                        text = { Text("Replace exercise") },
                        onClick = { menu = false; vm.openPickerForReplace(ux) },
                    )
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
        // column headers — cardio logs minutes only (no load, no rep count)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ux.isCardio) {
                HCell("SET", 34.dp); HCell("PREV", 72.dp); HCell("MIN", 76.dp)
            } else {
                HCell("SET", 34.dp); HCell("PREV", 72.dp); HCell("KG", 76.dp); HCell("REPS", 64.dp)
            }
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
                    cardio = ux.isCardio,
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
internal fun HCell(text: String, w: androidx.compose.ui.unit.Dp) {
    Text(text, Modifier.width(w), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetRow(
    label: String,
    s: UiSet,
    prev: com.workoutmaker.app.strength.SetEntity?,
    cardio: Boolean = false,
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
            // Tap a previous result to copy it straight into this set's fields.
            Text(
                prev?.let { if (cardio) "${it.reps} min" else "${it.weightKg.toInt()}×${it.reps}" } ?: "—",
                Modifier.width(64.dp)
                    .then(
                        if (prev != null) Modifier.clickable {
                            if (!cardio) s.weight = trimKg(prev.weightKg)
                            s.reps = prev.reps.toString()
                            onEdit()
                        } else Modifier,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = if (prev != null) Sage else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (cardio) {
                // Minutes live in the reps slot; no load to enter.
                CompactField(s.reps, { s.reps = it; onEdit() }, 72.dp, decimal = false, placeholder = s.suggestedReps)
            } else {
                CompactField(s.weight, { s.weight = it; onEdit() }, 72.dp, decimal = true, placeholder = s.suggestedWeight)
                CompactField(s.reps, { s.reps = it; onEdit() }, 60.dp, decimal = false, placeholder = s.suggestedReps)
            }
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
internal fun CompactField(
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
internal fun RestPicker(restSec: Int, onPick: (Int) -> Unit) {
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
internal fun RestTimerBar(remaining: Int, vm: StrengthViewModel) {
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
