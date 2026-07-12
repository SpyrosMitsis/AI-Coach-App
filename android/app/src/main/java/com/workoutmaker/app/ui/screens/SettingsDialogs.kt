package com.workoutmaker.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workoutmaker.app.data.AppPreferences
import com.workoutmaker.app.data.AppSettings
import com.workoutmaker.app.data.LlmProvider
import com.workoutmaker.app.data.TestKeyRequest
import com.workoutmaker.app.data.TestKeyResponse
import com.workoutmaker.app.data.TrainingProfile
import com.workoutmaker.app.data.WeightUnit
import com.workoutmaker.app.data.WorkoutRepository
import com.workoutmaker.app.data.format
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.ScreenScaffold
import com.workoutmaker.app.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// Theme-aware: the raw band constants are dark-palette pastels that wash out on
// light paper (theme-aware-accents rule).
@Composable
internal fun priorityColor(p: String) = when (p.uppercase()) {
    "A" -> MaterialTheme.colorScheme.error
    "B" -> com.workoutmaker.app.ui.theme.amberAccent()
    else -> MaterialTheme.colorScheme.primary
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AddRaceDialog(onClose: () -> Unit, onAdd: (com.workoutmaker.app.data.Race, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(java.time.LocalDate.now().plusWeeks(8)) }
    var distance by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("A") }
    var sport by remember { mutableStateOf("run") }
    var target by remember { mutableStateOf("") }
    var setGoal by remember { mutableStateOf(true) }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    }
                    showPicker = false
                }) { Text("Set date") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onAdd(com.workoutmaker.app.data.Race(name = name.trim(), date = date.toString(),
                    priority = priority, sport = sport, distance = distance.ifBlank { null },
                    target = target.ifBlank { null }), setGoal && priority == "A") },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        title = { Text("Add goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GOAL_SPORTS.forEach { (key, label) ->
                        androidx.compose.material3.FilterChip(selected = sport == key, onClick = { sport = key }, label = { Text(label) })
                    }
                }
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.EditCalendar, null)
                    Text("  $date")
                }
                OutlinedTextField(distance, { distance = it },
                    label = { Text(if (sport == "strength") "Lift / event (e.g. Back squat)" else "Distance (e.g. Marathon)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(target, { target = it },
                    label = { Text(goalTargetHint(sport)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("A", "B", "C").forEach { p ->
                        androidx.compose.material3.FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text("$p goal") })
                    }
                }
                if (priority == "A") Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = setGoal, onCheckedChange = { setGoal = it })
                    Text("Make this my goal (drives the plan)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}

internal val GOAL_SPORTS = listOf(
    "run" to "Run", "ride" to "Ride", "swim" to "Swim", "strength" to "Strength", "other" to "Other",
)

internal fun goalSportLabel(sport: String): String =
    GOAL_SPORTS.firstOrNull { it.first == sport }?.second ?: "Other"

internal fun goalTargetHint(sport: String): String = when (sport) {
    "run" -> "Target pace or time (e.g. 4:45/km)"
    "ride" -> "Target (e.g. FTP 260W or finish time)"
    "swim" -> "Target (e.g. 1:50/100m or 0:32:00)"
    "strength" -> "Target (e.g. Squat 120kg ×1)"
    else -> "Target (optional)"
}

// ---------------------------------------------------------------------------
// E1 + E4 — training zones & threshold tests
// ---------------------------------------------------------------------------
@Composable
internal fun ZonesSection(vm: SettingsViewModel) {
    val profile by vm.profile.collectAsStateSafe()
    val tests by vm.thresholdTests.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()

    var lthr by remember(profile.lthr) { mutableStateOf(profile.lthr?.toString() ?: "") }
    var pace by remember(profile.threshold_pace_per_km) { mutableStateOf(profile.threshold_pace_per_km ?: "") }
    var ftp by remember(profile.ftp) { mutableStateOf(profile.ftp?.toString() ?: "") }
    var showTest by remember { mutableStateOf(false) }

    if (showTest) LogTestDialog(onClose = { showTest = false }) { vm.addThresholdTest(it); showTest = false }

    SectionCard(title = "Thresholds") {
        Text("Set your thresholds; zones below are derived automatically. LTHR = lactate-threshold HR, threshold pace ≈ your 1-hour race pace.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(lthr, { lthr = it }, label = { Text("LTHR (bpm)") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pace, { pace = it }, label = { Text("Threshold pace /km (m:ss)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(ftp, { ftp = it }, label = { Text("FTP (watts, optional)") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.saveThresholds(lthr.toIntOrNull(), ftp.toIntOrNull(), pace.ifBlank { null }) },
            enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save thresholds") }
    }

    ZoneTables(profile.lthr, profile.threshold_pace_per_km, profile.ftp)

    SectionCard(title = "Threshold tests") {
        Text("Log a test result and your threshold (and zones) update automatically.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        tests.take(10).forEach { t ->
            val label = when (t.kind) {
                "lthr" -> "${t.value.toInt()} bpm LTHR"
                "ftp" -> "${t.value.toInt()} W FTP"
                "threshold_pace" -> "${com.workoutmaker.app.data.Zones.formatPace(t.value.toInt())}/km threshold"
                else -> "${t.value}"
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t.date, style = MaterialTheme.typography.bodySmall)
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        OutlinedButton(onClick = { showTest = true }, modifier = Modifier.fillMaxWidth()) { Text("Log a test") }
    }
}

// Shared pace/HR/power zone tables, reused by Settings and the cardio plan peek.
@Composable
internal fun ZoneTables(lthr: Int?, thresholdPace: String?, ftp: Int?) {
    val hrZones = lthr?.let { com.workoutmaker.app.data.Zones.hrZonesFromLthr(it) }.orEmpty()
    val paceZones = thresholdPace?.let { com.workoutmaker.app.data.Zones.parsePace(it) }
        ?.let { com.workoutmaker.app.data.Zones.paceZonesFromThreshold(it) }.orEmpty()
    val powerZones = ftp?.let { com.workoutmaker.app.data.Zones.powerZonesFromFtp(it) }.orEmpty()

    if (hrZones.isNotEmpty()) SectionCard(title = "Heart-rate zones") {
        hrZones.forEach { z -> ZoneRow(z.name, "${z.min}–${z.max} bpm") }
    }
    if (paceZones.isNotEmpty()) SectionCard(title = "Pace zones") {
        paceZones.forEach { z -> ZoneRow(z.name, z.range) }
    }
    if (powerZones.isNotEmpty()) SectionCard(title = "Power zones") {
        powerZones.forEach { z -> ZoneRow(z.name, z.range) }
    }
}

@Composable
internal fun ZoneRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun LogTestDialog(onClose: () -> Unit, onLog: (com.workoutmaker.app.data.ThresholdTest) -> Unit) {
    var kind by remember { mutableStateOf("lthr") }
    var value by remember { mutableStateOf("") }
    val date = java.time.LocalDate.now().toString()
    // For pace, the input is m:ss; otherwise a plain number.
    val isPace = kind == "threshold_pace"
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(
                enabled = if (isPace) com.workoutmaker.app.data.Zones.parsePace(value) != null else value.toDoubleOrNull() != null,
                onClick = {
                    val v = if (isPace) com.workoutmaker.app.data.Zones.parsePace(value)!!.toDouble() else value.toDouble()
                    onLog(com.workoutmaker.app.data.ThresholdTest(date = date, kind = kind, value = v))
                },
            ) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        title = { Text("Log threshold test") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("lthr" to "LTHR", "threshold_pace" to "Pace", "ftp" to "FTP").forEach { (k, label) ->
                        androidx.compose.material3.FilterChip(selected = kind == k, onClick = { kind = k; value = "" }, label = { Text(label) })
                    }
                }
                OutlinedTextField(value, { value = it },
                    label = { Text(if (isPace) "Pace /km (m:ss)" else if (kind == "ftp") "Watts" else "bpm") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

@Composable
internal fun ImportResultDialog(s: com.workoutmaker.app.strength.ImportSummary, onClose: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Done") } },
        title = { Text(if (s.ok) "✓ Import complete" else "Import failed") },
        text = {
            if (!s.ok) {
                Text(s.error ?: "Something went wrong reading the file.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Detected format: ${s.format}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ResultLine("Workouts added", "${s.workoutsAdded}")
                    ResultLine("Sets logged", "${s.setsAdded}")
                    if (s.duplicatesSkipped > 0) ResultLine("Already in history (skipped)", "${s.duplicatesSkipped}")
                    if (s.cardioRowsSkipped > 0) ResultLine("Cardio entries skipped", "${s.cardioRowsSkipped}")
                    if (s.unparsedRows > 0) ResultLine("Rows we couldn't read", "${s.unparsedRows}")
                    if (s.workoutsAdded == 0 && s.duplicatesSkipped > 0)
                        Text("Everything in this file was already imported.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

@Composable
internal fun ResultLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
    }
}
