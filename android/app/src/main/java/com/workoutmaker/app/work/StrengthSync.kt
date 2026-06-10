package com.workoutmaker.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.workoutmaker.app.strength.StrengthRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

// Drains the offline strength sync queue (unsynced workouts/routines/custom
// exercises + pending deletes). The work request carries a CONNECTED network
// constraint, so WorkManager runs it the moment a connection is available —
// even if the app has been closed — and retries with backoff on failure.
@HiltWorker
class StrengthSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repo: StrengthRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        repo.syncPending()
        Result.success()
    } catch (_: Exception) {
        // Network/transient failure → let WorkManager retry with backoff.
        Result.retry()
    }
}

object StrengthSync {
    private const val WORK_NAME = "strength-sync"

    /** Request a sync. Runs now if online, otherwise as soon as a network appears. */
    fun request(context: Context) {
        val req = OneTimeWorkRequestBuilder<StrengthSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        // KEEP: if a sync is already queued/running, don't pile up duplicates.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, req)
    }
}
