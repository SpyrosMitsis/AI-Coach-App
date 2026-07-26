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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.workoutmaker.app.data.TrainingProfile
import com.workoutmaker.app.ui.collectAsStateSafe
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.platform.LocalContext
import com.workoutmaker.app.notify.vibrateCelebrate
import com.workoutmaker.app.ui.components.BreathingBackdrop
import com.workoutmaker.app.ui.components.Confetti
import com.workoutmaker.app.ui.components.rememberAnimationsEnabled

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

@Composable internal fun Spacer16() = Spacer(Modifier.padding(8.dp))

// Frameless step body: content flows on the backdrop with steady spacing (no
// boxing card, no repeated title — the header above already names the step).
@Composable
internal fun StepColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

// --- Steps -----------------------------------------------------------------























// --- Coach AI (Pro / bring-your-own key) + Intervals, unchanged from before -
