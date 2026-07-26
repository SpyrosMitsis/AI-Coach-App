package com.workoutmaker.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import com.workoutmaker.app.ui.components.GhostButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.format
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.SectionCard
import kotlinx.coroutines.launch
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.workoutmaker.app.data.GenerationLogRow
import com.workoutmaker.app.data.RestChime
import com.workoutmaker.app.notify.playRestOverSound
import com.workoutmaker.app.strength.ImportSummary
import com.workoutmaker.app.ui.components.EmptyState
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionsSection(vm: SettingsViewModel) {
    val intervalsStatus by vm.intervalsStatus.collectAsStateSafe()
    val intervalsSaved by vm.intervalsSaved.collectAsStateSafe()
    val healthStatus by vm.healthStatus.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Sync runs with whatever was granted — a partial grant (e.g. no steps) is
    // still useful; only a fully-empty grant is a real denial.
    val healthPermLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
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
                        Intent(
                            HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS,
                        ),
                    )
                }.onFailure { vm.setHealthStatus("Couldn't open Health Connect, open it from your app drawer instead.") }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open Health Connect (manage permissions)") }
        healthStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
    SectionCard(title = "Device calendar") {
        val s by vm.appSettings.collectAsStateSafe()
        val calendarStatus by vm.calendarStatus.collectAsStateSafe()
        val readPermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) vm.setCalendarRead(true)
            else vm.setCalendarStatus("Permission denied. Grant calendar access in system settings to use this.")
        }
        val writePermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) vm.setCalendarWrite(true)
            else vm.setCalendarStatus("Permission denied. Grant calendar access in system settings to use this.")
        }
        Text(
            "Connects training to real life. Works with any calendar synced to this phone (Google, Outlook…). " +
                "For planning, only busy TIMES are used; event titles never leave your phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        ToggleRow(
            "Plan around my calendar",
            "The planner reads your busy times and puts long or hard sessions on your free days.",
            s.calendarRead,
        ) { on ->
            when {
                !on -> vm.setCalendarRead(false)
                vm.calendarReadGranted() -> vm.setCalendarRead(true)
                else -> readPermLauncher.launch(android.Manifest.permission.READ_CALENDAR)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        ToggleRow(
            "Show workouts in my calendar",
            "Planned sessions appear as all-day entries on the day, no clock time, no reminders.",
            s.calendarWrite,
        ) { on ->
            when {
                !on -> vm.setCalendarWrite(false)
                vm.calendarWriteGranted() -> vm.setCalendarWrite(true)
                else -> writePermLauncher.launch(android.Manifest.permission.WRITE_CALENDAR)
            }
        }
        calendarStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotificationsSection(vm: SettingsViewModel) {
    val s by vm.appSettings.collectAsStateSafe()
    val context = LocalContext.current
    SectionCard {
        ToggleRow("Morning readiness summary", "One notification at wake-up with your readiness score and the day's plan.", s.morningNotify) { vm.setMorningNotify(it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        ToggleRow("Rest-timer alert", "Notify when a rest period ends, even if the app is in the background.", s.restNotify) { vm.setRestNotify(it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        ToggleRow("Vibrate on rest end", "Buzz the phone when the rest timer finishes.", s.restVibrate) { vm.setRestVibrate(it) }
    }
    SectionCard(title = "Rest-end chime") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RestChime.entries.forEach { c ->
                FilterChip(
                    selected = s.restChime == c,
                    onClick = {
                        vm.setRestChime(c)
                        // Preview the pick right away so choosing doesn't need a live timer.
                        playRestOverSound(context, c)
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
            EmptyState(
                title = "No AI generations yet",
                subtitle = "Generate a workout or plan and it'll be logged here.",
                icon = Icons.Filled.AutoAwesome,
            )
        }
        return
    }
    // Local-date based windows (the app's "today" is the client's local date).
    val today = LocalDate.now()
    fun daysAgo(l: GenerationLogRow): Long = runCatching {
        ChronoUnit.DAYS.between(LocalDate.parse(l.created_at?.take(10)), today)
    }.getOrDefault(99999L)
    fun money(v: Double) = "$${"%.3f".format(v)}"
    val within30 = logs.filter { daysAgo(it) <= 29 }
    val spentToday = logs.filter { daysAgo(it) <= 0 }.sumOf { it.estimated_cost_usd ?: 0.0 }
    val spent7 = logs.filter { daysAgo(it) <= 6 }.sumOf { it.estimated_cost_usd ?: 0.0 }
    val spent30 = within30.sumOf { it.estimated_cost_usd ?: 0.0 }
    val fails = within30.count { !it.parsed_ok }
    val byProvider = within30.groupBy { it.provider ?: "?" }
        .mapValues { e -> e.value.sumOf { it.estimated_cost_usd ?: 0.0 } }
        .entries.sortedByDescending { it.value }
    val byFeature = within30.groupBy { it.feature ?: "?" }
        .mapValues { e -> e.value.sumOf { it.estimated_cost_usd ?: 0.0 } }
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            trailingIcon = {
                TextButton(onClick = { vm.setSpendCap(capText.toDoubleOrNull() ?: 0.0) }) { Text("Set") }
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
                    "  ${l.created_at?.take(16)?.replace('T', ' ') ?: ""} · ${l.feature ?: "?"} · ${l.provider ?: "?"} · ${money(l.estimated_cost_usd ?: 0.0)}" +
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
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            Log.i("IMPORT", "picker returned null (no file selected)")
            vm.importResult.value = ImportSummary(
                ok = false, error = "No file was selected.")
        } else {
            val read = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }
            val text = read.getOrNull()
            Log.i("IMPORT", "picked uri=$uri readOk=${read.isSuccess} len=${text?.length ?: -1} err=${read.exceptionOrNull()?.message}")
            if (text.isNullOrBlank()) {
                vm.importResult.value = ImportSummary(
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
                CircularProgressIndicator(
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
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
        AlertDialog(
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
