package com.workoutmaker.app.util

import android.content.Context
import android.os.Build
import com.workoutmaker.app.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

// Crash visibility without any third-party SDK (the privacy policy promises
// "no analytics, no trackers", and the foss flavor must stay Google-free).
// On an uncaught exception we write ONE small JSON file to filesDir/crashes/
// and hand off to the previous handler, so the system crash dialog and Play
// vitals behave exactly as before. No network in the dying process: the next
// app start uploads pending files to the crash_reports table (best effort).
@Serializable
data class CrashRecord(
    val crashed_at: String,
    val version_name: String,
    val version_code: Int,
    val flavor: String,
    val sdk_int: Int,
    val device: String,
    val thread: String,
    val exception: String,
    val stack: String,
    val fatal: Boolean = true,
)

object CrashReporter {
    private const val DIR = "crashes"
    private const val MAX_PENDING = 5
    private const val MAX_STACK_CHARS = 6_000
    // Give up on files this old: the insert has failed repeatedly (e.g. the
    // user never comes back online, or the table doesn't exist yet).
    private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    private val json = Json { encodeDefaults = true }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { persist(app, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun persist(context: Context, thread: Thread, t: Throwable) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        // Bound disk usage: keep only the newest few unsent crashes.
        dir.listFiles()?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_PENDING - 1)?.forEach { it.delete() }
        val record = CrashRecord(
            crashed_at = Instant.now().toString(),
            version_name = BuildConfig.VERSION_NAME,
            version_code = BuildConfig.VERSION_CODE,
            flavor = BuildConfig.FLAVOR,
            sdk_int = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            thread = thread.name,
            exception = t.toString().take(500),
            stack = t.stackTraceToString().take(MAX_STACK_CHARS),
        )
        File(dir, "crash-${System.currentTimeMillis()}.json")
            .writeText(json.encodeToString(record))
    }

    /** Pending crash files, oldest first; stale ones are pruned here. */
    fun pending(context: Context): List<File> {
        val dir = File(context.filesDir, DIR)
        val files = dir.listFiles()?.toList().orEmpty()
        val (stale, fresh) = files.partition {
            System.currentTimeMillis() - it.lastModified() > MAX_AGE_MS
        }
        stale.forEach { it.delete() }
        return fresh.sortedBy { it.lastModified() }
    }

    fun parse(file: File): CrashRecord? =
        runCatching { json.decodeFromString<CrashRecord>(file.readText()) }.getOrNull()
}
