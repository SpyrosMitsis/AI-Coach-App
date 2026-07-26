package com.workoutmaker.app.ui.components

import com.workoutmaker.app.ui.screens.coach.RevealPacer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// With real token streaming a line is re-rendered many times as it grows.
// Without this, "**Key po" draws literal asterisks and then snaps to bold when
// the closing "**" lands, which read as the whole message flickering. That
// flicker is why the old client revealed whole lines only, and why it always
// lagged behind the stream. Fixing the renderer is what lets the pacer be fast.
class StreamingMarkdownTest {

    @Test
    fun `an unterminated bold run is styled, not printed`() {
        assertEquals("**Key po**", closeOpenMarkup("**Key po"))
        // Once it completes, the text is already correct and unchanged.
        assertEquals("**Key point**", closeOpenMarkup("**Key point**"))
    }

    @Test
    fun `italic, code and underscore runs close too`() {
        assertEquals("*ital*", closeOpenMarkup("*ital"))
        assertEquals("`cod`", closeOpenMarkup("`cod"))
        assertEquals("_emph_", closeOpenMarkup("_emph"))
    }

    @Test
    fun `a completed bold run followed by an open italic closes only the italic`() {
        assertEquals("**bold** and *ital*", closeOpenMarkup("**bold** and *ital"))
    }

    @Test
    fun `an unterminated link renders as its label, with no stray brackets`() {
        assertEquals("see label", closeOpenMarkup("see [label](htt"))
        assertEquals("see label", closeOpenMarkup("see [label]("))
        assertEquals("see label", closeOpenMarkup("see [label"))
    }

    @Test
    fun `plain prose is returned untouched`() {
        val s = "You're fresh today, so let's push on Thursday."
        assertEquals(s, closeOpenMarkup(s))
    }

    // The property that matters: as a line grows one character at a time, the
    // rendered result must never contain a dangling marker character.
    @Test
    fun `no prefix of a formatted line ever shows a raw marker`() {
        val full = "**Key point**: keep it `easy` and *relaxed*"
        for (n in 1..full.length) {
            val fixed = closeOpenMarkup(full.take(n))
            for (m in listOf("**", "`", "*", "_")) {
                val count = Regex(Regex.escape(m)).findAll(fixed).count()
                assertTrue(
                    "prefix of $n chars left an odd number of \"$m\": $fixed",
                    count % 2 == 0,
                )
            }
        }
    }
}

// The old pacer revealed one whole line per 120ms, so a 30-line reply spent
// ~4 seconds typing after it had entirely arrived. Draining a fraction of the
// backlog per tick makes the reveal finish WITH the stream.
class RevealPacerTest {

    @Test
    fun `an empty queue asks for nothing`() {
        assertEquals(0, RevealPacer.chunk(""))
    }

    @Test
    fun `a trickle still advances, so the drain can never stall`() {
        assertEquals(1, RevealPacer.chunk("a"))
        assertTrue(RevealPacer.chunk("short") >= 1)
    }

    @Test
    fun `a big backlog drains faster than a small one`() {
        val small = RevealPacer.chunk("a".repeat(40))
        val large = RevealPacer.chunk("a".repeat(400))
        assertTrue("backlog should accelerate the drain: $small vs $large", large > small)
    }

    @Test
    fun `the chunk is capped so text never appears in visible slabs`() {
        assertTrue(RevealPacer.chunk("a".repeat(10_000)) <= 24)
    }

    @Test
    fun `a newline inside the chunk is preferred as the cut point`() {
        // MarkdownText styles a completed line once; landing on the boundary
        // avoids restyling it as it grows.
        assertEquals(4, RevealPacer.chunk("abc\ndefghijklmnop".repeat(4)))
    }

    @Test
    fun `it never waits for a newline that is not there`() {
        val noNewline = "a".repeat(200)
        assertTrue(RevealPacer.chunk(noNewline) > 0)
    }

    // A full drain must terminate and emit every character exactly once.
    @Test
    fun `draining a large reply consumes it fully and finishes quickly`() {
        val text = ("Some coaching prose that goes on a while. ".repeat(40))
        val queue = StringBuilder(text)
        val out = StringBuilder()
        var ticks = 0
        while (queue.isNotEmpty()) {
            val n = RevealPacer.chunk(queue)
            assertTrue("chunk must advance", n in 1..queue.length)
            out.append(queue.substring(0, n))
            queue.delete(0, n)
            ticks++
            assertTrue("drain should terminate", ticks < 10_000)
        }
        assertEquals(text, out.toString())
        // At ~16ms a tick, this must be well under a second of wall clock.
        assertTrue("too many ticks for a ${text.length}-char reply: $ticks", ticks < 120)
    }
}

// Regressions found by the property test above while writing this.
class StreamingMarkdownEdgeTest {

    @Test
    fun `a half-typed closer does not multiply asterisks`() {
        // "**Key point*" is bold mid-close, not bold plus a new italic.
        assertEquals("**Key point**", closeOpenMarkup("**Key point*"))
    }

    @Test
    fun `a marker with no content yet is dropped, not closed`() {
        // Closing "*" into "**" would print literal asterisks.
        assertEquals("", closeOpenMarkup("*"))
        assertEquals("", closeOpenMarkup("**"))
        assertEquals("Ready ", closeOpenMarkup("Ready **"))
    }

    @Test
    fun `a bullet's own marker is list syntax, never emphasis`() {
        assertEquals("* item", closeOpenMarkup("* item"))
        assertEquals("- item", closeOpenMarkup("- item"))
        // Emphasis inside a bullet still gets closed.
        assertEquals("- **bold**", closeOpenMarkup("- **bold"))
    }

    @Test
    fun `a completed line is left exactly as it was`() {
        for (s in listOf("**bold**", "*ital*", "`code`", "- plain bullet", "no markup here")) {
            assertEquals(s, closeOpenMarkup(s))
        }
    }
}
