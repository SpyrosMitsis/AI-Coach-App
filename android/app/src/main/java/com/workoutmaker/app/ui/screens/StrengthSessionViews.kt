package com.workoutmaker.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import com.workoutmaker.app.ui.components.ChipRow
import com.workoutmaker.app.ui.components.DragDropState
import com.workoutmaker.app.ui.components.EmptyState
import com.workoutmaker.app.ui.components.rememberDragDropState
import com.workoutmaker.app.ui.components.GhostButton
import com.workoutmaker.app.ui.components.InsetStat
import com.workoutmaker.app.ui.components.MetaChip
import com.workoutmaker.app.ui.components.ScreenScaffold
import com.workoutmaker.app.ui.components.SectionCard
import com.workoutmaker.app.ui.components.SectionLabel
import com.workoutmaker.app.ui.components.SkeletonCard
import com.workoutmaker.app.ui.theme.amberAccent

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
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
    val finishHaptics = LocalHapticFeedback.current

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
        // Drag-to-reorder: exercise cards sit after the two header fields, so
        // LazyColumn indices are exercise index + 2. Only exercise rows are
        // draggable / valid drop targets.
        val listState = rememberLazyListState()
        val headerItems = 2
        val dragState = rememberDragDropState(
            listState,
            canDrag = { info -> info.index - headerItems in vm.exercises.indices },
            onMove = { from, to -> vm.moveExerciseTo(from - headerItems, to - headerItems) },
        )
        LazyColumn(
            Modifier.padding(padding).fillMaxWidth(),
            state = listState,
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
            itemsIndexed(vm.exercises, key = { _, ux -> ux.name + System.identityHashCode(ux) }) { i, ux ->
                val isDragging = dragState.draggingItemIndex == i + headerItems
                val isSettling = dragState.settlingItemIndex == i + headerItems
                Box(
                    when {
                        // The grabbed card floats over its neighbours and tracks the finger.
                        isDragging -> Modifier.zIndex(1f).graphicsLayer {
                            translationY = dragState.draggingItemOffset
                            shadowElevation = 24f
                        }
                        // Just released: glide home from the finger position.
                        isSettling -> Modifier.zIndex(1f).graphicsLayer {
                            translationY = dragState.settlingItemOffset.value
                            shadowElevation = 24f * (dragState.settlingItemOffset.value.let { v ->
                                (kotlin.math.abs(v) / 100f).coerceAtMost(1f)
                            })
                        }
                        else -> Modifier.animateItem()
                    },
                ) {
                    ExerciseCard(vm, ux, dragState)
                }
            }
            if (vm.exercises.isEmpty()) {
                item {
                    EmptyState(
                        title = "No exercises yet",
                        subtitle = "Tap “Add exercise” to start logging sets.",
                        icon = Icons.Filled.FitnessCenter,
                    )
                }
            }
        }
    }

    if (showPlate) PlateCalcDialog(settings, onClose = { showPlate = false })
    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text(if (vm.isEditing) "Save changes?" else "Finish workout?") },
            text = { Text(if (vm.isEditing) "Your edits replace the saved workout." else "Completed sets are saved and synced for your AI coach.") },
            confirmButton = { TextButton(onClick = { confirmFinish = false; finishHaptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.finish() }) { Text(if (vm.isEditing) "Save" else "Finish") } },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Keep going") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Post-workout effort rating — session RPE + difficulty feed the AI engine.
// RPE measures effort only — it is NOT a quality score — so the bars use one
// neutral accent rather than a green→red "good/bad" ramp.
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RateEffortView(vm: StrengthViewModel) {
    var rpe by rememberSaveable { mutableStateOf<Int?>(null) }
    var difficulty by rememberSaveable { mutableStateOf<String?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    val haptics = LocalHapticFeedback.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Text("Rate your effort") }) },
    ) { padding ->
        // Full page, NO scroll: the bars flex to absorb slack so the readout
        // stays at the top and the buttons stay pinned at the bottom on any phone.
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "How hard did that feel?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))

            // Big animated readout of the chosen effort.
            Text(
                "RATE OF PERCEIVED EXERTION",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    rpe?.toString() ?: "-",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                Text(
                    " / 10",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            Text(
                rpe?.let { rpeWord(it).replaceFirstChar { c -> c.uppercase() } } ?: "Tap a bar below",
                style = MaterialTheme.typography.titleMedium,
                color = accent,
            )

            // Flexes to fill whatever space is left between readout and the card.
            EffortBars(
                selected = rpe,
                onSelect = { rpe = it },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp),
            )

            // Quick qualitative tag — strongest signal for autoregulation.
            SectionCard {
                Text(
                    "How did the session go?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("too_easy" to "Too easy", "just_right" to "Just right", "too_hard" to "Too hard").forEach { (k, label) ->
                        val sel = difficulty == k
                        FilterChip(
                            selected = sel,
                            onClick = { difficulty = if (sel) null else k },
                            label = {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.submitEffort(rpe, difficulty)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Save effort", style = MaterialTheme.typography.titleSmall) }
            TextButton(onClick = { vm.skipEffort() }, modifier = Modifier.fillMaxWidth()) {
                Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Tall, rounded "equalizer" bars in one neutral accent (effort, not a score).
// Bars 1..selected light up; the chosen bar is outlined. Bars and their number
// labels live in separate rows so the numbers always sit on one baseline,
// regardless of how tall each bar is.
@Composable
private fun EffortBars(selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.surfaceContainerHighest
    val haptics = LocalHapticFeedback.current
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().weight(1f).heightIn(min = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            (1..10).forEach { n ->
                val active = selected != null && n <= selected
                val current = selected == n
                val heightFrac = 0.34f + (n - 1) * (0.66f / 9f)
                val barColor by animateColorAsState(if (active) accent else idle, label = "effortBar$n")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(heightFrac)
                        .clip(RoundedCornerShape(50))
                        .background(barColor)
                        .then(
                            if (current) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), RoundedCornerShape(50))
                            else Modifier,
                        )
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelect(n)
                        },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            (1..10).forEach { n ->
                val current = selected == n
                Text(
                    "$n",
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
internal fun ExerciseCard(vm: StrengthViewModel, ux: UiExercise, dragState: DragDropState? = null) {
    var menu by remember { mutableStateOf(false) }
    var showInsight by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    if (showInsight) ExerciseInsightSheet(vm, ux.name) { showInsight = false }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Q4: grab the handle and drag the card to reorder the session.
            if (dragState != null) {
                Icon(
                    Icons.Filled.DragHandle, "Reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(40.dp)
                        // Gesture area = the full 40dp square (padding only shrinks the glyph).
                        .pointerInput(dragState, ux) {
                            detectDragGestures(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    // Must match the LazyColumn item key exactly.
                                    dragState.onDragStart(ux.name + System.identityHashCode(ux))
                                },
                                onDrag = { change, amount -> change.consume(); dragState.onDrag(amount.y) },
                                onDragEnd = { dragState.onDragEnd() },
                                onDragCancel = { dragState.onDragEnd() },
                            )
                        }
                        .padding(8.dp),
                )
            }
            // Tap the name to peek this exercise's history without leaving the session.
            Column(Modifier.weight(1f).clickable { showInsight = true }) {
                Text(ux.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(ux.muscle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RestPicker(ux.restSec) { vm.setRest(ux, it) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, "Exercise options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!ux.isCardio) {
                        DropdownMenuItem(
                            text = { Text("Add warm-up sets") },
                            onClick = { menu = false; vm.addWarmupRamp(ux) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Replace exercise") },
                        onClick = { menu = false; vm.openPickerForReplace(ux) },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove exercise", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; vm.removeExercise(ux) },
                    )
                }
            }
        }
        // B1 auto-progression target
        ux.suggestion?.let { s ->
            Text("↗ Target ${trimKg(s.weightKg)}kg × ${s.reps} · ${s.note}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        // Column headers share the SetRow grid exactly so labels sit over their
        // fields. Inputs are flexible (weight); cardio's single MIN fills the area.
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HCell("SET", Modifier.width(30.dp)); HCell("PREV", Modifier.width(56.dp))
            if (ux.isCardio) {
                HCell("MIN", Modifier.weight(1f))
            } else {
                HCell("KG", Modifier.weight(1f)); HCell("REPS", Modifier.weight(1f))
            }
            // Reserve the note + check columns (no labels) so the weighted headers
            // line up with the fields above the icons.
            Spacer(Modifier.width(36.dp)); Spacer(Modifier.width(40.dp))
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
        TextButton(onClick = { vm.addSet(ux) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null); Text(" Add set")
        }
    }
}

// In-session peek at an exercise's history (PRs, e1RM/volume trend, recent
// sessions) as a bottom sheet, so the live session stays mounted underneath.
// Reuses the stats pieces from the standalone ExerciseStatsView.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExerciseInsightSheet(vm: StrengthViewModel, exercise: String, onDismiss: () -> Unit) {
    var stats by remember(exercise) { mutableStateOf<com.workoutmaker.app.strength.ExerciseStats?>(null) }
    var loaded by remember(exercise) { mutableStateOf(false) }
    LaunchedEffect(exercise) { stats = vm.statsFor(exercise); loaded = true }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(exercise, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            val s = stats
            when {
                !loaded -> Text("Loading…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                s == null || !s.hasData -> EmptyState(
                    title = "No history yet",
                    subtitle = "Log this exercise to see e1RM and PRs.",
                    icon = Icons.Filled.BarChart,
                )
                else -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Pr("Best e1RM", "${s.bestE1rm.toInt()} kg")
                        Pr("Best set", "${s.bestWeight.toInt()} kg")
                        Pr("Best volume", "${s.bestVolume.toInt()} kg")
                    }
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
                        MetricChart(series, unit = if (metric == "Volume") "kg vol" else "kg")
                        Text(
                            "${s.points.size} sessions · latest ${series.lastOrNull()?.toInt() ?: 0}" +
                                if (metric == "Volume") " kg vol" else " kg",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SectionCard(title = "Recent sessions") {
                        s.points.reversed().take(3).forEach { p ->
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
    }
}

@Composable
internal fun HCell(text: String, modifier: Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall,
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
            // Rendered only mid-swipe: at rest the red layer peeked out of the
            // row's rounded corners as a hairline sliver.
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.9f)).padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(Icons.Filled.Delete, "Delete set", tint = MaterialTheme.colorScheme.onError)
                }
            }
        },
    ) {
    Column(Modifier.fillMaxWidth().background(rowBg, RoundedCornerShape(8.dp)).padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // working-set number / warmup toggle
            Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                Text(
                    label,
                    Modifier.clickable { s.warmup = !s.warmup },
                    color = if (s.warmup) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            }
            // Tap a previous result to copy it straight into this set's fields.
            Text(
                prev?.let { if (cardio) "${it.reps} min" else "${it.weightKg.toInt()}×${it.reps}" } ?: "-",
                Modifier.width(56.dp)
                    .then(
                        if (prev != null) Modifier.clickable {
                            if (!cardio) s.weight = trimKg(prev.weightKg)
                            s.reps = prev.reps.toString()
                            onEdit()
                        } else Modifier,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = if (prev != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (cardio) {
                // Minutes live in the reps slot; no load to enter. Fills the row.
                CompactField(s.reps, { s.reps = it; onEdit() }, Modifier.weight(1f), decimal = false, placeholder = s.suggestedReps)
            } else {
                CompactField(s.weight, { s.weight = it; onEdit() }, Modifier.weight(1f), decimal = true, placeholder = s.suggestedWeight)
                CompactField(s.reps, { s.reps = it; onEdit() }, Modifier.weight(1f), decimal = false, placeholder = s.suggestedReps)
            }
            IconButton(onClick = { showNote = !showNote }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.NoteAdd, "Note",
                    tint = if (s.note.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = {
                    val becomingDone = !s.done
                    onToggle()
                    if (becomingDone) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Filled.Check, "Done", tint = if (s.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showNote) {
            Spacer(Modifier.height(8.dp))
            NoteField(s.note, { s.note = it; onEdit() })
        }
    }
    }
}

// Borderless, filled note field matching the cell style; full-width with a touch
// of inset so it sits clearly below the set's row rather than cramping it.
@Composable
private fun NoteField(value: String, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(start = 30.dp, end = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text("Note (e.g. felt easy, left knee)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                    inner()
                }
            },
        )
    }
}

// Borderless, filled input cell: a recessed (darker than the card) rounded box
// with centered text. No outline. The greyed placeholder is the AI/last-time
// suggestion and disappears the moment the user types.
@Composable
internal fun CompactField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier,
    decimal: Boolean,
    placeholder: String = "",
) {
    val centered = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center)
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = centered.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (value.isEmpty() && placeholder.isNotBlank()) {
                        Text(
                            placeholder,
                            style = centered,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    inner()
                }
            },
        )
    }
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
    val accent = if (nearDone) amberAccent() else MaterialTheme.colorScheme.primary
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
