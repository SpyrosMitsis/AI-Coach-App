package com.workoutmaker.app.ui.screens.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.TrainingProfile
import com.workoutmaker.app.ui.screens.settings.EQUIPMENT_ITEMS
import com.workoutmaker.app.ui.screens.settings.EXPERIENCE_BY_SPORT
import com.workoutmaker.app.ui.screens.settings.GOALS_BY_SPORT
import com.workoutmaker.app.ui.screens.settings.SPLIT_STYLES

// ===========================================================================
// The gym, asked one question at a time.
//
// The gym used to be a single screen of twenty-two chips: five goals, four
// levels, four splits and nine bits of kit, all the same shape, all the same
// size, with nothing to say which mattered. It is four questions, so it is four
// screens, and each option carries the sentence that explains what choosing it
// does to your training. "Push / pull / legs" is not self-explanatory to
// someone who needs to be asked.
//
// Every control here is used TWICE: frameless in onboarding, boxed in Settings.
// That is the same arrangement SportGoalsLevel and EquipmentSelector already
// had, and the reason changing a goal in Settings is the same act as choosing
// it during setup.
// ===========================================================================

internal const val GYM = "strength"

/** What each goal actually does to the sessions, so the choice can be made. */
internal fun gymGoalBlurb(goal: String): String = when (goal) {
    "Build muscle" -> "Moderate loads, more sets, shorter rests."
    "Get stronger" -> "Heavy top sets, low reps, long rests."
    "Lose fat" -> "Denser sessions that keep the strength you have."
    "Body recomposition" -> "Both at once. Slower, and it asks more of your food."
    "General fitness" -> "Keep everything ticking. Nothing punishing."
    else -> ""
}

/** The same for the splits, whose names assume knowledge the athlete may not have. */
internal fun gymSplitName(split: String): String =
    if (split == "Auto") "Let me choose" else split

internal fun gymSplitBlurb(split: String): String = when (split) {
    "Auto" -> "I pick a split from your days and your kit, week to week."
    "Full body" -> "Everything, every session. Best on two or three days."
    "Upper / lower" -> "Alternating halves. Four days is the classic shape."
    "Push / pull / legs" -> "Three rotating days. Wants five or six sessions to shine."
    else -> ""
}

/** And for the level, which sets the starting loads and the rate of increase. */
internal fun gymLevelBlurb(level: String): String = when (level) {
    "Beginner" -> "New to lifting, or coming back after a long time away. " +
        "I start light and teach the pattern before I add weight."
    "Novice" -> "A few months in. The bar still goes up most weeks, so I keep " +
        "the progression simple and frequent."
    "Intermediate" -> "A year or more of steady training. Progress arrives in " +
        "blocks now, so I plan in blocks."
    "Advanced" -> "Long trained. Gains come in small slices, and the plan spends " +
        "most of its budget on the lifts that still move."
    else -> ""
}

internal fun gymLevels(): List<String> = EXPERIENCE_BY_SPORT[GYM] ?: emptyList()

/** Kit the athlete ticks by hand. "Full gym" is a switch above these, not one of them. */
internal fun gymKitItems(): List<String> = EQUIPMENT_ITEMS.filterNot { it == FULL_GYM }

/**
 * Everything chosen so far, in one line, for the caption under the scene and
 * the summary on the Settings row. Reads as a sentence about the athlete rather
 * than a field dump: goals, then level, then split, then what they train with.
 */
internal fun gymSummary(p: TrainingProfile): String {
    val kit = p.equipment_list
    return listOfNotNull(
        p.goals_by_sport[GYM].orEmpty().joinToString(" · ").ifBlank { null },
        p.experience_by_sport[GYM],
        p.split_style?.takeIf { it != "Auto" },
        when {
            kit.contains(FULL_GYM) -> FULL_GYM
            kit.isEmpty() -> null
            else -> "${kit.size} piece${if (kit.size == 1) "" else "s"} of kit"
        },
    ).joinToString("  ·  ")
}

// --- The controls ----------------------------------------------------------

/**
 * One choice, with the sentence that explains it. A row rather than a chip
 * because the explanation is the point: a chip can hold "Body recomposition"
 * but not "both at once, slower, and it asks more of your food", and without
 * that second half the athlete is guessing.
 */
@Composable
internal fun GymChoiceRow(
    title: String,
    blurb: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val border by animateFloatAsState(if (selected) 1f else 0f, label = "sel")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .border(
                (1.5f * border).dp,
                MaterialTheme.colorScheme.primary.copy(alpha = border),
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            if (blurb.isNotBlank()) {
                Text(
                    blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp).size(22.dp),
            )
        }
    }
}

/** "What are you chasing?" Multi-select: these compound rather than compete. */
@Composable
internal fun GymGoalPicker(profile: TrainingProfile, onToggle: (String) -> Unit) {
    val picked = profile.goals_by_sport[GYM].orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (GOALS_BY_SPORT[GYM] ?: emptyList()).forEach { g ->
            GymChoiceRow(g, gymGoalBlurb(g), picked.contains(g)) { onToggle(g) }
        }
    }
}

/**
 * "How long have you been lifting?" The slider lives in SportLevelPicker now,
 * because the question is the same one every sport asks and the gym was only
 * the first to get a control worth sharing.
 */
@Composable
internal fun GymLevelPicker(profile: TrainingProfile, onLevel: (String) -> Unit) {
    SportLevelPicker(GYM, profile.experience_by_sport[GYM], onLevel)
}

/** "How should the week break up?" One of four, Auto first because it is the default. */
@Composable
internal fun GymSplitPicker(profile: TrainingProfile, onSplit: (String) -> Unit) {
    val current = profile.split_style ?: "Auto"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SPLIT_STYLES.forEach { s ->
            GymChoiceRow(gymSplitName(s), gymSplitBlurb(s), current == s) { onSplit(s) }
        }
    }
}

/**
 * "What can you train with?" Full gym is a switch above the chips rather than
 * a chip among them, because it is not one more piece of kit: it is the answer
 * that makes the rest of the question moot. Ticking it hides the chips, which
 * is the honest UI for "everything is available".
 *
 * The athlete's hand-picked chips are kept underneath while Full gym is on, so
 * turning it back off restores what they had rather than an empty room.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GymKitPicker(profile: TrainingProfile, onToggle: (String) -> Unit) {
    val kit = profile.equipment_list
    val fullGym = kit.contains(FULL_GYM)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (fullGym) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                .clickable { onToggle(FULL_GYM) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.FitnessCenter,
                contentDescription = null,
                tint = if (fullGym) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 14.dp).size(24.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    FULL_GYM,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (fullGym) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Racks, benches, machines, the lot. Nothing else to answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (fullGym) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = fullGym,
                onCheckedChange = { onToggle(FULL_GYM) },
                modifier = Modifier.clearAndSetSemantics {},
            )
        }

        if (!fullGym) {
            Text(
                "OR PICK WHAT YOU HAVE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            // Both arrangements, not just the horizontal one: nine chips always
            // wrap onto three or four lines, and a FlowRow with no vertical
            // spacing stacks those lines edge to edge, so the chips touched.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gymKitItems().forEach { item ->
                    GymKitChip(item, kit.contains(item)) { onToggle(item) }
                }
            }
        }
    }
}

@Composable
private fun GymKitChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .border(
                if (selected) 1.5.dp else 0.dp,
                if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Ticking a kit item, with the one rule the data model needs: Full gym and a
 * hand-picked list are alternatives, so turning Full gym on does not wipe the
 * chips (turning it back off should give them back) but ticking a chip while
 * Full gym is on does turn Full gym off, since the athlete has just started
 * describing a specific room.
 */
internal fun toggledGymKit(current: List<String>, item: String): List<String> = when {
    item == FULL_GYM && current.contains(FULL_GYM) -> current - FULL_GYM
    item == FULL_GYM -> current + FULL_GYM
    current.contains(item) -> current - item - FULL_GYM
    else -> current - FULL_GYM + item
}
