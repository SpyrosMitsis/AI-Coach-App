package com.workoutmaker.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.workoutmaker.app.data.RestChime
import java.time.LocalDateTime

object Notifications {
    const val CH_TIMER = "rest_timer"
    const val CH_REMINDERS = "reminders"
    const val CH_WORKOUT = "active_workout"
    const val ID_REST_OVER = 2001

    // Time-aware greeting so a reminder that fires late never says "Good
    // morning" at 3pm. A few variants per slot, rotated by day of year, keep
    // the daily notification from reading word-for-word identical.
    fun greeting(now: LocalDateTime = LocalDateTime.now()): String {
        val pool = when (now.hour) {
            in 0..11 -> listOf("Good morning", "Morning", "New day")
            in 12..16 -> listOf("Good afternoon", "Afternoon", "Midday check")
            else -> listOf("Good evening", "Evening", "Day's end")
        }
        return pool[now.dayOfYear % pool.size]
    }

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

/** A light celebratory triple-tap, softer and quicker than [vibrateStrong]. */
fun vibrateCelebrate(ctx: Context) {
    runCatching {
        vibrator(ctx)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 30, 60, 55), -1))
    }
}

/** A stronger double-buzz used when a rest timer ends. */
fun vibrateStrong(ctx: Context) {
    runCatching {
        vibrator(ctx)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 250), -1))
    }
}

/** Play the user's chosen rest-over cue directly (foreground path, where we
 *  don't want to post a heads-up banner). Everything routes through the MEDIA
 *  stream — the one music uses — so it still sounds with the phone on silent,
 *  mixing over whatever's playing (gym scenario: silent mode + earbuds). */
fun playRestOverSound(ctx: Context, chime: RestChime = RestChime.SYSTEM) {
    runCatching {
        when (chime) {
            RestChime.SILENT -> return
            RestChime.SYSTEM -> {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(ctx, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    play()
                }
            }
            RestChime.CHIME -> playTone(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 700)
            RestChime.BEEP -> playTone(ToneGenerator.TONE_PROP_BEEP, 250)
            RestChime.DOUBLE_BEEP -> playTone(ToneGenerator.TONE_PROP_BEEP2, 750)
        }
    }
}

/**
 * A soft, quiet tick for the last seconds of a rest countdown, so the athlete
 * gets ready for the set instead of being startled by the buzzer. Deliberately
 * quieter (vol 45 vs the cue's 85) and short; routes through MEDIA like the
 * rest-over cue so it mixes over music with the phone on silent.
 */
fun playCountdownTick(ctx: Context) {
    runCatching {
        val gen = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
        gen.startTone(ToneGenerator.TONE_PROP_ACK, 60)
        Handler(ctx.mainLooper).postDelayed({ runCatching { gen.release() } }, 200)
    }
}

private fun playTone(tone: Int, durationMs: Int) {
    val gen = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
    gen.startTone(tone, durationMs)
    // Release once the tone has played — ToneGenerator holds a native audio track.
    Handler(Looper.getMainLooper()).postDelayed({ runCatching { gen.release() } }, durationMs + 150L)
}

/** Fires when a scheduled rest period ends — even if the app was killed or the
 *  screen is off. The notification channel carries the sound; we add a strong buzz. */
class RestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        vibrateStrong(context)
        Notifications.notifyRestOver(context)
    }
}
