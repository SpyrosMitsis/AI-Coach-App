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
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import com.workoutmaker.app.ui.theme.palette
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
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
import com.workoutmaker.app.ui.components.GhostButton
import com.workoutmaker.app.ui.theme.Sage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    val busy by vm.busy.collectAsStateSafe()
    val haptics = LocalHapticFeedback.current
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
            "Sessions vary with their purpose, the typical length is a flexible budget, the max is a hard cap. The AI won't pad every workout to the same number.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChipGroup("Equipment", EQUIPMENT, profile.equipment) { e -> vm.updateProfile { it.copy(equipment = e) } }
        ChipGroup("Strength split", SPLIT_STYLES, profile.split_style ?: "Auto") { s ->
            vm.updateProfile { it.copy(split_style = if (s == "Auto") null else s) }
        }
        Text("Sports you do", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SPORTS.forEach { s ->
                FilterChip(
                    selected = profile.sports.contains(s),
                    onClick = { vm.updateProfile { it.copy(sports = if (it.sports.contains(s)) it.sports - s else it.sports + s) } },
                    label = { Text(s.replaceFirstChar { c -> c.uppercase() }) },
                )
            }
        }
        Text(
            "Only the sports you pick get scheduled. Leave empty to let the coach infer from your goal.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow(
            "Periodize my weeks",
            "Progressive build weeks with an automatic deload every ~4 weeks (applies when planning a week).",
            profile.periodized,
        ) { checked -> vm.updateProfile { it.copy(periodized = checked) } }
        ToggleRow(
            "Daily coach briefing",
            "A short, human note from your coach at the top of Home each day. Costs one AI call per day; turn off to avoid any automatic spend.",
            profile.briefing,
        ) { checked -> vm.updateProfile { it.copy(briefing = checked) } }
        if (profile.goal_date != null || profile.target_pace != null) {
            Text(
                buildString {
                    append("Goal: ")
                    append(profile.goal ?: "-")
                    profile.goal_date?.let { append(" · $it") }
                    profile.target_pace?.let { append(" · $it") }
                    append("  (set in Goals & races below)")
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(profile.injury_history ?: "", { v -> vm.updateProfile { it.copy(injury_history = v) } },
            label = { Text("Injury history (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.saveProfile() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save profile") }
    }
    SectionCard(title = "About you") {
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
                (profile.weight_kg?.toString() ?: ""), { v -> vm.updateProfile { it.copy(weight_kg = v.toIntOrNull()) } },
                label = { Text("Weight kg") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                (profile.height_cm?.toString() ?: ""), { v -> vm.updateProfile { it.copy(height_cm = v.toIntOrNull()) } },
                label = { Text("Height cm") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Normally read from your Intervals.icu profile (weight follows your latest wellness entry). Anything set here overrides it; leave blank to use Intervals.icu.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.saveProfile() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save") }
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
    val haptics = LocalHapticFeedback.current
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
        Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.saveProfile() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save") }
    }
}

// "What your coach knows about you" — the three coach-memory documents in one
// place: hard constraints (coach_knowledge ≈ user.md), the rolling notes
// (training_memory ≈ memory.md), and the coach's identity (coach_soul ≈ soul.md).
@Composable
internal fun KnowledgeSection(vm: SettingsViewModel) {
    val knowledge by vm.knowledge.collectAsStateSafe()
    val knowledgeStatus by vm.knowledgeStatus.collectAsStateSafe()
    val memory by vm.memory.collectAsStateSafe()
    val memoryStatus by vm.memoryStatus.collectAsStateSafe()
    val soul by vm.soul.collectAsStateSafe()
    val soulStatus by vm.soulStatus.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    SectionCard {
        Text(
            "Durable facts your coach must respect on every plan, e.g. \"left knee, avoid deep lunges\", " +
                "\"no leg press machine\", \"only dumbbells at home\", \"hate burpees\". The coach chat updates this " +
                "automatically, and you can edit it here.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            knowledge, { vm.updateKnowledge(it) }, label = { Text("Constraints & preferences") },
            placeholder = { Text("- Left knee tendinitis, avoid deep knee flexion\n- Home gym: dumbbells + bands only\n- Runs only before work (mornings)") },
            minLines = 5, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.saveKnowledge() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save knowledge") }
        knowledgeStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
    SectionCard {
        Text(
            "Your coach's running notes, durable patterns it has learned from your sessions, " +
                "feedback and PRs (e.g. how you respond to volume, recurring soreness, what motivates you). " +
                "It carries these into every chat and plan. Tap Refresh to re-derive them from recent training, " +
                "or edit them directly.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            memory, { vm.updateMemory(it) }, label = { Text("Coach's notes about you") },
            placeholder = { Text("Builds up automatically as you train, or jot something here.") },
            minLines = 4, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.refreshMemory() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Refresh") }
            Button(onClick = { vm.saveMemory() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Save notes") }
        }
        memoryStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
    SectionCard {
        Text(
            "Your coach's soul, who it is to you: its voice, coaching philosophy, and the " +
                "story of how you two train together. It deepens slowly on its own; you rarely need to " +
                "touch it, but you can shape its personality here. Leave it blank to use the default coach.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            soul, { vm.updateSoul(it) }, label = { Text("Coach's identity & your story") },
            placeholder = { Text("Seeded with a default coach personality, then deepened over time as you train together.") },
            minLines = 5, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.saveSoul() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save soul") }
        soulStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

// Pro (hosted AI) — rendered only when this build can bill (play flavor) AND
// the server advertises a hosted key. Self-hosted stacks and foss builds
// never see any of this.
@Composable
internal fun ProSection(vm: SettingsViewModel) {
    val plan = vm.planStatus.collectAsStateSafe().value
    val busy = vm.proBusy.collectAsStateSafe().value
    val error = vm.proError.collectAsStateSafe().value
    val context = androidx.compose.ui.platform.LocalContext.current

    SectionCard(title = if (plan.isPro) "Pro, hosted AI" else "Pro") {
        if (plan.isPro) {
            ToggleRow(
                title = "Use hosted AI",
                subtitle = "Coach and workouts run on our key, no setup. Turn off to use your own keys below.",
                checked = plan.useHostedAi,
                onChange = { vm.setUseHostedAi(it) },
            )
            Text(
                "Fair-use allowance applies; if you hit it, generation pauses until it resets (or add your own key below).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    val url = "https://play.google.com/store/account/subscriptions" +
                        "?sku=${com.workoutmaker.app.billing.PRO_PRODUCT_ID}&package=${context.packageName}"
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Manage subscription") }
        } else {
            Text(
                "Skip the API keys. Pro runs the coach and workout generation on a fast hosted model, with a fair-use allowance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { (context as? android.app.Activity)?.let { vm.buyPro(it) } },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Working…" else "Get Pro") }
            TextButton(onClick = { vm.restorePro() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Restore purchase")
            }
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

// Ko-fi page used by builds without Play Billing (foss). TODO: replace the
// placeholder handle with the real Ko-fi page before release.
internal const val KOFI_URL = "https://ko-fi.com/PLACEHOLDER"

@Composable
internal fun SupportSection(vm: SettingsViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SectionCard(title = "Support the developer") {
        Text(
            "This app is free and open source. If it helps your training, a small tip keeps it alive.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (vm.tipsSupported) {
            val busy = vm.tipBusy.collectAsStateSafe().value
            val status = vm.tipStatus.collectAsStateSafe().value
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "tip_small" to "2 €",
                    "tip_medium" to "5 €",
                    "tip_large" to "10 €",
                ).forEach { (id, label) ->
                    androidx.compose.material3.OutlinedButton(
                        onClick = { (context as? android.app.Activity)?.let { vm.sendTip(it, id) } },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (busy) "…" else label) }
                }
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            Button(
                onClick = { runCatching { uriHandler.openUri(KOFI_URL) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Tip on Ko-fi") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiSection(vm: SettingsViewModel) {
    val proAvailable = vm.proAvailable.collectAsStateSafe().value
    val plan = vm.planStatus.collectAsStateSafe().value
    if (proAvailable) ProSection(vm)

    // A Pro user on hosted AI shouldn't wade through key plumbing; keep the
    // BYO section one tap away instead of front and center.
    val hostedActive = proAvailable && plan.isPro && plan.useHostedAi
    var byoExpanded by rememberSaveable { mutableStateOf(false) }
    if (hostedActive && !byoExpanded) {
        TextButton(onClick = { byoExpanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Advanced: bring your own keys",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    SectionCard(title = "Active provider") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LlmProvider.entries.forEach { p ->
                FilterChip(selected = vm.active == p, onClick = { vm.selectProvider(p) }, label = { Text(if (p.freeTier) "${p.label} ✦" else p.label) })
            }
        }
        Text("✦ = has a free tier. Add a key below; the others act as fallbacks.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The model is the single biggest lever on coach quality — the free default
        // is capable but generic. Nudge toward a stronger model without forcing spend.
        Text(
            "Tip: your coach gets noticeably more human and insightful on a stronger model " +
                "(e.g. Claude / Anthropic). Free tiers work fine to start.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    LlmProvider.entries.forEach { provider -> ProviderCard(Modifier, provider, vm) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionsSection(vm: SettingsViewModel) {
    val intervalsStatus by vm.intervalsStatus.collectAsStateSafe()
    val intervalsSaved by vm.intervalsSaved.collectAsStateSafe()
    val healthStatus by vm.healthStatus.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Sync runs with whatever was granted — a partial grant (e.g. no steps) is
    // still useful; only a fully-empty grant is a real denial.
    val healthPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.intersect(vm.healthPermissions).isNotEmpty()) vm.syncHealth()
        else vm.setHealthStatus(
            "Permission denied. Android stops asking after two denials. Use “Open Health Connect” below " +
                "and grant Workout Maker access under App permissions.",
        )
    }
    val connected = intervalsSaved != null || intervalsStatus?.startsWith("✓") == true

    SectionCard(title = "Intervals.icu") {
        StatusChip("Intervals.icu", connected)
        intervalsSaved?.let { (athlete, hint) ->
            Text(
                "Saved: athlete $athlete · API key ${hint ?: "••••••••"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // Sync uses the saved credentials server-side — no need to re-enter
            // the key/id. (Also runs automatically every 30 min via the backend.)
            Button(
                onClick = { vm.syncIntervalsNow() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Syncing…" else "Sync now") }
        }
        Text("Pushes structured workouts to your Amazfit watch (via Zepp → Intervals.icu). Find the athlete ID + API key in Intervals.icu → Settings → Developer.",
            style = MaterialTheme.typography.bodySmall)
        var athleteId by remember { mutableStateOf("") }
        var apiKey by remember { mutableStateOf("") }
        OutlinedTextField(
            athleteId, { athleteId = it },
            label = { Text(if (intervalsSaved != null) "Athlete ID (replace)" else "Athlete ID (e.g. i123456)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            apiKey, { apiKey = it },
            label = { Text(if (intervalsSaved != null) "API key (replace)" else "API key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
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
                else scope.launch {
                    if (vm.grantedHealthPerms().isNotEmpty()) vm.syncHealth()
                    else healthPermLauncher.launch(vm.healthPermissions)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sync wellness from Health Connect") }
        // Recovery path when the in-app dialog can no longer appear (Android
        // auto-denies after two refusals): grant directly in Health Connect.
        GhostButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            androidx.health.connect.client.HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS,
                        ),
                    )
                }.onFailure { vm.setHealthStatus("Couldn't open Health Connect, open it from your app drawer instead.") }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open Health Connect (manage permissions)") }
        healthStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotificationsSection(vm: SettingsViewModel) {
    val s by vm.appSettings.collectAsStateSafe()
    val context = androidx.compose.ui.platform.LocalContext.current
    SectionCard {
        ToggleRow("Rest-timer alert", "Notify when a rest period ends, even if the app is in the background.", s.restNotify) { vm.setRestNotify(it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        ToggleRow("Vibrate on rest end", "Buzz the phone when the rest timer finishes.", s.restVibrate) { vm.setRestVibrate(it) }
    }
    SectionCard(title = "Rest-end chime") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.workoutmaker.app.data.RestChime.entries.forEach { c ->
                FilterChip(
                    selected = s.restChime == c,
                    onClick = {
                        vm.setRestChime(c)
                        // Preview the pick right away so choosing doesn't need a live timer.
                        com.workoutmaker.app.notify.playRestOverSound(context, c)
                    },
                    label = { Text(c.label) },
                )
            }
        }
        Text(
            "Played when the rest timer finishes while the app is open. Uses the media volume, sounds even on silent, alongside your music. Background alerts use the system notification sound.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DiagnosticsSection(vm: SettingsViewModel) {
    val logs by vm.logs.collectAsStateSafe()
    if (logs.isEmpty()) {
        SectionCard {
            com.workoutmaker.app.ui.components.EmptyState(
                title = "No AI generations yet",
                subtitle = "Generate a workout or plan and it'll be logged here.",
                icon = Icons.Filled.AutoAwesome,
            )
        }
        return
    }
    // Local-date based windows (the app's "today" is the client's local date).
    val today = java.time.LocalDate.now()
    fun daysAgo(l: com.workoutmaker.app.data.GenerationLogRow): Long = runCatching {
        java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(l.created_at?.take(10)), today)
    }.getOrDefault(99999L)
    fun money(v: Double) = "$${"%.3f".format(v)}"
    val within30 = logs.filter { daysAgo(it) <= 29 }
    val spentToday = logs.filter { daysAgo(it) <= 0 }.sumOf { it.estimated_cost_usd }
    val spent7 = logs.filter { daysAgo(it) <= 6 }.sumOf { it.estimated_cost_usd }
    val spent30 = within30.sumOf { it.estimated_cost_usd }
    val fails = within30.count { !it.parsed_ok }
    val byProvider = within30.groupBy { it.provider ?: "?" }
        .mapValues { e -> e.value.sumOf { it.estimated_cost_usd } }
        .entries.sortedByDescending { it.value }
    val byFeature = within30.groupBy { it.feature ?: "?" }
        .mapValues { e -> e.value.sumOf { it.estimated_cost_usd } }
        .entries.sortedByDescending { it.value }

    val appSettings by vm.appSettings.collectAsStateSafe()
    val cap = appSettings.spendCapUsd

    SectionCard(title = "AI spend (estimated)") {
        Text("Today ${money(spentToday)} · 7d ${money(spent7)} · 30d ${money(spent30)}",
            style = MaterialTheme.typography.titleSmall)
        Text("${within30.size} generations in 30d" + (if (fails > 0) " · $fails failed" else " · all OK"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Soft monthly cap: a warning banner only — never blocks generation.
        if (cap > 0 && spent30 >= cap) {
            Text(
                "⚠ Over your ${money(cap)}/month cap, 30-day spend is ${money(spent30)}. " +
                    "Consider a cheaper provider or turning off the daily briefing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        var capText by remember(cap) { mutableStateOf(if (cap > 0) cap.toString() else "") }
        OutlinedTextField(
            value = capText,
            onValueChange = { capText = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Monthly spend cap (USD, 0 = off)") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
            trailingIcon = {
                androidx.compose.material3.TextButton(onClick = { vm.setSpendCap(capText.toDoubleOrNull() ?: 0.0) }) { Text("Set") }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (byFeature.isNotEmpty()) {
            Text("By feature (30d): " + byFeature.joinToString(" · ") { "${it.key} ${money(it.value)}" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
        }
        if (byProvider.isNotEmpty()) {
            Text("By provider (30d): " + byProvider.joinToString(" · ") { "${it.key} ${money(it.value)}" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SectionCard(title = "Recent generations") {
        logs.take(12).forEach { l ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (l.parsed_ok) "✓" else "✗", color = if (l.parsed_ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text(
                    "  ${l.created_at?.take(16)?.replace('T', ' ') ?: ""} · ${l.feature ?: "?"} · ${l.provider ?: "?"} · ${money(l.estimated_cost_usd)}" +
                        (if (!l.parsed_ok && l.error != null) ", ${l.error.take(50)}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
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
        Text("Import a CSV export from Strong or Hevy. Workouts, sets and weights (kg/lb) are detected automatically. Re-importing the same file is safe: sessions you already have are skipped.",
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
    SectionCard(title = "Palette") {
        Text("Re-skin the whole app. “Serene Vanguard” is the original sage look; the others are experiments you can switch any time.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        com.workoutmaker.app.data.ThemePalette.entries.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.setThemePalette(p) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(selected = s.themePalette == p, onClick = { vm.setThemePalette(p) })
                Text(p.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                Box(Modifier.weight(1f))
                PaletteSwatches(p)
            }
        }
    }
    SectionCard(title = "Light / Dark") {
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

// Three little dots previewing a palette's primary/secondary/background so the
// choice is visible without selecting it. Uses the palette's dark scheme.
@Composable
private fun PaletteSwatches(p: com.workoutmaker.app.data.ThemePalette) {
    val scheme = p.palette().dark
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(scheme.primary, scheme.secondary, scheme.surface).forEach { c ->
            Box(
                Modifier.size(16.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(c)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.CircleShape),
            )
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

    SectionCard(title = "Goals & races") {
        Text("Set goals for any sport, races, FTP targets, swim times, lifts. Your A-goal drives periodization and the taper; B/C goals are tune-ups shown on the countdown.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (races.isEmpty()) {
            com.workoutmaker.app.ui.components.EmptyState(
                title = "No goals yet",
                subtitle = "Add a goal race or target to drive your periodization.",
                icon = Icons.Filled.Flag,
            )
        }
        races.forEach { r ->
            val days = runCatching { java.time.temporal.ChronoUnit.DAYS.between(today, java.time.LocalDate.parse(r.date)) }.getOrNull()
            val isGoal = profile.goal_date == r.date
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val dotColor = priorityColor(r.priority)
                Box(Modifier.size(26.dp).background(dotColor, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(r.priority, style = MaterialTheme.typography.labelMedium,
                        color = androidx.compose.material3.contentColorFor(dotColor))
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

@Composable
internal fun AccountSection(vm: SettingsViewModel) {
    val deleteState = vm.deleteAccountState.collectAsStateSafe().value
    val confirmDelete = remember { mutableStateOf(false) }
    SectionCard {
        Text("Workout Maker", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("Personalised endurance + strength coaching.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { vm.signOut() }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        TextButton(onClick = { confirmDelete.value = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Delete account & all data", color = MaterialTheme.colorScheme.onErrorContainer)
        }
        if (deleteState != null && deleteState.isNotEmpty()) {
            Text(deleteState, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    if (confirmDelete.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete.value = false },
            title = { Text("Delete account?") },
            text = {
                Text(
                    "This permanently deletes your account and everything in it, profile, " +
                        "workouts, strength logs, coach conversations, and stored API keys. " +
                        "There is no undo. Data already pushed to Intervals.icu stays there.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete.value = false; vm.deleteAccount() },
                    enabled = deleteState?.isEmpty() != true,
                ) { Text("Delete everything", color = MaterialTheme.colorScheme.onErrorContainer) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete.value = false }) { Text("Cancel") }
            },
        )
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
    val llmKeys by vm.llmKeys.collectAsStateSafe()
    val customPrice by vm.customPrice.collectAsStateSafe()
    val saved = llmKeys[provider.key]
    val activeModel = overrides[provider.key] ?: provider.model
    val isCustom = provider == LlmProvider.CUSTOM
    var showModelPicker by remember { mutableStateOf(false) }

    // Custom-provider config: base URL + free-text model id (no fixed defaults).
    var baseUrl by remember(saved?.base_url) { mutableStateOf(saved?.base_url ?: "") }
    var modelId by remember(overrides[provider.key]) { mutableStateOf(overrides[provider.key] ?: "") }

    if (showModelPicker) {
        ModelPickerDialog(provider, vm) { showModelPicker = false }
    }

    SectionCard(mod, title = "${provider.label}${if (provider.freeTier) "  · free tier" else ""}") {
        if (isCustom) {
            Text(
                "Point at any OpenAI-compatible endpoint. Ollama, LM Studio, vLLM, OpenRouter, a LiteLLM proxy. The phone must be able to reach the URL.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                baseUrl, { baseUrl = it },
                label = { Text("Base URL") },
                placeholder = { Text("http://192.168.1.10:11434/v1") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                modelId, { modelId = it },
                label = { Text("Model id") },
                placeholder = { Text("llama3.1:8b") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            // Optional pricing so the diagnostics screen shows real spend (a BYO
            // endpoint has no known price → cost would otherwise read $0).
            var priceIn by remember(customPrice.first) { mutableStateOf(customPrice.first?.toString() ?: "") }
            var priceOut by remember(customPrice.second) { mutableStateOf(customPrice.second?.toString() ?: "") }
            Text("Pricing (optional, $ per 1M tokens), for cost tracking only.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    priceIn, { priceIn = it },
                    label = { Text("Input") }, placeholder = { Text("0.20") },
                    singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
                OutlinedTextField(
                    priceOut, { priceOut = it },
                    label = { Text("Output") }, placeholder = { Text("0.60") },
                    singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
                TextButton(onClick = { vm.setCustomPricing(priceIn.toDoubleOrNull(), priceOut.toDoubleOrNull()) }) {
                    Text("Save")
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(activeModel, style = MaterialTheme.typography.bodySmall)
                    if (overrides[provider.key] != null) {
                        Text("custom, default is ${provider.model}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { showModelPicker = true }) { Text("Change model") }
            }
        }
        // What's already configured, masked — so you know which key is in use.
        saved?.let { s ->
            Text(
                "Saved key: ${s.key_hint ?: "••••••••"} · " + when (s.is_valid) {
                    true -> "valid ✓"
                    false -> "invalid ✗"
                    null -> "untested"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (s.is_valid == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (isCustom && !s.base_url.isNullOrBlank()) {
                Text("Endpoint: ${s.base_url}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            key, { key = it },
            label = { Text(if (saved != null) "API key (replace)" else if (isCustom) "API key (any value if none)" else "API key") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        // Custom needs a base URL + model id too; others just need the key.
        val testing by vm.testing.collectAsStateSafe()
        val busy = testing == provider.key
        val canTest = !busy && key.isNotBlank() && (!isCustom || (baseUrl.isNotBlank() && modelId.isNotBlank()))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.testKey(provider, key, false, baseUrl.takeIf { isCustom }, modelId.takeIf { isCustom }) },
                enabled = canTest,
            ) { Text(if (busy) "Testing…" else "Save & Test") }
            OutlinedButton(
                onClick = { vm.testKey(provider, key, true, baseUrl.takeIf { isCustom }, modelId.takeIf { isCustom }) },
                enabled = canTest,
            ) { Text("Test Gen") }
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
                    label = "Default, ${provider.model}",
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
