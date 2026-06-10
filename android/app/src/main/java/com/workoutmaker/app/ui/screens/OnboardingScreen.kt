package com.workoutmaker.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workoutmaker.app.data.LlmProvider
import com.workoutmaker.app.data.TestKeyRequest
import com.workoutmaker.app.data.TrainingProfile
import com.workoutmaker.app.data.WorkoutRepository
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val repo: WorkoutRepository) : ViewModel() {
    val complete = MutableStateFlow<Boolean?>(null)
    val step = MutableStateFlow(0)
    val profile = MutableStateFlow(TrainingProfile())
    val keyStatus = MutableStateFlow<String?>(null)
    val intervalsStatus = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    var provider by androidx.compose.runtime.mutableStateOf(LlmProvider.GROQ)

    init { recheck() }
    fun recheck() = viewModelScope.launch { complete.value = repo.isOnboardingComplete() }

    fun update(t: (TrainingProfile) -> TrainingProfile) { profile.value = t(profile.value) }
    fun next() { step.value = (step.value + 1).coerceAtMost(2) }
    fun back() { step.value = (step.value - 1).coerceAtLeast(0) }

    fun testKey(key: String) = viewModelScope.launch {
        busy.value = true
        keyStatus.value = "Testing…"
        runCatching { repo.testLlmKey(TestKeyRequest(provider.key, key.trim())) }
            .onSuccess {
                if (it.is_valid) { repo.setActiveProvider(provider); keyStatus.value = "✓ ${provider.label} key saved & active" }
                else keyStatus.value = "✗ ${it.error ?: "invalid key"}"
            }
            .onFailure { keyStatus.value = "Failed: ${it.message}" }
        busy.value = false
    }

    fun connect(athleteId: String, key: String) = viewModelScope.launch {
        busy.value = true
        intervalsStatus.value = "Connecting…"
        runCatching { repo.connectIntervalsVerified(athleteId.trim(), key.trim()) }
            .onSuccess {
                intervalsStatus.value = if (it.ok) "✓ Connected as ${it.athlete_name}" else "Failed: ${it.error}"
                if (it.ok) runCatching { repo.syncIntervals() }
            }
            .onFailure { intervalsStatus.value = "Failed: ${it.message}" }
        busy.value = false
    }

    fun finish() = viewModelScope.launch {
        busy.value = true
        runCatching { repo.saveProfile(profile.value) }
        busy.value = false
        complete.value = true // saveProfile flips onboarding_complete → enters the app
    }
}

private val GOALS = listOf(
    "5K pace", "10K pace", "Half Marathon", "Marathon pace",
    "General fitness", "Muscle gain", "Body recomposition", "Hybrid athlete",
)
private val LEVELS = listOf("Beginner", "Intermediate", "Advanced")
private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val DURATIONS = listOf(30, 45, 60, 90)
private val EQUIPMENT = listOf("Bodyweight", "Dumbbells", "Full gym", "Barbell + rack")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(vm: OnboardingViewModel = hiltViewModel()) {
    val step by vm.step.collectAsStateSafe()
    val profile by vm.profile.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Text("Welcome", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text("Let's set up your coach", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))

        when (step) {
            0 -> StepGoal(profile, vm)
            1 -> StepKey(vm)
            else -> StepConnect(vm)
        }

        Spacer16()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (step > 0) OutlinedButton(onClick = { vm.back() }, modifier = Modifier.weight(1f)) { Text("Back") }
            if (step < 2) {
                Button(onClick = { vm.next() }, modifier = Modifier.weight(1f)) { Text("Next") }
            } else {
                Button(onClick = { vm.finish() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(if (busy) "Saving…" else "Finish")
                }
            }
        }
        if (step < 2) {
            TextButton(onClick = { vm.finish() }, modifier = Modifier.fillMaxWidth()) { Text("Skip setup for now") }
        }
    }
}

@Composable private fun Spacer16() = androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepGoal(profile: TrainingProfile, vm: OnboardingViewModel) {
    SectionCard(title = "Your goal") {
        Chips("What are you training for?", GOALS, profile.goal) { g -> vm.update { it.copy(goal = g) } }
        Chips("Experience", LEVELS, profile.experience) { e -> vm.update { it.copy(experience = e) } }
        Text("Days you can train", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DAYS.forEach { d ->
                FilterChip(
                    selected = profile.days.contains(d),
                    onClick = { vm.update { p -> p.copy(days = if (p.days.contains(d)) p.days - d else p.days + d) } },
                    label = { Text(d) },
                )
            }
        }
        Text("Session length", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DURATIONS.forEach { d ->
                FilterChip(selected = profile.session_duration == d, onClick = { vm.update { it.copy(session_duration = d) } }, label = { Text("${d}m") })
            }
        }
        Chips("Equipment", EQUIPMENT, profile.equipment) { e -> vm.update { it.copy(equipment = e) } }
        OutlinedTextField(profile.goal_date ?: "", { v -> vm.update { it.copy(goal_date = v) } },
            label = { Text("Goal race date YYYY-MM-DD (optional)") }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StepKey(vm: OnboardingViewModel) {
    var key by remember { mutableStateOf("") }
    val status by vm.keyStatus.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    SectionCard(title = "Add an AI key") {
        Text(
            "This app is free — you bring your own LLM key, so generations cost only what your provider charges (often pennies, or free tiers). Pick one to start.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRowProviders(vm)
        Text("Get a free key: ${vm.provider.freeKeyUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(key, { key = it }, label = { Text("${vm.provider.label} API key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.testKey(key) }, enabled = !busy && key.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Save & test")
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowProviders(vm: OnboardingViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LlmProvider.entries.forEach { p ->
            FilterChip(selected = vm.provider == p, onClick = { vm.provider = p },
                label = { Text(if (p.freeTier) "${p.label} ✦" else p.label) })
        }
    }
}

@Composable
private fun StepConnect(vm: OnboardingViewModel) {
    var athleteId by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    val status by vm.intervalsStatus.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    SectionCard(title = "Connect Intervals.icu (optional)") {
        Text(
            "Links your Amazfit watch (via Zepp → Intervals.icu) so the coach sees your real fitness, HRV and activities, and can push workouts to your watch. You can skip and add this later in Settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(athleteId, { athleteId = it }, label = { Text("Athlete ID (e.g. i123456)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text("API key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { vm.connect(athleteId, key) }, enabled = !busy && athleteId.isNotBlank() && key.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Verify & connect")
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Text("Tip: you can also sync HRV/sleep from your phone via Health Connect in Settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(label: String, options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = selected == o, onClick = { onSelect(o) }, label = { Text(o) }) }
    }
}
