package com.workoutmaker.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// The coach reaches for a markdown table whenever it lays out a week. Before
// this parser existed, MarkdownText rendered one Text per line, so the pipes
// and the |---|---| separator printed literally.
class MarkdownTableTest {

    private fun lines(s: String) = s.trimIndent().lines()

    private val week = """
        | Day | What | Why |
        |---|---|---|
        | **Mon** (120 min) | Long ride + 20 min brick run | Biggest aerobic builder |
        | **Tue** (60 min) | Full-body strength | Build leg resilience |
    """

    @Test
    fun `a real coach week table parses into headers and rows`() {
        val (table, end) = parseMarkdownTable(lines(week), 0)!!
        assertEquals(listOf("Day", "What", "Why"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals("**Mon** (120 min)", table.rows[0][0])
        assertEquals("Long ride + 20 min brick run", table.rows[0][1])
        assertEquals("Biggest aerobic builder", table.rows[0][2])
        assertEquals(4, end)
    }

    @Test
    fun `alignment colons in the delimiter row are accepted`() {
        val t = lines(
            """
            | Metric | Value |
            |:---|---:|
            | Weekly TSS | 350 |
            """,
        )
        assertEquals(listOf("Metric", "Value"), parseMarkdownTable(t, 0)!!.first.headers)
    }

    @Test
    fun `tables without outer pipes still parse`() {
        val t = lines(
            """
            | Day | What |
            | --- | --- |
            | Mon | Easy run |
            """,
        )
        assertEquals(listOf("Mon", "Easy run"), parseMarkdownTable(t, 0)!!.first.rows[0])
    }

    @Test
    fun `ragged rows are padded to the header width so every cell has a label`() {
        val t = lines(
            """
            | Day | What | Why |
            |---|---|---|
            | Mon | Easy run |
            """,
        )
        val row = parseMarkdownTable(t, 0)!!.first.rows[0]
        assertEquals(3, row.size)
        assertEquals("", row[2])
    }

    // The delimiter row is what distinguishes a table from prose that happens to
    // contain a pipe. It is also what makes a half-streamed table safe.
    @Test
    fun `a header row with no delimiter is not a table`() {
        assertNull(parseMarkdownTable(lines("| Day | What | Why |\n| Mon | Easy run |"), 0))
    }

    @Test
    fun `a header and delimiter with no body rows is not a table`() {
        assertNull(parseMarkdownTable(lines("| Day | What |\n|---|---|"), 0))
    }

    @Test
    fun `prose containing a pipe is not a table`() {
        assertNull(parseMarkdownTable(lines("Run easy | keep it aerobic\nsecond line"), 0))
    }

    // --- streaming hold-back ------------------------------------------------
    // Real deltas arrive mid-table. Showing a table before its delimiter row
    // lands would flash raw pipes, which is the bug being fixed.

    @Test
    fun `an incomplete trailing table is held back while streaming`() {
        val t = lines("Here's the week:\n| Day | What |")
        assertEquals(1, streamingHoldbackFrom(t))
    }

    @Test
    fun `hold-back covers a table whose delimiter arrived but rows have not`() {
        val t = lines("Here's the week:\n| Day | What |\n|---|---|")
        assertEquals(1, streamingHoldbackFrom(t))
    }

    @Test
    fun `a complete trailing table is rendered, not held back`() {
        val t = lines("Here's the week:\n| Day | What |\n|---|---|\n| Mon | Easy run |")
        assertEquals(t.size, streamingHoldbackFrom(t))
    }

    @Test
    fun `text after a table is never held back`() {
        val t = lines(
            """
            | Day | What |
            |---|---|
            | Mon | Easy run |
            That's the whole week.
            """,
        )
        assertEquals(t.size, streamingHoldbackFrom(t))
    }

    @Test
    fun `ordinary prose is never held back`() {
        val t = lines("You're fresh this week.\nLet's push on Thursday.")
        assertEquals(t.size, streamingHoldbackFrom(t))
    }

    // Growing the message one line at a time must never render raw pipes: at
    // every prefix, the visible portion either excludes the table or contains
    // all of it. This is the property the whole hold-back exists for.
    @Test
    fun `no prefix of a streaming reply ever exposes a raw pipe line`() {
        val full = lines("Here's the week:\n$week".trimIndent())
        for (n in 1..full.size) {
            val prefix = full.take(n)
            val limit = streamingHoldbackFrom(prefix)
            val visible = prefix.take(limit)
            var i = 0
            while (i < visible.size) {
                val parsed = parseMarkdownTable(visible, i)
                if (parsed != null) {
                    i = parsed.second
                    continue
                }
                assert(!visible[i].trimStart().startsWith("|")) {
                    "prefix of $n lines leaked a raw pipe line: ${visible[i]}"
                }
                i++
            }
        }
    }

    @Test
    fun `the parser tolerates an empty document`() {
        assertNull(parseMarkdownTable(emptyList(), 0))
        assertEquals(0, streamingHoldbackFrom(emptyList()))
        assertNotNull(parseMarkdownTable(lines(week), 0))
    }
}
