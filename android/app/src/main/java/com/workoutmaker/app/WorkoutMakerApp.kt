package com.workoutmaker.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.workoutmaker.app.work.CheckinReminderScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WorkoutMakerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        com.workoutmaker.app.notify.Notifications.ensureChannels(this)
        // Schedule the recurring 7am wellness check-in reminder.
        CheckinReminderScheduler.schedule(this)
        // Evening "how did it feel?" prompt that feeds the autoregulation loop.
        com.workoutmaker.app.work.FeedbackPromptScheduler.schedule(this)
        // Flush any strength changes made offline last session (runs when online).
        com.workoutmaker.app.work.StrengthSync.request(this)
    }
}
