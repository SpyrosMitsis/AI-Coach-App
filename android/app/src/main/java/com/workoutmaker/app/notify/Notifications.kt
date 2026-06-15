package com.workoutmaker.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

object Notifications {
    const val CH_TIMER = "rest_timer"
    const val CH_REMINDERS = "reminders"
    const val CH_WORKOUT = "active_workout"
    const val ID_REST_OVER = 2001

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_TIMER, "Rest timer", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when your set rest period ends"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 90, 180)
                // Explicit alarm-style sound so it rings even with the screen off.
                val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(sound, attrs)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Daily check-in and workout reminders"
            },
        )
        // Silent, ongoing channel for the live "workout in progress" notification.
        nm.createNotificationChannel(
            NotificationChannel(CH_WORKOUT, "Active workout", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps your session alive with a live timer while you train"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun notifyRestOver(ctx: Context) {
        runCatching {
            val n = NotificationCompat.Builder(ctx, CH_TIMER)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Rest complete")
                .setContentText("Time for your next set 💪")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build()
            NotificationManager::class.java.let {
                ctx.getSystemService(NotificationManager::class.java)?.notify(ID_REST_OVER, n)
            }
        }
    }
}

private fun vibrator(ctx: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= 31) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

fun vibrateOnce(ctx: Context, ms: Long = 450) {
    runCatching { vibrator(ctx)?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
}

/** A stronger double-buzz used when a rest timer ends. */
fun vibrateStrong(ctx: Context) {
    runCatching {
        vibrator(ctx)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 250), -1))
    }
}

/** Play the default notification tone directly (used for the foreground
 *  rest-over cue, where we don't want to post a heads-up banner). */
fun playRestOverSound(ctx: Context) {
    runCatching {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        RingtoneManager.getRingtone(ctx, uri)?.play()
    }
}

/** Fires when a scheduled rest period ends — even if the app was killed or the
 *  screen is off. The notification channel carries the sound; we add a strong buzz. */
class RestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        vibrateStrong(context)
        Notifications.notifyRestOver(context)
    }
}
