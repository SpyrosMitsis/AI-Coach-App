package com.workoutmaker.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import com.workoutmaker.app.ui.theme.palette
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.TrainingProfile
import com.workoutmaker.app.data.WeightUnit
import com.workoutmaker.app.data.format
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.SectionCard
import com.workoutmaker.app.ui.components.SectionLabel
import android.app.Activity
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.contentColorFor
import com.workoutmaker.app.data.Periodization
import com.workoutmaker.app.ui.components.EmptyState
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import com.workoutmaker.app.data.addRace
import com.workoutmaker.app.data.deleteRace
import com.workoutmaker.app.data.races
import com.workoutmaker.app.data.saveProfile
import com.workoutmaker.app.data.setAutoPlan

// The one-page "Profile & week" grew too crowded, so it's now three pages
// (About you / Sports & goals / Your training week). They all edit the same
// TrainingProfile in the shared SettingsViewModel and each Save writes the
// whole profile, so nothing is lost when hopping between pages mid-edit.

@Composable
private fun SaveProfileButton(vm: SettingsViewModel) {
    val busy by vm.busy.collectAsStateSafe()
    val haptics = LocalHapticFeedback.current
    Button(
        onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.saveProfile() },
        enabled = !busy, modifier = Modifier.fillMaxWidth(),
    ) { Text("Save profile") }
}

@Composable
internal fun AboutYouSection(vm: SettingsViewModel, onOpenBodyHistory: () -> Unit) {
    val profile by vm.profile.collectAsStateSafe()
    // Injuries live on "Injuries & constraints", the briefing toggle on
    // "Planning" — this page is purely who you are, physically.
    SectionCard(title = "About you") {
        OutlinedTextField(
            profile.display_name ?: "", { v -> vm.updateProfile { it.copy(display_name = v.ifBlank { null }) } },
            label = { Text("Your name") }, placeholder = { Text("What the coach calls you") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        ChipGroup("Sex", listOf("Male", "Female"), profile.sex?.replaceFirstChar { c -> c.uppercase() }) { s ->
            // Tap the selected chip again to clear back to the Intervals value.
            vm.updateProfile { it.copy(sex = if (it.sex == s.lowercase()) null else s.lowercase()) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                (profile.birth_year?.toString() ?: ""), { v -> vm.updateProfile { it.copy(birth_year = v.toIntOrNull()) } },
                label = { Text("Born") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                (profile.height_cm?.toString() ?: ""), { v -> vm.updateProfile { it.copy(height_cm = v.toIntOrNull()) } },
                label = { Text("Height cm") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                (profile.weight_kg?.toString() ?: ""), { v -> vm.updateProfile { it.copy(weight_kg = v.toIntOrNull()) } },
                label = { Text("Weight kg") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                (profile.body_fat_pct?.let { bf -> if (bf % 1.0 == 0.0) bf.toInt().toString() else bf.toString() } ?: ""),
                { v -> vm.updateProfile { it.copy(body_fat_pct = v.toDoubleOrNull()?.takeIf { bf -> bf in 3.0..60.0 }) } },
                label = { Text("Body fat %") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Normally read from your Intervals.icu profile; weight and body fat also sync from a Health Connect smart scale. Anything set here overrides those; leave blank to use the synced value.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenBodyHistory) { Text("Body trends →") }
    }
    SaveProfileButton(vm)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SportsGoalsSection(vm: SettingsViewModel) {
    val profile by vm.profile.collectAsStateSafe()
    SectionCard(title = "Your sports") {
        SportSelector(profile.sports) { s -> vm.updateProfile { it.copy(sports = it.sports.toggled(s)) } }
    }
    // Activity-first: each selected sport gets its own card, so two+ sports read
    // as separate topics instead of one undivided run of goal/level/split chips.
    SPORTS.filter { profile.sports.contains(it) }.forEach { sport ->
        SectionCard {
            SectionLabel(sportLabel(sport))
            SportGoalsLevel(
                sport = sport,
                goals = profile.goals_by_sport[sport].orEmpty(),
                level = profile.experience_by_sport[sport],
                splitStyle = profile.split_style,
                onGoalToggle = { g -> vm.updateProfile { it.copy(goals_by_sport = it.goals_by_sport.toggleIn(sport, g)) } },
                onLevel = { lvl -> vm.updateProfile { it.copy(experience_by_sport = it.experience_by_sport + (sport to lvl)) } },
                onSplit = { s -> vm.updateProfile { it.copy(split_style = if (s == "Auto") null else s) } },
            )
        }
    }
    if (sportNeedsEquipment(profile.sports)) {
        SectionCard(title = "Equipment") {
            EquipmentSelector(profile.equipment_list) { e -> vm.updateProfile { it.copy(equipment_list = it.equipment_list.toggled(e)) } }
        }
    }
    SaveProfileButton(vm)
}

@Composable
internal fun TrainingWeekSection(vm: SettingsViewModel) {
    val profile by vm.profile.collectAsStateSafe()
    // WeeklyAvailabilityEditor renders its own Schedule + Session length cards.
    WeeklyAvailabilityEditor(profile.day_availability) { list -> vm.updateProfile { it.copy(day_availability = list) } }
    SectionCard {
        SectionLabel("Progression")
        PeriodizationControl(
            periodized = profile.periodized,
            onChange = { p -> vm.updateProfile { it.copy(periodized = p) } },
            weeklyTssTarget = profile.weekly_tss_target,
        )
    }
    SectionCard {
        SectionLabel("8-week forecast")
        PeriodizationNumbers(profile.weekly_tss_target, profile.periodized, showHeading = false)
    }
    SaveProfileButton(vm)
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
    SectionCard(title = "Display") {
        ToggleRow("Keep screen on during workouts", "Stops the display sleeping while you train.", s.keepScreenOn) { vm.setKeepScreenOn(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanningSection(vm: SettingsViewModel) {
    val autoPlan by vm.autoPlan.collectAsStateSafe()
    val profile by vm.profile.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    val haptics = LocalHapticFeedback.current
    SectionCard(title = "Automatic coaching") {
        ToggleRow("Auto-plan next week", "Every Sunday the AI lays out your week and (if connected) pushes it to your watch.", autoPlan) { vm.setAutoPlan(it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        // Saved immediately — a toggle that silently needs a Save button reads
        // as broken. (Moved here from Profile: it's coach behavior, not identity.)
        ToggleRow(
            "Daily coach briefing",
            "A short, human note from your coach at the top of Home each day. Costs one AI call per day; turn off to avoid any automatic spend.",
            profile.briefing,
        ) { checked ->
            vm.updateProfile { it.copy(briefing = checked) }
            vm.saveProfile()
        }
    }
    SectionCard(title = "Weekly load target") {
        // The same effort chips onboarding offers: fractions of the athlete's
        // own availability ceiling, so the suggestion is always achievable.
        val minutes = profile.day_availability.sumOf { it.max_minutes }
        Periodization.availabilityCeiling(minutes)?.let { ceiling ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Periodization.Effort.entries.forEach { e ->
                    val target = e.targetFor(ceiling)
                    FilterChip(
                        selected = profile.weekly_tss_target == target,
                        onClick = { vm.updateProfile { it.copy(weekly_tss_target = target) } },
                        label = { Text("${e.label} · ~$target") },
                    )
                }
            }
        }
        OutlinedTextField(
            (profile.weekly_tss_target?.toString() ?: ""), { v -> vm.updateProfile { it.copy(weekly_tss_target = v.toIntOrNull()) } },
            label = { Text("Target weekly TSS (optional)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        Text("Guides how much training load the weekly planner aims for. Leave blank to auto-estimate.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionCard(title = "Challenge level") {
        Text(
            "A standing bias on how hard sessions feel. The coach still adapts to your daily readiness on top of this.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Easier" to "easier", "Standard" to null, "Harder" to "harder").forEach { (label, value) ->
                FilterChip(
                    selected = profile.challenge == value,
                    onClick = { vm.updateProfile { it.copy(challenge = value) } },
                    label = { Text(label) },
                )
            }
        }
    }
    Button(
        onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.saveProfile() },
        enabled = !busy, modifier = Modifier.fillMaxWidth(),
    ) { Text("Save") }
}











@Composable
internal fun AppearanceSection(vm: SettingsViewModel) {
    val s by vm.appSettings.collectAsStateSafe()
    // Palette + light/dark now live in the shared AppearancePicker (ProfileEditors.kt)
    // so onboarding and Settings render the exact same control.
    SectionCard {
        AppearancePicker(
            themeMode = s.themeMode,
            palette = s.themePalette,
            onMode = { vm.setThemeMode(it) },
            onPalette = { vm.setThemePalette(it) },
        )
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
    val today = LocalDate.now()

    if (showAdd) AddRaceDialog(onClose = { showAdd = false }) { race, setGoal ->
        vm.addRace(race, setGoal); showAdd = false
    }

    SectionCard(title = "Goals & races") {
        Text("Set goals for any sport, races, FTP targets, swim times, lifts. Your A-goal drives periodization and the taper; B/C goals are tune-ups shown on the countdown.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (races.isEmpty()) {
            EmptyState(
                title = "No goals yet",
                subtitle = "Add a goal race or target to drive your periodization.",
                icon = Icons.Filled.Flag,
            )
        }
        races.forEach { r ->
            val days = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(r.date)) }.getOrNull()
            val isGoal = profile.goal_date == r.date
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val dotColor = priorityColor(r.priority)
                Box(Modifier.size(26.dp).background(dotColor, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(r.priority, style = MaterialTheme.typography.labelMedium,
                        color = contentColorFor(dotColor))
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(r.name + if (isGoal) "  ⭐" else "", style = MaterialTheme.typography.titleSmall)
                    Text(buildString {
                        append(goalSportLabel(r.sport))
                        append(" · ${r.date}")
                        r.distance?.let { append(" · $it") }
                        r.target?.let { append(" · $it") }
                        days?.let { append(" · ${if (it >= 0) "$it days" else "past"}") }
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!isGoal) TextButton(onClick = { vm.makeGoalRace(r) }) { Text("Set goal") }
                r.id?.let { IconButton(onClick = { vm.deleteRace(r) }) {
                    Icon(Icons.Filled.Delete, "Delete goal", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }
        OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("Add goal") }
    }
}


// --- Shared bits -----------------------------------------------------------
