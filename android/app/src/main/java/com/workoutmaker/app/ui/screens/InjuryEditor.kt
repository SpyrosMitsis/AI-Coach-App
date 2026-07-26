package com.workoutmaker.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.InjuryEntry

// Shared injuries editor (onboarding + Settings → Injuries & constraints):
// area chips add/remove, explicit per-area severity chips, free text for the
// rest. Edits profile.injuries directly (structured — see InjuryEntry in
// Models.kt), so the backend's safety engine can match on `area` instead of
// regexing a flattened string, and can use `severity` to gate strip-vs-flag.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InjuryEditor(injuries: List<InjuryEntry>, onChange: (List<InjuryEntry>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        INJURY_AREAS.forEach { area ->
            FilterChip(
                selected = injurySeverity(injuries, area) != null,
                onClick = { onChange(toggleInjury(injuries, area)) },
                label = { Text(area) },
            )
        }
    }
    // Severity is its own explicit control per selected area — the old
    // tap-again-to-cycle pattern was invisible and nobody used it.
    val selected = INJURY_AREAS.filter { injurySeverity(injuries, it) != null }
    if (selected.isNotEmpty()) {
        Text(
            "How serious is it?",
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
        )
        selected.forEach { area ->
            val severity = injurySeverity(injuries, area)
            Text(area, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                INJURY_SEVERITIES.filter { it.isNotEmpty() }.forEach { s ->
                    FilterChip(
                        selected = severity == s,
                        onClick = { onChange(setInjurySeverity(injuries, area, if (severity == s) "" else s)) },
                        label = { Text(s.replaceFirstChar { c -> c.uppercase() }) },
                    )
                }
            }
        }
        Text(
            "The more serious it is, the more strictly the coach protects it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OutlinedTextField(
        injuryNote(injuries),
        { v -> onChange(withNote(injuries, v)) },
        label = { Text("Injuries / notes (optional)") },
        modifier = Modifier.fillMaxWidth(),
    )
}

private val INJURY_SEVERITIES = listOf("", "mild", "moderate", "serious")

// Freeform notes (the text field below the chips) live in one entry with a
// blank area — not a body-area chip — so they still ride along to the backend
// (activeSafetyRules matches rule patterns against `note` too) without forcing
// arbitrary text into the structured `area` field.
private const val NOTE_AREA = ""

/** Current severity for [area]: null = not present, "" = present unqualified. */
internal fun injurySeverity(current: List<InjuryEntry>, area: String): String? =
    current.firstOrNull { it.area.equals(area, ignoreCase = true) }?.severity

/** Add [area] (unqualified) when absent, remove it entirely when present. */
internal fun toggleInjury(current: List<InjuryEntry>, area: String): List<InjuryEntry> =
    if (injurySeverity(current, area) != null) current.filterNot { it.area.equals(area, ignoreCase = true) }
    else current + InjuryEntry(area = area)

/** Set [area]'s severity ("" clears the qualifier, keeping the area listed). */
internal fun setInjurySeverity(current: List<InjuryEntry>, area: String, severity: String): List<InjuryEntry> =
    if (current.any { it.area.equals(area, ignoreCase = true) }) {
        current.map { if (it.area.equals(area, ignoreCase = true)) it.copy(severity = severity) else it }
    } else {
        current + InjuryEntry(area = area, severity = severity)
    }

internal fun injuryNote(current: List<InjuryEntry>): String =
    current.firstOrNull { it.area == NOTE_AREA }?.note ?: ""

internal fun withNote(current: List<InjuryEntry>, note: String): List<InjuryEntry> {
    val rest = current.filterNot { it.area == NOTE_AREA }
    return if (note.isBlank()) rest else rest + InjuryEntry(area = NOTE_AREA, note = note)
}

/** Human-readable summary for review/settings screens, e.g. "Knee (moderate); old ankle sprain". */
internal fun injuriesSummary(current: List<InjuryEntry>): String =
    current.joinToString("; ") { e ->
        when {
            e.area == NOTE_AREA -> e.note.orEmpty()
            e.severity.isNotEmpty() -> "${e.area} (${e.severity})"
            else -> e.area
        }
    }
