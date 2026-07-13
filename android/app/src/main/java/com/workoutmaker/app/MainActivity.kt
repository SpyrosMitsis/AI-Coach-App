package com.workoutmaker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.handleDeeplinks
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
import com.workoutmaker.app.ui.screens.RecoveryHistoryScreen
import com.workoutmaker.app.ui.screens.SettingsScreen
import com.workoutmaker.app.ui.screens.StrengthScreen
import com.workoutmaker.app.ui.screens.WorkoutHistoryScreen
import com.workoutmaker.app.ui.theme.WorkoutMakerTheme
import com.workoutmaker.app.ui.theme.palette
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workoutmaker.app.data.AppPreferences
import com.workoutmaker.app.data.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    val themePalette = prefs.settings
        .map { it.themePalette }
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.workoutmaker.app.data.ThemePalette.SERENE)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var billing: com.workoutmaker.app.billing.BillingGateway
    @Inject lateinit var repo: com.workoutmaker.app.data.WorkoutRepository
    @Inject lateinit var supabase: io.github.jan.supabase.SupabaseClient

    // Auth email links (workoutmaker://auth/...) re-enter here; import the
    // session and route recovery links to the set-new-password dialog.
    private fun handleAuthLink(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "workoutmaker" || data.host != "auth") return
        val fragment = data.fragment ?: ""
        if (fragment.contains("error")) {
            com.workoutmaker.app.data.AuthDeepLinks.message.value =
                if (fragment.contains("otp_expired")) {
                    "That link has expired or was already used. If you were confirming your email it's likely already confirmed: just sign in. For a password reset, request a fresh link."
                } else {
                    "That sign-in link didn't work. Try again."
                }
            return
        }
        supabase.handleDeeplinks(intent) { session ->
            if (session.type == "recovery" || data.path?.contains("reset") == true) {
                com.workoutmaker.app.data.AuthDeepLinks.recoveryPending.value = true
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleAuthLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        handleAuthLink(intent)

        // A recovery link's session survives a process kill, but the in-memory
        // recoveryPending flag doesn't — without persistence, kill+relaunch
        // would skip the forced password change. Mirror the flag to disk and
        // re-arm the dialog once the stored session has been restored.
        val authFlags = getSharedPreferences("auth_flags", MODE_PRIVATE)
        lifecycleScope.launch {
            if (authFlags.getBoolean("recovery_pending", false)) {
                val restored = supabase.auth.sessionStatus.first {
                    it is io.github.jan.supabase.gotrue.SessionStatus.Authenticated ||
                        it is io.github.jan.supabase.gotrue.SessionStatus.NotAuthenticated
                }
                if (restored is io.github.jan.supabase.gotrue.SessionStatus.Authenticated) {
                    com.workoutmaker.app.data.AuthDeepLinks.recoveryPending.value = true
                }
            }
            com.workoutmaker.app.data.AuthDeepLinks.recoveryPending.collect { pending ->
                authFlags.edit().putBoolean("recovery_pending", pending).apply()
            }
        }

        // Heal a lost RTDN renewal: if Play knows an active subscription but the
        // profile says free, re-verify server-side. Cheap no-op for everyone else.
        lifecycleScope.launch {
            runCatching {
                if (!billing.supported || repo.auth.currentUserOrNull() == null) return@runCatching
                if (repo.planStatus().isPro) return@runCatching
                val token = billing.currentPurchaseToken() ?: return@runCatching
                repo.verifyPurchase(token)
            }
        }
        setContent {
            val themeVm: ThemeViewModel = hiltViewModel()
            val mode by themeVm.themeMode.collectAsState()
            val palette by themeVm.themePalette.collectAsState()
            val dark = when (mode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            WorkoutMakerTheme(palette = palette.palette(), darkTheme = dark) {
                Surface {
                    AuthGate { MainScaffold() }
                    // Password-recovery deep link: ask for the new password on
                    // top of whatever is showing.
                    val recovery by com.workoutmaker.app.data.AuthDeepLinks.recoveryPending.collectAsState()
                    if (recovery) com.workoutmaker.app.ui.screens.SetNewPasswordDialog(repo)
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
            composable("home") {
                HomeScreen(
                    // singleTop: a fast double-tap must not stack two copies
                    // (Back then appears broken, popping to the duplicate).
                    onOpenRecoveryHistory = { nav.navigate("recovery-history") { launchSingleTop = true } },
                )
            }
            composable("recovery-history") {
                RecoveryHistoryScreen(onBack = { nav.popBackStack() })
            }
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
                StrengthScreen(onOpenHistory = { nav.navigate("history") { launchSingleTop = true } })
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
