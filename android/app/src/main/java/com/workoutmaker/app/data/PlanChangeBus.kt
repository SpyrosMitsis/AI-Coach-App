package com.workoutmaker.app.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The missing wire between screens: Home and Calendar keep their ViewModels
 * alive across tab switches (saveState/restoreState), so when the COACH edits
 * the plan mid-chat (plan_week, generate_workout, move_workout, set_rest_day,
 * make_easier) they kept showing yesterday's calendar until a manual
 * pull-to-refresh. Emit here after any plan-mutating action; interested
 * ViewModels collect and re-run their existing loaders.
 *
 * Deliberately fire-and-forget with a small buffer: a missed event costs one
 * stale screen until the next refresh, never a crash or a block.
 */
data class PlanChange(val source: String)

@Singleton
class PlanChangeBus @Inject constructor() {
    private val _changes = MutableSharedFlow<PlanChange>(extraBufferCapacity = 8)
    val changes = _changes.asSharedFlow()

    fun emit(source: String) {
        _changes.tryEmit(PlanChange(source))
    }

    /**
     * A date (yyyy-MM-dd) another screen wants the Calendar to open on — set by
     * chat's tappable workout cards right before navigating. Lives here rather
     * than in nav arguments because the calendar tab restores state
     * (restoreState = true), so its route never re-composes with new args.
     * The CalendarViewModel consumes it exactly once.
     */
    val focusDate = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}
