package com.workoutmaker.app.strength

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetSanityTest {

    @Test
    fun `normal progression passes`() {
        // +2.5kg on an 80kg bench, one extra rep: everyday training.
        assertNull(SetSanity.check(weight = 82.5, reps = 6, baselineWeight = 80.0, baselineReps = 5))
    }

    @Test
    fun `the missing-decimal typo flags`() {
        // 845 where 84.5 was meant: the exact failure this guard exists for.
        val q = SetSanity.check(weight = 845.0, reps = 5, baselineWeight = 84.5, baselineReps = 5)
        assertNotNull(q)
        assertTrue(q!!.contains("845"))
    }

    @Test
    fun `a 10x rep typo flags`() {
        assertNotNull(SetSanity.check(weight = 80.0, reps = 50, baselineWeight = 80.0, baselineReps = 5))
    }

    @Test
    fun `below half the usual load flags`() {
        assertNotNull(SetSanity.check(weight = 30.0, reps = 5, baselineWeight = 80.0, baselineReps = 5))
    }

    @Test
    fun `a jump just inside 1_5x passes`() {
        assertNull(SetSanity.check(weight = 119.0, reps = 5, baselineWeight = 80.0, baselineReps = 5))
    }

    @Test
    fun `no baseline means only the absolute caps apply`() {
        assertNull(SetSanity.check(weight = 180.0, reps = 3, baselineWeight = null, baselineReps = null))
        assertNotNull(SetSanity.check(weight = 500.0, reps = 3, baselineWeight = null, baselineReps = null))
        assertNotNull(SetSanity.check(weight = null, reps = 90, baselineWeight = null, baselineReps = null))
    }

    @Test
    fun `warmups skip the relative checks but not the caps`() {
        // Half the working weight is what a warmup IS.
        assertNull(SetSanity.check(weight = 40.0, reps = 8, baselineWeight = 100.0, baselineReps = 5, warmup = true))
        assertNotNull(SetSanity.check(weight = 500.0, reps = 8, baselineWeight = 100.0, baselineReps = 5, warmup = true))
    }

    @Test
    fun `fewer reps than usual never flags`() {
        // A heavy triple after a set of 8 is training, not a typo.
        assertNull(SetSanity.check(weight = 85.0, reps = 3, baselineWeight = 80.0, baselineReps = 8))
    }

    @Test
    fun `blank fields pass`() {
        assertNull(SetSanity.check(weight = null, reps = null, baselineWeight = 80.0, baselineReps = 5))
    }
}
