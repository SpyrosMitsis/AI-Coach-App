package com.workoutmaker.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

// Lifecycle-aware: collection pauses while the app is backgrounded instead of
// recomposing invisible screens.
@Composable
fun <T> StateFlow<T>.collectAsStateSafe(): State<T> = collectAsStateWithLifecycle()
