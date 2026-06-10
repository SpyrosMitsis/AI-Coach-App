package com.workoutmaker.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow

// Thin alias so screens don't need the extra lifecycle-compose dependency.
@Composable
fun <T> StateFlow<T>.collectAsStateSafe(): State<T> = collectAsState()
