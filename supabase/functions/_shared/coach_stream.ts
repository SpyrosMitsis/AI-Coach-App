// ---------------------------------------------------------------------------
// Streaming policy for the agentic coach.
//
// THE PROBLEM. You cannot know, before making a call, whether the model will
// emit prose for the athlete or decide to call a tool. Models narrate before
// tool calls ("Let me check your recent runs..."), and showing that narration
// means showing a sentence that is then taken away again.
//
// WHAT WAS TRIED, AND WHY IT FAILED. The first policy held back the first N
// characters of each message and flushed past the threshold, on the measured
// basis that preambles ran 23 to 33 characters. That measurement did not
// survive the prompt rework. Re-measured on deepseek-v4-flash across five real
// asks, prose emitted BEFORE a tool call ran to 947 characters (57, 138, 183,
// 264, 619, 947), while FINAL answers ran 175 to 397. The two distributions do
// not merely overlap, they invert: intermediate legs write MORE than final
// ones. No threshold can separate them, so the athlete saw paragraphs appear
// and vanish, repeatedly, for the whole turn.
//
// THE POLICY NOW. Buffer per leg and send nothing until the buffer is known to
// have survived. A tool call means the leg was working, not talking, so its
// buffer is dropped unsent. Whatever is still buffered when the turn ends is
// by definition the text of the leg that called no tool: the real answer.
//
// THE TRADE. The final answer no longer streams token by token; it arrives when
// its leg completes. That is deliberate. Nothing else can guarantee the athlete
// never sees discarded text, and the client's RevealPacer already types out a
// bulk arrival, so what they see is text appearing smoothly, just starting
// later. A reset caused by a tool call is now impossible by construction rather
// than by tuning.
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

export interface FinalLegStream {
  /** A text delta arrived from the model. */
  onDelta(t: string): void;
  /** The model started a tool call, so this leg was working, not answering. */
  onToolStart(): void;
  /** The turn ended; whatever is still buffered is the real answer. */
  endMessage(): void;
  /** Exactly what the client has been told to display. */
  committed(): string;
  /** Characters discarded unsent. Logged per turn to keep the cost visible. */
  discarded(): number;
}

/**
 * Buffer the coach's prose and emit only the part that survives to the end of
 * the turn.
 *
 * The invariant: nothing reaches the client until it is known not to be a
 * preamble. Since a leg that calls a tool has its buffer dropped, and the loop
 * only stops when a leg calls no tool, the text left at endMessage() is exactly
 * the final leg's.
 */
export function finalLegStream(emit: (e: StreamEvent) => void): FinalLegStream {
  let buffer = "";
  let shown = "";
  let dropped = 0;

  return {
    onDelta(t: string) {
      if (t) buffer += t;
    },

    onToolStart() {
      // Narration before a tool call. Nothing was ever shown, so this costs
      // the athlete nothing and needs no reset.
      dropped += buffer.length;
      buffer = "";
    },

    endMessage() {
      if (buffer.length > 0) {
        shown += buffer;
        emit({ text: buffer });
        buffer = "";
      }
    },

    committed: () => shown,
    discarded: () => dropped,
  };
}
