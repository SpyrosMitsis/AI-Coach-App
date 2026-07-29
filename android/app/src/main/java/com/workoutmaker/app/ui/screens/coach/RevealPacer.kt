package com.workoutmaker.app.ui.screens.coach

/**
 * How fast the coach's reply is revealed on screen.
 *
 * The server streams real token deltas now, so this is smoothing, not
 * simulation. Two properties matter and they pull against each other:
 *
 *  - it must never fall BEHIND the stream. The old typewriter revealed one
 *    whole line per 120ms, so a 30-line reply spent ~4 seconds typing after it
 *    had entirely arrived. Draining a fixed fraction of the backlog each tick
 *    makes the reveal finish with the stream: the more that is waiting, the
 *    faster it goes.
 *  - it must not jump in visible slabs. Hence the cap, and the preference for
 *    cutting on a newline that already falls inside the chunk.
 *
 * Pure and object-scoped so the pacing can be unit-tested without coroutines.
 */
internal object RevealPacer {
    /** One frame at ~60fps. Small enough that pacing comes from chunk size. */
    const val TICK_MS = 16L

    private const val MIN_CHUNK = 1
    private const val MAX_CHUNK = 24

    /** Fraction of the backlog to emit per tick (1/8 drains ~63% in 8 ticks). */
    private const val DIVISOR = 8

    /**
     * How many characters of [queue] to reveal on this tick. Always at least 1
     * when the queue is non-empty, so the drain can never stall.
     */
    fun chunk(queue: CharSequence): Int {
        if (queue.isEmpty()) return 0
        val size = (queue.length / DIVISOR).coerceIn(MIN_CHUNK, MAX_CHUNK)
        // Prefer to stop at a newline already inside this chunk: a line that
        // lands whole is styled once by MarkdownText instead of being restyled
        // as it grows. Never WAIT for one, which is what made the old pacer lag.
        val nl = queue.take(size).indexOf('\n')
        return if (nl >= 0) nl + 1 else size
    }
}
