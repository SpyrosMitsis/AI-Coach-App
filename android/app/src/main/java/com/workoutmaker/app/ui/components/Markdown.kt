package com.workoutmaker.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
fun MarkdownText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val linkColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var lastBlank = false
        text.trim().lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {
                    // Collapse runs of blank lines into one paragraph gap.
                    if (!lastBlank) Spacer(Modifier.height(6.dp))
                    lastBlank = true
                    return@forEach
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
