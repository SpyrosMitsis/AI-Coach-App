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
import androidx.compose.material.icons.outlined.VolunteerActivism
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

// Grouped by how often a normal athlete touches things: the everyday knobs up
// top, integrations and app chrome in the middle, and an Advanced group for
// power-user surfaces (raw thresholds, coach memory docs, import/export,
// generation logs) so the first screen stays calm.
internal val SETTINGS_GROUPS = listOf(
    SettingsGroup("Training", listOf(
        SettingsItem("profile", Icons.Outlined.Person, "Profile & goal", "Goal, experience, days, equipment, pace"),
        SettingsItem("races", Icons.Outlined.Flag, "Goals & races", "Multi-sport goals, targets & countdown"),
        SettingsItem("planning", Icons.Outlined.CalendarMonth, "Planning", "Auto-plan, weekly load & challenge level"),
        SettingsItem("defaults", Icons.Outlined.FitnessCenter, "Workout defaults", "Units, rest timer, barbell, screen"),
    )),
    SettingsGroup("Coaching & AI", listOf(
        SettingsItem("ai", Icons.Outlined.AutoAwesome, "AI providers", "Active model & API keys"),
    )),
    SettingsGroup("App", listOf(
        SettingsItem("connections", Icons.Outlined.Link, "Connections", "Intervals.icu & Health Connect"),
        SettingsItem("appearance", Icons.Outlined.Palette, "Appearance", "Light / dark theme"),
        SettingsItem("notifications", Icons.Outlined.Notifications, "Notifications", "Rest-timer alerts & vibration"),
    )),
    SettingsGroup("Advanced", listOf(
        SettingsItem("zones", Icons.Outlined.Favorite, "Training zones", "Thresholds, HR/pace/power zones & tests"),
        SettingsItem("knowledge", Icons.Outlined.Psychology, "Coach knowledge", "Injuries, equipment & preferences"),
        SettingsItem("data", Icons.Outlined.Download, "Import & export", "Strong/Hevy import · CSV backup"),
        SettingsItem("diagnostics", Icons.Outlined.MonitorHeart, "Diagnostics", "AI generation log & cost"),
    )),
    SettingsGroup("Account", listOf(
        SettingsItem("account", Icons.Outlined.AccountCircle, "About & account", "Version & sign out"),
        SettingsItem("support", Icons.Outlined.VolunteerActivism, "Support the developer", "Tips keep this app alive"),
    )),
)

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.load() }
    // Save confirmations / errors go to the one app-wide snackbar.
    val snackbar = com.workoutmaker.app.ui.components.LocalAppSnackbar.current
    val saveStatus by vm.saveStatus.collectAsStateSafe()
    LaunchedEffect(saveStatus) {
        val s = saveStatus ?: return@LaunchedEffect
        snackbar?.show(if (s.startsWith("✓")) s else com.workoutmaker.app.ui.components.friendlyError(s))
        vm.saveStatus.value = null
    }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = open != null) { open = null }

    if (open == null) {
        SettingsIndex(onOpen = { open = it })
    } else {
        SettingsDetail(open!!, vm) { open = null }
    }
}

@Composable
internal fun SettingsIndex(onOpen: (String) -> Unit) {
    ScreenScaffold(title = "Settings", subtitle = "Everything in one place", eyebrow = "PREFERENCES") { mod ->
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
internal fun SettingsDetail(id: String, vm: SettingsViewModel, onBack: () -> Unit) {
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
        // Diagnostics shows live AI activity: refresh on open, or a coach chat
        // from a minute ago is missing until Settings fully reloads.
        LaunchedEffect(id) { if (id == "diagnostics") vm.reloadLogs() }
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
                "support" -> SupportSection(vm)
            }
        }
    }
}

// --- Detail sections -------------------------------------------------------

internal data class SettingsItem(val id: String, val icon: ImageVector, val title: String, val subtitle: String)

internal data class SettingsGroup(val header: String, val items: List<SettingsItem>)
