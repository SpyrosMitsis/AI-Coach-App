package com.workoutmaker.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.SectionCard
import androidx.compose.material3.AlertDialog
import com.workoutmaker.app.data.ThresholdTest
import com.workoutmaker.app.data.Zones
import com.workoutmaker.app.strength.ImportSummary
import com.workoutmaker.app.ui.theme.amberAccent
import java.time.LocalDate
import com.workoutmaker.app.data.addThresholdTest
import com.workoutmaker.app.data.saveProfile
import com.workoutmaker.app.data.thresholdTests

// Theme-aware: the raw band constants are dark-palette pastels that wash out on
// light paper (theme-aware-accents rule).
@Composable
internal fun priorityColor(p: String) = when (p.uppercase()) {
    "A" -> MaterialTheme.colorScheme.error
    "B" -> amberAccent()
    else -> MaterialTheme.colorScheme.primary
}

internal val GOAL_SPORTS = listOf(
    "run" to "Run", "ride" to "Ride", "swim" to "Swim", "strength" to "Strength", "other" to "Other",
)

internal fun goalSportLabel(sport: String): String =
    GOAL_SPORTS.firstOrNull { it.first == sport }?.second ?: "Other"

// ---------------------------------------------------------------------------
// E1 + E4 — training zones & threshold tests
// ---------------------------------------------------------------------------

/**
 * When [kind]'s threshold was last set by a logged test, or null.
 *
 * Null is a normal answer, not a missing one: a threshold can also arrive from
 * onboarding, an Intervals.icu sync, or the coach's update_profile tool, and
 * none of those leave a test row. The UI shows the value with no date.
 *
 * Does not assume the caller's ordering (the repository sorts by date
 * descending, but a max is cheap and survives that changing).
 */
internal fun latestTestDate(tests: List<ThresholdTest>, kind: String): String? =
    tests.filter { it.kind == kind }.maxByOrNull { it.date }?.date

/** One performance anchor: what it is, what it is set to, and what it costs to leave it empty. */
@Composable
private fun AnchorRow(label: String, value: String?, setOn: String?, whyItMatters: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                setOn?.let {
                    Text("Set $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                value ?: "— : —",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (value != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
        // Only say what is at stake while it IS at stake; once the number is
        // there the warning is just noise on a solved problem.
        if (value == null) {
            Text(whyItMatters, style = MaterialTheme.typography.bodySmall, color = amberAccent())
        }
    }
}

@Composable
internal fun ZonesSection(vm: SettingsViewModel) {
    val profile by vm.profile.collectAsStateSafe()
    val tests by vm.thresholdTests.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()

    var showTest by remember { mutableStateOf(false) }

    if (showTest) LogTestDialog(onClose = { showTest = false }) { vm.addThresholdTest(it); showTest = false }

    // ONE way in. This card used to hold three editable fields and a Save
    // button, while "Log a test" below did the same three numbers again via
    // applyThreshold — the same value settable two ways, one of which quietly
    // recorded a date and one of which didn't. Logging a test is now the only
    // editor, so every threshold the app holds knows when it was set.
    // One number per sport, each with the line explaining what it costs to leave
    // it empty. A number that is missing says so in place of showing a dash and
    // hoping you notice, because "your zones are guessed" is the whole stake.
    SectionCard {
        Text(
            "One number per sport sets every zone below it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnchorRow(
            "Threshold pace",
            profile.threshold_pace_per_km?.let { "$it /km" },
            latestTestDate(tests, "threshold_pace"),
            "Without this, run zones are guessed from your age. A 20-minute time trial fixes it.",
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        AnchorRow(
            "FTP",
            profile.ftp?.let { "$it W" },
            latestTestDate(tests, "ftp"),
            "Your one-hour power. Sets every ride target.",
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        AnchorRow(
            "LTHR",
            profile.lthr?.let { "$it bpm" },
            latestTestDate(tests, "lthr"),
            "Lactate-threshold heart rate. Without it, HR zones come from your age.",
        )
        OutlinedButton(onClick = { showTest = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Test me")
        }
        Text("Logging a test updates the number and its zones automatically.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    // History only — the button that adds to it now lives with the values it
    // changes, one card up.
    if (tests.isNotEmpty()) {
        SectionCard(title = "Test history") {
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
        }
    }

    // A number you do not have is not an unfinished job. Someone who rides but
    // has never tested has no FTP and may never get one, and the amber "FTP
    // missing" that follows them around the index cannot be resolved by any
    // action except hiding it. So: hide it, per number, reversibly, and say
    // plainly what the coach does instead. The number itself stays editable
    // above, and hushing one never changes what is planned, only what is asked.
    val hushed by vm.hushedNumbers.collectAsStateSafe()
    val applicable = applicableNumbers(profile)
    if (applicable.isNotEmpty()) {
        SectionCard(title = "Numbers I ask for") {
            Text(
                "Turn one off if you don't have it. I'll estimate from your age and your " +
                    "logged sessions instead, and stop flagging it as missing.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            applicable.forEach { name ->
                ToggleRow(
                    title = name,
                    subtitle = if (name in hushed) "Not asking, estimated instead" else "Flagged while it's empty",
                    checked = name !in hushed,
                    onChange = { on -> vm.setNumberHushed(name, !on) },
                )
            }
        }
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
        title = { Text("Update a threshold") },
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
