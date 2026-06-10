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

@Composable
internal fun PrCelebrationDialog(prs: List<com.workoutmaker.app.strength.PrHit>, onClose: () -> Unit) {
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
internal fun RoutineEditorDialog(
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
internal fun RoutineEditItemRow(
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
internal fun ExerciseAddDialog(onPick: (String) -> Unit, onClose: () -> Unit) {
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
internal fun ConfirmDeleteDialog(what: String, detail: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = BandRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Delete $what?") },
        text = { Text(detail) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateExerciseDialog(onClose: () -> Unit, onCreate: (String, String, String, Boolean) -> Unit) {
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

@Composable
internal fun PlateCalcDialog(settings: com.workoutmaker.app.data.AppSettings, onClose: () -> Unit) {
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
