package com.workoutmaker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

// ============================================================================
// Tiny markdown renderer for LLM output — no dependency, covers what the
// models actually emit in chat: **bold**, *italic*, `code`, [text](url) and
// bare links, # headers and bullet/numbered lists. Anything else renders as
// plain text unchanged.
// ============================================================================

private val INLINE = Regex("""(\*\*([^*\n]+)\*\*|\*([^*\n]+)\*|_([^_\n]+)_|`([^`\n]+)`)""")

// [label](url) or a bare http(s) URL. Trailing punctuation stays out of the
// bare-URL match so "see https://x.dev." doesn't link the period.
private val LINKS = Regex("""\[([^\]\n]+)]\((https?://[^)\s]+)\)|(https?://[^\s)\]>"',]+)""")

/** The old inline pass (bold/italic/code) appended into an existing builder. */
private fun AnnotatedString.Builder.appendInline(line: String) {
    var idx = 0
    for (m in INLINE.findAll(line)) {
        if (m.range.first > idx) append(line.substring(idx, m.range.first))
        val (bold, italic1, italic2, code) =
            listOf(m.groupValues[2], m.groupValues[3], m.groupValues[4], m.groupValues[5])
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            italic1.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic1) }
            italic2.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic2) }
            code.isNotEmpty() -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33808080)),
            ) { append(code) }
        }
        idx = m.range.last + 1
    }
    if (idx < line.length) append(line.substring(idx))
}

/**
 * Close an unterminated inline marker on the LAST line of a streaming reply.
 *
 * With real token streaming a line is rendered many times as it grows, so
 * "**Key po" would draw literal asterisks and then snap to bold once the
 * closing "**" arrives. Applying the style early instead means the completed
 * prefix never changes appearance. An unterminated link renders as its label.
 *
 * Only ever applied to the final line, so a genuine lone asterisk earlier in
 * the message is untouched.
 */
internal fun closeOpenMarkup(line: String): String {
    // A bullet's own "-"/"*" is list syntax, not emphasis. Balancing the whole
    // line would turn "* item" into "* item*" and print a stray asterisk, so
    // hand only the body to the balancer and put the marker back.
    val bullet = Regex("""^(\s*[-*•]\s+)(.*)$""").find(line)
    if (bullet != null) {
        return bullet.groupValues[1] + closeOpenMarkup(bullet.groupValues[2])
    }
    // A dangling link: [label](htt -> label
    val link = Regex("""\[([^\]\n]*)\]\([^)\n]*$""").find(line)
    if (link != null) return line.substring(0, link.range.first) + link.groupValues[1]
    val openBracket = line.lastIndexOf('[')
    if (openBracket >= 0 && !line.substring(openBracket).contains(']')) {
        return line.substring(0, openBracket) + line.substring(openBracket + 1)
    }
    // Drop a trailing marker run FIRST, then balance what's left. Such a run is
    // either an opener whose word hasn't arrived ("**") or a closer only half
    // typed ("**Key point*"); in both cases the characters carry no meaning yet
    // and counting them produces nonsense. Dropping first makes the two cases
    // identical and keeps the completed prefix stable.
    val body = line.trimEnd('*', '_', '`')
    return body + markerClosers(body)
}

/** The markers needed to balance [s], longest first so "**" pairs before "*". */
private fun markerClosers(s: String): String {
    var out = s
    var added = ""
    for (m in listOf("**", "`", "*", "_")) {
        if (Regex(Regex.escape(m)).findAll(out).count() % 2 == 1) {
            out += m
            added += m
        }
    }
    return added
}

/** Parse one line's inline markdown (incl. tappable links) into a styled AnnotatedString. */
fun inlineMarkdown(line: String, linkColor: Color = Color.Unspecified): AnnotatedString =
    buildAnnotatedString {
        var idx = 0
        for (m in LINKS.findAll(line)) {
            if (m.range.first > idx) appendInline(line.substring(idx, m.range.first))
            val label = m.groupValues[1].ifEmpty { m.groupValues[3] }
            val url = m.groupValues[2].ifEmpty { m.groupValues[3] }
            withLink(
                LinkAnnotation.Url(
                    url,
                    TextLinkStyles(
                        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    ),
                ),
            ) { append(label) }
            idx = m.range.last + 1
        }
        if (idx < line.length) appendInline(line.substring(idx))
    }

private val BULLET = Regex("""^\s*[-*•]\s+(.*)$""")
private val NUMBERED = Regex("""^\s*(\d+)[.)]\s+(.*)$""")
private val HEADER = Regex("""^(#{1,4})\s+(.*)$""")

// --- GFM tables -------------------------------------------------------------
// Models reach for tables for day-by-day plans, and rendering them line by line
// leaked raw pipes into the chat. Parsing is kept pure and separate from the
// composable so it can be unit-tested; the rendering choice (a card per row,
// header cells as field labels) is in TableBlock, because a 3-column table of
// prose does not fit a phone's width as a grid.

/** One parsed GFM table: the header cells plus one list of cells per body row. */
internal data class MarkdownTable(val headers: List<String>, val rows: List<List<String>>)

private val DELIMITER_ROW = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")

private fun looksLikeRow(line: String): Boolean = line.trimStart().startsWith("|")

/** Split "| a | b |" into ["a", "b"], tolerating the optional outer pipes. */
private fun cellsOf(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

/**
 * Parse a GFM table starting at [start], or null if one does not begin there.
 * Requires a header row AND a delimiter row, which is what makes a half-streamed
 * table (header only, no delimiter yet) correctly not-a-table until it completes.
 * Returns the table and the index just past its last row.
 */
internal fun parseMarkdownTable(lines: List<String>, start: Int): Pair<MarkdownTable, Int>? {
    if (start + 1 >= lines.size) return null
    if (!looksLikeRow(lines[start])) return null
    if (!DELIMITER_ROW.matches(lines[start + 1]) || !lines[start + 1].contains('-')) return null

    val headers = cellsOf(lines[start])
    var i = start + 2
    val rows = mutableListOf<List<String>>()
    while (i < lines.size && looksLikeRow(lines[i])) {
        val cells = cellsOf(lines[i])
        // Ragged rows are common from LLMs; pad/trim to the header width so the
        // renderer can always pair a cell with its label.
        rows += List(headers.size) { cells.getOrElse(it) { "" } }
        i++
    }
    if (rows.isEmpty()) return null
    return MarkdownTable(headers, rows) to i
}

/**
 * Index of the trailing run of pipe lines that is not yet a complete table.
 * While a reply is still streaming, those lines must be held back rather than
 * shown as raw pipes: the delimiter row may simply not have arrived yet.
 * Returns lines.size when there is nothing to hold back.
 */
internal fun streamingHoldbackFrom(lines: List<String>): Int {
    var i = lines.size
    while (i > 0 && looksLikeRow(lines[i - 1])) i--
    // A trailing run that already parses as a whole table is safe to render.
    if (i < lines.size && parseMarkdownTable(lines, i)?.second == lines.size) return lines.size
    return i
}

@Composable
fun MarkdownText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    // True while the reply is still arriving. Only affects incomplete trailing
    // tables, which are held back instead of flashing as raw pipes.
    streaming: Boolean = false,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    // remember: a stream ticks this composable ~60x/second, and re-splitting
    // plus re-parsing the whole message each frame is wasted work that grows
    // with the reply.
    val all = remember(text, streaming) {
        val lines = text.trim().lines().map { it.trimEnd() }.toMutableList()
        // Only the final line can be mid-token, so only it gets the fix-up.
        if (streaming && lines.isNotEmpty()) {
            lines[lines.lastIndex] = closeOpenMarkup(lines.last())
        }
        lines.toList()
    }
    val limit = if (streaming) streamingHoldbackFrom(all) else all.size

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var lastBlank = false
        var i = 0
        while (i < limit) {
            val line = all[i]

            val table = parseMarkdownTable(all, i)
            if (table != null && table.second <= limit) {
                TableBlock(table.first, color, style, linkColor)
                i = table.second
                lastBlank = false
                continue
            }
            i++

            when {
                line.isBlank() -> {
                    // Collapse runs of blank lines into one paragraph gap.
                    if (!lastBlank) Spacer(Modifier.height(6.dp))
                    lastBlank = true
                    continue
                }
                HEADER.matches(line) -> {
                    val (_, body) = HEADER.find(line)!!.destructured
                    Text(
                        inlineMarkdown(body, linkColor),
                        color = color,
                        style = style.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = style.fontSize * 1.08f,
                        ),
                    )
                }
                BULLET.matches(line) -> {
                    val body = BULLET.find(line)!!.groupValues[1]
                    Text(
                        buildAnnotatedString { append("•  "); append(inlineMarkdown(body, linkColor)) },
                        color = color,
                        style = style,
                    )
                }
                NUMBERED.matches(line) -> {
                    val (n, body) = NUMBERED.find(line)!!.destructured
                    Text(
                        buildAnnotatedString { append("$n.  "); append(inlineMarkdown(body, linkColor)) },
                        color = color,
                        style = style,
                    )
                }
                else -> Text(inlineMarkdown(line, linkColor), color = color, style = style)
            }
            lastBlank = false
        }
    }
}

// A table row per card, header cells as field labels. A grid loses on a phone:
// the chat's content column is ~350dp, so three columns of prose wrap to one or
// two words each. Stacking keeps every cell readable and matches how the coach
// screen already renders structured data (DataCard, CalendarResultCard).
@Composable
private fun TableBlock(
    table: MarkdownTable,
    color: Color,
    style: TextStyle,
    linkColor: Color,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        table.rows.forEach { cells ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // First cell titles the row (the day, date or exercise). Its own
                // header label is dropped: "Mon" needs no "Day:" in front of it.
                val title = cells.firstOrNull().orEmpty()
                if (title.isNotBlank()) {
                    Text(
                        inlineMarkdown(title, linkColor),
                        color = color,
                        style = style.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
                cells.drop(1).forEachIndexed { idx, cell ->
                    if (cell.isBlank()) return@forEachIndexed
                    val label = table.headers.getOrNull(idx + 1).orEmpty()
                    Text(
                        buildAnnotatedString {
                            if (label.isNotBlank()) {
                                withStyle(SpanStyle(color = muted)) { append("$label  ") }
                            }
                            append(inlineMarkdown(cell, linkColor))
                        },
                        color = color,
                        style = style,
                    )
                }
            }
        }
    }
}
