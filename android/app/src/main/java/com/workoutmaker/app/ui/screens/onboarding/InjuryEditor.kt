package com.workoutmaker.app.ui.screens.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.InjuryEntry
import com.workoutmaker.app.ui.screens.settings.INJURY_AREAS
import com.workoutmaker.app.ui.theme.amberAccent
import java.time.LocalDate

// ===========================================================================
// Injuries as HARD RULES, one card each.
//
// The old editor was a chip row plus a severity chip row plus a text box: three
// controls describing one thing, and none of them said what the coach would
// actually DO about it. A rule is a statement, so it gets to look like one: a
// card per constraint, its area as the headline and what it means underneath,
// with an X to drop it. Amber, not red: these are things to work around, not
// emergencies (see the palette note in CLAUDE.md).
//
// Shared by onboarding and Settings, which is the point: the athlete edits the
// same rules in the same shape wherever they meet them. Edits profile.injuries
// directly (structured, see InjuryEntry) so the backend's safety engine matches
// on `area` rather than regexing a flattened string, and uses `severity` to gate
// strip-vs-flag.
// ===========================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InjuryEditor(injuries: List<InjuryEntry>, onChange: (List<InjuryEntry>) -> Unit) {
    val rules = injuries.filter { it.area.isNotBlank() }
    var expanded by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    Text(
        injuryHeadline(rules.size),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(
        "I never program around these. Everything else is fair game.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    rules.forEach { rule ->
        InjuryRuleCard(
            rule = rule,
            expanded = expanded == rule.area,
            onToggleExpand = { expanded = if (expanded == rule.area) null else rule.area },
            onRemove = { onChange(toggleInjury(injuries, rule.area)); expanded = null },
            onSeverity = { s -> onChange(setInjurySeverity(injuries, rule.area, s)) },
            onNote = { n -> onChange(setInjuryNote(injuries, rule.area, n)) },
        )
    }

    // Anything not in the standard list. The old editor had no way to say
    // "left ankle" at all, which is a strange gap in a list of YOUR injuries.
    if (adding) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                draft,
                { draft = it },
                label = { Text("What should I avoid loading?") },
                placeholder = { Text("Left ankle") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    val area = draft.trim()
                    if (area.isNotBlank() && injurySeverity(injuries, area) == null) {
                        onChange(injuries + newInjury(area))
                    }
                    draft = ""
                    adding = false
                },
                enabled = draft.isNotBlank(),
            ) { Text("Add") }
        }
    } else {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                .clickable { adding = true }
                .padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                "  Add something else",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Quick-add for the common areas, minus the ones already listed: a shortcut
    // under the add button rather than the primary control it used to be.
    val remaining = INJURY_AREAS.filter { injurySeverity(injuries, it) == null }
    if (remaining.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            remaining.forEach { area ->
                FilterChip(
                    selected = false,
                    onClick = { onChange(toggleInjury(injuries, area)) },
                    label = { Text(area) },
                )
            }
        }
    }

    // Kept from the old editor: anything that is not about one body area. Old
    // profiles store their free text here, so dropping it would lose data.
    OutlinedTextField(
        injuryNote(injuries),
        { v -> onChange(withNote(injuries, v)) },
        label = { Text("Anything else (optional)") },
        placeholder = { Text("Only train mornings, no burpees, ...") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InjuryRuleCard(
    rule: InjuryEntry,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onRemove: () -> Unit,
    onSeverity: (String) -> Unit,
    onNote: (String) -> Unit,
) {
    val amber = amberAccent()
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(amber.copy(alpha = 0.12f))
            .clickable(onClick = onToggleExpand)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Filled.Healing,
                contentDescription = null,
                tint = amber,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(rule.area, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    ruleDetail(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove ${rule.area}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        // Severity and the specifics stay folded away until asked for. They are
        // the difference between "flag it" and "strip it" server-side, but they
        // are not what you need to see to know the rule exists.
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "How serious is it? The more serious, the more strictly I protect it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    INJURY_SEVERITIES.forEach { s ->
                        FilterChip(
                            selected = rule.severity == s,
                            onClick = { onSeverity(if (rule.severity == s) "" else s) },
                            label = { Text(s.replaceFirstChar { c -> c.uppercase() }) },
                        )
                    }
                }
                OutlinedTextField(
                    rule.note.orEmpty(),
                    onNote,
                    label = { Text("What exactly should I avoid?") },
                    placeholder = { Text("No deep lunges") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The headline over the rules, which is just a count said out loud. */
internal fun injuryHeadline(count: Int): String = when (count) {
    0 -> "Nothing to work around"
    1 -> "One thing I avoid"
    else -> "$count things I avoid"
}

/** The one line under the area: what this rule actually tells the coach. */
internal fun ruleDetail(rule: InjuryEntry): String {
    val severity = rule.severity.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
    val note = rule.note?.takeIf { it.isNotBlank() }
    return listOfNotNull(severity, note).joinToString(" · ")
        .ifBlank { "Tap to say how serious it is" }
}

private val INJURY_SEVERITIES = listOf("mild", "moderate", "serious")

// Freeform notes (the text field at the bottom) live in one entry with a blank
// area — not a body-area chip — so they still ride along to the backend
// (activeSafetyRules matches rule patterns against `note` too) without forcing
// arbitrary text into the structured `area` field.
private const val NOTE_AREA = ""

/** Current severity for [area]: null = not present, "" = present unqualified. */
internal fun injurySeverity(current: List<InjuryEntry>, area: String): String? =
    current.firstOrNull { it.area.equals(area, ignoreCase = true) }?.severity

/**
 * A brand-new injury, stamped with the day it was raised.
 *
 * The stamp is the whole reason the follow-up loop can exist: "a couple of days
 * after you told me" needs a date, and adding the injury is the only moment the
 * app knows one. Every path that creates an entry goes through here so none of
 * them can quietly skip it. Entries saved before this shipped have no date, and
 * the server reads that as "never asked", which is what they are.
 */
internal fun newInjury(
    area: String,
    severity: String = "",
    today: String = LocalDate.now().toString(),
): InjuryEntry = InjuryEntry(area = area, severity = severity, raised_at = today)

/** Add [area] (unqualified) when absent, remove it entirely when present. */
internal fun toggleInjury(
    current: List<InjuryEntry>,
    area: String,
    today: String = LocalDate.now().toString(),
): List<InjuryEntry> =
    if (injurySeverity(current, area) != null) current.filterNot { it.area.equals(area, ignoreCase = true) }
    else current + newInjury(area, today = today)

/** Set [area]'s severity ("" clears the qualifier, keeping the area listed). */
internal fun setInjurySeverity(
    current: List<InjuryEntry>,
    area: String,
    severity: String,
    today: String = LocalDate.now().toString(),
): List<InjuryEntry> =
    if (current.any { it.area.equals(area, ignoreCase = true) }) {
        current.map { if (it.area.equals(area, ignoreCase = true)) it.copy(severity = severity) else it }
    } else {
        current + newInjury(area, severity, today)
    }

/** The per-area specifics ("no deep lunges"), distinct from the catch-all note. */
internal fun setInjuryNote(current: List<InjuryEntry>, area: String, note: String): List<InjuryEntry> =
    current.map {
        if (it.area.equals(area, ignoreCase = true)) it.copy(note = note.takeIf { n -> n.isNotBlank() }) else it
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
