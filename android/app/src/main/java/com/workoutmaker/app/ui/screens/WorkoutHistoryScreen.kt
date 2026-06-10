package com.workoutmaker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workoutmaker.app.data.CompletedActivity
import com.workoutmaker.app.data.WorkoutRepository
import com.workoutmaker.app.strength.ExerciseCatalog
import com.workoutmaker.app.strength.SetEntity
import com.workoutmaker.app.strength.StrengthRepository
import com.workoutmaker.app.strength.WorkoutEntity
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.ChipRow
import com.workoutmaker.app.ui.components.EmptyState
import com.workoutmaker.app.ui.components.InsetStat
import com.workoutmaker.app.ui.components.ScreenScaffold
import com.workoutmaker.app.ui.components.SectionCard
import com.workoutmaker.app.ui.components.SectionLabel
import com.workoutmaker.app.ui.theme.Moss
import com.workoutmaker.app.ui.theme.Sage
import com.workoutmaker.app.ui.theme.Sand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

// ===========================================================================
// Dedicated, full-screen workout history. A single chronological feed across
// every source: logged strength sessions (app) and completed cardio activities
// (watch / Intervals.icu). When the same workout was tracked on both — e.g. a
// strength session logged in-app while the watch recorded HR + calories — the
// two are paired into ONE unified record (display-time merge, non-destructive).
// ===========================================================================

// One row in the feed. A strength session may carry a paired watch activity.
sealed interface HistoryRow {
    val epoch: Long
    data class Strength(val workout: WorkoutEntity, val watch: CompletedActivity?) : HistoryRow {
        override val epoch get() = workout.startedAt
    }
    data class Cardio(val activity: CompletedActivity, override val epoch: Long) : HistoryRow
}

private fun isStrengthType(type: String?): Boolean {
    val t = (type ?: "").lowercase()
    return t.contains("weight") || t.contains("strength") || t.contains("gym") || t == "workout"
}

private fun activityEpoch(a: CompletedActivity): Long =
    runCatching { LocalDate.parse(a.date).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        .getOrDefault(0L)

private fun dateOf(epoch: Long): LocalDate =
    Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDate()

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val strength: StrengthRepository,
    private val repo: WorkoutRepository,
) : ViewModel() {
    val rows = MutableStateFlow<List<HistoryRow>>(emptyList())
    val loading = MutableStateFlow(true)

    // Lazily-loaded sets for an opened strength session, keyed by workout id.
    val detailSets = MutableStateFlow<Map<String, List<SetEntity>>>(emptyMap())

    fun load() = viewModelScope.launch {
        loading.value = true
        val workouts = runCatching { strength.recentWorkouts(500) }.getOrDefault(emptyList())
        val from = LocalDate.now().minusYears(2).toString()
        val activities = runCatching { repo.completedActivities(from) }.getOrDefault(emptyList())

        // Pairing: a strength session "absorbs" a same-day weight-training watch
        // activity. Those activities are removed from the top-level feed and shown
        // inside the strength record instead.
        val strengthDates = workouts.map { dateOf(it.startedAt) }.toSet()
        val pairedIds = HashSet<String>()
        fun watchFor(w: WorkoutEntity): CompletedActivity? {
            val day = dateOf(w.startedAt)
            return activities.firstOrNull {
                it.id !in pairedIds && isStrengthType(it.type) &&
                    runCatching { LocalDate.parse(it.date) == day }.getOrDefault(false)
            }?.also { pairedIds.add(it.id) }
        }

        val strengthRows = workouts.map { HistoryRow.Strength(it, watchFor(it)) }
        val cardioRows = activities
            .filterNot { it.id in pairedIds }
            // also drop a stray strength-typed activity on a day we already logged
            // strength for, even if not directly paired, to avoid obvious dupes
            .filterNot { a ->
                isStrengthType(a.type) &&
                    runCatching { LocalDate.parse(a.date) in strengthDates }.getOrDefault(false)
            }
            .map { HistoryRow.Cardio(it, activityEpoch(it)) }

        rows.value = (strengthRows + cardioRows).sortedByDescending { it.epoch }
        loading.value = false
    }

    fun loadSets(workoutId: String) = viewModelScope.launch {
        if (detailSets.value.containsKey(workoutId)) return@launch
        val sets = runCatching { strength.setsForWorkout(workoutId) }.getOrDefault(emptyList())
        detailSets.value = detailSets.value + (workoutId to sets)
    }
}

@Composable
fun WorkoutHistoryScreen(onBack: () -> Unit, vm: HistoryViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateSafe()
    val loading by vm.loading.collectAsStateSafe()
    val detailSets by vm.detailSets.collectAsStateSafe()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<HistoryRow?>(null) }

    LaunchedEffect(Unit) { vm.load() }

    // Detail is a full sub-screen.
    selected?.let { row ->
        androidx.activity.compose.BackHandler { selected = null }
        when (row) {
            is HistoryRow.Strength -> {
                LaunchedEffect(row.workout.id) { vm.loadSets(row.workout.id) }
                StrengthHistoryDetail(row, detailSets[row.workout.id].orEmpty()) { selected = null }
            }
            is HistoryRow.Cardio -> ActivityDetailScreen(row.activity, null) { selected = null }
        }
        return
    }

    val filtered = remember(rows, query) {
        if (query.isBlank()) rows
        else rows.filter { r ->
            val name = when (r) {
                is HistoryRow.Strength -> r.workout.name
                is HistoryRow.Cardio -> r.activity.displayName + " " + (r.activity.type ?: "")
            }
            name.contains(query.trim(), ignoreCase = true)
        }
    }

    ScreenScaffold(
        title = "History",
        subtitle = "${rows.size} workouts",
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        isRefreshing = loading,
        onRefresh = { vm.load() },
    ) { mod ->
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = mod,
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Search workouts") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        if (!loading && rows.isEmpty()) {
            EmptyState(
                title = "No workouts yet",
                subtitle = "Logged strength sessions and synced activities will show up here.",
                icon = Icons.Filled.FitnessCenter,
            )
            return@ScreenScaffold
        }

        // Group the feed by date for scannable section headers.
        var lastHeader: String? = null
        filtered.forEach { row ->
            val header = dateOf(row.epoch).let { d ->
                d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) +
                    " · " + d
            }
            if (header != lastHeader) {
                SectionLabel(header, color = MaterialTheme.colorScheme.onSurfaceVariant)
                lastHeader = header
            }
            HistoryCard(row) { selected = row }
        }
    }
}

@Composable
private fun HistoryCard(row: HistoryRow, onClick: () -> Unit) {
    SectionCard(Modifier.clickable { onClick() }) {
        when (row) {
            is HistoryRow.Strength -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceIcon(Icons.Filled.FitnessCenter, Sand)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(row.workout.name, style = MaterialTheme.typography.titleMedium)
                        val chips = buildList {
                            add("${row.workout.totalVolumeKg.toInt()} kg")
                            if (row.workout.durationSec > 0) add("${row.workout.durationSec / 60} min")
                        }
                        ChipRow(chips)
                    }
                }
                row.watch?.let { w ->
                    // Unified record: this strength session also has watch data.
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Watch, null, Modifier.size(16.dp), tint = Sage)
                        val parts = buildList {
                            w.avg_hr?.let { add("♥ $it bpm") }
                            w.calories?.let { add("$it kcal") }
                            w.tss?.let { if (it > 0) add("TSS ${it.toInt()}") }
                        }
                        Text(
                            "  Merged with watch" + if (parts.isNotEmpty()) " · ${parts.joinToString(" · ")}" else "",
                            style = MaterialTheme.typography.labelSmall, color = Sage,
                        )
                    }
                }
            }
            is HistoryRow.Cardio -> {
                val a = row.activity
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceIcon(Icons.Filled.DirectionsRun, Sage)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(a.displayName, style = MaterialTheme.typography.titleMedium)
                        val chips = buildList {
                            a.type?.let { add(it) }
                            a.distanceKm?.let { if (it > 0) add("%.1f km".format(it)) }
                            a.durationMin?.let { if (it > 0) add("$it min") }
                            a.avg_hr?.let { add("♥ $it bpm") }
                        }
                        ChipRow(chips)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier.size(40.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun StrengthHistoryDetail(row: HistoryRow.Strength, sets: List<SetEntity>, onBack: () -> Unit) =
    StrengthSessionDetailScreen(row.workout, sets, row.watch, onBack)

// Full-screen strength session detail with its sets — plus the paired watch data
// when present, forming one unified record. Public so the Calendar can reuse it.
@Composable
fun StrengthSessionDetailScreen(
    w: WorkoutEntity,
    sets: List<SetEntity>,
    watch: CompletedActivity?,
    onBack: () -> Unit,
) {
    ScreenScaffold(
        title = w.name,
        subtitle = dateOf(w.startedAt).toString(),
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    ) { mod ->
        SectionCard(mod) {
            SectionLabel("Logged in app", color = Sand)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InsetStat("Volume", "${w.totalVolumeKg.toInt()} kg")
                if (w.durationSec > 0) InsetStat("Time", "${w.durationSec / 60} min")
                InsetStat("Sets", "${sets.size}")
            }
            if (w.note.isNotBlank()) Text("“${w.note}”", style = MaterialTheme.typography.bodyMedium, color = Sage)
        }

        // Unified: watch contributions for the same session.
        watch?.let { a ->
            SectionCard(mod) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Watch, null, Modifier.size(18.dp), tint = Sage)
                    Spacer(Modifier.width(6.dp))
                    SectionLabel("From your watch", color = Sage)
                }
                a.avg_hr?.let { InsetStat("Avg HR", "$it bpm") }
                a.maxHr?.let { InsetStat("Max HR", "$it bpm") }
                a.calories?.let { InsetStat("Calories", "$it kcal") }
                a.tss?.let { if (it > 0) InsetStat("Training load (TSS)", "${it.toInt()}") }
            }
        }

        val byExercise = remember(sets) { sets.groupBy { it.exerciseName } }
        byExercise.forEach { (name, exSets) ->
            SectionCard(mod) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val vol = exSets.filter { !it.isWarmup }.sumOf { it.weightKg * it.reps }
                Text("${ExerciseCatalog.muscleOf(name)} · ${exSets.size} sets · ${vol.toInt()} kg",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                exSets.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                            Text(if (s.isWarmup) "W" else "${s.idx}",
                                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall,
                                color = if (s.isWarmup) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                        }
                        val kg = if (s.weightKg % 1.0 == 0.0) s.weightKg.toInt().toString() else s.weightKg.toString()
                        Text("$kg kg × ${s.reps}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        s.rpe?.let { Text("RPE $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}
