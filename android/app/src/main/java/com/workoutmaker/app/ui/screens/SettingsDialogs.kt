package com.workoutmaker.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.format
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.SectionCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import com.workoutmaker.app.data.Race
import com.workoutmaker.app.data.ThresholdTest
import com.workoutmaker.app.data.Zones
import com.workoutmaker.app.strength.ImportSummary
import com.workoutmaker.app.ui.theme.amberAccent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Theme-aware: the raw band constants are dark-palette pastels that wash out on
// light paper (theme-aware-accents rule).
@Composable
internal fun priorityColor(p: String) = when (p.uppercase()) {
    "A" -> MaterialTheme.colorScheme.error
    "B" -> amberAccent()
    else -> MaterialTheme.colorScheme.primary
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AddRaceDialog(onClose: () -> Unit, onAdd: (Race, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().plusWeeks(8)) }
    var distance by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("A") }
    var sport by remember { mutableStateOf("run") }
    var target by remember { mutableStateOf("") }
    var setGoal by remember { mutableStateOf(true) }
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showPicker = false
                }) { Text("Set date") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    // The distance is a chip picker per sport, with a "Custom" escape hatch that
    // reveals the old free-text field. Typing "Marathon" by hand was the single
    // most error-prone field here, and the presets are exactly the distances the
    // planner already knows how to periodize toward.
    var customDistance by remember { mutableStateOf(false) }
    val presets = distancePresets(sport)
    // Changing sport invalidates the chosen distance (a "5K" ride goal is noise).
    LaunchedEffect(sport) { distance = ""; customDistance = false }

    val weeksAway = ChronoUnit.WEEKS.between(LocalDate.now(), date).toInt()
    val daysAway = ChronoUnit.DAYS.between(LocalDate.now(), date)

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && daysAway >= 0,
                onClick = { onAdd(Race(name = name.trim(), date = date.toString(),
                    priority = priority, sport = sport, distance = distance.ifBlank { null },
                    target = target.ifBlank { null }), setGoal && priority == "A") },
            ) { Text("Add goal") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        title = { Text("Add goal") },
        text = {
            // Grew past one screen once the presets and explainers landed.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("Event name") },
                    placeholder = { Text("Athens Marathon") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                FieldLabel("Sport")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GOAL_SPORTS.forEach { (key, label) ->
                        FilterChip(selected = sport == key, onClick = { sport = key }, label = { Text(label) })
                    }
                }

                FieldLabel("Date")
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.EditCalendar, null)
                    Text("  " + date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")))
                }
                // The countdown is the whole point of a goal date: it's what picks
                // the training phase and decides whether a taper even fits.
                Text(
                    when {
                        daysAway < 0 -> "That date has already passed. Pick a future date."
                        daysAway == 0L -> "Today."
                        weeksAway < 1 -> "In $daysAway day${if (daysAway == 1L) "" else "s"}. Too close to build toward, the plan will just taper you into it."
                        weeksAway < 4 -> "In $weeksAway week${if (weeksAway == 1) "" else "s"}. Enough to sharpen and taper, not to build."
                        weeksAway <= 24 -> "In $weeksAway weeks. Room for a full base, build, peak and taper."
                        else -> "In $weeksAway weeks. The coach plans the nearest 16 weeks and grows into the rest."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (daysAway < 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FieldLabel(if (sport == "strength") "Lift or event" else "Distance")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { d ->
                        FilterChip(
                            selected = !customDistance && distance == d,
                            onClick = { customDistance = false; distance = if (distance == d) "" else d },
                            label = { Text(d) },
                        )
                    }
                    FilterChip(
                        selected = customDistance,
                        onClick = { customDistance = !customDistance; if (!customDistance) distance = "" },
                        label = { Text("Custom") },
                    )
                }
                if (customDistance) {
                    OutlinedTextField(
                        distance, { distance = it },
                        label = { Text(if (sport == "strength") "Lift / event" else "Distance") },
                        placeholder = { Text(if (sport == "strength") "Back squat" else "12 km trail") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }

                FieldLabel("Target (optional)")
                OutlinedTextField(
                    target, { target = it },
                    label = { Text(goalTargetHint(sport)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                FieldLabel("How much does this one matter?")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("A", "B", "C").forEach { p ->
                        FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text("$p goal") })
                    }
                }
                // A/B/C is jargon everywhere except in the head of someone who
                // already races. Say what the choice actually does to the plan.
                Text(
                    priorityBlurb(priority),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (priority == "A") Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = setGoal, onCheckedChange = { setGoal = it })
                    Text(
                        "Make this the goal my whole plan builds toward",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

/** Small field caption, so the picker rows aren't unlabelled chip soup. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The distances the planner can actually periodize toward, per sport. */
internal fun distancePresets(sport: String): List<String> = when (sport) {
    "run" -> listOf("5K", "10K", "Half marathon", "Marathon", "50K", "100K")
    "ride" -> listOf("40K TT", "Gran fondo", "Century", "Stage race")
    "swim" -> listOf("750 m", "1.5 km", "3.8 km", "5 km open water")
    "strength" -> listOf("Powerlifting meet", "Weightlifting meet", "1RM test", "Hyrox")
    else -> listOf("Sprint tri", "Olympic tri", "70.3", "Ironman", "Hyrox")
}

/** What picking A/B/C actually changes about the plan. */
internal fun priorityBlurb(priority: String): String = when (priority) {
    "A" -> "Everything is built around it: full base, build and peak, then a real taper into race week."
    "B" -> "Worth doing well, but you train through it. Expect a couple of easy days beforehand, no taper."
    else -> "Treated as a hard session with a number pinned on. The plan doesn't change for it."
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pace, { pace = it }, label = { Text("Threshold pace /km (m:ss)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(ftp, { ftp = it }, label = { Text("FTP (watts, optional)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.saveThresholds(lthr.toIntOrNull(), ftp.toIntOrNull(), pace.ifBlank { null }) },
            enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save thresholds") }
    }

    // The two "Your numbers" anchors onboarding now collects that existed
    // nowhere in Settings: swim CSS and strength starting loads. Without these
    // editors, everyone who onboarded before the step existed could never set them.
    SectionCard(title = "Your numbers") {
        if (profile.sports.isEmpty() || profile.sports.contains("swim")) {
            OutlinedTextField(
                profile.css_per_100m ?: "",
                { v -> vm.updateProfile { it.copy(css_per_100m = v.ifBlank { null }) } },
                label = { Text("Swim: comfortable 100m pace (m:ss)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (profile.sports.isEmpty() || profile.sports.contains("strength")) {
            Text(
                "Top set per lift. Used to start progression when you have no logged history.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf("Back Squat", "Barbell Bench Press", "Deadlift").forEach { lift ->
                StartingLiftRow(lift, profile) { t -> vm.updateProfile(t) }
            }
        }
        Button(
            onClick = { vm.saveProfile() },
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }

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

    // Derived reference tables last — outputs of the inputs above, not settings.
    ZoneTables(profile.lthr, profile.threshold_pace_per_km, profile.ftp)
}

// Shared pace/HR/power zone tables, reused by Settings and the cardio plan peek.
@Composable
internal fun ZoneTables(lthr: Int?, thresholdPace: String?, ftp: Int?) {
    val hrZones = lthr?.let { Zones.hrZonesFromLthr(it) }.orEmpty()
    val paceZones = thresholdPace?.let { Zones.parsePace(it) }
        ?.let { Zones.paceZonesFromThreshold(it) }.orEmpty()
    val powerZones = ftp?.let { Zones.powerZonesFromFtp(it) }.orEmpty()

    if (hrZones.isNotEmpty()) SectionCard(title = "Heart-rate zones") {
        hrZones.forEach { z -> ZoneRow(z.name, "${z.min}-${z.max} bpm") }
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
internal fun LogTestDialog(onClose: () -> Unit, onLog: (ThresholdTest) -> Unit) {
    var kind by remember { mutableStateOf("lthr") }
    var value by remember { mutableStateOf("") }
    val date = LocalDate.now().toString()
    // For pace, the input is m:ss; otherwise a plain number.
    val isPace = kind == "threshold_pace"
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(
                enabled = if (isPace) Zones.parsePace(value) != null else value.toDoubleOrNull() != null,
                onClick = {
                    val v = if (isPace) Zones.parsePace(value)!!.toDouble() else value.toDouble()
                    onLog(ThresholdTest(date = date, kind = kind, value = v))
                },
            ) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        title = { Text("Log threshold test") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("lthr" to "LTHR", "threshold_pace" to "Pace", "ftp" to "FTP").forEach { (k, label) ->
                        FilterChip(selected = kind == k, onClick = { kind = k; value = "" }, label = { Text(label) })
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
internal fun ImportResultDialog(s: ImportSummary, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
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
