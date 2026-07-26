package com.workoutmaker.app.ui.components

import com.workoutmaker.app.data.Zones
import kotlin.math.abs

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

