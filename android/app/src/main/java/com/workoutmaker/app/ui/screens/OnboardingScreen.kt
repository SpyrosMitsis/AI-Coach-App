package com.workoutmaker.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workoutmaker.app.data.AppPreferences
import com.workoutmaker.app.data.AppSettings
import com.workoutmaker.app.data.InjuryEntry
import com.workoutmaker.app.data.LlmProvider
import com.workoutmaker.app.data.Race
import com.workoutmaker.app.data.TestKeyRequest
import com.workoutmaker.app.data.ThemeMode
import com.workoutmaker.app.data.ThemePalette
import com.workoutmaker.app.data.TrainingProfile
import com.workoutmaker.app.data.WorkoutRepository
import com.workoutmaker.app.data.deriveLegacyFields
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.app.Activity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalContext
import com.workoutmaker.app.billing.BillingGateway
import com.workoutmaker.app.billing.ProPurchaseResult
import com.workoutmaker.app.billing.purchaseAndVerify
import com.workoutmaker.app.calendar.DeviceCalendarManager
import com.workoutmaker.app.data.Periodization
import com.workoutmaker.app.data.PlanWeekRequest
import com.workoutmaker.app.data.PlanWeekResult
import com.workoutmaker.app.health.HealthConnectManager
import com.workoutmaker.app.notify.vibrateCelebrate
import com.workoutmaker.app.ui.components.BreathingBackdrop
import com.workoutmaker.app.ui.components.Confetti
import com.workoutmaker.app.ui.components.SectionLabel
import com.workoutmaker.app.ui.components.rememberAnimationsEnabled
import com.workoutmaker.app.util.AppLog
import com.workoutmaker.app.util.friendlyFnError
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

// The onboarding steps, in order. RACE and EQUIPMENT are conditional; the rest
// always show. Conditional steps come AFTER the step that decides them (SPORTS),
// so the visible-step index of the current screen never shifts under the user.
internal enum class ObStep { WELCOME, APPEARANCE, PERSONAL, SPORTS, ACTIVITY, PERFORMANCE, RACE, AVAILABILITY, EFFORT, EQUIPMENT, INJURIES, COACH, CONNECT, PERMISSIONS, REVIEW }

// A step, plus (for ACTIVITY) which sport it asks about.
internal data class StepSpec(val kind: ObStep, val sport: String? = null)

internal fun visibleSteps(p: TrainingProfile): List<StepSpec> = buildList {
    add(StepSpec(ObStep.WELCOME)) // includes the theme picker (was its own step)
    add(StepSpec(ObStep.PERSONAL))
    add(StepSpec(ObStep.SPORTS))
    // Import before asking: a connected watch answers the zones/thresholds
    // questions the later steps would otherwise pose, so CONNECT comes early.
    add(StepSpec(ObStep.CONNECT))
    // One guided step per selected activity (goal + level; the gym adds its split).
    SPORTS.filter { p.sports.contains(it) }.forEach { add(StepSpec(ObStep.ACTIVITY, it)) }
    // "Your numbers": optional performance anchors, one section per sport.
    if (p.sports.isNotEmpty()) add(StepSpec(ObStep.PERFORMANCE))
    if (shouldAskGoalRace(p)) add(StepSpec(ObStep.RACE))
    add(StepSpec(ObStep.AVAILABILITY))
    // Effort + progression get their own page: they price themselves from the
    // week just chosen, and burying them under the day picker hid the choice.
    add(StepSpec(ObStep.EFFORT))
    if (sportNeedsEquipment(p.sports)) add(StepSpec(ObStep.EQUIPMENT))
    add(StepSpec(ObStep.INJURIES))
    add(StepSpec(ObStep.COACH))
    // Permissions last, right before Review: by here the user has seen what the
    // readiness score, the planner and the rest timer actually do, so "why does
    // it want my calendar" answers itself instead of being a cold ask up front.
    add(StepSpec(ObStep.PERMISSIONS))
    add(StepSpec(ObStep.REVIEW))
}

private fun stepTitle(spec: StepSpec): String = when (spec.kind) {
    ObStep.WELCOME -> "Welcome"
    ObStep.APPEARANCE -> "Make it yours"
    ObStep.PERSONAL -> "About you"
    ObStep.SPORTS -> "What you train"
    ObStep.ACTIVITY -> spec.sport?.let { sportLabel(it) } ?: "Your training"
    ObStep.PERFORMANCE -> "Your numbers"
    ObStep.RACE -> "Your goal race"
    ObStep.AVAILABILITY -> "Your week"
    ObStep.EFFORT -> "How hard to go"
    ObStep.EQUIPMENT -> "Your equipment"
    ObStep.INJURIES -> "Injuries"
    ObStep.COACH -> "Your AI coach"
    ObStep.CONNECT -> "Connect your watch"
    ObStep.PERMISSIONS -> "Permissions"
    ObStep.REVIEW -> "Review & finish"
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val repo: WorkoutRepository,
    private val prefs: AppPreferences,
    private val billing: BillingGateway,
    private val health: HealthConnectManager,
    private val deviceCalendar: DeviceCalendarManager,
) : ViewModel() {
    val complete = MutableStateFlow<Boolean?>(null)
    val step = MutableStateFlow(0)
    val profile = MutableStateFlow(TrainingProfile())
    val keyStatus = MutableStateFlow<String?>(null)
    val intervalsStatus = MutableStateFlow<String?>(null)
    val finishStatus = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    var provider by mutableStateOf(LlmProvider.GROQ)

    // Appearance step: same device-local theme prefs the Settings screen edits.
    val appSettings = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    fun setThemeMode(m: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(m) }
    fun setThemePalette(p: ThemePalette) = viewModelScope.launch { prefs.setThemePalette(p) }

    // Permissions step: the same managers Settings uses, so "granted" here and
    // "granted" there can never disagree.
    val healthAvailable: Boolean get() = health.isAvailable
    val healthPermissions: Set<String> get() = health.permissions
    suspend fun grantedHealthPerms(): Set<String> = health.grantedPermissions()
    fun calendarReadGranted() = deviceCalendar.hasReadPermission()
    fun calendarWriteGranted() = deviceCalendar.hasWritePermission()
    fun setCalendarRead(on: Boolean) = viewModelScope.launch { prefs.setCalendarRead(on) }
    fun setCalendarWrite(on: Boolean) = viewModelScope.launch { prefs.setCalendarWrite(on) }

    // Zero-setup Pro path: shown only when this build can bill AND this server
    // hosts an LLM key. The one summary fetch also warms the Room cache.
    val hostedAvailable = MutableStateFlow(false)
    val proActive = MutableStateFlow(false)
    val proBusy = MutableStateFlow(false)
    val proError = MutableStateFlow<String?>(null)

    init {
        recheck()
    }

    // Billing state is PER ACCOUNT: it must reload every time the signed-in
    // user changes, not once per ViewModel. Fetching it only in init meant a
    // fresh sign-up on a device whose previous account had Pro saw "Pro is
    // active" during onboarding, while actually being free.
    private fun refreshBilling() {
        hostedAvailable.value = false
        proActive.value = false
        if (!billing.supported) return
        viewModelScope.launch {
            runCatching {
                val summary = repo.dailySummary()
                hostedAvailable.value = summary.server?.hosted_ai == true
                proActive.value = repo.planStatus().isPro
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
        refreshBilling()
    }

    fun buyPro(activity: Activity) = viewModelScope.launch {
        proBusy.value = true
        proError.value = null
        when (val r = purchaseAndVerify(activity, billing, repo)) {
            // Success only means the server VERIFIED the token, not that it granted
            // Pro: a pending/on-hold purchase verifies fine and still returns "free".
            // Re-read the plan columns rather than assuming (Settings does the same).
            is ProPurchaseResult.Success -> {
                proActive.value = repo.planStatus().isPro
                if (!proActive.value) {
                    proError.value = "Google Play is still confirming your purchase. " +
                        "Pro switches on by itself once it clears."
                }
            }
            is ProPurchaseResult.Cancelled -> Unit
            is ProPurchaseResult.Failed -> proError.value = r.message
        }
        proBusy.value = false
    }

    // First-week preview on the review step: the payoff moment. plan-week reads
    // the profile from the DB, so save the in-progress answers first — safe,
    // because OnboardingGate keys on OUR `complete` flag (set only in finish()),
    // not the DB column, so the screen stays put. push=false: no watch spam
    // before the athlete has even entered the app.
    val previewWeek = MutableStateFlow<PlanWeekResult?>(null)
    val previewBusy = MutableStateFlow(false)
    val previewError = MutableStateFlow<String?>(null)
    fun previewFirstWeek() = viewModelScope.launch {
        previewBusy.value = true
        previewError.value = null
        runCatching {
            repo.saveProfile(profile.value.deriveLegacyFields())
            repo.planWeek(
                PlanWeekRequest(
                    start_date = LocalDate.now().toString(),
                    push = false,
                ),
            )
        }.onSuccess { previewWeek.value = it }
            .onFailure {
                AppLog.w("onboarding", "preview failed", it)
                previewError.value = friendlyFnError(
                    it, "Couldn't build the preview. Finish setup and plan from the app instead.",
                )
            }
        previewBusy.value = false
    }

    fun update(t: (TrainingProfile) -> TrainingProfile) { profile.value = t(profile.value) }
    fun goNext(lastIndex: Int) { step.value = (step.value + 1).coerceAtMost(lastIndex) }
    fun goBack() { step.value = (step.value - 1).coerceAtLeast(0) }

    // The goal race is stored in the races table (safe mid-onboarding), but the
    // goal date/pace is set LOCALLY on the in-progress profile — NOT saved yet.
    // Calling repo.setGoalRace here would flip onboarding_complete early and wipe
    // the in-progress answers, so finish() is the single persist point.
    fun addGoalRace(race: Race, setGoal: Boolean) = viewModelScope.launch {
        runCatching { repo.addRace(race) }
        if (setGoal) {
            val pace = race.target?.takeIf { race.sport == "run" && it.isNotBlank() }
            update { it.copy(goal_date = race.date, target_pace = pace ?: it.target_pace) }
        }
    }

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

    // Set between a successful save and actually entering the app, so the
    // celebration has a moment to play. OnboardingGate swaps this screen out the
    // instant `complete` flips, which would otherwise kill the animation on the
    // frame it started. Only ever set here, on an explicit Finish: a cold-start
    // restore goes through recheck(), so returning users never see confetti.
    val celebrating = MutableStateFlow(false)

    // [celebrate] is false when skipping setup (nothing to celebrate) and when the
    // user has animations turned off, in which case we enter the app immediately
    // rather than make them wait out an animation they will never see.
    fun finish(celebrate: Boolean = false) = viewModelScope.launch {
        busy.value = true
        finishStatus.value = null
        // Derive the single-value fields the live backend reads from the rich ones.
        runCatching { repo.saveProfile(profile.value.deriveLegacyFields()) }
            // saveProfile flips onboarding_complete → enters the app. Entering
            // without the save would leave every downstream feature profileless.
            .onSuccess {
                if (celebrate) {
                    celebrating.value = true
                    delay(CELEBRATION_MS)
                }
                complete.value = true
            }
            .onFailure { finishStatus.value = "Couldn't save your profile: ${it.message}. Check your connection and try again." }
        busy.value = false
    }

    private companion object {
        // Long enough for Confetti's DURATION_MS burst to play out fully (it is
        // deliberately shorter), short enough not to feel like a loading screen.
        const val CELEBRATION_MS = 2_400L
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(vm: OnboardingViewModel = hiltViewModel()) {
    val stepIndex by vm.step.collectAsStateSafe()
    val profile by vm.profile.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    val finishStatus by vm.finishStatus.collectAsStateSafe()

    val celebrating by vm.celebrating.collectAsStateSafe()

    val steps = remember(profile.sports, profile.goals_by_sport) { visibleSteps(profile) }
    val idx = stepIndex.coerceIn(0, steps.lastIndex)
    val current = steps[idx]
    val animationsOn = rememberAnimationsEnabled()

    Box(Modifier.fillMaxSize()) {
        BreathingBackdrop(Modifier.fillMaxSize(), intensity = 0.6f)
        // Onboarding renders OUTSIDE MainScaffold (see AuthGate.OnboardingGate), so it
        // inherits none of the Scaffold's window insets — and targetSdk 35 forces
        // edge-to-edge. Inset the content only; the backdrop above stays full-bleed.
        // Fixed header and fixed nav buttons; only the step content scrolls.
        // Back/Next stop drifting with each step's height, and the thumb always
        // finds them in the same place.
        var confirmSkip by remember { mutableStateOf(false) }
        if (confirmSkip) {
            AlertDialog(
                onDismissRequest = { confirmSkip = false },
                title = { Text("Skip setup?") },
                text = {
                    Text(
                        "Without your sports, week and goals, the coach plans from generic " +
                            "defaults instead of you. You can finish setup anytime in Settings.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmSkip = false; vm.finish() }) { Text("Skip anyway") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmSkip = false }) { Text("Keep going") }
                },
            )
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "STEP ${idx + 1} OF ${steps.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(stepTitle(current), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                if (current.kind != ObStep.REVIEW) {
                    TextButton(onClick = { confirmSkip = true }) { Text("Skip") }
                }
            }
            StepDots(idx, total = steps.size, modifier = Modifier.padding(vertical = 14.dp))

            // Directional slide between steps, in the app's tween(240) language.
            // Each step scrolls independently (state is per AnimatedContent slot,
            // so a new step always starts at the top).
            AnimatedContent(
                targetState = idx,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = targetState > initialState
                    val enter = slideInHorizontally(tween(240)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(240))
                    val exit = slideOutHorizontally(tween(240)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(150))
                    enter.togetherWith(exit)
                },
                label = "onboardingStep",
            ) { i ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    val spec = steps.getOrElse(i) { steps.last() }
                    when (spec.kind) {
                        ObStep.WELCOME -> StepWelcome(vm)
                        ObStep.APPEARANCE -> StepAppearance(vm)
                        ObStep.PERSONAL -> StepPersonal(profile, vm)
                        ObStep.SPORTS -> StepSports(profile, vm)
                        ObStep.ACTIVITY -> StepActivity(spec.sport ?: "strength", profile, vm)
                        ObStep.PERFORMANCE -> StepPerformance(profile, vm)
                        ObStep.RACE -> StepRace(profile, vm)
                        ObStep.AVAILABILITY -> StepAvailability(profile, vm)
                        ObStep.EFFORT -> StepEffort(profile, vm)
                        ObStep.EQUIPMENT -> StepEquipment(profile, vm)
                        ObStep.INJURIES -> StepInjuries(profile, vm)
                        ObStep.COACH -> StepKey(vm)
                        ObStep.CONNECT -> StepConnect(vm)
                        ObStep.PERMISSIONS -> StepPermissions(vm)
                        ObStep.REVIEW -> StepReview(profile, vm)
                    }
                    Spacer16()
                }
            }

            finishStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (idx > 0) OutlinedButton(onClick = { vm.goBack() }, modifier = Modifier.weight(1f)) { Text("Back") }
                if (current.kind != ObStep.REVIEW) {
                    Button(onClick = { vm.goNext(steps.lastIndex) }, modifier = Modifier.weight(1f)) { Text("Next") }
                } else {
                    Button(
                        onClick = { vm.finish(celebrate = animationsOn) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (busy) "Saving…" else "Finish")
                    }
                }
            }
        }
        // Above the content and full-bleed, so it falls past the insets too.
        // The buzz belongs to the celebration moment, not the drawing: with
        // animations off, finish() never celebrates, so neither fires.
        val ctx = LocalContext.current
        LaunchedEffect(celebrating) {
            if (celebrating) vibrateCelebrate(ctx)
        }
        Confetti(playing = celebrating)
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

@Composable private fun Spacer16() = Spacer(Modifier.padding(8.dp))

// Frameless step body: content flows on the backdrop with steady spacing (no
// boxing card, no repeated title — the header above already names the step).
@Composable
private fun StepColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

// --- Steps -----------------------------------------------------------------

@Composable
private fun StepWelcome(vm: OnboardingViewModel) {
    val s by vm.appSettings.collectAsStateSafe()
    StepColumn {
        Text(
            "Let's set up your coach",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "A few quick steps and your AI coach will plan training that fits your goals, " +
                "your week and your equipment. Everything here can be changed later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The theme picker rides along here (it used to be a whole step): one
        // fewer screen between the athlete and the questions that matter.
        AppearancePicker(
            themeMode = s.themeMode,
            palette = s.themePalette,
            onMode = { vm.setThemeMode(it) },
            onPalette = { vm.setThemePalette(it) },
        )
    }
}

@Composable
private fun StepAppearance(vm: OnboardingViewModel) {
    val s by vm.appSettings.collectAsStateSafe()
    StepColumn {
        AppearancePicker(
            themeMode = s.themeMode,
            palette = s.themePalette,
            onMode = { vm.setThemeMode(it) },
            onPalette = { vm.setThemePalette(it) },
        )
    }
}

@Composable
private fun StepPersonal(profile: TrainingProfile, vm: OnboardingViewModel) {
    val year = LocalDate.now().year
    StepColumn {
        Text(
            "This tunes training load, recovery and intensity to you. All optional, but it helps the coach calibrate.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            profile.display_name ?: "",
            { v -> vm.update { it.copy(display_name = v.ifBlank { null }) } },
            label = { Text("Your name") },
            placeholder = { Text("What should the coach call you?") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        // "Other" is stored but the backend only uses M/F for demographics, so
        // it reads as "not stated" downstream — the honest treatment.
        ChipGroup("Sex", listOf("Male", "Female", "Other"), profile.sex?.replaceFirstChar { c -> c.uppercase() }) { s ->
            vm.update { it.copy(sex = if (it.sex == s.lowercase()) null else s.lowercase()) }
        }
        // Single-word labels + unit suffixes keep all three boxes the same height.
        // Soft validation: an out-of-range value shows red but never blocks —
        // the guard is against typos (SetSanity philosophy), not the athlete.
        val ageBad = profile.birth_year?.let { (year - it) !in 10..100 } == true
        val heightBad = profile.height_cm?.let { it !in 100..230 } == true
        val weightBad = profile.weight_kg?.let { it !in 30..250 } == true
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                profile.birth_year?.let { (year - it).toString() } ?: "",
                { v -> vm.update { it.copy(birth_year = v.toIntOrNull()?.let { a -> year - a }) } },
                label = { Text("Age") }, singleLine = true, isError = ageBad,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                profile.height_cm?.toString() ?: "", { v -> vm.update { it.copy(height_cm = v.toIntOrNull()) } },
                label = { Text("Height") }, suffix = { Text("cm") }, singleLine = true, isError = heightBad,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                profile.weight_kg?.toString() ?: "", { v -> vm.update { it.copy(weight_kg = v.toIntOrNull()) } },
                label = { Text("Weight") }, suffix = { Text("kg") }, singleLine = true, isError = weightBad,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
        }
        if (ageBad || heightBad || weightBad) {
            Text(
                "That looks like a typo, double-check the highlighted field.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StepSports(profile: TrainingProfile, vm: OnboardingViewModel) {
    StepColumn {
        SportSelector(profile.sports) { s -> vm.update { it.copy(sports = it.sports.toggled(s)) } }
    }
}

// One activity's own questions: its goal(s) + level (+ split for the gym).
@Composable
private fun StepActivity(sport: String, profile: TrainingProfile, vm: OnboardingViewModel) {
    StepColumn {
        SportGoalsLevel(
            sport = sport,
            goals = profile.goals_by_sport[sport].orEmpty(),
            level = profile.experience_by_sport[sport],
            splitStyle = profile.split_style,
            onGoalToggle = { g -> vm.update { it.copy(goals_by_sport = it.goals_by_sport.toggleIn(sport, g)) } },
            onLevel = { lvl -> vm.update { it.copy(experience_by_sport = it.experience_by_sport + (sport to lvl)) } },
            onSplit = { s -> vm.update { it.copy(split_style = if (s == "Auto") null else s) } },
        )
    }
}

@Composable
private fun StepPerformance(profile: TrainingProfile, vm: OnboardingViewModel) {
    val intervalsConnected = (vm.intervalsStatus.collectAsStateSafe().value ?: "").startsWith("\u2713")
    StepColumn {
        PerformanceEditor(
            profile = profile,
            intervalsConnected = intervalsConnected,
            onUpdate = { t -> vm.update(t) },
        )
    }
}

@Composable
private fun StepRace(profile: TrainingProfile, vm: OnboardingViewModel) {
    var showAdd by remember { mutableStateOf(false) }
    if (showAdd) AddRaceDialog(onClose = { showAdd = false }) { race, setGoal ->
        vm.addGoalRace(race, setGoal); showAdd = false
    }
    StepColumn {
        Text(
            "Optional. Set the event you're building toward and your A-goal drives periodization and the taper. You can add or change it later in Settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val goalDate = profile.goal_date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (goalDate != null) {
            // The race as a real card: date, phase countdown, target pace, and
            // clear Change/Remove actions instead of one ambiguous button.
            val phase = Periodization.phaseFor(goalDate, LocalDate.now())
            SectionCard {
                SectionLabel("YOUR GOAL RACE")
                Text(
                    goalDate.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    phase.weeksToGoal?.let { "${phase.name} phase · $it week${if (it == 1) "" else "s"} to go" }
                        ?: "Race day has passed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                profile.target_pace?.let {
                    Text(
                        "Target pace $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.weight(1f)) { Text("Change") }
                    TextButton(onClick = { vm.update { it.copy(goal_date = null, target_pace = null) } }) {
                        Text("Remove")
                    }
                }
            }
        } else {
            OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Add goal race")
            }
        }
    }
}

@Composable
private fun StepAvailability(profile: TrainingProfile, vm: OnboardingViewModel) {
    StepColumn {
        WeeklyAvailabilityEditor(profile.day_availability) { list -> vm.update { it.copy(day_availability = list) } }
    }
}

// Weekly effort + progression, on their own page: the chips price themselves
// from the week chosen on the previous step (Periodization.availabilityCeiling
// mirrors the server's clamp), so the numbers are always achievable and never
// TSS jargon, and the chart shows what the chosen effort does over the weeks.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepEffort(profile: TrainingProfile, vm: OnboardingViewModel) {
    StepColumn {
        val minutes = profile.day_availability.sumOf { it.max_minutes }
        val ceiling = Periodization.availabilityCeiling(minutes)
        if (ceiling == null) {
            Text(
                "Set your training days on the previous step first, this page sizes the effort options to the time you actually have.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "How hard should your weeks be?",
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Periodization.Effort.entries.forEach { e ->
                    val target = e.targetFor(ceiling)
                    FilterChip(
                        selected = profile.weekly_tss_target == target,
                        onClick = { vm.update { it.copy(weekly_tss_target = target) } },
                        label = { Text("${e.label} · ~$target TSS") },
                    )
                }
            }
            Text(
                "Based on your ${minutes / 60}h ${minutes % 60}min week. The coach plans toward this; change it anytime in Settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Progression lives right under the effort choice so the chart can
            // show what THAT choice does over the weeks — one story, in order.
            PeriodizationControl(
                periodized = profile.periodized,
                onChange = { p -> vm.update { it.copy(periodized = p) } },
                weeklyTssTarget = profile.weekly_tss_target,
            )
        }
    }
}

@Composable
private fun StepEquipment(profile: TrainingProfile, vm: OnboardingViewModel) {
    StepColumn {
        Text(
            "Pick what you can train with, the coach only prescribes lifts your kit supports.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EquipmentSelector(profile.equipment_list) { e -> vm.update { it.copy(equipment_list = it.equipment_list.toggled(e)) } }
    }
}

// Injuries / niggles the coach should train around. Quick-add chips append to
// the free text; tapping a present chip removes it.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepInjuries(profile: TrainingProfile, vm: OnboardingViewModel) {
    StepColumn {
        Text(
            "Anything the coach should train around? Injuries, niggles or areas to protect. " +
                "The coach avoids loading these and picks safer alternatives.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InjuryEditor(profile.injuries) { v -> vm.update { it.copy(injuries = v) } }
    }
}

// Shared injuries editor (onboarding + Settings → Injuries & constraints):
// area chips add/remove, explicit per-area severity chips, free text for the
// rest. Edits profile.injuries directly (structured — see InjuryEntry in
// Models.kt), so the backend's safety engine can match on `area` instead of
// regexing a flattened string, and can use `severity` to gate strip-vs-flag.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InjuryEditor(injuries: List<InjuryEntry>, onChange: (List<InjuryEntry>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        INJURY_AREAS.forEach { area ->
            FilterChip(
                selected = injurySeverity(injuries, area) != null,
                onClick = { onChange(toggleInjury(injuries, area)) },
                label = { Text(area) },
            )
        }
    }
    // Severity is its own explicit control per selected area — the old
    // tap-again-to-cycle pattern was invisible and nobody used it.
    val selected = INJURY_AREAS.filter { injurySeverity(injuries, it) != null }
    if (selected.isNotEmpty()) {
        Text(
            "How serious is it?",
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        )
        selected.forEach { area ->
            val severity = injurySeverity(injuries, area)
            Text(area, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                INJURY_SEVERITIES.filter { it.isNotEmpty() }.forEach { s ->
                    FilterChip(
                        selected = severity == s,
                        onClick = { onChange(setInjurySeverity(injuries, area, if (severity == s) "" else s)) },
                        label = { Text(s.replaceFirstChar { c -> c.uppercase() }) },
                    )
                }
            }
        }
        Text(
            "The more serious it is, the more strictly the coach protects it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedTextField(
        injuryNote(injuries),
        { v -> onChange(withNote(injuries, v)) },
        label = { Text("Injuries / notes (optional)") },
        modifier = Modifier.fillMaxWidth(),
    )
}

private val INJURY_SEVERITIES = listOf("", "mild", "moderate", "serious")

// Freeform notes (the text field below the chips) live in one entry with a
// blank area — not a body-area chip — so they still ride along to the backend
// (activeSafetyRules matches rule patterns against `note` too) without forcing
// arbitrary text into the structured `area` field.
private const val NOTE_AREA = ""

/** Current severity for [area]: null = not present, "" = present unqualified. */
internal fun injurySeverity(current: List<InjuryEntry>, area: String): String? =
    current.firstOrNull { it.area.equals(area, ignoreCase = true) }?.severity

/** Add [area] (unqualified) when absent, remove it entirely when present. */
internal fun toggleInjury(current: List<InjuryEntry>, area: String): List<InjuryEntry> =
    if (injurySeverity(current, area) != null) current.filterNot { it.area.equals(area, ignoreCase = true) }
    else current + InjuryEntry(area = area)

/** Set [area]'s severity ("" clears the qualifier, keeping the area listed). */
internal fun setInjurySeverity(current: List<InjuryEntry>, area: String, severity: String): List<InjuryEntry> =
    if (current.any { it.area.equals(area, ignoreCase = true) }) {
        current.map { if (it.area.equals(area, ignoreCase = true)) it.copy(severity = severity) else it }
    } else {
        current + InjuryEntry(area = area, severity = severity)
    }

internal fun injuryNote(current: List<InjuryEntry>): String =
    current.firstOrNull { it.area == NOTE_AREA }?.note ?: ""

internal fun withNote(current: List<InjuryEntry>, note: String): List<InjuryEntry> {
    val rest = current.filterNot { it.area == NOTE_AREA }
    return if (note.isBlank()) rest else rest + InjuryEntry(area = NOTE_AREA, note = note)
}

/** Human-readable summary for review/settings screens, e.g. "Knee (moderate); old ankle sprain". */
internal fun injuriesSummary(current: List<InjuryEntry>): String =
    current.joinToString("; ") { e ->
        when {
            e.area == NOTE_AREA -> e.note.orEmpty()
            e.severity.isNotEmpty() -> "${e.area} (${e.severity})"
            else -> e.area
        }
    }

@Composable
private fun StepReview(profile: TrainingProfile, vm: OnboardingViewModel) {
    StepColumn {
        Text("You're all set", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        profile.display_name?.takeIf { it.isNotBlank() }?.let { ReviewLine("Name", it) }
        val trained = SPORTS.filter { profile.sports.contains(it) }
        if (trained.isEmpty()) {
            ReviewLine("Activities", "-")
        } else trained.forEach { sport ->
            val goals = profile.goals_by_sport[sport].orEmpty().joinToString(", ")
            val level = profile.experience_by_sport[sport]
            val detail = listOfNotNull(goals.ifBlank { null }, level).joinToString(" · ").ifBlank { "-" }
            ReviewLine(sportLabel(sport), detail)
        }
        if (profile.day_availability.isNotEmpty()) {
            val q = availabilityToQuestions(profile.day_availability)
            val summary = "${q.daysPerWeek} days/wk · ${durationLabel(q.typicalMin)}" +
                (q.longDays.takeIf { it.isNotEmpty() }
                    ?.let { " · long ${it.joinToString("+")} ${durationLabel(q.longMin)}" } ?: "")
            ReviewLine("Availability", summary)
        }
        val numbers = listOfNotNull(
            profile.threshold_pace_per_km?.let { "threshold $it/km" },
            profile.ftp?.let { "FTP ${it}w" },
            profile.css_per_100m?.let { "CSS $it/100m" },
            profile.lthr?.let { "LTHR $it" },
            profile.starting_lifts.takeIf { it.isNotEmpty() }?.let { "${it.size} lifts" },
        )
        if (numbers.isNotEmpty()) ReviewLine("Your numbers", numbers.joinToString(" · "))
        profile.weekly_tss_target?.let { ReviewLine("Weekly load", "~$it TSS") }
        if (profile.sports.contains("strength")) {
            ReviewLine("Progression", if (profile.periodized) "Periodized" else "Steady")
        }
        if (profile.equipment_list.isNotEmpty()) {
            ReviewLine("Equipment", profile.equipment_list.joinToString(", "))
        }
        injuriesSummary(profile.injuries).takeIf { it.isNotBlank() }?.let { ReviewLine("Injuries", it) }
        profile.goal_date?.let { ReviewLine("Goal race", it) }

        // The payoff: build the actual first week from these answers, right
        // here — setup stops being a form and becomes a result.
        val preview by vm.previewWeek.collectAsStateSafe()
        val previewBusy by vm.previewBusy.collectAsStateSafe()
        val previewError by vm.previewError.collectAsStateSafe()
        when {
            preview != null -> SectionCard {
                SectionLabel("YOUR FIRST WEEK")
                preview!!.week_focus?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                preview!!.days.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            d.weekday,
                            Modifier.width(44.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (d.type == "rest") "Rest" else d.title,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                        if (d.tss > 0) {
                            Text(
                                "${d.tss} TSS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "Already on your calendar. Tap Finish and start training.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                )
            }
            previewBusy -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp,
                )
                Text(
                    "Building your first week (about 30 seconds)…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            else -> {
                previewError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(
                    onClick = { vm.previewFirstWeek() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Build my first week now") }
            }
        }
        Text(
            "Tap Finish to save. You can fine-tune everything later in Settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ReviewLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

// --- Coach AI (Pro / bring-your-own key) + Intervals, unchanged from before -

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
    val context = LocalContext.current

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
                onClick = { (context as? Activity)?.let { vm.buyPro(it) } },
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
        Text(
            "Later, when you generate an outdoor workout, the app may ask for coarse location once, " +
                "only to factor in today's weather (heat, rain, wind). It's optional and everything " +
                "works without it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
