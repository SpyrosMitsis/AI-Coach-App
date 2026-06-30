package com.workoutmaker.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// Hands the workout to play from Home to the player screen without serializing a
// whole Workout through a nav argument. Set it just before navigating to "player".
@Singleton
class WorkoutPlayerHolder @Inject constructor() {
    val workout = MutableStateFlow<Workout?>(null)
}
