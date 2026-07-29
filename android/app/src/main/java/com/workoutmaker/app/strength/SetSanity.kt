package com.workoutmaker.app.strength

// Typo guard for set entry: a fat-fingered 845 where 84.5 was meant becomes a
// permanent "PR" and poisons every downstream suggestion (progression targets,
// 1RM estimates, volume records). So the done-toggle asks for confirmation when
// an entry lands far outside the athlete's usual values. Pure and stateless so
// the thresholds are unit-testable without Compose.
object SetSanity {

    // Relative bounds vs the athlete's own baseline (last time, else the
    // suggestion). 1.5x up / half down clears any legitimate progression jump;
    // doubling reps clears an AMRAP gone well.
    private const val WEIGHT_HIGH = 1.5
    private const val WEIGHT_LOW = 0.5
    private const val REPS_HIGH = 2.0

    // Absolute backstops for when there is no history at all.
    private const val WEIGHT_CAP_KG = 400.0
    private const val REPS_CAP = 60

    /**
     * Returns a short question to confirm with, or null when the entry looks
     * normal. [warmup] sets only get the absolute backstops: lighter warmup
     * loads are the point, not a typo.
     */
    fun check(
        weight: Double?,
        reps: Int?,
        baselineWeight: Double?,
        baselineReps: Int?,
        warmup: Boolean = false,
    ): String? {
        if (weight != null && weight > WEIGHT_CAP_KG) {
            return "${trim(weight)} kg is beyond any human lift. Log it anyway?"
        }
        if (reps != null && reps > REPS_CAP) {
            return "$reps reps in one set is extremely unusual. Log it anyway?"
        }
        if (warmup) return null

        if (weight != null && baselineWeight != null && baselineWeight > 0) {
            if (weight > baselineWeight * WEIGHT_HIGH) {
                return "${trim(weight)} kg is a big jump from your usual ${trim(baselineWeight)} kg. Log it anyway?"
            }
            if (weight < baselineWeight * WEIGHT_LOW) {
                return "${trim(weight)} kg is far below your usual ${trim(baselineWeight)} kg. Log it anyway?"
            }
        }
        if (reps != null && baselineReps != null && baselineReps > 0 && reps > baselineReps * REPS_HIGH) {
            return "$reps reps is far more than your usual $baselineReps. Log it anyway?"
        }
        return null
    }

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
