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
// Exercise picker
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExercisePickerView(vm: StrengthViewModel) {
    var query by remember { mutableStateOf("") }
    var muscle by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var confirmDeleteCustom by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    val favorites by vm.favorites.collectAsStateSafe()
    val recents by vm.recentExercises.collectAsStateSafe()
    val custom by vm.customExercises.collectAsStateSafe()
    // Replace mode (hamburger → "Replace exercise"): tap once to swap, no multi-select.
    val replacing by vm.replaceTarget.collectAsStateSafe()
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
                title = { Text(replacing?.let { "Replace “${it.name}”" } ?: "Add exercises") },
                navigationIcon = { IconButton(onClick = { vm.backToActive() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { showCreate = true }) { Icon(Icons.Filled.Add, null); Text(" New") } },
            )
        },
        bottomBar = {
            if (replacing == null) {
                Button(
                    onClick = { vm.addExercises(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) { Text("Add ${selected.size} exercise(s)") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxWidth()) {
            OutlinedTextField(query, { query = it }, label = { Text("Search") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true)

            // D5: quick-add favorites & recents (hidden while replacing — one tap swaps)
            if (replacing == null && query.isBlank() && muscle == null && category == null) {
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
                            if (replacing != null) vm.replaceExercise(ex.name)
                            else if (isSel) selected.remove(ex.name) else selected.add(ex.name)
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
                        if (replacing == null) {
                            Checkbox(checked = isSel, onCheckedChange = {
                                if (isSel) selected.remove(ex.name) else selected.add(ex.name)
                            })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickAddRow(label: String, names: List<String>, selected: MutableList<String>) {
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

// ---------------------------------------------------------------------------
// Per-exercise stats
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExerciseStatsView(vm: StrengthViewModel, exercise: String) {
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
internal fun Pr(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = Sage)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MetricChart(values: List<Double>) {
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
