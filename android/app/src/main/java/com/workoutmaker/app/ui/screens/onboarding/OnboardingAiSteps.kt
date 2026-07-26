package com.workoutmaker.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.LlmProvider
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.SectionCard
import android.app.Activity
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun StepKey(vm: OnboardingViewModel) {
    val hosted by vm.hostedAvailable.collectAsStateSafe()
    val proActive by vm.proActive.collectAsStateSafe()
    var byoExpanded by rememberSaveable { mutableStateOf(false) }

    if (hosted) {
        ProOnboardingCard(vm)
        Spacer16()
    }
    // A subscribed user never sees key setup unless they ask for it.
    if (!hosted || !proActive || byoExpanded) {
        ByoKeyCard(vm)
    } else {
        TextButton(onClick = { byoExpanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Prefer your own key? Set one up anyway",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// The zero-setup path: subscribe here and skip keys entirely.
@Composable
private fun ProOnboardingCard(vm: OnboardingViewModel) {
    val proActive by vm.proActive.collectAsStateSafe()
    val proBusy by vm.proBusy.collectAsStateSafe()
    val proError by vm.proError.collectAsStateSafe()
    val context = LocalContext.current

    SectionCard(title = if (proActive) "Pro is active" else "Pro: zero setup") {
        if (proActive) {
            Text(
                "✓ You're set. Your coach and workouts run on our hosted AI, nothing to configure.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                "Skip the API keys. Pro runs the coach and workout generation on a fast hosted model, with a fair-use allowance. It also supports the developer and keeps this app alive. Manage or cancel any time in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { (context as? Activity)?.let { vm.buyPro(it) } },
                enabled = !proBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (proBusy) "Working…" else "Get Pro") }
            proError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun ByoKeyCard(vm: OnboardingViewModel) {
    var key by rememberSaveable { mutableStateOf("") }
    val status by vm.keyStatus.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    SectionCard(title = "Bring your own key (free)") {
        Text(
            "This app is free and you bring your own LLM key. You pay your provider directly for what you use. Pick one to start.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRowProviders(vm)
        if (vm.provider.freeKeyUrl.isNotBlank()) {
            // Only Groq and Gemini actually have free tiers; don't promise one
            // for the paid providers.
            val keyLabel = if (vm.provider.freeTier) "Get a free key" else "Get an API key"
            Text("$keyLabel: ${vm.provider.freeKeyUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        OutlinedTextField(key, { key = it }, label = { Text("${vm.provider.label} API key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.testKey(key) }, enabled = !busy && key.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Save & test")
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowProviders(vm: OnboardingViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Custom (bring-your-own endpoint) needs extra base-URL/model fields, so
        // it's configured later in Settings — not during quick onboarding.
        LlmProvider.entries.filter { it != LlmProvider.CUSTOM }.forEach { p ->
            FilterChip(selected = vm.provider == p, onClick = { vm.provider = p },
                label = { Text(if (p.freeTier) "${p.label} ✦" else p.label) })
        }
    }
}

@Composable
internal fun StepConnect(vm: OnboardingViewModel) {
    var athleteId by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    val status by vm.intervalsStatus.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    SectionCard(title = "Connect Intervals.icu (optional)") {
        Text(
            "Links your Amazfit watch (via Zepp → Intervals.icu) so the coach sees your real fitness, HRV and activities, and can push workouts to your watch. You can skip and add this later in Settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(athleteId, { athleteId = it }, label = { Text("Athlete ID (e.g. i123456)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text("API key") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { vm.connect(athleteId, key) }, enabled = !busy && athleteId.isNotBlank() && key.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Verify & connect")
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Text("Tip: you can also sync HRV/sleep from your phone via Health Connect in Settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Later, when you generate an outdoor workout, the app may ask for coarse location once, " +
                "only to factor in today's weather (heat, rain, wind). It's optional and everything " +
                "works without it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
