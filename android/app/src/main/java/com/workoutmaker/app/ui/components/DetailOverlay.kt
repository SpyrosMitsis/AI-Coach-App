package com.workoutmaker.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Wraps a full-screen detail page so it slides + fades in from the right when it
 * appears, instead of swapping in abruptly. Drop it around a detail screen that
 * is shown via an early `return` overlay; the enter animation plays on the first
 * composition.
 */
@Composable
fun DetailOverlay(content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = slideInHorizontally(tween(240)) { it / 3 } + fadeIn(tween(240)),
        exit = fadeOut(tween(150)),
    ) {
        content()
    }
}
