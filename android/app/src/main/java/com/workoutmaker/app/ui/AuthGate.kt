package com.workoutmaker.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workoutmaker.app.data.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.gotrue.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

// Supabase auth errors are terse and technical — translate the common ones.
internal fun friendlyAuthError(t: Throwable): String {
    val m = t.message ?: return "Something went wrong — please try again."
    return when {
        m.contains("Invalid login credentials", true) -> "Wrong email or password."
        m.contains("Email not confirmed", true) ->
            "Your email isn't confirmed yet — check your inbox for the confirmation link."
        m.contains("already registered", true) ->
            "An account with this email already exists — sign in instead."
        m.contains("Password should be", true) -> "Password is too short — use at least 6 characters."
        m.contains("rate limit", true) || m.contains("too many", true) ->
            "Too many attempts — wait a minute and try again."
        m.contains("is invalid", true) || m.contains("validate email", true) ->
            "That doesn't look like a valid email address."
        m.contains("Unable to resolve host", true) || m.contains("Failed to connect", true) ||
            m.contains("timeout", true) || m.contains("No address associated", true) ->
            "Can't reach the server — check your internet connection."
        else -> m
    }
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    val repo: WorkoutRepository,
) : ViewModel() {
    val sessionStatus: StateFlow<SessionStatus> = repo.auth.sessionStatus as StateFlow<SessionStatus>
    val error = MutableStateFlow<String?>(null)
    // Non-error guidance (confirmation mail sent, reset mail sent…).
    val info = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    // Offline cold start: the SDK reports NetworkError when it can't refresh the
    // stored session. If one IS stored locally, let the user into the app (the
    // screens render their cached data); the SDK keeps retrying in the background
    // and flips to Authenticated once the connection returns. Null = not checked.
    val offlineWithSession = MutableStateFlow<Boolean?>(null)

    init {
        viewModelScope.launch {
            sessionStatus.collectLatest { st ->
                if (st is SessionStatus.NetworkError && offlineWithSession.value == null) {
                    offlineWithSession.value =
                        runCatching { repo.auth.sessionManager.loadSession() != null }.getOrDefault(false)
                }
            }
        }
    }

    fun signIn(email: String, pw: String) = viewModelScope.launch {
        busy.value = true
        error.value = null
        info.value = null
        runCatching { repo.signIn(email.trim(), pw) }
            .onFailure { error.value = friendlyAuthError(it) }
        busy.value = false
    }

    fun signUp(email: String, pw: String) = viewModelScope.launch {
        busy.value = true
        error.value = null
        info.value = null
        runCatching { repo.signUp(email.trim(), pw) }
            .onSuccess {
                // With email confirmation enabled there's no session yet — say
                // what to do instead of failing silently.
                if (repo.auth.currentSessionOrNull() == null) {
                    info.value = "Almost there — confirm your email from the link in your inbox, then sign in."
                }
            }
            .onFailure { error.value = friendlyAuthError(it) }
        busy.value = false
    }

    fun forgotPassword(email: String) = viewModelScope.launch {
        if (email.isBlank()) {
            error.value = "Type your email above first, then tap “Forgot password?”."
            return@launch
        }
        busy.value = true
        error.value = null
        runCatching { repo.resetPassword(email.trim()) }
            .onSuccess { info.value = "Check your email — we sent you a link to set a new password." }
            .onFailure { error.value = friendlyAuthError(it) }
        busy.value = false
    }
}

@Composable
fun AuthGate(vm: AuthViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val status by vm.sessionStatus.collectAsStateSafe()
    val offlineWithSession by vm.offlineWithSession.collectAsStateSafe()
    when (status) {
        is SessionStatus.Authenticated -> OnboardingGate(content)
        is SessionStatus.NotAuthenticated -> LoginScreen(vm)
        // Can't reach the server: with a locally-saved session, enter the app on
        // cached data (the SDK keeps retrying and signs in when back online).
        // Without one there's no account to show — fall back to the login form.
        is SessionStatus.NetworkError -> when (offlineWithSession) {
            true -> OnboardingGate(content)
            false -> LoginScreen(vm)
            null -> CenteredSpinner()
        }
        else -> CenteredSpinner()
    }
}

@Composable
private fun CenteredSpinner() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { CircularProgressIndicator() }
}

@Composable
private fun OnboardingGate(
    content: @Composable () -> Unit,
    vm: com.workoutmaker.app.ui.screens.OnboardingViewModel = hiltViewModel(),
) {
    val complete by vm.complete.collectAsStateSafe()
    when (complete) {
        true -> content()
        false -> com.workoutmaker.app.ui.screens.OnboardingScreen(vm)
        null -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }
    }
}

@Composable
private fun LoginScreen(vm: AuthViewModel) {
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    val error by vm.error.collectAsStateSafe()
    val info by vm.info.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Workout Maker", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        Text(
            "AI running & strength, synced to your Amazfit.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        val pwFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        OutlinedTextField(
            email, { email = it }, label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                imeAction = androidx.compose.ui.text.input.ImeAction.Next,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onNext = { pwFocus.requestFocus() },
            ),
        )
        OutlinedTextField(
            pw, { pw = it }, label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                .focusRequester(pwFocus),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { focus.clearFocus(); vm.signIn(email, pw) },
            ),
        )
        Button({ vm.signIn(email, pw) }, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = !busy) { Text("Sign in") }
        OutlinedButton({ vm.signUp(email, pw) }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy) { Text("Create account") }
        androidx.compose.material3.TextButton(
            onClick = { vm.forgotPassword(email) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            enabled = !busy,
        ) {
            Text(
                "Forgot password?",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.let { Text(it, color = com.workoutmaker.app.ui.theme.Red, modifier = Modifier.padding(top = 12.dp)) }
        info?.let {
            Text(
                it,
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
