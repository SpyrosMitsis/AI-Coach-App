/**
 * The no-em-dash house rule, enforced on OUTPUT rather than hoped for.
 *
 * PUNCTUATION_RULE has been in every prompt for a long time and models still
 * ignore it: measured against deepseek-v4-flash, 1 in 5 replies carried an em
 * dash on the old chat prompt and 3 in 5 on the new shorter one. A prompt line
 * cannot be the enforcement point for a rule this absolute.
 *
 * It lives in its own module, away from the eval harness it grew up in, because
 * the enforcement point is now `llmGenerate` in llm.ts — and coach_eval.ts
 * imports llm.ts, so leaving it there would have made the cycle llm -> eval ->
 * llm and dragged the eval harness into every function bundle.
 *
 * The substitution follows what PUNCTUATION_RULE itself asks for: a plain
 * hyphen inside a numeric range, otherwise a comma for a spaced parenthetical
 * dash, otherwise a plain hyphen.
 */
export function stripDashes(text: string): string {
  return text
    // 5—8 reps, 20 – 30 min: ranges close up to a plain hyphen. Runs first so a
    // spaced range is not mistaken for a parenthetical pause below.
    .replace(/(\d)\s*[—–]\s*(\d)/g, "$1-$2")
    // "fine — nothing alarming": a spaced dash reads as a pause, so a comma.
    // The trailing space is preserved by the replacement, not the pattern.
    .replace(/\s+[—–]\s+/g, ", ")
    // Any survivor (unspaced, mid-word) becomes a hyphen rather than vanishing.
    .replace(/[—–]/g, "-")
    // A dash after existing punctuation can leave ", ," or ". ,".
    .replace(/([,.:;!?])\s*,\s+/g, "$1 ")
    .replace(/,\s*,+/g, ",");
}
