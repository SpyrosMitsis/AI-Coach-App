package com.workoutmaker.app.ui.screens.settings

import com.workoutmaker.app.data.ThresholdTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestTestDateTest {

    private fun test(date: String, kind: String, value: Double = 1.0) =
        ThresholdTest(date = date, kind = kind, value = value)

    @Test
    fun `picks the newest test of that kind`() {
        val tests = listOf(
            test("2026-07-20", "lthr"),
            test("2026-01-04", "lthr"),
            test("2026-03-11", "lthr"),
        )
        assertEquals("2026-07-20", latestTestDate(tests, "lthr"))
    }

    @Test
    fun `ignores other kinds entirely`() {
        // The bug this guards: showing the FTP test's date next to the LTHR
        // value, which would read as a measurement that never happened.
        val tests = listOf(
            test("2026-07-25", "ftp"),
            test("2026-07-24", "threshold_pace"),
            test("2026-02-02", "lthr"),
        )
        assertEquals("2026-02-02", latestTestDate(tests, "lthr"))
        assertEquals("2026-07-25", latestTestDate(tests, "ftp"))
        assertEquals("2026-07-24", latestTestDate(tests, "threshold_pace"))
    }

    @Test
    fun `does not depend on the caller's ordering`() {
        // The repository sorts by date descending today. It should not have to.
        val newestFirst = listOf(test("2026-07-20", "lthr"), test("2026-01-04", "lthr"))
        assertEquals(latestTestDate(newestFirst, "lthr"), latestTestDate(newestFirst.reversed(), "lthr"))
    }

    @Test
    fun `absent is null, not an empty string`() {
        // A threshold can be set by onboarding, an Intervals sync or the coach's
        // update_profile tool, none of which leave a test row. Null makes the
        // row render the value with no date rather than "set ".
        assertNull(latestTestDate(emptyList(), "lthr"))
        assertNull(latestTestDate(listOf(test("2026-07-20", "ftp")), "lthr"))
    }
}
