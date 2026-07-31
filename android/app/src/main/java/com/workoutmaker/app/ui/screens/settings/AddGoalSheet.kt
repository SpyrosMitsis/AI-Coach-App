package com.workoutmaker.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.EnduranceGoals
import com.workoutmaker.app.data.Race
import com.workoutmaker.app.ui.screens.onboarding.DistanceGoalPicker
import com.workoutmaker.app.ui.theme.amberAccent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// ===========================================================================
// A goal, asked one question at a time.
//
// The old dialog put seven fields in one scrolling AlertDialog: name, sport
// chips, a date button, distance chips with a "Custom" escape hatch, a
// free-text target, three priority chips and a checkbox. It asked for the
// distance as a WORD ("Marathon") and the pace as free text ("4:45/km") when
// onboarding already knows how to ask both properly, on a line you drag.
//
// So it is four questions, one per sheet: what the event is, how far and how
// fast, how much it matters, and when it is. Step 2 is onboarding's own
// DistanceGoalPicker, so a goal is set here exactly the way it was set at
// signup. Priority comes BEFORE the date because what the countdown is allowed
// to promise depends on it: only a main goal earns a taper, so only a main goal
// gets the phase breakdown.
//
// Strength (and "other") have no distance to drag, so they get three steps and
// the counter says so.
// ===========================================================================

internal enum class GoalStep { EVENT, DISTANCE, TARGET, PRIORITY, DATE }

/**
 * Four questions, always four, but the second one depends on the sport: a line
 * you drag for the sports that cover ground, and an event-and-target pair for
 * the ones that do not. The gym has no distance and a marathon has no top set.
 */
internal fun goalStepsFor(sport: String): List<GoalStep> = listOf(
    GoalStep.EVENT,
    if (EnduranceGoals.isEndurance(sport)) GoalStep.DISTANCE else GoalStep.TARGET,
    GoalStep.PRIORITY,
    GoalStep.DATE,
)

/**
 * What a non-endurance goal can BE, as things to press. These are the events
 * the coach can actually shape a block around; "Something else" is the escape
 * hatch that leaves the target field to say what it is.
 */
internal fun goalEventKinds(sport: String): List<String> = when (sport) {
    GYM_SPORT -> listOf("Powerlifting meet", "Weightlifting meet", "1RM test", "Hyrox", "A lift PR", "Something else")
    else -> listOf("Sprint tri", "Olympic tri", "70.3", "Ironman", "Hyrox", "Something else")
}

/** The example that shows what shape of answer the target field wants. */
internal fun goalTargetPlaceholder(sport: String, kind: String?): String = when {
    sport != GYM_SPORT -> "Sub 5:30, or just finish"
    kind == "Powerlifting meet" -> "Total 400 kg"
    kind == "1RM test" || kind == "A lift PR" -> "Squat 120 kg x 1"
    kind == "Hyrox" -> "Sub 1:20"
    else -> "Bench 100 kg x 1"
}

private const val GYM_SPORT = "strength"

/** Sport tiles, in the order the app lists sports everywhere else. */
private val GOAL_TILES: List<Triple<String, String, ImageVector>> = listOf(
    Triple("run", "Run", Icons.AutoMirrored.Filled.DirectionsRun),
    Triple("ride", "Ride", Icons.AutoMirrored.Filled.DirectionsBike),
    Triple("swim", "Swim", Icons.Filled.Pool),
    Triple("strength", "Strength", Icons.Filled.FitnessCenter),
    Triple("other", "Other", Icons.Filled.Flag),
)

private val PRIORITIES = listOf(
    Triple("A", "Main goal", "Full base, build and peak, then a real taper."),
    Triple("B", "Tune-up", "You train through it. Easy days first, no taper."),
    Triple("C", "Just for fun", "A hard session with a number on it. Plan unchanged."),
)

/**
 * How a main goal's remaining weeks divide into phases, using the SAME bands
 * `Periodization.phaseFor` reads (taper <= 2 weeks, peak <= 6, build <= 14,
 * base beyond that), which are themselves a mirror of the server's
 * `trainingPhase`. The strip has to show the shape the coach will actually
 * plan, not a prettier one.
 *
 * Phases with no weeks in them are left out rather than drawn as slivers.
 */
internal fun goalPhaseWeeks(weeksAway: Int): List<Pair<String, Int>> {
    val w = weeksAway.coerceAtLeast(0)
    return listOf(
        "BASE" to (w - 14).coerceAtLeast(0),
        "BUILD" to (w - 6).coerceIn(0, 8),
        "PEAK" to (w - 2).coerceIn(0, 4),
        "TAPER" to w.coerceIn(0, 2),
    ).filter { it.second > 0 }
}

/**
 * Everything the sheet leaves behind.
 *
 * [km] and [paceSec] are the part that is easy to miss: the race row alone is
 * invisible to the workout generator, which reads the PROFILE's distance goal
 * and pace. A main goal has to write both or it is a diary entry, not a plan.
 */
internal data class GoalDraft(
    val race: Race,
    val setAsGoal: Boolean,
    val km: Double? = null,
    val paceSec: Int? = null,
)

/**
 * The guided sheet, driven by both callers (Settings' goal list and
 * onboarding's race step), which is what keeps the two flows identical.
 *
 * [mainGoal] is the goal that currently owns the plan, when there is one. It is
 * only ever read to tell a tune-up what it is being planned around.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddGoalSheet(
    onClose: () -> Unit,
    mainGoal: Race? = null,
    onAdd: (GoalDraft) -> Unit,
) {
    var sport by remember { mutableStateOf("run") }
    var name by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("A") }
    var date by remember { mutableStateOf(LocalDate.now().plusWeeks(12)) }
    var index by remember { mutableStateOf(0) }

    // The picker's own state, reset when the sport changes because a 42.2 km
    // swim is not a considered answer, it is the previous answer left behind.
    var fraction by remember(sport) { mutableFloatStateOf(EnduranceGoals.defaultFraction()) }
    var paceSec by remember(sport) { mutableIntStateOf(EnduranceGoals.defaultPaceSec(sport)) }
    // The same, for the sports with no line to drag.
    var kind by remember(sport) { mutableStateOf<String?>(null) }
    var target by remember(sport) { mutableStateOf("") }

    val steps = remember(sport) { goalStepsFor(sport) }
    val step = steps[index.coerceIn(0, steps.lastIndex)]

    val km = EnduranceGoals.kmForFraction(sport, fraction)
    val post = EnduranceGoals.postAt(sport, fraction)
    val isEndurance = EnduranceGoals.isEndurance(sport)
    val daysAway = ChronoUnit.DAYS.between(LocalDate.now(), date)
    val weeksAway = ChronoUnit.WEEKS.between(LocalDate.now(), date).toInt()

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            // imePadding: step 1 has a text field, and without this the
            // keyboard covers the Continue button that leaves it.
            Modifier.fillMaxWidth().imePadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            StepHeader(
                position = steps.indexOf(step) + 1,
                total = steps.size,
                suffix = if (step == GoalStep.DATE && priority != "A") "TUNE-UP"
                else if (step == GoalStep.DATE) "MAIN GOAL" else null,
                headline = when (step) {
                    GoalStep.EVENT -> "What's the event?"
                    GoalStep.DISTANCE -> "How far, and how fast?"
                    GoalStep.TARGET -> if (sport == GYM_SPORT) "What are you lifting for?" else "What are you chasing?"
                    GoalStep.PRIORITY -> "How much does it matter?"
                    // "the marathon" reads as a sentence; "the 10k" reads as a
                    // typo, so a name carrying a number keeps its own case.
                    GoalStep.DATE -> post?.name?.takeIf { priority == "A" }
                        ?.let { n -> if (n.any { it.isDigit() }) n else n.lowercase() }
                        ?.let { "When is the $it?" } ?: "When is it?"
                },
                sub = when (step) {
                    GoalStep.PRIORITY -> "This decides how much of your plan bends around it."
                    GoalStep.TARGET -> "The words here are what the coach reads. Be specific and it plans for it."
                    else -> null
                },
                accent = if (step == GoalStep.DATE && priority != "A") amberAccent()
                else MaterialTheme.colorScheme.primary,
            )

            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (step) {
                    GoalStep.EVENT -> EventStep(sport, name, { sport = it }, { name = it })
                    GoalStep.DISTANCE -> Column(Modifier.padding(horizontal = 24.dp)) {
                        DistanceGoalPicker(
                            sport = sport,
                            fraction = fraction,
                            paceSec = paceSec,
                            onFraction = { fraction = it },
                            onRelease = { fraction = EnduranceGoals.snapFraction(sport, fraction) },
                            onPace = { paceSec = EnduranceGoals.clampPace(sport, it) },
                        )
                    }
                    GoalStep.TARGET -> TargetStep(sport, kind, target, { kind = it }, { target = it })
                    GoalStep.PRIORITY -> PriorityStep(priority) { priority = it }
                    GoalStep.DATE -> DateStep(
                        date = date,
                        priority = priority,
                        weeksAway = weeksAway,
                        daysAway = daysAway,
                        mainGoal = mainGoal,
                        onDate = { date = it },
                    )
                }
            }

            StepBar(
                backLabel = if (index == 0) "Cancel" else "Back",
                backIcon = index > 0,
                onBack = { if (index == 0) onClose() else index-- },
                nextLabel = when (step) {
                    GoalStep.PRIORITY -> "Next: when is it?"
                    GoalStep.DATE -> "Add goal"
                    else -> "Continue"
                },
                nextIcon = if (step == GoalStep.DATE) Icons.Filled.Flag else Icons.AutoMirrored.Filled.ArrowForward,
                nextEnabled = when (step) {
                    GoalStep.EVENT -> name.isNotBlank()
                    GoalStep.DATE -> daysAway >= 0
                    else -> true
                },
                onNext = {
                    if (step != GoalStep.DATE) {
                        index++
                    } else {
                        onAdd(
                            GoalDraft(
                                race = Race(
                                    name = name.trim(),
                                    date = date.toString(),
                                    priority = priority,
                                    sport = sport,
                                    // The picker's answer, in the words the rest
                                    // of the app already stores: a named distance
                                    // when the flag is standing on a post, the
                                    // number itself when it is between two.
                                    distance = if (isEndurance) post?.name ?: EnduranceGoals.formatKm(km)
                                    else kind?.takeIf { it != "Something else" },
                                    target = if (isEndurance) EnduranceGoals.formatPace(sport, paceSec)
                                    else target.trim().ifBlank { null },
                                ),
                                // The note on the priority step is the whole rule:
                                // a main goal replaces the current one, and
                                // nothing else touches the plan's anchor.
                                setAsGoal = priority == "A",
                                // Only a main goal carries its distance into the
                                // profile: a B-race 10K must not overwrite the
                                // marathon the athlete is actually training for.
                                km = if (isEndurance && priority == "A") km else null,
                                paceSec = if (isEndurance && priority == "A") paceSec else null,
                            ),
                        )
                    }
                },
            )
        }
    }
}

// --- The chrome ------------------------------------------------------------

@Composable
private fun StepHeader(
    position: Int,
    total: Int,
    suffix: String?,
    headline: String,
    sub: String?,
    accent: androidx.compose.ui.graphics.Color,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "STEP $position OF $total" + (suffix?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            // The same count as dashes, so progress is legible without reading.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(total) { i ->
                    Box(
                        Modifier.width(14.dp).height(3.dp).clip(RoundedCornerShape(50))
                            .background(
                                if (i < position) accent
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                    )
                }
            }
        }
        Text(headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        sub?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepBar(
    backLabel: String,
    backIcon: Boolean,
    onBack: () -> Unit,
    nextLabel: String,
    nextIcon: ImageVector,
    nextEnabled: Boolean,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            if (backIcon) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
            }
            Text(backLabel, modifier = Modifier.padding(start = if (backIcon) 4.dp else 0.dp))
        }
        Button(onClick = onNext, enabled = nextEnabled, modifier = Modifier.weight(1f)) {
            Text(nextLabel, fontWeight = FontWeight.SemiBold)
            Icon(nextIcon, null, Modifier.padding(start = 6.dp).size(18.dp))
        }
    }
}

// --- Step 1: what the event is ---------------------------------------------

@Composable
private fun EventStep(
    sport: String,
    name: String,
    onSport: (String) -> Unit,
    onName: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The name first: it is the thing the athlete came here holding, and
        // typing it is what turns "a goal" into "the Athens Marathon".
        OutlinedTextField(
            name,
            onName,
            label = { Text("Call it what you call it") },
            placeholder = { Text("Athens Marathon") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Two per row, because a tile with an icon and a word in it needs the
        // width, and five sports is not a list worth scrolling.
        GOAL_TILES.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (key, label, icon) ->
                    SportTile(label, icon, sport == key, Modifier.weight(1f)) { onSport(key) }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }

        Text(
            if (EnduranceGoals.isEndurance(sport)) {
                "Next you'll set how far and how fast."
            } else {
                "Next you'll say what you're chasing and what would make it a good day."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SportTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .border(
                if (selected) 0.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// --- Step 2, for the sports with no line to drag ---------------------------
//
// The gym's version of "how far and how fast". It matters more than it looks:
// a distance goal reaches the coach as a number the planner reads, but a
// strength goal reaches it as WORDS, so what is typed here is the whole of what
// the coach knows about what the day is for.

@Composable
private fun TargetStep(
    sport: String,
    kind: String?,
    target: String,
    onKind: (String) -> Unit,
    onTarget: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        goalEventKinds(sport).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { k ->
                    EventKindTile(k, kind == k, Modifier.weight(1f)) { onKind(k) }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }

        OutlinedTextField(
            target,
            onTarget,
            label = { Text(if (sport == GYM_SPORT) "What would make it a good day?" else "Target (optional)") },
            placeholder = { Text(goalTargetPlaceholder(sport, kind)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            if (sport == GYM_SPORT) {
                "A number here is what turns \"get stronger\" into a rep range and a " +
                    "starting load. Leave it blank and the coach picks both."
            } else {
                "Anything you write is read as the point of the day."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EventKindTile(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .border(
                if (selected) 0.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// --- Step 3: how much it matters -------------------------------------------

@Composable
private fun PriorityStep(priority: String, onPriority: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PRIORITIES.forEach { (key, title, blurb) ->
            PriorityCard(key, title, blurb, priority == key) { onPriority(key) }
        }
        // A/B/C is jargon outside the head of someone who already races, and the
        // one consequence nobody expects is that picking A moves the anchor.
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            // Intrinsic height so the accent bar runs the full height of
            // however many lines the sentence wraps to.
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            Text(
                "A main goal replaces your current one. Everything else stays in the list as a tune-up.",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun PriorityCard(
    key: String,
    title: String,
    blurb: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val badge = priorityColor(key)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .border(
                if (selected) 0.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(24.dp).clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                // Tinted rather than filled: the A/B/C colours include
                // amberAccent(), which is a light tone in dark mode and a dark
                // one on paper, so no single text colour is legible on top of
                // it in both. The tint carries the same meaning and cannot
                // become unreadable in either.
                Text(
                    "Goal $key",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = badge,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(badge.copy(alpha = 0.20f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            Text(
                blurb,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Step 4: when it is ----------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateStep(
    date: LocalDate,
    priority: String,
    weeksAway: Int,
    daysAway: Long,
    mainGoal: Race?,
    onDate: (LocalDate) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("Set date") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }

    val isMain = priority == "A"
    val accent = if (isMain) MaterialTheme.colorScheme.primary else amberAccent()

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "RACE DAY",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    date.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    date.year.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { showPicker = true }) {
                Icon(Icons.Filled.EditCalendar, null, Modifier.size(18.dp))
                Text("Change", modifier = Modifier.padding(start = 8.dp))
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (daysAway < 0) "Past" else if (weeksAway < 1) "$daysAway" else "$weeksAway",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (daysAway < 0) MaterialTheme.colorScheme.error else accent,
                )
                if (daysAway >= 0) {
                    Text(
                        if (weeksAway < 1) "day${if (daysAway == 1L) "" else "s"} away" else "weeks away",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }

            Text(
                when {
                    daysAway < 0 -> "That date has already passed. Pick a future one."
                    !isMain -> "No taper and no phase change: you train through it. It lands in " +
                        "your build weeks with a couple of easy days in front of it."
                    weeksAway < 1 -> "Too close to build toward. The plan just tapers you into it."
                    weeksAway < 4 -> "Enough to sharpen and taper, not to build. The plan takes this shape:"
                    weeksAway <= 24 -> "Room for a full base, build, peak and taper. Because this is " +
                        "your main goal, the plan takes this shape:"
                    else -> "The coach plans the nearest 16 weeks and grows into the rest. Nearer " +
                        "the day it takes this shape:"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (daysAway < 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The phase strip is a promise about the plan, so only a main goal
            // with room to actually run the phases gets one.
            if (isMain && daysAway >= 0) PhaseStrip(goalPhaseWeeks(weeksAway))

            // What a tune-up is being planned AROUND, since that is the thing
            // that decides it does not get a taper of its own.
            if (!isMain && mainGoal != null) {
                val gap = runCatching {
                    ChronoUnit.WEEKS.between(date, LocalDate.parse(mainGoal.date)).toInt()
                }.getOrNull()
                if (gap != null && gap > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Flag,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            "${mainGoal.name}, $gap week${if (gap == 1) "" else "s"} later, still owns the plan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // The three dates people actually pick, so the common case never opens
        // the calendar at all.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(8, 12, 16).forEach { w ->
                val target = LocalDate.now().plusWeeks(w.toLong())
                FilterChip(
                    selected = date == target,
                    onClick = { onDate(target) },
                    label = { Text("In $w weeks") },
                )
            }
        }
    }
}

/** Base / build / peak / taper as one bar, with the weeks each phase gets. */
@Composable
private fun PhaseStrip(phases: List<Pair<String, Int>>) {
    if (phases.isEmpty()) return
    val amber = amberAccent()
    val primary = MaterialTheme.colorScheme.primary
    fun tint(label: String) = when (label) {
        "BASE" -> primary.copy(alpha = 0.40f)
        "BUILD" -> primary.copy(alpha = 0.65f)
        "TAPER" -> amber
        else -> primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            phases.forEach { (label, weeks) ->
                Box(Modifier.weight(weeks.toFloat()).height(8.dp).background(tint(label)))
            }
        }
        // Each label is only as wide as its own slice, so a short phase cannot
        // hold "TAPER 2w" and was clipping to "TAPER" by accident. Drop the
        // weeks deliberately instead, on the slices too narrow to say them.
        val total = phases.sumOf { it.second }.toFloat()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            phases.forEach { (label, weeks) ->
                Text(
                    if (weeks / total >= 0.25f) "$label ${weeks}w" else label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (label == "TAPER") amber else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(weeks.toFloat()),
                )
            }
        }
    }
}
