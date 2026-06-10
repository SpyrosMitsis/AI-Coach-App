package com.workoutmaker.app.data

// E1 — derive training zones from thresholds. Pure functions so they're unit-
// testable and shared by the Settings UI and (later) workout targets.
//
// HR zones use Joe Friel's running model as % of LTHR (lactate-threshold HR).
// Pace zones are multiples of threshold pace (your ~1-hour race pace). Power
// zones are % of FTP (Coggan, collapsed to 5 bands).

data class PaceZone(val name: String, val fastSec: Int, val slowSec: Int) {
    val range: String get() = "${Zones.formatPace(fastSec)}–${Zones.formatPace(slowSec)} /km"
}

data class PowerZone(val name: String, val min: Int, val max: Int) {
    val range: String get() = "$min–$max W"
}

object Zones {

    /** Parse "m:ss" (or "mm:ss") into seconds. Returns null if malformed. */
    fun parsePace(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val m = parts[0].toIntOrNull() ?: return null
        val s = parts[1].toIntOrNull() ?: return null
        if (m < 0 || s < 0 || s >= 60) return null
        return m * 60 + s
    }

    fun formatPace(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    // % of LTHR boundaries (lower-bound of each zone), Friel running.
    private val HR_BANDS = listOf(
        "Z1 Recovery" to 0.0,
        "Z2 Aerobic" to 0.81,
        "Z3 Tempo" to 0.90,
        "Z4 Threshold" to 0.94,
        "Z5 VO2max" to 1.00,
    )

    fun hrZonesFromLthr(lthr: Int): List<HrZone> {
        if (lthr <= 0) return emptyList()
        val out = mutableListOf<HrZone>()
        for (i in HR_BANDS.indices) {
            val lo = Math.round(HR_BANDS[i].second * lthr).toInt()
            val hi = if (i < HR_BANDS.size - 1) Math.round(HR_BANDS[i + 1].second * lthr).toInt() - 1
                else Math.round(1.15 * lthr).toInt()
            out.add(HrZone(HR_BANDS[i].first, lo, hi))
        }
        return out
    }

    // Pace zone bounds as multipliers of threshold pace (faster = smaller).
    // (fast×, slow×) per zone, from Z1 (easy/slow) to Z5 (fast).
    private val PACE_BANDS = listOf(
        "Z1 Easy" to (1.15 to 1.30),
        "Z2 Aerobic" to (1.06 to 1.15),
        "Z3 Tempo" to (1.01 to 1.06),
        "Z4 Threshold" to (0.97 to 1.01),
        "Z5 Interval" to (0.88 to 0.97),
    )

    fun paceZonesFromThreshold(thresholdSecPerKm: Int): List<PaceZone> {
        if (thresholdSecPerKm <= 0) return emptyList()
        return PACE_BANDS.map { (name, mult) ->
            val fast = Math.round(thresholdSecPerKm * mult.first).toInt()
            val slow = Math.round(thresholdSecPerKm * mult.second).toInt()
            PaceZone(name, fast, slow)
        }
    }

    // % of FTP lower bounds (Coggan, collapsed to 5).
    private val POWER_BANDS = listOf(
        "Z1 Recovery" to 0.0,
        "Z2 Endurance" to 0.56,
        "Z3 Tempo" to 0.76,
        "Z4 Threshold" to 0.91,
        "Z5 VO2max+" to 1.06,
    )

    fun powerZonesFromFtp(ftp: Int): List<PowerZone> {
        if (ftp <= 0) return emptyList()
        val out = mutableListOf<PowerZone>()
        for (i in POWER_BANDS.indices) {
            val lo = Math.round(POWER_BANDS[i].second * ftp).toInt()
            val hi = if (i < POWER_BANDS.size - 1) Math.round(POWER_BANDS[i + 1].second * ftp).toInt() - 1
                else Math.round(1.50 * ftp).toInt()
            out.add(PowerZone(POWER_BANDS[i].first, lo, hi))
        }
        return out
    }
}
