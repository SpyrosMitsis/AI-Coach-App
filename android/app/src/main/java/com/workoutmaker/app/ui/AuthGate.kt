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

@HiltViewModel
class AuthViewModel @Inject constructor(
    val repo: WorkoutRepository,
) : ViewModel() {
    val sessionStatus: StateFlow<SessionStatus> = repo.auth.sessionStatus as StateFlow<SessionStatus>
    val error = MutableStateFlow<String?>(null)

    fun signIn(email: String, pw: String) = viewModelScope.launch {
        runCatching { repo.signIn(email, pw) }.onFailure { error.value = it.message }
    }

    fun signUp(email: String, pw: String) = viewModelScope.launch {
        runCatching { repo.signUp(email, pw) }.onFailure { error.value = it.message }
    }
}

@Composable
fun AuthGate(vm: AuthViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val status by vm.sessionStatus.collectAsStateSafe()
    when (status) {
        is SessionStatus.Authenticated -> OnboardingGate(content)
        is SessionStatus.NotAuthenticated -> LoginScreen(vm)
        else -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }
    }
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
        Button({ vm.signIn(email, pw) }, Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Sign in") }
        OutlinedButton({ vm.signUp(email, pw) }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Create account") }
        error?.let { Text(it, color = com.workoutmaker.app.ui.theme.Red, modifier = Modifier.padding(top = 12.dp)) }
    }
}
