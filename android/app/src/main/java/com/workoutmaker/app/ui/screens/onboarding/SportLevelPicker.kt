package com.workoutmaker.app.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.ui.screens.settings.EXPERIENCE_BY_SPORT
import com.workoutmaker.app.ui.screens.settings.LEVELS

// ===========================================================================
// "What level are you?", for every sport, on one control.
//
// The gym asked this with a slider and a paragraph, and the endurance sports
// asked it with a row of equal chips. Experience is one ordered thing in all
// four: chips said nothing about Novice sitting between Beginner and
// Intermediate, and nothing at all about which rung the athlete is actually on.
// The rungs differ per sport (running starts at "Never ran"), the shape does
// not, so there is one picker and a blurb table.
// ===========================================================================

internal fun sportLevels(sport: String): List<String> = EXPERIENCE_BY_SPORT[sport] ?: LEVELS

/**
 * What each rung means, in terms of what the coach will DO about it, since
 * "Intermediate" is a word the athlete has to translate into their own week
 * before they can honestly pick it.
 */
internal fun sportLevelBlurb(sport: String, level: String): String = when (sport) {
    GYM -> gymLevelBlurb(level)
    "run" -> when (level) {
        "Never ran" -> "Not a runner yet, or not since school. I start you on walk and run " +
            "intervals and build the habit before the distance."
        "Beginner" -> "Running on and off, or new this year. Easy miles first, with one " +
            "harder session a week at most."
        "Intermediate" -> "A year or more of steady running. There is enough base for a real " +
            "week: easy volume, a quality day, and a long run."
        "Experienced" -> "Long trained, with races behind you. Two quality sessions a week, " +
            "and the long run carries work of its own."
        else -> ""
    }
    "ride" -> when (level) {
        "Beginner" -> "New to riding with a plan. Steady endurance rides, and any intervals " +
            "kept short and simple."
        "Intermediate" -> "Riding regularly and comfortable with a few hours out. Tempo and " +
            "threshold work goes in properly."
        "Advanced" -> "Long trained on the bike. Hard days are genuinely hard, easy days stay " +
            "easy, and the long ride means it."
        else -> ""
    }
    "swim" -> when (level) {
        "Beginner" -> "Still building the stroke. Short repeats, long rests, technique before " +
            "conditioning."
        "Intermediate" -> "Comfortable swimming continuously. The sets get longer and the rests " +
            "get shorter."
        "Advanced" -> "Strong technique and a feel for pace. Proper interval sets at threshold " +
            "and above."
        else -> ""
    }
    else -> ""
}

/**
 * The slider, its rungs, and the sentence for whichever rung is chosen.
 *
 * Stateless on purpose: onboarding and Settings both pass the profile's current
 * level in and write the answer back themselves, so the two flows cannot drift.
 */
@Composable
internal fun SportLevelPicker(sport: String, level: String?, onLevel: (String) -> Unit) {
    val levels = sportLevels(sport)
    if (levels.isEmpty()) return
    val idx = levels.indexOf(level).takeIf { it >= 0 } ?: 0

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Slider(
            value = idx.toFloat(),
            onValueChange = { v -> levels.getOrNull(v.toInt())?.let(onLevel) },
            valueRange = 0f..(levels.size - 1).toFloat(),
            steps = levels.size - 2,
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
        // The rungs, under the track they belong to. Hidden from the screen
        // reader: the slider already announces the level it is on, and having
        // the words loose after it just repeats the scale.
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics {},
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            levels.forEach { l ->
                Text(
                    l,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (l == levels[idx]) FontWeight.Bold else FontWeight.Normal,
                    color = if (l == levels[idx]) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val blurb = sportLevelBlurb(sport, levels[idx])
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(levels[idx], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (blurb.isNotBlank()) {
                Text(
                    blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
