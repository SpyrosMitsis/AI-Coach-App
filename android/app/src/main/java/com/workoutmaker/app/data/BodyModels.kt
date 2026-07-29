package com.workoutmaker.app.data

import kotlinx.serialization.Serializable
import java.time.LocalDate

// Kotlin mirror of /shared/types.ts. Keep field names in sync with the schema.
//
// Body composition: the smart-scale trends behind the Body history screen,
// plus the derived maths (lean mass, weekly slope) the coach reads.

// Compact goal-aware trend computed server-side (_shared/body_trend.ts). The
// plots read the full history via bodyHistory(); this is just the verdict.
@Serializable
data class BodyMetricTrend(
    val latest: Double = 0.0,
    val latestDate: String = "",
    val slopePerWeek: Double? = null,
    val points: Int = 0,
)

@Serializable
data class BodyTrend(
    val focus: String = "general", // muscle | fat_loss | recomp | general
    val weight: BodyMetricTrend? = null,
    val bodyFat: BodyMetricTrend? = null,
    val leanMass: BodyMetricTrend? = null,
    val onTrack: Boolean? = null,
    val summary: String = "",
)

// One day's measured body metrics from wellness_checkins (nullable per column).
@Serializable
data class BodyHistoryPoint(
    val date: String,
    val weight_kg: Double? = null,
    val body_fat_pct: Double? = null,
    val lean_mass_kg: Double? = null,
)

// Upsert row for a scale sync or manual quick-log (server merge on user_id,date).
@Serializable
data class BodyMetricUpsert(
    val date: String,
    val weight_kg: Double? = null,
    val body_fat_pct: Double? = null,
    val lean_mass_kg: Double? = null,
    val source: String = "health_connect",
)

// Lean mass fallback when the scale only writes weight + fat. Mirrors the
// backend's derivation in body_trend.ts so both sides chart the same number.
fun deriveLeanKg(weightKg: Double?, bodyFatPct: Double?): Double? {
    if (weightKg == null || bodyFatPct == null) return null
    if (weightKg !in 30.0..250.0 || bodyFatPct !in 3.0..60.0) return null
    return Math.round(weightKg * (1 - bodyFatPct / 100) * 10) / 10.0
}

// Least-squares change per week over dated points, mirroring body_trend.ts:
// null unless >= 3 points spanning >= 14 days (day-to-day water weight is not
// a trend). Dates are ISO yyyy-MM-dd; values are already bounds-checked.
fun slopePerWeek(points: List<Pair<String, Double>>): Double? {
    val dated = points.mapNotNull { (d, v) ->
        runCatching { LocalDate.parse(d).toEpochDay() }.getOrNull()?.let { it to v }
    }.sortedBy { it.first }
    if (dated.size < 3 || dated.last().first - dated.first().first < 14) return null
    val xs = dated.map { (it.first - dated.first().first) / 7.0 }
    val ys = dated.map { it.second }
    val mx = xs.average()
    val my = ys.average()
    var num = 0.0
    var den = 0.0
    for (i in xs.indices) {
        num += (xs[i] - mx) * (ys[i] - my)
        den += (xs[i] - mx) * (xs[i] - mx)
    }
    if (den == 0.0) return null
    return Math.round(num / den * 100) / 100.0
}
