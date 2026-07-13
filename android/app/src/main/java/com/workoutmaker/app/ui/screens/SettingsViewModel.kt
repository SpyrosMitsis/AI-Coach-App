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

internal val GOALS = listOf(
    "5K pace", "10K pace", "Half Marathon", "Marathon pace",
    "General fitness", "Muscle gain", "Body recomposition", "Hybrid athlete",
)

internal val EQUIPMENT = listOf("Bodyweight", "Dumbbells", "Full gym", "Barbell + rack")

internal val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

internal val DURATIONS = listOf(30, 45, 60, 90)

internal val DURATIONS_MAX = listOf(45, 60, 75, 90, 120)

internal val LEVELS = listOf("Beginner", "Intermediate", "Advanced")

internal val SPLIT_STYLES = listOf("Auto", "Full body", "Upper / lower", "Push / pull / legs")

// Canonical sport keys (stored); UI capitalizes them. Match workout `type` values.
internal val SPORTS = listOf("run", "ride", "swim", "strength")

internal val BAR_WEIGHTS = listOf(20.0, 15.0, 10.0, 7.0)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: WorkoutRepository,
    private val prefs: AppPreferences,
    private val strength: com.workoutmaker.app.strength.StrengthRepository,
    private val health: com.workoutmaker.app.health.HealthConnectManager,
    private val billing: com.workoutmaker.app.billing.BillingGateway,
) : ViewModel() {

    // --- Pro plan / hosted AI (only when this build can bill AND this server
    // hosts an LLM key — self-hosted stacks and foss builds never see it) ----
    val planStatus = MutableStateFlow(com.workoutmaker.app.data.PlanStatus())
    val proAvailable = MutableStateFlow(false)
    val proBusy = MutableStateFlow(false)
    val proError = MutableStateFlow<String?>(null)

    fun buyPro(activity: android.app.Activity) = viewModelScope.launch {
        proBusy.value = true
        proError.value = null
        when (val r = com.workoutmaker.app.billing.purchaseAndVerify(activity, billing, repo)) {
            is com.workoutmaker.app.billing.ProPurchaseResult.Success -> planStatus.value = repo.planStatus()
            is com.workoutmaker.app.billing.ProPurchaseResult.Cancelled -> Unit
            is com.workoutmaker.app.billing.ProPurchaseResult.Failed -> proError.value = r.message
        }
        proBusy.value = false
    }

    // Re-verify a purchase Play already knows about (bought on another device,
    // or verify failed right after the buy).
    fun restorePro() = viewModelScope.launch {
        proBusy.value = true
        proError.value = null
        runCatching {
            val token = billing.currentPurchaseToken() ?: error("No active subscription found on this Google account.")
            repo.verifyPurchase(token)
            planStatus.value = repo.planStatus()
        }.onFailure { proError.value = it.message }
        proBusy.value = false
    }

    fun setUseHostedAi(on: Boolean) = viewModelScope.launch {
        runCatching { repo.setUseHostedAi(on) }
            .onSuccess { planStatus.value = planStatus.value.copy(useHostedAi = on) }
    }

    // --- Support the developer (one-time tips / Ko-fi) ----------------------
    // Same section in both flavors; only the rail differs: Play builds tip
    // through Play Billing, foss builds open Ko-fi in the browser.
    val tipsSupported: Boolean get() = billing.tipsSupported
    val tipBusy = MutableStateFlow(false)
    val tipStatus = MutableStateFlow<String?>(null)

    fun sendTip(activity: android.app.Activity, productId: String) = viewModelScope.launch {
        tipBusy.value = true
        tipStatus.value = null
        val ok = runCatching { billing.tip(activity, productId) }.getOrDefault(false)
        tipStatus.value = if (ok) "Thank you! Every tip helps." else "Tip not completed. Nothing was charged."
        tipBusy.value = false
    }

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

    // Dynamic model selector: per-provider override + live model lists.
    val modelOverrides = MutableStateFlow<Map<String, String>>(emptyMap())
    val modelLists = androidx.compose.runtime.mutableStateMapOf<String, com.workoutmaker.app.data.ModelListResponse>()
    val modelBusy = MutableStateFlow<String?>(null)

    fun loadModels(p: LlmProvider) = viewModelScope.launch {
        modelBusy.value = p.key
        runCatching { repo.listModels(p) }
            .onSuccess { modelLists[p.key] = it }
            .onFailure {
                modelLists[p.key] = com.workoutmaker.app.data.ModelListResponse(
                    provider = p.key, error = it.message ?: "couldn't fetch models")
            }
        modelBusy.value = null
    }

    fun setModel(p: LlmProvider, model: String?) = viewModelScope.launch {
        runCatching {
            repo.setModelOverride(p, model)
            modelOverrides.value = repo.modelOverrides()
        }.onFailure { saveStatus.value = "Couldn't set model: ${it.message}" }
    }

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
    fun setMorningNotify(on: Boolean) = viewModelScope.launch { prefs.setMorningNotify(on) }
    fun setRestChime(c: com.workoutmaker.app.data.RestChime) = viewModelScope.launch { prefs.setRestChime(c) }
    fun setKeepScreenOn(on: Boolean) = viewModelScope.launch { prefs.setKeepScreenOn(on) }
    fun setThemeMode(m: com.workoutmaker.app.data.ThemeMode) = viewModelScope.launch { prefs.setThemeMode(m) }
    fun setThemePalette(p: com.workoutmaker.app.data.ThemePalette) = viewModelScope.launch { prefs.setThemePalette(p) }
    fun setSpendCap(usd: Double) = viewModelScope.launch { prefs.setSpendCap(usd) }

    // Q11: build a Strong-compatible CSV of all strength history for the user to save.
    suspend fun buildExportCsv(): String = strength.exportCsv()

    // --- Health Connect ---------------------------------------------------
    val healthAvailable: Boolean get() = health.isAvailable
    val healthPermissions: Set<String> get() = health.permissions
    val healthProviderPackage: String get() = health.providerPackage
    val healthStatus = MutableStateFlow<String?>(null)

    suspend fun hasHealthPerms(): Boolean = health.hasAllPermissions()

    /** Granted read permissions — syncing works with a partial grant too. */
    suspend fun grantedHealthPerms(): Set<String> = health.grantedPermissions()
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

    // Rolling coach memory — durable notes the coach carries between sessions.
    val memory = MutableStateFlow("")
    val memoryStatus = MutableStateFlow<String?>(null)

    // Coach soul — the coach's identity/voice + its evolving relationship with you.
    val soul = MutableStateFlow("")
    val soulStatus = MutableStateFlow<String?>(null)

    // P1 races + E4 threshold tests.
    val races = MutableStateFlow<List<com.workoutmaker.app.data.Race>>(emptyList())
    val thresholdTests = MutableStateFlow<List<com.workoutmaker.app.data.ThresholdTest>>(emptyList())

    // Saved credentials, shown masked so it's clear what's already configured:
    // Intervals.icu (athlete id + key hint) and per-provider LLM key rows.
    val intervalsSaved = MutableStateFlow<Pair<String, String?>?>(null)
    val llmKeys = MutableStateFlow<Map<String, com.workoutmaker.app.data.LlmKeyRow>>(emptyMap())
    // Custom (BYO) provider per-1M-token prices, so its cost isn't shown as $0.
    val customPrice = MutableStateFlow<Pair<Double?, Double?>>(null to null)

    fun load() = viewModelScope.launch {
        repo.loadProfile()?.let { profile.value = it }
        runCatching { repo.planStatus() }.onSuccess { planStatus.value = it }
        proAvailable.value = billing.supported && repo.serverHostedAi()
        autoPlan.value = repo.autoPlanEnabled()
        runCatching { repo.modelOverrides() }.onSuccess { modelOverrides.value = it }
        runCatching { repo.loadKnowledge() }.onSuccess { knowledge.value = it }
        runCatching { repo.loadMemory() }.onSuccess { memory.value = it }
        runCatching { repo.loadSoul() }.onSuccess { soul.value = it }
        runCatching { repo.generationLogs() }.onSuccess { logs.value = it }
        runCatching { repo.races() }.onSuccess { races.value = it }
        runCatching { repo.thresholdTests() }.onSuccess { thresholdTests.value = it }
        runCatching { repo.intervalsConnection() }.onSuccess { intervalsSaved.value = it }
        runCatching { repo.llmKeyRows() }.onSuccess { rows -> llmKeys.value = rows.associateBy { it.provider } }
        runCatching { repo.customLlmPricing() }.onSuccess { customPrice.value = it }
    }

    fun setCustomPricing(inputPer1M: Double?, outputPer1M: Double?) = viewModelScope.launch {
        runCatching {
            repo.setCustomLlmPricing(inputPer1M, outputPer1M)
            customPrice.value = inputPer1M to outputPer1M
        }
    }

    fun addRace(r: com.workoutmaker.app.data.Race, setAsGoal: Boolean) = viewModelScope.launch {
        runCatching {
            repo.addRace(r)
            if (setAsGoal) repo.setGoalRace(r)
            races.value = repo.races()
            repo.loadProfile()?.let { profile.value = it }
        }.onSuccess { saveStatus.value = "✓ Goal added" }.onFailure { saveStatus.value = "Couldn't add: ${it.message}" }
    }

    fun deleteRace(r: com.workoutmaker.app.data.Race) = viewModelScope.launch {
        runCatching {
            repo.deleteRace(r)
            races.value = repo.races()
            repo.loadProfile()?.let { profile.value = it }
        }.onFailure { saveStatus.value = "Couldn't delete: ${it.message}" }
    }

    fun makeGoalRace(r: com.workoutmaker.app.data.Race) = viewModelScope.launch {
        runCatching { repo.setGoalRace(r); repo.loadProfile()?.let { profile.value = it } }
            .onSuccess { saveStatus.value = "✓ “${r.name}” is now your goal" }
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
        }.onSuccess { saveStatus.value = "✓ Test logged, zones updated" }
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

    fun updateMemory(text: String) { memory.value = text }

    fun saveMemory() = viewModelScope.launch {
        busy.value = true
        runCatching { repo.saveMemory(memory.value) }
            .onSuccess { memoryStatus.value = "✓ Saved" }
            .onFailure { memoryStatus.value = "Couldn't save: ${it.message}" }
        busy.value = false
    }

    fun updateSoul(text: String) { soul.value = text }

    fun saveSoul() = viewModelScope.launch {
        busy.value = true
        runCatching { repo.saveSoul(soul.value) }
            .onSuccess { soulStatus.value = "✓ Saved" }
            .onFailure { soulStatus.value = "Couldn't save: ${it.message}" }
        busy.value = false
    }

    // Re-derive the rolling notes from recent training, then reload them.
    fun refreshMemory() = viewModelScope.launch {
        busy.value = true
        memoryStatus.value = "Refreshing from recent training…"
        runCatching { repo.refreshMemory(); repo.loadMemory() }
            .onSuccess { memory.value = it; memoryStatus.value = "✓ Updated" }
            .onFailure { memoryStatus.value = "Couldn't refresh: ${it.message}" }
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
                if (r.ok) {
                    runCatching { repo.intervalsConnection() }.onSuccess { intervalsSaved.value = it }
                    runCatching { repo.syncIntervals() }
                }
            }
            .onFailure { intervalsStatus.value = "Failed: ${it.message}" }
        busy.value = false
    }

    /** Pull fresh Intervals.icu data using the already-saved credentials — no
     *  need to re-enter the key/id just to sync. */
    fun syncIntervalsNow() = viewModelScope.launch {
        busy.value = true
        intervalsStatus.value = "Syncing…"
        runCatching { repo.syncIntervals() }
            .onSuccess { intervalsStatus.value = "✓ Synced from Intervals.icu" }
            .onFailure { intervalsStatus.value = "Sync failed: ${it.message}" }
        busy.value = false
    }

    fun selectProvider(p: LlmProvider) {
        active = p
        viewModelScope.launch { runCatching { repo.setActiveProvider(p) } }
    }

    // Which provider key is currently being tested (drives the button spinner).
    val testing = MutableStateFlow<String?>(null)

    fun testKey(
        p: LlmProvider,
        key: String,
        sample: Boolean,
        baseUrl: String? = null,
        model: String? = null,
    ) = viewModelScope.launch {
        testing.value = p.key
        runCatching {
            repo.testLlmKey(
                TestKeyRequest(p.key, key, sample, baseUrl = baseUrl?.trim()?.ifBlank { null }, model = model?.trim()?.ifBlank { null }),
            )
        }
            .onFailure {
                // Surface the failure instead of silently doing nothing — most
                // commonly the function returned an error (e.g. endpoint
                // unreachable from the server, or DB migration not yet applied).
                results[p.key] = com.workoutmaker.app.data.TestKeyResponse(
                    provider = p.key, model = model ?: p.model, is_valid = false,
                    error = it.message ?: "request failed",
                )
            }
            .onSuccess {
                results[p.key] = it
                // For the custom provider the model id lives in the per-provider
                // override (llm_models), so persist it alongside the key.
                if (p == LlmProvider.CUSTOM && it.is_valid && !model.isNullOrBlank()) {
                    runCatching {
                        repo.setModelOverride(p, model.trim())
                        modelOverrides.value = repo.modelOverrides()
                    }
                }
                // Refresh the saved-key rows so the masked hint + base URL appear.
                runCatching { repo.llmKeyRows() }.onSuccess { rows -> llmKeys.value = rows.associateBy { r -> r.provider } }
            }
        testing.value = null
    }

    fun signOut() = viewModelScope.launch { repo.signOut() }

    // null = idle, "" = in flight, anything else = error text.
    val deleteAccountState = MutableStateFlow<String?>(null)

    fun deleteAccount() = viewModelScope.launch {
        deleteAccountState.value = ""
        runCatching { repo.deleteAccount() }
            .onFailure { deleteAccountState.value = it.message ?: "Deletion failed, try again." }
            .onSuccess { deleteAccountState.value = null }
        // On success AuthGate flips to the login screen by itself (session gone).
    }
}

// ===========================================================================
// Holistic, two-level Settings panel: an index of grouped categories, each
// opening its own focused detail page (no more one flat scroll).
// ===========================================================================
