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

private val GOALS = listOf(
    "5K pace", "10K pace", "Half Marathon", "Marathon pace",
    "General fitness", "Muscle gain", "Body recomposition", "Hybrid athlete",
)
private val EQUIPMENT = listOf("Bodyweight", "Dumbbells", "Full gym", "Barbell + rack")
private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val DURATIONS = listOf(30, 45, 60, 90)
private val LEVELS = listOf("Beginner", "Intermediate", "Advanced")
private val BAR_WEIGHTS = listOf(20.0, 15.0, 10.0, 7.0)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: WorkoutRepository,
    private val prefs: AppPreferences,
    private val strength: com.workoutmaker.app.strength.StrengthRepository,
    private val health: com.workoutmaker.app.health.HealthConnectManager,
) : ViewModel() {

    // CSV import (Strong / Hevy) lives in Settings → Import data.
    val importStatus = MutableStateFlow<String?>(null)
    val importBusy = MutableStateFlow(false)
    // Set once an import finishes so the UI can show a detailed result dialog.
    val importResult = MutableStateFlow<com.workoutmaker.app.strength.ImportSummary?>(null)
    fun dismissImportResult() { importResult.value = null }
    fun importCsv(text: String) = viewModelScope.launch {
        importBusy.value = true
        importStatus.value = "Importing…"
        runCatching { strength.importCsv(text) }
            .onSuccess { s ->
                importResult.value = s
                importStatus.value = if (s.ok)
                    "✓ Imported ${s.workoutsAdded} ${s.format} workouts (${s.setsAdded} sets)" +
                        (if (s.duplicatesSkipped > 0) " · ${s.duplicatesSkipped} already imported" else "")
                else "Import failed: ${s.error}"
            }
            .onFailure {
                android.util.Log.e("IMPORT", "import threw", it)
                importResult.value = com.workoutmaker.app.strength.ImportSummary(
                    ok = false, error = "${it::class.simpleName}: ${it.message ?: "unknown error"}")
                importStatus.value = "Import failed: ${it.message}"
            }
        importBusy.value = false
    }
    val results = androidx.compose.runtime.mutableStateMapOf<String, TestKeyResponse>()
    var active by androidx.compose.runtime.mutableStateOf(LlmProvider.GROQ)

    val profile = MutableStateFlow(TrainingProfile())
    val intervalsStatus = MutableStateFlow<String?>(null)
    val saveStatus = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    // Device-local app settings (units, rest defaults, vibration, etc.).
    val appSettings = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    fun setUnits(u: WeightUnit) = viewModelScope.launch { prefs.setUnits(u) }
    fun setDefaultRest(sec: Int) = viewModelScope.launch { prefs.setDefaultRest(sec) }
    fun setBarbell(kg: Double) = viewModelScope.launch { prefs.setBarbell(kg) }
    fun setRestVibrate(on: Boolean) = viewModelScope.launch { prefs.setRestVibrate(on) }
    fun setRestNotify(on: Boolean) = viewModelScope.launch { prefs.setRestNotify(on) }
    fun setKeepScreenOn(on: Boolean) = viewModelScope.launch { prefs.setKeepScreenOn(on) }
    fun setThemeMode(m: com.workoutmaker.app.data.ThemeMode) = viewModelScope.launch { prefs.setThemeMode(m) }

    // Q11: build a Strong-compatible CSV of all strength history for the user to save.
    suspend fun buildExportCsv(): String = strength.exportCsv()

    // --- Health Connect ---------------------------------------------------
    val healthAvailable: Boolean get() = health.isAvailable
    val healthPermissions: Set<String> get() = health.permissions
    val healthProviderPackage: String get() = health.providerPackage
    val healthStatus = MutableStateFlow<String?>(null)

    suspend fun hasHealthPerms(): Boolean = health.hasAllPermissions()
    fun setHealthStatus(msg: String) { healthStatus.value = msg }

    fun syncHealth() = viewModelScope.launch {
        healthStatus.value = "Reading Health Connect (7-day trend)…"
        runCatching {
            val week = health.readWeek(7)
            if (week.isEmpty()) {
                healthStatus.value = "No HRV/HR/sleep data found in Health Connect yet."
                return@launch
            }
            repo.submitHealthSnapshots(week)
            val today = week.firstOrNull { it.date == java.time.LocalDate.now().toString() } ?: week.first()
            healthStatus.value = buildString {
                append("✓ Synced ${week.size} days")
                today.hrvRmssd?.let { append(" · HRV ${"%.0f".format(it)}ms") }
                today.restingHr?.let { append(" · RHR $it") }
                today.sleepMinutes?.let { append(" · sleep ${it / 60}h${it % 60}m") }
            }
        }.onFailure { healthStatus.value = "Failed: ${it.message}" }
    }

    val autoPlan = MutableStateFlow(false)
    val logs = MutableStateFlow<List<com.workoutmaker.app.data.GenerationLogRow>>(emptyList())

    // Durable coaching knowledge (injuries, equipment, preferences).
    val knowledge = MutableStateFlow("")
    val knowledgeStatus = MutableStateFlow<String?>(null)

    // P1 races + E4 threshold tests.
    val races = MutableStateFlow<List<com.workoutmaker.app.data.Race>>(emptyList())
    val thresholdTests = MutableStateFlow<List<com.workoutmaker.app.data.ThresholdTest>>(emptyList())

    fun load() = viewModelScope.launch {
        repo.loadProfile()?.let { profile.value = it }
        autoPlan.value = repo.autoPlanEnabled()
        runCatching { repo.loadKnowledge() }.onSuccess { knowledge.value = it }
        runCatching { repo.generationLogs() }.onSuccess { logs.value = it }
        runCatching { repo.races() }.onSuccess { races.value = it }
        runCatching { repo.thresholdTests() }.onSuccess { thresholdTests.value = it }
    }

    fun addRace(r: com.workoutmaker.app.data.Race, setAsGoal: Boolean) = viewModelScope.launch {
        runCatching {
            repo.addRace(r)
            if (setAsGoal) repo.setGoalRace(r.name, r.date)
            races.value = repo.races()
            repo.loadProfile()?.let { profile.value = it }
        }.onSuccess { saveStatus.value = "✓ Race added" }.onFailure { saveStatus.value = "Couldn't add: ${it.message}" }
    }

    fun deleteRace(id: String) = viewModelScope.launch {
        runCatching { repo.deleteRace(id); races.value = repo.races() }
            .onFailure { saveStatus.value = "Couldn't delete: ${it.message}" }
    }

    fun makeGoalRace(r: com.workoutmaker.app.data.Race) = viewModelScope.launch {
        runCatching { repo.setGoalRace(r.name, r.date); repo.loadProfile()?.let { profile.value = it } }
            .onSuccess { saveStatus.value = "✓ “${r.name}” is now your goal race" }
            .onFailure { saveStatus.value = it.message }
    }

    fun saveThresholds(lthr: Int?, ftp: Int?, pace: String?) = viewModelScope.launch {
        busy.value = true
        runCatching { repo.saveThresholds(lthr, ftp, pace); repo.loadProfile()?.let { profile.value = it } }
            .onSuccess { saveStatus.value = "✓ Thresholds saved" }
            .onFailure { saveStatus.value = it.message }
        busy.value = false
    }

    fun addThresholdTest(t: com.workoutmaker.app.data.ThresholdTest) = viewModelScope.launch {
        runCatching {
            repo.addThresholdTest(t)
            thresholdTests.value = repo.thresholdTests()
            repo.loadProfile()?.let { profile.value = it }
        }.onSuccess { saveStatus.value = "✓ Test logged — zones updated" }
            .onFailure { saveStatus.value = "Couldn't log: ${it.message}" }
    }

    fun updateKnowledge(text: String) { knowledge.value = text }

    fun saveKnowledge() = viewModelScope.launch {
        busy.value = true
        runCatching { repo.saveKnowledge(knowledge.value) }
            .onSuccess { knowledgeStatus.value = "✓ Saved" }
            .onFailure { knowledgeStatus.value = "Couldn't save: ${it.message}" }
        busy.value = false
    }

    fun setAutoPlan(enabled: Boolean) = viewModelScope.launch {
        autoPlan.value = enabled
        runCatching { repo.setAutoPlan(enabled) }
            .onFailure { autoPlan.value = !enabled; saveStatus.value = "Couldn't update: ${it.message}" }
    }

    fun updateProfile(transform: (TrainingProfile) -> TrainingProfile) {
        profile.value = transform(profile.value)
    }

    fun saveProfile() = viewModelScope.launch {
        busy.value = true
        runCatching { repo.saveProfile(profile.value) }
            .onSuccess { saveStatus.value = "✓ Saved" }
            .onFailure { saveStatus.value = it.message }
        busy.value = false
    }

    fun connectIntervals(athleteId: String, apiKey: String) = viewModelScope.launch {
        busy.value = true
        intervalsStatus.value = "Connecting…"
        runCatching { repo.connectIntervalsVerified(athleteId, apiKey) }
            .onSuccess { r ->
                intervalsStatus.value = if (r.ok) "✓ Connected as ${r.athlete_name}"
                else "Failed: ${r.error ?: "unknown"}"
                if (r.ok) runCatching { repo.syncIntervals() }
            }
            .onFailure { intervalsStatus.value = "Failed: ${it.message}" }
        busy.value = false
    }

    fun selectProvider(p: LlmProvider) {
        active = p
        viewModelScope.launch { runCatching { repo.setActiveProvider(p) } }
    }

    fun testKey(p: LlmProvider, key: String, sample: Boolean) = viewModelScope.launch {
        runCatching { repo.testLlmKey(TestKeyRequest(p.key, key, sample)) }
            .onSuccess { results[p.key] = it }
    }

    fun signOut() = viewModelScope.launch { repo.signOut() }
}

// ===========================================================================
// Holistic, two-level Settings panel: an index of grouped categories, each
// opening its own focused detail page (no more one flat scroll).
// ===========================================================================

private data class SettingsItem(val id: String, val icon: ImageVector, val title: String, val subtitle: String)
private data class SettingsGroup(val header: String, val items: List<SettingsItem>)

private val SETTINGS_GROUPS = listOf(
    SettingsGroup("Training", listOf(
        SettingsItem("profile", Icons.Outlined.Person, "Profile & goal", "Goal, experience, days, equipment, pace"),
        SettingsItem("races", Icons.Outlined.Flag, "Goal races", "A/B/C races & countdown"),
        SettingsItem("zones", Icons.Outlined.Favorite, "Training zones", "Thresholds, HR/pace/power zones & tests"),
        SettingsItem("defaults", Icons.Outlined.FitnessCenter, "Workout defaults", "Units, rest timer, barbell, screen"),
        SettingsItem("planning", Icons.Outlined.CalendarMonth, "Planning", "Auto-plan & weekly load target"),
    )),
    SettingsGroup("Coaching & AI", listOf(
        SettingsItem("knowledge", Icons.Outlined.Psychology, "Coach knowledge", "Injuries, equipment & preferences"),
        SettingsItem("ai", Icons.Outlined.AutoAwesome, "AI providers", "Active model & API keys"),
    )),
    SettingsGroup("Integrations", listOf(
        SettingsItem("connections", Icons.Outlined.Link, "Connections", "Intervals.icu & Health Connect"),
    )),
    SettingsGroup("App", listOf(
        SettingsItem("appearance", Icons.Outlined.Palette, "Appearance", "Light / dark theme"),
        SettingsItem("notifications", Icons.Outlined.Notifications, "Notifications", "Rest-timer alerts & vibration"),
        SettingsItem("data", Icons.Outlined.Download, "Import & export", "Strong/Hevy import · CSV backup"),
        SettingsItem("diagnostics", Icons.Outlined.MonitorHeart, "Diagnostics", "AI generation log & cost"),
    )),
    SettingsGroup("Account", listOf(
        SettingsItem("account", Icons.Outlined.AccountCircle, "About & account", "Version & sign out"),
    )),
)

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.load() }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = open != null) { open = null }

    if (open == null) {
        SettingsIndex(onOpen = { open = it })
    } else {
        SettingsDetail(open!!, vm) { open = null }
    }
}

@Composable
private fun SettingsIndex(onOpen: (String) -> Unit) {
    ScreenScaffold(title = "Settings", subtitle = "Everything in one place") { mod ->
        SETTINGS_GROUPS.forEach { group ->
            Text(
                group.header.uppercase(),
                mod.padding(start = 4.dp, top = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            SectionCard(mod) {
                group.items.forEachIndexed { i, item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(item.id) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 14.dp).size(22.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (i < group.items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDetail(id: String, vm: SettingsViewModel, onBack: () -> Unit) {
    val title = SETTINGS_GROUPS.flatMap { it.items }.firstOrNull { it.id == id }?.title ?: "Settings"
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (id) {
                "profile" -> ProfileSection(vm)
                "races" -> RacesSection(vm)
                "zones" -> ZonesSection(vm)
                "defaults" -> WorkoutDefaultsSection(vm)
                "planning" -> PlanningSection(vm)
                "knowledge" -> KnowledgeSection(vm)
                "ai" -> AiSection(vm)
                "connections" -> ConnectionsSection(vm)
                "appearance" -> AppearanceSection(vm)
                "notifications" -> NotificationsSection(vm)
                "data" -> DataSection(vm)
                "diagnostics" -> DiagnosticsSection(vm)
                "account" -> AccountSection(vm)
            }
        }
    }
}

// --- Detail sections -------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileSection(vm: SettingsViewModel) {
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
        Text("Session length", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DURATIONS.forEach { d ->
                FilterChip(selected = profile.session_duration == d, onClick = { vm.updateProfile { it.copy(session_duration = d) } }, label = { Text("${d}m") })
            }
        }
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
private fun WorkoutDefaultsSection(vm: SettingsViewModel) {
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
private fun PlanningSection(vm: SettingsViewModel) {
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
private fun KnowledgeSection(vm: SettingsViewModel) {
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
private fun AiSection(vm: SettingsViewModel) {
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
private fun ConnectionsSection(vm: SettingsViewModel) {
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
private fun NotificationsSection(vm: SettingsViewModel) {
    val s by vm.appSettings.collectAsStateSafe()
    SectionCard {
        ToggleRow("Rest-timer alert", "Notify when a rest period ends — even if the app is in the background.", s.restNotify) { vm.setRestNotify(it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        ToggleRow("Vibrate on rest end", "Buzz the phone when the rest timer finishes.", s.restVibrate) { vm.setRestVibrate(it) }
    }
}

@Composable
private fun DiagnosticsSection(vm: SettingsViewModel) {
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
private fun DataSection(vm: SettingsViewModel) {
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
private fun ExportCard(vm: SettingsViewModel) {
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
private fun AppearanceSection(vm: SettingsViewModel) {
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
private fun RacesSection(vm: SettingsViewModel) {
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

private fun priorityColor(p: String) = when (p.uppercase()) {
    "A" -> com.workoutmaker.app.ui.theme.BandRed
    "B" -> com.workoutmaker.app.ui.theme.BandAmber
    else -> com.workoutmaker.app.ui.theme.Sage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRaceDialog(onClose: () -> Unit, onAdd: (com.workoutmaker.app.data.Race, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(java.time.LocalDate.now().plusWeeks(8)) }
    var distance by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("A") }
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
                }) { Text("OK") }
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
                    priority = priority, distance = distance.ifBlank { null }), setGoal && priority == "A") },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        title = { Text("Add race") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.EditCalendar, null)
                    Text("  $date")
                }
                OutlinedTextField(distance, { distance = it }, label = { Text("Distance (e.g. Marathon)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("A", "B", "C").forEach { p ->
                        androidx.compose.material3.FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text("$p race") })
                    }
                }
                if (priority == "A") Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = setGoal, onCheckedChange = { setGoal = it })
                    Text("Make this my goal race (drives the plan)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// E1 + E4 — training zones & threshold tests
// ---------------------------------------------------------------------------
@Composable
private fun ZonesSection(vm: SettingsViewModel) {
    val profile by vm.profile.collectAsStateSafe()
    val tests by vm.thresholdTests.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    val saveStatus by vm.saveStatus.collectAsStateSafe()

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
        saveStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }

    val hrZones = profile.lthr?.let { com.workoutmaker.app.data.Zones.hrZonesFromLthr(it) }.orEmpty()
    val paceZones = profile.threshold_pace_per_km?.let { com.workoutmaker.app.data.Zones.parsePace(it) }
        ?.let { com.workoutmaker.app.data.Zones.paceZonesFromThreshold(it) }.orEmpty()
    val powerZones = profile.ftp?.let { com.workoutmaker.app.data.Zones.powerZonesFromFtp(it) }.orEmpty()

    if (hrZones.isNotEmpty()) SectionCard(title = "Heart-rate zones") {
        hrZones.forEach { z -> ZoneRow(z.name, "${z.min}–${z.max} bpm") }
    }
    if (paceZones.isNotEmpty()) SectionCard(title = "Pace zones") {
        paceZones.forEach { z -> ZoneRow(z.name, z.range) }
    }
    if (powerZones.isNotEmpty()) SectionCard(title = "Power zones") {
        powerZones.forEach { z -> ZoneRow(z.name, z.range) }
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
                Text(label, style = MaterialTheme.typography.bodyMedium, color = com.workoutmaker.app.ui.theme.Sage)
            }
        }
        OutlinedButton(onClick = { showTest = true }, modifier = Modifier.fillMaxWidth()) { Text("Log a test") }
    }
}

@Composable
private fun ZoneRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LogTestDialog(onClose: () -> Unit, onLog: (com.workoutmaker.app.data.ThresholdTest) -> Unit) {
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
private fun ImportResultDialog(s: com.workoutmaker.app.strength.ImportSummary, onClose: () -> Unit) {
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
private fun ResultLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun AccountSection(vm: SettingsViewModel) {
    SectionCard {
        Text("Workout Maker", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("Personalised endurance + strength coaching.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { vm.signOut() }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

// --- Shared bits -----------------------------------------------------------

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun restLabel(sec: Int): String =
    if (sec <= 0) "Off" else if (sec < 60) "${sec}s" else "%d:%02d".format(sec / 60, sec % 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(label: String, options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { opt ->
            FilterChip(selected = selected == opt, onClick = { onSelect(opt) }, label = { Text(opt) })
        }
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    AssistChip(
        onClick = {}, enabled = false,
        label = { Text(if (ok) "$label ✓" else label) },
        colors = AssistChipDefaults.assistChipColors(disabledLabelColor = color),
    )
}

@Composable
private fun ProviderCard(mod: Modifier, provider: LlmProvider, vm: SettingsViewModel) {
    var key by remember { mutableStateOf("") }
    val result = vm.results[provider.key]
    SectionCard(mod, title = "${provider.label}${if (provider.freeTier) "  · free tier" else ""}") {
        Text(provider.model, style = MaterialTheme.typography.bodySmall)
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
