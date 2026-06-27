package com.workoutmaker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.workoutmaker.app.ui.AuthGate
import com.workoutmaker.app.ui.screens.CalendarScreen
import com.workoutmaker.app.ui.screens.CoachScreen
import com.workoutmaker.app.ui.screens.HomeScreen
import com.workoutmaker.app.ui.screens.SettingsScreen
import com.workoutmaker.app.ui.screens.StrengthScreen
import com.workoutmaker.app.ui.screens.WorkoutHistoryScreen
import com.workoutmaker.app.ui.theme.WorkoutMakerTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workoutmaker.app.data.AppPreferences
import com.workoutmaker.app.data.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("coach", "Coach", Icons.AutoMirrored.Filled.Chat),
    Tab("calendar", "Calendar", Icons.Filled.CalendarMonth),
    Tab("strength", "Strength", Icons.Filled.FitnessCenter),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

// Exposes the user's theme choice so the whole UI can react to it live.
@HiltViewModel
class ThemeViewModel @Inject constructor(prefs: AppPreferences) : ViewModel() {
    val themeMode = prefs.settings
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeVm: ThemeViewModel = hiltViewModel()
            val mode by themeVm.themeMode.collectAsState()
            val dark = when (mode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            WorkoutMakerTheme(darkTheme = dark) {
                Surface {
                    AuthGate { MainScaffold() }
                }
            }
        }
    }
}

@Composable
private fun MainScaffold() {
    val nav = rememberNavController()

    // If a workout was in progress when the app was last closed/killed, START on
    // the Strength tab (not Home) so there's no Home→workout flash on launch.
    val context = androidx.compose.ui.platform.LocalContext.current
    val startDestination = androidx.compose.runtime.remember {
        if (java.io.File(context.filesDir, "active_session.json").exists()) "strength" else "home"
    }

    val snackHost = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    val snackScope = androidx.compose.runtime.rememberCoroutineScope()
    val appSnackbar = androidx.compose.runtime.remember(snackHost, snackScope) {
        com.workoutmaker.app.ui.components.AppSnackbar(snackHost, snackScope)
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackHost) },
        bottomBar = {
            NavigationBar {
                val current by nav.currentBackStackEntryAsState()
                val dest = current?.destination
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = dest?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        // Handoffs (Calendar "Log this session", History/Calendar "Edit") need the
        // LOGGER itself on screen. Plain tab navigation restores the strength
        // tab's saved sub-stack — which can have the History screen on top, hiding
        // the logger — so after restoring we pop anything above "strength".
        val openStrengthLogger: () -> Unit = {
            nav.navigate("strength") {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            nav.popBackStack("strength", false)
        }
        androidx.compose.runtime.CompositionLocalProvider(
            com.workoutmaker.app.ui.components.LocalAppSnackbar provides appSnackbar,
        ) {
        NavHost(nav, startDestination = startDestination, modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen() }
            composable("coach") {
                CoachScreen(onOpenCalendar = {
                    nav.navigate("calendar") {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable("calendar") {
                CalendarScreen(onOpenStrength = openStrengthLogger)
            }
            composable("strength") {
                StrengthScreen(onOpenHistory = { nav.navigate("history") })
            }
            composable("history") {
                WorkoutHistoryScreen(
                    onBack = { nav.popBackStack() },
                    // History sits directly ON TOP of the Strength tab, so the live
                    // StrengthViewModel — which the edit handoff has just switched
                    // into edit mode (nav = Active) — is still on the back stack.
                    // Pop straight back to it. Do NOT reuse the cross-tab
                    // openStrengthLogger here: its popUpTo(saveState)+restoreState
                    // recreates the StrengthViewModel, discarding that in-memory
                    // edit state, so the app bounced back to the Home tab and the
                    // edit only reappeared after a force-close (from the persisted
                    // active_session.json).
                    onEditInLogger = { nav.popBackStack("strength", false) },
                )
            }
            composable("settings") { SettingsScreen() }
        }
        }
    }
}
