package com.workoutmaker.app.ui.screens.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.DayAvailability
import com.workoutmaker.app.ui.screens.settings.DAYS

// ===========================================================================
// The week you just described, drawn.
//
// This started out as the INPUT: seven bars you tapped and dragged to shape the
// week directly. It was expressive and it was miserable, because shaping a whole
// week one bar at a time is a dozen precise gestures to say something the three
// questions underneath say in three taps.
//
// So the chart went back to being what it is good at: showing the answer. The
// questions (WeeklyAvailabilityEditor) own the input; this shows what they
// produced, including the two things a chip row cannot show at all, which are
// WHICH weekdays got picked and how much the long day towers over the others.
//
// Read-only on purpose. Nothing here takes a gesture.
// ===========================================================================

/** At or above this a day reads as "the long one", matching availabilityToQuestions. */
internal const val LONG_DAY_MINUTES = 90

/** "5h 30m" / "45m" / "0m" — the week total, in the units an athlete thinks in. */
internal fun weekTotalLabel(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h %02dm".format(minutes % 60)
}

private val TRACK_HEIGHT = 104.dp
private val REST_STUB = 8.dp

@Composable
internal fun WeekPreviewBars(availability: List<DayAvailability>, modifier: Modifier = Modifier) {
    val byDay = remember(availability) { availability.associateBy { it.day } }
    val total = availability.sumOf { it.max_minutes }
    // Scaled to the athlete's own longest day, not to a fixed ceiling: a 45min
    // week and a 3h week should both fill the chart and both look like weeks.
    val top = (availability.maxOfOrNull { it.max_minutes } ?: 60).coerceAtLeast(30)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DAYS.forEach { day ->
                DayBar(day, byDay[day]?.max_minutes, top, Modifier.weight(1f))
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                weekTotalLabel(total),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                when (availability.size) {
                    0 -> "no days yet"
                    1 -> "across 1 day"
                    else -> "across ${availability.size} days"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}

@Composable
private fun DayBar(day: String, minutes: Int?, top: Int, modifier: Modifier = Modifier) {
    val barHeight by animateDpAsState(
        targetValue = minutes?.let { (TRACK_HEIGHT * (it.toFloat() / top)).coerceAtLeast(REST_STUB) } ?: REST_STUB,
        animationSpec = tween(180),
        label = "dayBar$day",
    )
    val color = when {
        minutes == null -> MaterialTheme.colorScheme.surfaceContainerHigh
        minutes >= LONG_DAY_MINUTES -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    Column(
        modifier.semantics {
            contentDescription = if (minutes == null) "$day, rest day" else "$day, $minutes minutes"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.height(TRACK_HEIGHT).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxWidth().height(barHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color),
            )
        }
        Text(
            // Single letter: seven three-letter labels do not fit at 390dp.
            day.take(1),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (minutes != null) FontWeight.SemiBold else FontWeight.Normal,
            color = if (minutes != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            minutes?.let { if (it % 60 == 0) "${it / 60}h" else "${it}m" } ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
