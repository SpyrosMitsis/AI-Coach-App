package com.workoutmaker.app.work

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.workoutmaker.app.data.WorkoutRepository
import com.workoutmaker.app.notify.Notifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import java.time.LocalDate
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

// Evening prompt: if a completed activity matched today's planned session but
// no feedback was logged, ask "how did it feel?" — feedback is what closes the
// autoregulation loop (the generator reads it to adjust the next sessions).
@HiltWorker
class FeedbackPromptWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repo: WorkoutRepository,
    private val supabase: SupabaseClient,
) : CoroutineWorker(appContext, params) {

    @Serializable
    private data class FeedbackRow(val date: String)

    override suspend fun doWork(): Result {
        // Not signed in → nothing to do (don't retry).
        if (repo.auth.currentUserOrNull() == null) return Result.success()

        val today = LocalDate.now().toString()
        val pending = runCatching {
            val planned = repo.plannedWorkouts(today).filter { it.date == today && it.type != "rest" }
            if (planned.isEmpty()) return@runCatching null

            val acts = repo.completedActivities(today)
            val done = planned.firstOrNull { p ->
                p.completed || acts.any { a -> typeLooksLike(p.type, a.type) }
            } ?: return@runCatching null

            val feedback: List<FeedbackRow> = supabase.postgrest.from("workout_feedback").select {
                filter { eq("date", today) }
            }.decodeList()
            if (feedback.isNotEmpty()) null else done
        }.getOrNull() ?: return Result.success()

        val title = pending.workout_json.title.ifBlank { "today's session" }
        notify(title)
        return Result.success()
    }

    private fun typeLooksLike(plannedType: String, actualType: String?): Boolean {
        val a = (actualType ?: "").lowercase()
        return when (plannedType.lowercase()) {
            "run" -> a.contains("run") || a.contains("walk")
            "ride" -> a.contains("ride") || a.contains("bike") || a.contains("cycl")
            "swim" -> a.contains("swim")
            "strength" -> a.contains("weight") || a.contains("strength") || a.contains("workout") || a.contains("gym")
            else -> false
        }
    }

    private fun notify(workoutTitle: String) {
        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val launch = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val pi = launch?.let {
            android.app.PendingIntent.getActivity(
                applicationContext, 0, it,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val n = NotificationCompat.Builder(applicationContext, Notifications.CH_REMINDERS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("How did it feel?")
            .setContentText("Rate \"$workoutTitle\", your coach uses it to tune the next sessions.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(ID, n)
    }

    private companion object {
        const val ID = 1002
    }
}

object FeedbackPromptScheduler {
    fun schedule(context: Context) {
        // Fire at the next 18:30 local time, then daily.
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 18); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val request = PeriodicWorkRequestBuilder<FeedbackPromptWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(target.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "evening-feedback", ExistingPeriodicWorkPolicy.UPDATE, request,
        )
    }
}
