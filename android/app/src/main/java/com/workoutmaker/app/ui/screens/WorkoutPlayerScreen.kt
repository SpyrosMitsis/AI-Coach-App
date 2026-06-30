package com.workoutmaker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workoutmaker.app.data.WorkoutPlayerHolder
import com.workoutmaker.app.data.WorkoutRepository
import com.workoutmaker.app.data.Workout
import com.workoutmaker.app.data.Zones
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.GhostButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// One thing the athlete does right now: a timed interval, or a strength set.
data class PlayerStep(
    val section: String,
    val name: String,
    val durationSec: Int?, // null = manual (rep/strength step, advance by tap)
    val detail: String?,   // target pace/HR/weight/reps
    val setLabel: String?, // "Set 2 of 5" / "Rep 3 of 8"
)

// Flatten the workout's sections/exercises into the ordered step list the player
// walks through. Duration steps (e.g. "3 min") expand to one timed step per set;
// strength steps expand to one manual step per set.
internal fun flattenWorkout(w: Workout, thresholdSecPerKm: Int?, lthr: Int?): List<PlayerStep> {
    val steps = mutableListOf<PlayerStep>()
    for (sec in w.sections) {
        for (ex in sec.exercises) {
            val dur = Zones.parseDurationSec(ex.reps)
            val target = Zones.targetRange(ex.pace_zone, ex.hr_zone, thresholdSecPerKm, lthr)
            val n = ex.sets.coerceAtLeast(1)
            if (dur != null) {
                for (k in 1..n) steps.add(PlayerStep(sec.name, ex.name, dur, target, if (n > 1) "Rep $k of $n" else null))
            } else {
                val repText = ex.reps.takeIf { it.isNotBlank() }?.let { "$it reps" }
                val wt = ex.weight_kg?.let { "${trimKg(it)} kg" }
                val detail = listOfNotNull(repText, wt, target).joinToString(" · ").ifBlank { null }
                for (k in 1..n) steps.add(PlayerStep(sec.name, ex.name, null, detail, if (n > 1) "Set $k of $n" else null))
            }
        }
    }
    return steps
}

@HiltViewModel
class WorkoutPlayerViewModel @Inject constructor(
    private val holder: WorkoutPlayerHolder,
    private val repo: WorkoutRepository,
) : ViewModel() {
    val workout = holder.workout
    val steps = MutableStateFlow<List<PlayerStep>>(emptyList())
    val ready = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val profile = runCatching { repo.loadProfile() }.getOrNull()
            val thr = profile?.threshold_pace_per_km?.let { Zones.parsePace(it) }
            steps.value = holder.workout.value?.let { flattenWorkout(it, thr, profile?.lthr) } ?: emptyList()
            ready.value = true
        }
    }
}

private fun mmss(sec: Int): String = "${sec / 60}:${"%02d".format(sec % 60)}"

@Composable
fun WorkoutPlayerScreen(onExit: () -> Unit, vm: WorkoutPlayerViewModel = hiltViewModel()) {
    val workout by vm.workout.collectAsStateSafe()
    val steps by vm.steps.collectAsStateSafe()
    val ready by vm.ready.collectAsStateSafe()
    val haptics = LocalHapticFeedback.current

    BackHandler { onExit() }

    // Audio + haptic cues so the athlete can keep their eyes off the phone. Uses
    // the ALARM stream at full volume so the end-of-interval cue is actually heard
    // over music / a low media volume / a pocket.
    val tone = remember { android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100) }
    DisposableEffect(Unit) { onDispose { runCatching { tone.release() } } }
    fun beep(type: Int, ms: Int) { runCatching { tone.startTone(type, ms) } }
    // Soft single beep when a new step begins / a manual set is up.
    fun cue() {
        beep(android.media.ToneGenerator.TONE_PROP_BEEP, 200)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    // Distinct, longer double-beep the moment a timed interval ends.
    fun endCue() {
        beep(android.media.ToneGenerator.TONE_PROP_BEEP2, 750)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    var idx by remember { mutableIntStateOf(0) }
    var remaining by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(true) }
    var finished by remember { mutableStateOf(false) }

    val step = steps.getOrNull(idx)

    fun goTo(next: Int) {
        if (next >= steps.size) { finished = true; endCue(); return }
        idx = next.coerceAtLeast(0)
        running = true
    }

    // Reset the countdown when we land on a step. Announce the first step and every
    // manual (strength) step; timed steps after the first were already announced by
    // the previous interval's end cue, so we don't double up.
    LaunchedEffect(idx, steps) {
        val s = steps.getOrNull(idx) ?: return@LaunchedEffect
        remaining = s.durationSec ?: 0
        if (steps.isNotEmpty() && (idx == 0 || s.durationSec == null)) cue()
    }
    // Tick the timed step; beep each of the final 3 seconds, then a distinct end
    // cue, then auto-advance.
    LaunchedEffect(idx, running, steps) {
        val s = steps.getOrNull(idx) ?: return@LaunchedEffect
        if (s.durationSec == null) return@LaunchedEffect
        while (running && remaining > 0) {
            delay(1000)
            remaining -= 1
            if (remaining in 1..3) beep(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
        }
        if (running && remaining <= 0) { endCue(); goTo(idx + 1) }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    workout?.title ?: "Workout",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onExit) { Icon(Icons.Filled.Close, "Close player") }
            }

            when {
                !ready -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Preparing…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                steps.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nothing to play in this workout.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onExit) { Text("Back") }
                    }
                }
                finished -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Workout complete 🎉", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Mark it done back on Home to log how it felt.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = onExit) { Text("Done") }
                    }
                }
                step != null -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            step.setLabel?.let {
                                Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                step.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            step.detail?.let {
                                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (step.durationSec != null) {
                                Text(
                                    mmss(remaining),
                                    fontSize = 72.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    "Tap Done when you finish the set",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // Controls.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(onClick = { goTo(idx - 1) }, modifier = Modifier.weight(1f)) { Text("Previous") }
                        if (step.durationSec != null) {
                            Button(onClick = { running = !running }, modifier = Modifier.weight(1f)) {
                                Text(if (running) "Pause" else "Resume")
                            }
                        }
                        Button(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                goTo(idx + 1)
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (step.durationSec != null) "Skip" else "Done") }
                    }
                }
            }
        }
    }
}
