package com.workoutmaker.app.ui.components

import com.workoutmaker.app.data.Zones
import kotlin.math.abs
import kotlin.math.roundToInt

// Canonical formatters — one source of truth so durations, paces, weights and
// loads read identically on every screen. Older helpers (fmtPaceSec, trimKg, the
// per-screen fmtClock) delegate here.

/** Seconds → "m:ss", or "h:mm:ss" once past an hour. */
fun fmtClock(totalSec: Int): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun fmtClock(totalSec: Long): String = fmtClock(totalSec.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())

/** Pace in sec/km → "m:ss". */
fun fmtPace(secPerKm: Int): String = Zones.formatPace(secPerKm)

/** Weight in kg → a clean number: "100", "102.5" (no trailing ".0", ≤1 decimal). */
fun fmtWeight(kg: Double): String =
    if (abs(kg - kg.toLong()) < 0.05) kg.toLong().toString()
    else ((kg * 10).toLong() / 10.0).toString()

/** Training load → a whole number. */
fun fmtTss(tss: Double): String = tss.roundToInt().toString()

/** Form (TSB) → a signed whole number, e.g. "+3" / "-12". */
fun fmtSignedTsb(tsb: Double): String = "%+.0f".format(tsb)

/**
 * Turn a raw error (exception or message) into something a person can act on.
 * Network/timeout failures become a friendly connection hint; long technical
 * stack-y strings collapse to a generic retry message.
 */
fun friendlyError(error: Any?): String {
    val msg = when (error) {
        is Throwable -> error.message ?: error.toString()
        null -> ""
        else -> error.toString()
    }
    val low = msg.lowercase()
    return when {
        low.isBlank() -> "Something went wrong. Please try again."
        listOf(
            "unable to resolve host", "failed to connect", "network", "timeout",
            "timed out", "unreachable", "no address associated", "connection",
        ).any { it in low } -> "Can't reach the server, check your connection."
        "unauthor" in low || "401" in low -> "Your session expired, please sign in again."
        msg.length > 140 -> "Something went wrong. Please try again."
        else -> msg
    }
}
