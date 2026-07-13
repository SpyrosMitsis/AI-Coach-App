package com.workoutmaker.app.util

import android.util.Log
import com.workoutmaker.app.BuildConfig

/**
 * Thin, debug-gated wrapper over [android.util.Log].
 *
 * Everything logs under one tag family ("WM") so the whole app's chatter can be
 * filtered with a single grep — `scripts/dev.sh android:log` keys off it. Calls
 * no-op in release builds (BuildConfig.DEBUG == false), so it's safe to leave in
 * hot paths. The `area` is a short subsystem label, e.g. AppLog.i("gen", ...).
 */
object AppLog {
    private const val TAG = "WM"

    fun d(area: String, msg: String) { if (BuildConfig.DEBUG) Log.d(TAG, "[$area] $msg") }
    fun i(area: String, msg: String) { if (BuildConfig.DEBUG) Log.i(TAG, "[$area] $msg") }
    fun w(area: String, msg: String, t: Throwable? = null) { if (BuildConfig.DEBUG) Log.w(TAG, "[$area] $msg", t) }
    fun e(area: String, msg: String, t: Throwable? = null) { if (BuildConfig.DEBUG) Log.e(TAG, "[$area] $msg", t) }

    /**
     * Run [block], logging its elapsed wall-clock ms and ok/failed outcome.
     * Inlined so it threads through `suspend` call sites (network/LLM latency).
     */
    inline fun <T> time(area: String, label: String, block: () -> T): T {
        val started = System.currentTimeMillis()
        try {
            val r = block()
            i(area, "$label ok ${System.currentTimeMillis() - started}ms")
            return r
        } catch (t: Throwable) {
            e(area, "$label failed ${System.currentTimeMillis() - started}ms: ${t.message}", t)
            throw t
        }
    }
}
