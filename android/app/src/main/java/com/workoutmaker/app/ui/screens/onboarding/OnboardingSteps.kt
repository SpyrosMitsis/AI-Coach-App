package com.workoutmaker.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.Race
import com.workoutmaker.app.data.TrainingProfile
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.SectionCard
import com.workoutmaker.app.data.Periodization
import com.workoutmaker.app.ui.components.SectionLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.workoutmaker.app.ui.screens.settings.AddRaceDialog
import com.workoutmaker.app.ui.screens.settings.AppearancePicker
import com.workoutmaker.app.ui.screens.settings.ChipGroup
import com.workoutmaker.app.ui.screens.settings.EquipmentSelector
import com.workoutmaker.app.ui.screens.settings.PerformanceEditor
import com.workoutmaker.app.ui.screens.settings.PeriodizationControl
import com.workoutmaker.app.ui.screens.settings.SportGoalsLevel
import com.workoutmaker.app.ui.screens.settings.SportSelector
import com.workoutmaker.app.ui.screens.settings.WeeklyAvailabilityEditor
import com.workoutmaker.app.ui.screens.settings.toggleIn
import com.workoutmaker.app.ui.screens.settings.toggled

@Composable
internal fun StepWelcome(vm: OnboardingViewModel) {
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
internal fun StepAppearance(vm: OnboardingViewModel) {
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
internal fun StepPersonal(profile: TrainingProfile, vm: OnboardingViewModel) {
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
internal fun StepSports(profile: TrainingProfile, vm: OnboardingViewModel) {
    StepColumn {
        SportSelector(profile.sports) { s -> vm.update { it.copy(sports = it.sports.toggled(s)) } }
    }
}

// One activity's own questions: its goal(s) + level (+ split for the gym).
@Composable
internal fun StepActivity(sport: String, profile: TrainingProfile, vm: OnboardingViewModel) {
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
internal fun StepPerformance(profile: TrainingProfile, vm: OnboardingViewModel) {
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
internal fun StepRace(profile: TrainingProfile, vm: OnboardingViewModel) {
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
internal fun StepAvailability(profile: TrainingProfile, vm: OnboardingViewModel) {
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
internal fun StepEffort(profile: TrainingProfile, vm: OnboardingViewModel) {
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
internal fun StepEquipment(profile: TrainingProfile, vm: OnboardingViewModel) {
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
internal fun StepInjuries(profile: TrainingProfile, vm: OnboardingViewModel) {
    StepColumn {
        Text(
            "Anything the coach should train around? Injuries, niggles or areas to protect. " +
                "The coach avoids loading these and picks safer alternatives.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InjuryEditor(profile.injuries) { v -> vm.update { it.copy(injuries = v) } }
    }
}
