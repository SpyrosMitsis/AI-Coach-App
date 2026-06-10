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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileSection(vm: SettingsViewModel) {
    val profile by vm.profile.collectAsStateSafe()
    val saveStatus by vm.saveStatus.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    SectionCard {
        ChipGroup("Goal", GOALS, profile.goal) { g -> vm.updateProfile { it.copy(goal = g) } }
        ChipGroup("Experience", LEVELS, profile.experience) { e -> vm.updateProfile { it.copy(experience = e) } }
        Text("Available days", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DAYS.forEach { d ->
                FilterChip(
                    selected = profile.days.contains(d),
                    onClick = { vm.updateProfile { it.copy(days = if (it.days.contains(d)) it.days - d else it.days + d) } },
                    label = { Text(d) },
                )
            }
        }
        Text("Typical session length", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DURATIONS.forEach { d ->
                FilterChip(selected = profile.session_duration == d, onClick = { vm.updateProfile { it.copy(session_duration = d) } }, label = { Text("${d}m") })
            }
        }
        Text("Max session length (optional)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DURATIONS_MAX.forEach { d ->
                FilterChip(
                    selected = profile.session_duration_max == d,
                    // Tap the selected chip again to clear the cap.
                    onClick = { vm.updateProfile { it.copy(session_duration_max = if (it.session_duration_max == d) null else d) } },
                    label = { Text("${d}m") },
                )
            }
        }
        Text(
            "Sessions vary with their purpose — the typical length is a flexible budget, the max is a hard cap. The AI won't pad every workout to the same number.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChipGroup("Equipment", EQUIPMENT, profile.equipment) { e -> vm.updateProfile { it.copy(equipment = e) } }
        OutlinedTextField(profile.target_pace ?: "", { v -> vm.updateProfile { it.copy(target_pace = v) } },
            label = { Text("Target pace (e.g. 4:45/km)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(profile.goal_date ?: "", { v -> vm.updateProfile { it.copy(goal_date = v) } },
            label = { Text("Goal race date (YYYY-MM-DD, optional)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(profile.injury_history ?: "", { v -> vm.updateProfile { it.copy(injury_history = v) } },
            label = { Text("Injury history (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.saveProfile() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save profile") }
        saveStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkoutDefaultsSection(vm: SettingsViewModel) {
    val s by vm.appSettings.collectAsStateSafe()
    SectionCard(title = "Units") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WeightUnit.entries.forEach { u ->
                FilterChip(selected = s.units == u, onClick = { vm.setUnits(u) }, label = { Text(u.label) })
            }
        }
        Text("Used by the plate calculator and weight labels.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionCard(title = "Default rest timer") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(restLabel(s.defaultRestSec), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = { vm.setDefaultRest((s.defaultRestSec - 15).coerceAtLeast(0)) }) { Text("−15s") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.setDefaultRest(s.defaultRestSec + 15) }) { Text("+15s") }
        }
        Text("Applied to exercises without a specific rest time (e.g. custom lifts).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionCard(title = "Barbell weight") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BAR_WEIGHTS.forEach { w ->
                FilterChip(selected = s.barbellKg == w, onClick = { vm.setBarbell(w) }, label = { Text("${s.units.format(w)} ${s.units.suffix}") })
            }
        }
        Text("Base weight the plate calculator subtracts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionCard {
        ToggleRow("Keep screen on during workouts", "Stops the display sleeping while you train.", s.keepScreenOn) { vm.setKeepScreenOn(it) }
    }
}

@Composable
internal fun PlanningSection(vm: SettingsViewModel) {
    val autoPlan by vm.autoPlan.collectAsStateSafe()
    val profile by vm.profile.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    val saveStatus by vm.saveStatus.collectAsStateSafe()
    SectionCard {
        ToggleRow("Auto-plan next week", "Every Sunday the AI lays out your week and (if connected) pushes it to your watch.", autoPlan) { vm.setAutoPlan(it) }
    }
    SectionCard(title = "Weekly load target") {
        OutlinedTextField(
            (profile.weekly_tss_target?.toString() ?: ""), { v -> vm.updateProfile { it.copy(weekly_tss_target = v.toIntOrNull()) } },
            label = { Text("Target weekly TSS (optional)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        Text("Guides how much training load the weekly planner aims for. Leave blank to auto-estimate.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { vm.saveProfile() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        saveStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
internal fun KnowledgeSection(vm: SettingsViewModel) {
    val knowledge by vm.knowledge.collectAsStateSafe()
    val knowledgeStatus by vm.knowledgeStatus.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    SectionCard {
        Text(
            "Durable facts your coach must respect on every plan — e.g. \"left knee — avoid deep lunges\", " +
                "\"no leg press machine\", \"only dumbbells at home\", \"hate burpees\". The coach chat updates this " +
                "automatically, and you can edit it here.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            knowledge, { vm.updateKnowledge(it) }, label = { Text("Constraints & preferences") },
            placeholder = { Text("- Left knee tendinitis — avoid deep knee flexion\n- Home gym: dumbbells + bands only\n- Runs only before work (mornings)") },
            minLines = 5, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.saveKnowledge() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save knowledge") }
        knowledgeStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiSection(vm: SettingsViewModel) {
    SectionCard(title = "Active provider") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LlmProvider.entries.forEach { p ->
                FilterChip(selected = vm.active == p, onClick = { vm.selectProvider(p) }, label = { Text(if (p.freeTier) "${p.label} ✦" else p.label) })
            }
        }
        Text("✦ = has a free tier. Add a key below; the others act as fallbacks.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LlmProvider.entries.forEach { provider -> ProviderCard(Modifier, provider, vm) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionsSection(vm: SettingsViewModel) {
    val intervalsStatus by vm.intervalsStatus.collectAsStateSafe()
    val healthStatus by vm.healthStatus.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val healthPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.containsAll(vm.healthPermissions)) vm.syncHealth()
        else vm.setHealthStatus("Permission denied in Health Connect.")
    }
    val connected = intervalsStatus?.startsWith("✓") == true

    SectionCard(title = "Intervals.icu") {
        StatusChip("Intervals.icu", connected)
        Text("Pushes structured workouts to your Amazfit watch (via Zepp → Intervals.icu). Find the athlete ID + API key in Intervals.icu → Settings → Developer.",
            style = MaterialTheme.typography.bodySmall)
        var athleteId by remember { mutableStateOf("") }
        var apiKey by remember { mutableStateOf("") }
        OutlinedTextField(athleteId, { athleteId = it }, label = { Text("Athlete ID (e.g. i123456)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.connectIntervals(athleteId.trim(), apiKey.trim()) }, enabled = !busy && athleteId.isNotBlank() && apiKey.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Verify & connect") }
        intervalsStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
    SectionCard(title = "Health Connect") {
        StatusChip("Health Connect", vm.healthAvailable)
        Text("Pulls HRV, resting HR, sleep and steps from your phone (Zepp/Amazfit, Google Fit, Fitbit…) to sharpen your daily readiness score.",
            style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                if (!vm.healthAvailable) vm.setHealthStatus("Health Connect isn't available. Install/update it from the Play Store.")
                else scope.launch { if (vm.hasHealthPerms()) vm.syncHealth() else healthPermLauncher.launch(vm.healthPermissions) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sync wellness from Health Connect") }
        healthStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
internal fun NotificationsSection(vm: SettingsViewModel) {
    val s by vm.appSettings.collectAsStateSafe()
    SectionCard {
        ToggleRow("Rest-timer alert", "Notify when a rest period ends — even if the app is in the background.", s.restNotify) { vm.setRestNotify(it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        ToggleRow("Vibrate on rest end", "Buzz the phone when the rest timer finishes.", s.restVibrate) { vm.setRestVibrate(it) }
    }
}

@Composable
internal fun DiagnosticsSection(vm: SettingsViewModel) {
    val logs by vm.logs.collectAsStateSafe()
    if (logs.isEmpty()) {
        SectionCard { Text("No AI generations yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val spent = logs.sumOf { it.estimated_cost_usd }
    val fails = logs.count { !it.parsed_ok }
    SectionCard {
        Text("Last ${logs.size} generations · ~$${"%.3f".format(spent)} est. cost" + (if (fails > 0) " · $fails failed" else " · all OK"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        logs.take(12).forEach { l ->
            Row(Modifier.fillMaxWidth()) {
                Text(if (l.parsed_ok) "✓" else "✗", color = if (l.parsed_ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text("  ${l.created_at?.take(16)?.replace('T', ' ') ?: ""} · ${l.provider ?: "?"}" + (if (!l.parsed_ok && l.error != null) " — ${l.error.take(60)}" else ""),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun DataSection(vm: SettingsViewModel) {
    val importStatus by vm.importStatus.collectAsStateSafe()
    val importBusy by vm.importBusy.collectAsStateSafe()
    val result by vm.importResult.collectAsStateSafe()
    val context = androidx.compose.ui.platform.LocalContext.current
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            android.util.Log.i("IMPORT", "picker returned null (no file selected)")
            vm.importResult.value = com.workoutmaker.app.strength.ImportSummary(
                ok = false, error = "No file was selected.")
        } else {
            val read = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }
            val text = read.getOrNull()
            android.util.Log.i("IMPORT", "picked uri=$uri readOk=${read.isSuccess} len=${text?.length ?: -1} err=${read.exceptionOrNull()?.message}")
            if (text.isNullOrBlank()) {
                vm.importResult.value = com.workoutmaker.app.strength.ImportSummary(
                    ok = false, error = "Couldn't read that file (${read.exceptionOrNull()?.message ?: "empty"}). " +
                        "Pick the .csv exported from Strong or Hevy.")
            } else vm.importCsv(text)
        }
    }
    result?.let { ImportResultDialog(it) { vm.dismissImportResult() } }
    SectionCard(title = "Import strength history") {
        Text("Import a CSV export from Strong or Hevy. Workouts, sets and weights (kg/lb) are detected automatically. Re-importing the same file is safe — sessions you already have are skipped.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            // Broadest filter: some file pickers (notably Samsung's) grey out CSVs
            // when given several specific MIME types. "*/*" reliably shows them.
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            enabled = !importBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (importBusy) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                Text("  Importing…")
            } else Text("Choose CSV file")
        }
        importStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }

    ExportCard(vm)
}

@Composable
internal fun ExportCard(vm: SettingsViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri == null) { status = "Export cancelled." } else scope.launch {
            status = "Exporting…"
            val ok = runCatching {
                val csv = vm.buildExportCsv()
                context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                csv.count { it == '\n' } - 1
            }
            status = ok.fold({ "✓ Exported $it sets to CSV." }, { "Export failed: ${it.message}" })
        }
    }
    SectionCard(title = "Export your data") {
        Text("Save your entire strength history as a Strong-compatible CSV. You can re-import it here or open it anywhere.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            onClick = { exportLauncher.launch("workout-maker-strength-${java.time.LocalDate.now()}.csv") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Export strength CSV") }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
internal fun AppearanceSection(vm: SettingsViewModel) {
    val s by vm.appSettings.collectAsStateSafe()
    SectionCard(title = "Theme") {
        Text("Choose how the app looks. “Follow system” matches your phone's light/dark setting.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        com.workoutmaker.app.data.ThemeMode.entries.forEach { mode ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.setThemeMode(mode) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(selected = s.themeMode == mode, onClick = { vm.setThemeMode(mode) })
                Text(mode.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// P1 — goal races (A/B/C) + countdown
// ---------------------------------------------------------------------------
@Composable
internal fun RacesSection(vm: SettingsViewModel) {
    val races by vm.races.collectAsStateSafe()
    val profile by vm.profile.collectAsStateSafe()
    var showAdd by remember { mutableStateOf(false) }
    val today = java.time.LocalDate.now()

    if (showAdd) AddRaceDialog(onClose = { showAdd = false }) { race, setGoal ->
        vm.addRace(race, setGoal); showAdd = false
    }

    SectionCard(title = "Goal races") {
        Text("Your A-race drives periodization and the taper. B/C races are tune-ups shown on the countdown.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (races.isEmpty()) {
            Text("No races yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        races.forEach { r ->
            val days = runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, java.time.LocalDate.parse(r.date)) }.getOrNull()
            val isGoal = profile.goal_date == r.date
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).background(priorityColor(r.priority), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(r.priority, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(r.name + if (isGoal) "  ⭐" else "", style = MaterialTheme.typography.titleSmall)
                    Text(buildString {
                        append(r.date)
                        r.distance?.let { append(" · $it") }
                        days?.let { append(" · ${if (it >= 0) "$it days" else "past"}") }
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!isGoal) TextButton(onClick = { vm.makeGoalRace(r) }) { Text("Set goal") }
                r.id?.let { id -> IconButton(onClick = { vm.deleteRace(id) }) {
                    Icon(Icons.Filled.Delete, "Delete race", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }
        OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("Add race") }
    }
}

@Composable
internal fun AccountSection(vm: SettingsViewModel) {
    SectionCard {
        Text("Workout Maker", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("Personalised endurance + strength coaching.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { vm.signOut() }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

// --- Shared bits -----------------------------------------------------------

@Composable
internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

internal fun restLabel(sec: Int): String =
    if (sec <= 0) "Off" else if (sec < 60) "${sec}s" else "%d:%02d".format(sec / 60, sec % 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipGroup(label: String, options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { opt ->
            FilterChip(selected = selected == opt, onClick = { onSelect(opt) }, label = { Text(opt) })
        }
    }
}

@Composable
internal fun StatusChip(label: String, ok: Boolean) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    AssistChip(
        onClick = {}, enabled = false,
        label = { Text(if (ok) "$label ✓" else label) },
        colors = AssistChipDefaults.assistChipColors(disabledLabelColor = color),
    )
}

@Composable
internal fun ProviderCard(mod: Modifier, provider: LlmProvider, vm: SettingsViewModel) {
    var key by remember { mutableStateOf("") }
    val result = vm.results[provider.key]
    val overrides by vm.modelOverrides.collectAsStateSafe()
    val activeModel = overrides[provider.key] ?: provider.model
    var showModelPicker by remember { mutableStateOf(false) }

    if (showModelPicker) {
        ModelPickerDialog(provider, vm) { showModelPicker = false }
    }

    SectionCard(mod, title = "${provider.label}${if (provider.freeTier) "  · free tier" else ""}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(activeModel, style = MaterialTheme.typography.bodySmall)
                if (overrides[provider.key] != null) {
                    Text("custom — default is ${provider.model}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = { showModelPicker = true }) { Text("Change model") }
        }
        OutlinedTextField(key, { key = it }, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.testKey(provider, key, false) }, enabled = key.isNotBlank()) { Text("Save & Test") }
            OutlinedButton(onClick = { vm.testKey(provider, key, true) }, enabled = key.isNotBlank()) { Text("Test Gen") }
        }
        result?.let {
            Text(
                if (it.is_valid) "✓ valid · ~$${"%.4f".format(it.estimated_cost_usd)}/workout" else "✗ ${it.error ?: "invalid"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (it.is_valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

// Dynamic model picker: pulls the live model list from the provider's API
// (with the user's saved key) and stores the chosen id on the profile.
@Composable
internal fun ModelPickerDialog(provider: LlmProvider, vm: SettingsViewModel, onClose: () -> Unit) {
    val overrides by vm.modelOverrides.collectAsStateSafe()
    val busy by vm.modelBusy.collectAsStateSafe()
    val list = vm.modelLists[provider.key]
    val current = overrides[provider.key]

    LaunchedEffect(provider.key) { if (list == null) vm.loadModels(provider) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("${provider.label} model") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Default always available, even before/without a fetched list.
                ModelRow(
                    label = "Default — ${provider.model}",
                    selected = current == null,
                    onClick = { vm.setModel(provider, null); onClose() },
                )
                when {
                    busy == provider.key -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            Modifier.padding(end = 10.dp).then(Modifier.size(18.dp)), strokeWidth = 2.dp)
                        Text("Fetching available models…", style = MaterialTheme.typography.bodySmall)
                    }
                    list?.error != null -> {
                        Text(list.error!!, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                        OutlinedButton(onClick = { vm.loadModels(provider) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Retry")
                        }
                    }
                    else -> list?.models.orEmpty().forEach { m ->
                        ModelRow(label = m, selected = current == m,
                            onClick = { vm.setModel(provider, m); onClose() })
                    }
                }
            }
        },
    )
}

@Composable
private fun ModelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
