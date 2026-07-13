package com.workoutmaker.app.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
class OnboardingViewModel @Inject constructor(
    private val repo: WorkoutRepository,
    private val billing: com.workoutmaker.app.billing.BillingGateway,
) : ViewModel() {
    val complete = MutableStateFlow<Boolean?>(null)
    val step = MutableStateFlow(0)
    val profile = MutableStateFlow(TrainingProfile())
    val keyStatus = MutableStateFlow<String?>(null)
    val intervalsStatus = MutableStateFlow<String?>(null)
    val finishStatus = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    var provider by androidx.compose.runtime.mutableStateOf(LlmProvider.GROQ)

    // Zero-setup Pro path: shown only when this build can bill AND this server
    // hosts an LLM key. The one summary fetch also warms the Room cache.
    val hostedAvailable = MutableStateFlow(false)
    val proActive = MutableStateFlow(false)
    val proBusy = MutableStateFlow(false)
    val proError = MutableStateFlow<String?>(null)

    init {
        recheck()
        if (billing.supported) {
            viewModelScope.launch {
                runCatching {
                    val summary = repo.dailySummary()
                    hostedAvailable.value = summary.server?.hosted_ai == true
                    proActive.value = repo.planStatus().isPro
                }
            }
        }
    }
    fun currentUserId(): String? = repo.auth.currentUserOrNull()?.id

    // Re-runs whenever the authenticated user changes (OnboardingGate keys on
    // the uid): first scope-checks local per-account data, then asks the server
    // whether onboarding is done. Never serves a previous user's answer.
    private var checkedUid: String? = null
    fun recheck() = viewModelScope.launch {
        val uid = currentUserId()
        if (uid != checkedUid) complete.value = null
        checkedUid = uid
        repo.ensureAccountScope()
        complete.value = repo.isOnboardingComplete()
    }

    fun buyPro(activity: android.app.Activity) = viewModelScope.launch {
        proBusy.value = true
        proError.value = null
        when (val r = com.workoutmaker.app.billing.purchaseAndVerify(activity, billing, repo)) {
            is com.workoutmaker.app.billing.ProPurchaseResult.Success -> proActive.value = true
            is com.workoutmaker.app.billing.ProPurchaseResult.Cancelled -> Unit
            is com.workoutmaker.app.billing.ProPurchaseResult.Failed -> proError.value = r.message
        }
        proBusy.value = false
    }

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
        finishStatus.value = null
        runCatching { repo.saveProfile(profile.value) }
            // saveProfile flips onboarding_complete → enters the app. Entering
            // without the save would leave every downstream feature profileless.
            .onSuccess { complete.value = true }
            .onFailure { finishStatus.value = "Couldn't save your profile: ${it.message}. Check your connection and try again." }
        busy.value = false
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(vm: OnboardingViewModel = hiltViewModel()) {
    val step by vm.step.collectAsStateSafe()
    val profile by vm.profile.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    val finishStatus by vm.finishStatus.collectAsStateSafe()

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    com.workoutmaker.app.ui.components.BreathingBackdrop(Modifier.fillMaxSize(), intensity = 0.6f)
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Text("Welcome", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text("Let's set up your coach", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        StepDots(step, total = 3, modifier = Modifier.padding(vertical = 14.dp))

        // Directional slide between steps, in the app's tween(240) language.
        androidx.compose.animation.AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                val enter = androidx.compose.animation.slideInHorizontally(
                    androidx.compose.animation.core.tween(240),
                ) { if (forward) it / 3 else -it / 3 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240))
                val exit = androidx.compose.animation.slideOutHorizontally(
                    androidx.compose.animation.core.tween(240),
                ) { if (forward) -it / 3 else it / 3 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))
                enter.togetherWith(exit)
            },
            label = "onboardingStep",
        ) { s ->
            Column {
                when (s) {
                    0 -> StepGoal(profile, vm)
                    1 -> StepKey(vm)
                    else -> StepConnect(vm)
                }
            }
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
        finishStatus?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (step < 2) {
            TextButton(onClick = { vm.finish() }, modifier = Modifier.fillMaxWidth()) { Text("Skip setup for now") }
        }
    }
    }
}

// Step pills: the active one stretches, in the palette's primary.
@Composable
private fun StepDots(step: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val active = i == step
            val width by animateDpAsState(
                targetValue = if (active) 24.dp else 8.dp,
                animationSpec = tween(300),
                label = "dot$i",
            )
            Box(
                Modifier
                    .width(width)
                    .height(8.dp)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(50),
                    ),
            )
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
    val hosted by vm.hostedAvailable.collectAsStateSafe()
    val proActive by vm.proActive.collectAsStateSafe()
    var byoExpanded by rememberSaveable { mutableStateOf(false) }

    if (hosted) {
        ProOnboardingCard(vm)
        Spacer16()
    }
    // A subscribed user never sees key setup unless they ask for it.
    if (!hosted || !proActive || byoExpanded) {
        ByoKeyCard(vm)
    } else {
        TextButton(onClick = { byoExpanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Prefer your own key? Set one up anyway",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// The zero-setup path: subscribe here and skip keys entirely.
@Composable
private fun ProOnboardingCard(vm: OnboardingViewModel) {
    val proActive by vm.proActive.collectAsStateSafe()
    val proBusy by vm.proBusy.collectAsStateSafe()
    val proError by vm.proError.collectAsStateSafe()
    val context = androidx.compose.ui.platform.LocalContext.current

    SectionCard(title = if (proActive) "Pro is active" else "Pro: zero setup") {
        if (proActive) {
            Text(
                "✓ You're set. Your coach and workouts run on our hosted AI, nothing to configure.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                "Skip the API keys. Pro runs the coach and workout generation on a fast hosted model, with a fair-use allowance. It also supports the developer and keeps this app alive. Manage or cancel any time in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { (context as? android.app.Activity)?.let { vm.buyPro(it) } },
                enabled = !proBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (proBusy) "Working…" else "Get Pro") }
            proError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun ByoKeyCard(vm: OnboardingViewModel) {
    var key by rememberSaveable { mutableStateOf("") }
    val status by vm.keyStatus.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    SectionCard(title = "Bring your own key (free)") {
        Text(
            "This app is free and you bring your own LLM key. You pay your provider directly for what you use. Pick one to start.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRowProviders(vm)
        if (vm.provider.freeKeyUrl.isNotBlank()) {
            // Only Groq and Gemini actually have free tiers; don't promise one
            // for the paid providers.
            val keyLabel = if (vm.provider.freeTier) "Get a free key" else "Get an API key"
            Text("$keyLabel: ${vm.provider.freeKeyUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
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
        // Custom (bring-your-own endpoint) needs extra base-URL/model fields, so
        // it's configured later in Settings — not during quick onboarding.
        LlmProvider.entries.filter { it != LlmProvider.CUSTOM }.forEach { p ->
            FilterChip(selected = vm.provider == p, onClick = { vm.provider = p },
                label = { Text(if (p.freeTier) "${p.label} ✦" else p.label) })
        }
    }
}

@Composable
private fun StepConnect(vm: OnboardingViewModel) {
    var athleteId by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
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
