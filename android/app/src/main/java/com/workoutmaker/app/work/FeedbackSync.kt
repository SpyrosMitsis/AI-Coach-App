package com.workoutmaker.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.workoutmaker.app.data.WorkoutFeedback
import com.workoutmaker.app.data.WorkoutRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import com.workoutmaker.app.data.refreshMemory
import com.workoutmaker.app.data.submitFeedback

// Durably submit a post-workout effort rating. The set data is already safe in
// Room; this makes the session RPE/difficulty survive being offline at the gym
// (or even an app kill) instead of being dropped by a swallowed insert. The
// payload rides in WorkManager's input Data, which persists across restarts, so
// no extra local table is needed. On a network failure WorkManager retries with
// backoff — even if the app has been closed.
@HiltWorker
class FeedbackSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repo: WorkoutRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Not signed in → nothing to do (don't retry forever).
        if (repo.auth.currentUserOrNull() == null) return Result.success()
        val date = inputData.getString(KEY_DATE) ?: return Result.success()
        val rpe = inputData.getInt(KEY_RPE, -1).takeIf { it in 1..10 }
        val difficulty = inputData.getString(KEY_DIFFICULTY)?.takeIf { it.isNotBlank() }
        val notes = inputData.getString(KEY_NOTES)?.takeIf { it.isNotBlank() }
        return try {
            // Only write a feedback row when there's an actual rating; a bare
            // "completed" is already implied by the synced session.
            if (rpe != null || difficulty != null) {
                repo.submitFeedback(
                    WorkoutFeedback(date = date, completed = true, actual_rpe = rpe, difficulty = difficulty, notes = notes),
                )
            }
            // Fold the session into the coach's rolling memory; best-effort.
            runCatching { repo.refreshMemory() }
            Result.success()
        } catch (_: Exception) {
            // Network/transient failure → retry with backoff.
            Result.retry()
        }
    }

    companion object {
        private const val KEY_DATE = "date"
        private const val KEY_RPE = "rpe"
        private const val KEY_DIFFICULTY = "difficulty"
        private const val KEY_NOTES = "notes"

        /** Queue a durable, retried feedback submission for a finished session. */
        fun request(context: Context, date: String, rpe: Int?, difficulty: String?, notes: String?) {
            val req = OneTimeWorkRequestBuilder<FeedbackSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_DATE to date,
                        KEY_RPE to (rpe ?: -1),
                        KEY_DIFFICULTY to (difficulty ?: ""),
                        KEY_NOTES to (notes ?: ""),
                    ),
                )
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
