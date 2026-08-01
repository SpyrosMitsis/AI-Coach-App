package com.workoutmaker.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.workoutmaker.app.work.CheckinReminderScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import com.workoutmaker.app.notify.Notifications
import com.workoutmaker.app.util.AppLog
import com.workoutmaker.app.util.CrashReporter
import com.workoutmaker.app.work.FeedbackPromptScheduler
import com.workoutmaker.app.work.StrengthSync

@HiltAndroidApp
class WorkoutMakerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Local debug log file, so AppLog calls have somewhere to write from the
        // very first line (export/send-to-developer both read this file).
        AppLog.install(this)
        // First: capture uncaught exceptions to disk (uploaded on next start).
        CrashReporter.install(this)
        Notifications.ensureChannels(this)
        // Schedule the recurring 7am wellness check-in reminder.
        CheckinReminderScheduler.schedule(this)
        // Evening "how did it feel?" prompt that feeds the autoregulation loop.
        FeedbackPromptScheduler.schedule(this)
        // Flush any strength changes made offline last session (runs when online).
        StrengthSync.request(this)
    }
}
