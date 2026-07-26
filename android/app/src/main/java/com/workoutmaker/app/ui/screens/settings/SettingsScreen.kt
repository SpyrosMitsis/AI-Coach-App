package com.workoutmaker.app.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.ScreenScaffold
import com.workoutmaker.app.ui.components.SectionCard
import com.workoutmaker.app.ui.components.LocalAppSnackbar
import com.workoutmaker.app.util.friendlyError

// Grouped by INTENT, in the order a user reasons: who I am (everything the
// coach knows about the athlete, in one place), how it plans, phone/app
// concerns, power tools, account. Ids are stable — only grouping/copy changes.
internal val SETTINGS_GROUPS = listOf(
    SettingsGroup("About you", listOf(
        SettingsItem("profile", Icons.Outlined.Person, "About you", "Name, body & body trends"),
        SettingsItem("sports", Icons.Outlined.FitnessCenter, "Sports & goals", "Sports, goals, level & equipment"),
        SettingsItem("week", Icons.Outlined.CalendarMonth, "Your training week", "Availability & periodization"),
        SettingsItem("races", Icons.Outlined.Flag, "Goals & races", "Goal races, targets & countdown"),
        SettingsItem("zones", Icons.Outlined.Favorite, "Your numbers & zones", "FTP, paces, lifts & HR zones"),
        SettingsItem("knowledge", Icons.Outlined.Psychology, "Injuries & constraints", "Hard rules the coach must respect"),
    )),
    SettingsGroup("Coach & planning", listOf(
        SettingsItem("ai", Icons.Outlined.AutoAwesome, "AI model", "Model choice & API keys, the biggest quality lever"),
        SettingsItem("planning", Icons.Outlined.CalendarMonth, "Planning", "Auto-plan, weekly load & challenge level"),
    )),
    SettingsGroup("App", listOf(
        SettingsItem("connections", Icons.Outlined.Link, "Connections", "Intervals.icu, Health Connect & calendar"),
        SettingsItem("notifications", Icons.Outlined.Notifications, "Notifications", "Morning brief, rest alerts & vibration"),
        SettingsItem("defaults", Icons.Outlined.FitnessCenter, "Units & gym session", "Units, rest timer, barbell, screen"),
        SettingsItem("appearance", Icons.Outlined.Palette, "Appearance", "Theme & palette"),
    )),
    SettingsGroup("Data", listOf(
        SettingsItem("data", Icons.Outlined.Download, "Import & export", "Strong/Hevy import · CSV backup"),
        SettingsItem("diagnostics", Icons.Outlined.MonitorHeart, "Diagnostics", "AI generation log & cost"),
    )),
    SettingsGroup("Account", listOf(
        SettingsItem("account", Icons.Outlined.AccountCircle, "About & account", "Version & sign out"),
        SettingsItem("support", Icons.Outlined.VolunteerActivism, "Support the developer", "Tips keep this app alive"),
    )),
)

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel(), onOpenBodyHistory: () -> Unit = {}) {
    LaunchedEffect(Unit) { vm.load() }
    // Save confirmations / errors go to the one app-wide snackbar.
    val snackbar = LocalAppSnackbar.current
    val saveStatus by vm.saveStatus.collectAsStateSafe()
    LaunchedEffect(saveStatus) {
        val s = saveStatus ?: return@LaunchedEffect
        snackbar?.show(if (s.startsWith("✓")) s else friendlyError(s))
        vm.saveStatus.value = null
    }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = open != null) { open = null }

    if (open == null) {
        SettingsIndex(onOpen = { open = it })
    } else {
        SettingsDetail(open!!, vm, onOpenBodyHistory) { open = null }
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
internal fun SettingsDetail(id: String, vm: SettingsViewModel, onOpenBodyHistory: () -> Unit = {}, onBack: () -> Unit) {
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
                "profile" -> AboutYouSection(vm, onOpenBodyHistory)
                "sports" -> SportsGoalsSection(vm)
                "week" -> TrainingWeekSection(vm)
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
