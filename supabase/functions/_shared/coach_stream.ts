// ---------------------------------------------------------------------------
// Speculative streaming for the agentic coach.
//
// THE PROBLEM. You cannot know, before making a call, whether the model will
// emit prose for the athlete or decide to call a tool. So "only stream the
// final leg" is not decidable in advance. But models frequently narrate before
// a tool call ("Let me check your recent runs..."), and streaming that to the
// chat would show a sentence that is then replaced by the real answer.
//
// THE POLICY. Hold back the first HOLDBACK_CHARS of each assistant message.
//   - Tool call before the threshold: drop the buffer silently. The athlete
//     sees only the tool progress row, which is the correct rendering of "the
//     coach went and looked something up".
//   - Past the threshold with no tool call: flush and pass everything through.
//   - Tool call AFTER flushing: emit one reset so the client can discard what
//     it showed, and go back to buffering.
//
// Kept pure and separate from the edge function (the pattern coach_eval.ts
// already established) because it is the part most likely to be subtly wrong,
// and unit tests are the only cheap way to prove it.
// ---------------------------------------------------------------------------

/** What the client is told: append text, or throw away what it has shown. */
export interface StreamEvent {
  text?: string;
  reset?: true;
}

/**
 * Apply the no-em-dash house rule to a stream of deltas.
 *
 * cleanReply runs stripDashes on the finished reply, so without this the
 * streamed text and the persisted text differ on any reply containing a dash,
 * and the display/persist invariant in coach-chat fires a reset. Measured
 * against deepseek-v4-flash, 6 of 7 replies contain one, so nearly every reply
 * would stream in, vanish and reappear: strictly worse than not streaming.
 *
 * A dash is only rewritten to a comma when it has whitespace on BOTH sides, and
 * a delta boundary can fall in the middle of that. So hold back any trailing
 * run of whitespace-or-dash until the next delta shows what follows it. The
 * hold-back is normally a single space and never grows.
 */
export function dashScrubber(
  strip: (s: string) => string,
): { push(t: string): string; flush(): string } {
  let held = "";
  return {
    push(t: string): string {
      const buf = held + t;
      const tail = buf.match(/[\s—–]+$/);
      const cut = tail ? buf.length - tail[0].length : buf.length;
      held = buf.slice(cut);
      return strip(buf.slice(0, cut));
    },
    flush(): string {
      const out = strip(held);
      held = "";
      return out;
    },
  };
}

export interface SpeculativeStream {
  /** A text delta arrived from the model. */
  onDelta(t: string): void;
  /** The model started a tool call, so any narration so far was a preamble. */
  onToolStart(): void;
  /** The current assistant message ended; flush anything still held back. */
  endMessage(): void;
  /** Exactly what the client has been told to display. */
  committed(): string;
  /** How many resets were emitted; worth logging to tune the threshold. */
  resets(): number;
}

/**
 * MEASURED, not guessed. Across 5 real coach questions on deepseek-v4-flash,
 * the narration emitted before a tool call was 23, 28, 33, 33, 33 characters
 * ("Let me check your fitness."). An earlier guess of 24 sat inside that
 * distribution, so 2 in 3 replies visibly flashed: text appeared, then a reset
 * took it away.
 *
 * 48 clears the observed maximum with headroom. The cost is only that the
 * first ~48 characters of a real answer appear together rather than
 * streaming, which is nothing against the 1 to 2 seconds before the first
 * delta arrives at all. Re-measure if the default model changes; `resets()`
 * is logged per turn as `stream_resets` for exactly that.
 */
export const HOLDBACK_CHARS = 48;

export function speculativeStream(
  emit: (e: StreamEvent) => void,
  holdback: number = HOLDBACK_CHARS,
): SpeculativeStream {
  let buffer = "";
  let flushed = false;
  let shown = "";
  let resetCount = 0;

  const push = (t: string) => {
    if (!t) return;
    shown += t;
    emit({ text: t });
  };

  return {
    onDelta(t: string) {
      if (!t) return;
      if (flushed) {
        push(t);
        return;
      }
      buffer += t;
      if (buffer.length >= holdback) {
        flushed = true;
        push(buffer);
        buffer = "";
      }
    },

    onToolStart() {
      // Buffered narration was a preamble. Drop it; nothing was shown.
      buffer = "";
      if (flushed) {
        // Already on screen, so the client has to be told to take it back.
        // Only counts as a reset when something was actually displayed:
        // a flushed-but-empty message would otherwise reset for nothing.
        if (shown.length > 0) {
          resetCount++;
          shown = "";
          emit({ reset: true });
        }
        flushed = false;
      }
    },

    endMessage() {
      if (buffer.length > 0) {
        flushed = true;
        push(buffer);
        buffer = "";
      }
      // The next assistant message starts its own hold-back window.
      flushed = false;
    },

    committed: () => shown,
    resets: () => resetCount,
  };
}
