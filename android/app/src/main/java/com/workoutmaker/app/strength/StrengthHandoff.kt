package com.workoutmaker.app.strength

import com.workoutmaker.app.data.Workout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-tab handoff so the Calendar can open a *planned* strength session in the
 * Strength logger, pre-filled and linked back to the plan. The two screens have
 * separate ViewModels (different nav back-stack entries), so a shared singleton
 * carries the request between them.
 */
@Singleton
class StrengthHandoff @Inject constructor() {
    data class Start(val workout: Workout, val plannedId: String, val date: String)

    private val _pending = MutableStateFlow<Start?>(null)
    val pending = _pending.asStateFlow()

    fun request(workout: Workout, plannedId: String, date: String) {
        _pending.value = Start(workout, plannedId, date)
    }

    fun clear() { _pending.value = null }
}
