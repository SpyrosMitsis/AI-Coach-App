package com.workoutmaker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import com.workoutmaker.app.ui.components.GhostButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workoutmaker.app.data.ChatMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import com.workoutmaker.app.data.CoachConversation
import com.workoutmaker.app.data.PlannedWorkout
import com.workoutmaker.app.ui.components.LocalAppSnackbar
import com.workoutmaker.app.ui.components.LogoMark
import com.workoutmaker.app.ui.components.MarkdownText
import com.workoutmaker.app.ui.components.SectionCard
import com.workoutmaker.app.ui.components.SectionLabel
import com.workoutmaker.app.ui.theme.amberAccent
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun ConversationRow(
    c: CoachConversation,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = c.title?.takeIf { it.isNotBlank() }
        ?: c.messages.firstOrNull { it.role == "user" }?.content?.take(60)
        ?: "Conversation"
    val preview = c.messages.lastOrNull { it.role == "assistant" }?.content
        ?.let { previewText(it) }
        ?.take(80).orEmpty()
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            c.updated_at?.let { ts ->
                Text(
                    ts.take(10),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        IconButton(onClick = onPin) {
            Icon(
                if (c.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (c.pinned) "Unpin" else "Pin",
                tint = if (c.pinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { confirmDelete = true }) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete conversation?") },
            text = { Text("This permanently removes “$title”.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// Shown after the coach changes the calendar: the week as it now actually is,
// straight from planned_workouts, with a jump into the Calendar tab.
@Composable
internal fun CalendarResultCard(
    week: List<PlannedWorkout>,
    changed: String?,
    showReplan: Boolean,
    onOpen: () -> Unit,
    onOpenDay: (String) -> Unit,
    onReplan: () -> Unit,
    onDismiss: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(
                if (changed != null) "✓ Updated your calendar" else "Now on your calendar",
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Hide", style = MaterialTheme.typography.labelMedium) }
        }
        changed?.let {
            Text(
                "The coach $it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (week.isEmpty()) {
            Text(
                "Nothing scheduled this week yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        week.forEach { w ->
            val day = runCatching {
                LocalDate.parse(w.date).dayOfWeek
                    .getDisplayName(TextStyle.SHORT, Locale.getDefault())
            }.getOrDefault(w.date)
            // Each day is a jump straight to that date on the Calendar tab.
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onOpenDay(w.date) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    day,
                    Modifier.widthIn(min = 44.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (w.type == "rest") "Rest" else w.workout_json.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                val tss = w.workout_json.tss_estimate
                if (tss > 0) {
                    Text(
                        "${tss.toInt()} TSS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("View in calendar") }
            if (showReplan) {
                TextButton(onClick = onReplan) {
                    Text("Re-plan", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// One row of the live tool timeline: a pulsing dot while the tool runs, a ✓
// once the next event arrives. Write tools (they change the calendar) carry the
// amber accent so "about to modify your plan" is visually distinct from reads.
@Composable
internal fun ToolStepRow(step: CoachViewModel.ToolStep) {
    val accent = if (step.write) amberAccent()
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (step.done) {
            Text("✓", style = MaterialTheme.typography.labelMedium, color = accent)
        } else {
            val pulse = rememberInfiniteTransition(label = "step")
            val alpha by pulse.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "stepAlpha",
            )
            Box(
                Modifier.size(7.dp).clip(CircleShape)
                    .background(accent.copy(alpha = alpha)),
            )
        }
        Text(
            step.label,
            style = MaterialTheme.typography.bodySmall,
            color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// Soft three-dot "coach is typing" indicator.
@Composable
internal fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 160),
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
internal fun Bubble(msg: ChatMessage, showAvatar: Boolean = true) {
    val isUser = msg.role == "user"

    // Some agentic replies leak raw JSON — either the protocol envelope
    // ({action, message}) or a data blob. Unwrap the prose; render data as a card.
    if (!isUser && looksLikeJson(msg.content)) {
        val obj = runCatching { coachJson.parseToJsonElement(msg.content) }.getOrNull() as? JsonObject
        val unwrapped = (obj?.get("message") as? JsonPrimitive)?.contentOrNull
        when {
            unwrapped != null -> AssistantProse(unwrapped, showAvatar)
            obj != null && isWorkoutShape(obj) -> WorkoutCard(obj)
            else -> DataCard(msg.content)
        }
        return
    }

    if (!isUser && msg.content.startsWith("⚠️")) {
        ErrorTurn(msg.content.removePrefix("⚠️").trim())
        return
    }

    if (!isUser) {
        AssistantProse(msg.content, showAvatar)
        return
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                msg.content,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// The coach speaks as the app, not from a box: avatar + flat prose on the
// background, full reading width. Consecutive assistant messages group — the
// avatar appears only on the first, follow-ons indent to the same text column.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantProse(text: String, showAvatar: Boolean = true) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val snackbar = LocalAppSnackbar.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (showAvatar) {
            LogoMark(
                modifier = Modifier.padding(end = 8.dp, top = 3.dp),
                size = 20.dp,
                animate = false,
            )
        } else {
            Spacer(Modifier.width(28.dp))
        }
        Box(
            Modifier
                .weight(1f)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        clipboard.setText(AnnotatedString(text))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        snackbar?.show("Copied")
                    },
                )
                .padding(vertical = 2.dp),
        ) {
            MarkdownText(
                text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            )
        }
    }
}

// A failed turn: compact, clearly an error, never styled like coach advice.
@Composable
private fun ErrorTurn(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
