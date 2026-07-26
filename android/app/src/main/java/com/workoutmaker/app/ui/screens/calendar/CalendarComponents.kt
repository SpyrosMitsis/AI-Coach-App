package com.workoutmaker.app.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.PlannedWorkout
import com.workoutmaker.app.data.CompletedActivity
import com.workoutmaker.app.ui.components.ChipRow
import com.workoutmaker.app.ui.components.SectionCard
import com.workoutmaker.app.ui.components.SectionLabel
import com.workoutmaker.app.ui.theme.mossAccent
import java.time.LocalDate
import java.time.YearMonth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Button
import com.workoutmaker.app.data.Periodization
import com.workoutmaker.app.data.WeekPlanRow
import com.workoutmaker.app.ui.components.InfoIcon
import com.workoutmaker.app.ui.components.Metrics
import com.workoutmaker.app.ui.components.fmtPace
import com.workoutmaker.app.ui.theme.amberAccent
import com.workoutmaker.app.data.weekPlan

// Where this week sits in the bigger arc: the four phases as a strip with a
// marker at the athlete's position, plus the week's focus from the planner.
// The bands mirror prompt.ts trainingPhase, so what's shown is what the AI
// was actually told when it built the week.
@Composable
private fun PhaseStrip(phase: Periodization.Phase, focus: String?) {
    val segments = listOf(
        "Base" to MaterialTheme.colorScheme.primary,
        "Build" to MaterialTheme.colorScheme.secondary,
        "Peak" to amberAccent(),
        "Taper" to MaterialTheme.colorScheme.tertiary,
    )
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                phase.name + (phase.weeksToGoal?.let { " · $it wk${if (it == 1) "" else "s"} to race" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            focus?.takeIf { it.isNotBlank() }?.let {
                val deload = it.contains("deload", ignoreCase = true) || it.contains("recovery", ignoreCase = true)
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (deload) amberAccent()
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (phase.weeksToGoal != null) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
                    segments.forEach { (name, color) ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight()
                                .background(if (name == phase.name) color else color.copy(alpha = 0.25f)),
                        )
                    }
                }
                // Position marker along the arc (0 = deep Base, 1 = race week).
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .padding(start = maxWidth * phase.progress - 4.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                segments.forEach { (name, _) ->
                    Text(
                        name,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (name == phase.name) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun WeekSummaryCard(
    weekStart: LocalDate,
    workouts: List<PlannedWorkout>,
    planning: Boolean,
    weekPlan: WeekPlanRow?,
    onPlan: () -> Unit,
    goalDate: LocalDate? = null,
) {
    val weekDates = (0..6).map { weekStart.plusDays(it.toLong()).toString() }.toSet()
    val week = workouts.filter { it.date in weekDates }
    val nonRest = week.filter { it.type != "rest" }
    val sessions = nonRest.size
    val tss = week.sumOf { it.workout_json.tss_estimate.toInt() }
    val rest = week.count { it.type == "rest" }

    // Intensity distribution by target RPE.
    val easy = nonRest.count { it.workout_json.rpe_target <= 4 }
    val moderate = nonRest.count { it.workout_json.rpe_target in 5.0..6.9 }
    val hard = nonRest.count { it.workout_json.rpe_target >= 7 }
    val easyPct = if (sessions > 0) (easy + moderate) * 100 / sessions else 0

    var expanded by remember { mutableStateOf(false) }

    // P6: plan-vs-actual adherence for the week.
    val doneSessions = nonRest.count { it.completed }
    val doneTss = nonRest.filter { it.completed }.sumOf { it.workout_json.tss_estimate.toInt() }
    val adherencePct = if (sessions > 0) doneSessions * 100 / sessions else 0

    SectionCard(title = "This week · $weekStart → ${weekStart.plusDays(6)}") {
        PhaseStrip(
            phase = Periodization.phaseFor(goalDate, weekStart),
            focus = weekPlan?.focus,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                WeekStat("Sessions", "$sessions")
                WeekStat("Planned TSS", "$tss")
                WeekStat("Hard", "$hard")
                WeekStat("Rest", "$rest")
            }
            InfoIcon("Your week at a glance", Metrics.WEEK_CARD)
        }

        if (sessions > 0) {
            val adhColor = if (adherencePct >= 80) MaterialTheme.colorScheme.primary else if (adherencePct >= 50) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Box(Modifier.fillMaxWidth(adherencePct / 100f).fillMaxHeight().background(adhColor))
                }
                Text("  $doneSessions/$sessions done · $doneTss/$tss TSS",
                    style = MaterialTheme.typography.labelSmall, color = adhColor)
            }
        }

        if (sessions > 0) {
            // Easy/moderate/hard stacked bar (target ~80% easy).
            Row(
                Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
            ) {
                if (easy > 0) Box(Modifier.weight(easy.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                if (moderate > 0) Box(Modifier.weight(moderate.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.secondary))
                if (hard > 0) Box(Modifier.weight(hard.toFloat()).fillMaxHeight().background(mossAccent()))
            }
            Text(
                "$easyPct% easy/aerobic · target ~80%",
                style = MaterialTheme.typography.labelSmall,
                color = if (easyPct >= 75) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = onPlan,
            enabled = !planning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.AutoAwesome, null)
            Text(if (planning) "  Planning…" else if (week.isEmpty()) "  Plan my week (AI)" else "  Re-plan this week (AI)")
        }

        if (weekPlan?.rationale != null || weekPlan?.focus != null) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide explanation ▴" else "Explain this week ▾")
            }
            if (expanded) {
                weekPlan.focus?.let {
                    Text("Focus: $it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                weekPlan.rationale?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                nonRest.sortedBy { it.date }.forEach { w ->
                    val note = w.workout_json.coach_note
                    Column(Modifier.padding(top = 4.dp)) {
                        Text("${w.date} · ${w.workout_json.title}", style = MaterialTheme.typography.labelMedium)
                        if (note.isNotBlank()) {
                            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WeekStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    byDate: Map<String, List<PlannedWorkout>>,
    strengthDates: Set<String>,
    activityDates: Set<String>,
    onSelect: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    val leading = month.atDay(1).dayOfWeek.value - 1 // Monday = 0
    val daysInMonth = month.lengthOfMonth()
    val totalCells = ((leading + daysInMonth + 6) / 7) * 7

    SectionCard {
        Row(Modifier.fillMaxWidth()) {
            listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su").forEach { d ->
                Text(
                    d,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        var cell = 0
        while (cell < totalCells) {
            Row(Modifier.fillMaxWidth()) {
                repeat(7) {
                    val dayNum = cell - leading + 1
                    if (dayNum in 1..daysInMonth) {
                        val date = month.atDay(dayNum)
                        DayCell(
                            date = date,
                            isToday = date == today,
                            isSelected = date == selected,
                            sessions = byDate[date.toString()].orEmpty(),
                            hasStrength = date.toString() in strengthDates,
                            hasActivity = date.toString() in activityDates,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(date) },
                        )
                    } else {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    }
                    cell++
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    sessions: List<PlannedWorkout>,
    hasStrength: Boolean,
    hasActivity: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(
                if (isToday && !isSelected)
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier,
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${date.dayOfMonth}", style = MaterialTheme.typography.bodySmall, color = fg)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                sessions.take(3).forEach { s ->
                    Box(Modifier.size(5.dp).background(typeColor(s.type), CircleShape))
                }
                if (hasStrength) Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
                // A completed Intervals activity — hollow ring to read as "actually done".
                if (hasActivity) Box(
                    Modifier.size(5.dp).clip(CircleShape)
                        .border(1.2.dp, if (isSelected) fg else MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}

internal fun fmtPaceSec(sec: Int): String = fmtPace(sec)

internal fun activityMeta(act: CompletedActivity): List<String> = buildList {
    act.distanceKm?.let { if (it > 0) add("%.1f km".format(it)) }
    act.durationMin?.let { if (it > 0) add("$it min") }
    act.paceSecPerKm?.let { add("${fmtPaceSec(it)} /km") }
    act.avg_hr?.let { add("♥ $it bpm") }
    act.tss?.let { if (it > 0) add("TSS ${it.toInt()}") }
}

// Compact card for a completed activity in the day list — taps into the detail.
@Composable
internal fun ActivityCard(act: CompletedActivity, planned: PlannedWorkout?, onClick: () -> Unit) {
    SectionCard(Modifier.clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Done · ${act.type ?: "activity"}", color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.ChevronRight, "Open details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(act.displayName, style = MaterialTheme.typography.titleMedium)
        val meta = activityMeta(act)
        if (meta.isNotEmpty()) ChipRow(meta)
        // Hint when this isn't what was on the plan that day.
        planned?.let { p ->
            if (p.type != "rest" && !p.completed && !looksLike(p.type, act.type)) {
                Text("Off-plan, ${p.type} was scheduled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

internal fun looksLike(plannedType: String, actualType: String?): Boolean {
    val a = (actualType ?: "").lowercase()
    return when (plannedType.lowercase()) {
        "run" -> a.contains("run") || a.contains("walk")
        "ride" -> a.contains("ride") || a.contains("bike") || a.contains("cycl")
        "swim" -> a.contains("swim")
        "strength" -> a.contains("weight") || a.contains("strength") || a.contains("workout") || a.contains("gym")
        else -> false
    }
}
