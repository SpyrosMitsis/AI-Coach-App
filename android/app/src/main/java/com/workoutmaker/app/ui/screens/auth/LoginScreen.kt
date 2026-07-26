package com.workoutmaker.app.ui.screens.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.BackendConfig
import kotlinx.coroutines.launch
import com.workoutmaker.app.ui.AuthViewModel
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.BreathingBackdrop
import com.workoutmaker.app.ui.components.LogoMark
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.workoutmaker.app.data.AuthDeepLinks
import com.workoutmaker.app.data.WorkoutRepository

private enum class AuthMode { SignIn, Create }

// Staggered entrance: each tier fades in and drifts up with a small delay.
private fun Modifier.entrance(progress: Float): Modifier = graphicsLayer {
    alpha = progress
    translationY = (1f - progress) * 12.dp.toPx()
}

@Composable
internal fun LoginScreen(vm: AuthViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var pw by rememberSaveable { mutableStateOf("") }
    var showPw by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(AuthMode.SignIn) }
    val error by vm.error.collectAsStateSafe()
    val info by vm.info.collectAsStateSafe()
    val busy by vm.busy.collectAsStateSafe()
    val promptCreate by vm.promptCreate.collectAsStateSafe()

    // A failed sign-in against a non-existent account flips the form to Create.
    LaunchedEffect(promptCreate) {
        if (promptCreate) { mode = AuthMode.Create; vm.promptCreate.value = false }
    }

    // One-shot entrance choreography.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    @Composable
    fun tier(index: Int): Float {
        val p by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = tween(durationMillis = 400, delayMillis = index * 70),
            label = "tier$index",
        )
        return p
    }

    Box(Modifier.fillMaxSize()) {
        BreathingBackdrop(Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LogoMark(Modifier.entrance(tier(0)).padding(bottom = 20.dp))

                Text(
                    "Workout Maker",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.entrance(tier(1)),
                )
                Text(
                    "A coach that knows how you slept.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.entrance(tier(1)).padding(top = 4.dp, bottom = 28.dp),
                )

                val focus = LocalFocusManager.current
                val pwFocus = remember { FocusRequester() }
                val primaryAction: () -> Unit = {
                    focus.clearFocus()
                    when (mode) {
                        AuthMode.SignIn -> vm.signIn(email, pw)
                        AuthMode.Create -> vm.signUp(email, pw)
                    }
                }

                Column(Modifier.entrance(tier(2))) {
                    OutlinedTextField(
                        email, { email = it }, label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(onNext = { pwFocus.requestFocus() }),
                    )
                    OutlinedTextField(
                        pw, { pw = it }, label = { Text("Password") },
                        visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPw = !showPw }) {
                                Icon(
                                    if (showPw) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showPw) "Hide password" else "Show password",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(pwFocus),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { primaryAction() }),
                    )
                }

                Button(
                    onClick = primaryAction,
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp).entrance(tier(3)),
                    enabled = !busy,
                ) {
                    AnimatedContent(
                        targetState = busy to mode,
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                        label = "primaryLabel",
                    ) { (loading, m) ->
                        if (loading) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(if (m == AuthMode.SignIn) "Sign in" else "Create account")
                        }
                    }
                }

                TextButton(
                    onClick = { mode = if (mode == AuthMode.SignIn) AuthMode.Create else AuthMode.SignIn },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).entrance(tier(4)),
                    enabled = !busy,
                ) {
                    AnimatedContent(
                        targetState = mode,
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) },
                        label = "modeToggle",
                    ) { m ->
                        Text(
                            if (m == AuthMode.SignIn) "New here? Create an account" else "Have an account? Sign in",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = mode == AuthMode.SignIn,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                    modifier = Modifier.entrance(tier(4)),
                ) {
                    TextButton(
                        onClick = { vm.forgotPassword(email) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) {
                        Text(
                            "Forgot password?",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // onErrorContainer, not error: light-mode error is the pastel band
                // fill, which is unreadable as foreground text on the light paper.
                AnimatedVisibility(visible = error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
                AnimatedVisibility(visible = info != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Text(
                        info ?: "",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }

                // Notes arriving via auth deep links (expired/used email links).
                val deepLinkMsg by AuthDeepLinks.message.collectAsStateSafe()
                DisposableEffect(Unit) {
                    onDispose { AuthDeepLinks.message.value = null }
                }
                AnimatedVisibility(visible = deepLinkMsg != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Text(
                        deepLinkMsg ?: "",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }

                // Self-hosters: point this install at their own Supabase project.
                var showServerDialog by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { showServerDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp).entrance(tier(4)),
                    enabled = !busy,
                ) {
                    Text(
                        if (vm.backend.isCustom) "Server: ${vm.backend.url.removePrefix("https://").removePrefix("http://")}"
                        else "Advanced: custom server",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showServerDialog) CustomServerDialog(vm.backend) { showServerDialog = false }
            }
        }
    }
}

// Restarting the process is the price of the Supabase client being a Hilt
// singleton built at startup: a settings change can't rebuild it in place.
private fun relaunchApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    intent?.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
    )
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

@Composable
private fun CustomServerDialog(backend: BackendConfig, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(if (backend.isCustom) backend.url else "") }
    var anonKey by remember { mutableStateOf(if (backend.isCustom) backend.anonKey else "") }
    val valid = url.trim().startsWith("http") && anonKey.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom server") },
        text = {
            Column {
                Text(
                    "Point the app at your own Supabase project (see docs/SELF_HOSTING.md). " +
                        "The app restarts to apply the change.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    url, { url = it },
                    label = { Text("Supabase URL") },
                    placeholder = { Text("https://your-ref.supabase.co") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    anonKey, { anonKey = it },
                    label = { Text("Anon (public) key") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                )
                if (backend.isCustom) {
                    TextButton(
                        onClick = { backend.clearCustom(); relaunchApp(context) },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text("Reset to default server") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { backend.setCustom(url, anonKey); relaunchApp(context) },
                enabled = valid,
            ) { Text("Save & restart") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// Shown (over whatever screen is up) after a password-recovery deep link has
// imported its session; saving calls auth.updateUser with the new password.
@Composable
fun SetNewPasswordDialog(repo: WorkoutRepository) {
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismiss = { AuthDeepLinks.recoveryPending.value = false }
    // The reset link itself signed the user in, so backing out of the dialog
    // must not leave that session usable: cancelling signs out again. The only
    // way to stay signed in through a recovery link is to set a new password.
    val cancel: () -> Unit = {
        if (!busy) {
            busy = true
            scope.launch {
                runCatching { repo.signOut() }
                dismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = cancel,
        title = { Text("Set a new password") },
        text = {
            Column {
                Text(
                    "You followed a password reset link. Pick a new password to stay signed in. Cancelling signs you out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    pw, { pw = it }, label = { Text("New password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    singleLine = true,
                )
                OutlinedTextField(
                    confirm, { confirm = it }, label = { Text("Repeat new password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                )
                msg?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (pw.length < 6) { msg = "Use at least 6 characters."; return@TextButton }
                    if (pw != confirm) { msg = "The two passwords don't match."; return@TextButton }
                    busy = true
                    msg = null
                    scope.launch {
                        runCatching { repo.updatePassword(pw) }
                            .onSuccess { dismiss() }
                            .onFailure { msg = it.message ?: "Couldn't save the new password. Try again." }
                        busy = false
                    }
                },
            ) { Text(if (busy) "Saving…" else "Save password") }
        },
        dismissButton = { TextButton(onClick = cancel, enabled = !busy) { Text("Cancel") } },
    )
}
