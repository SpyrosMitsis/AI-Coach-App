package com.workoutmaker.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.workoutmaker.app.data.TrainingProfile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.DayAvailability
import com.workoutmaker.app.data.Periodization
import com.workoutmaker.app.data.ThemeMode
import com.workoutmaker.app.data.ThemePalette
import com.workoutmaker.app.ui.theme.palette
import androidx.compose.foundation.layout.ColumnScope
import com.workoutmaker.app.data.StartingLift
import com.workoutmaker.app.ui.theme.amberAccent

// ===========================================================================
// Stateless, ViewModel-agnostic profile editors. They take the current value
// plus change lambdas so BOTH onboarding and Settings drive them, keeping the
// two flows identical (the whole point of the redesign). Chips/labels follow
// the app's FilterChip + FlowRow idiom and theme-aware colours.
// ===========================================================================

// The sports the athlete actually trains; gates the per-activity + equipment steps.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SportSelector(selected: List<String>, onToggle: (String) -> Unit) {
    Text("Which activities do you want to train?", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SPORTS.forEach { s ->
            FilterChip(selected = selected.contains(s), onClick = { onToggle(s) }, label = { Text(sportLabel(s)) })
        }
    }
    Text(
        "Only what you pick gets scheduled. Combine freely, e.g. Running and Gym.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// Activity-first goals: for ONE sport, its goal(s) + level, and (for the gym)
// its preferred split. The onboarding shows one of these per activity; Settings
// loops them for every selected sport.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SportGoalsLevel(
    sport: String,
    goals: List<String>,
    level: String?,
    splitStyle: String? = null,
    onGoalToggle: (String) -> Unit,
    onLevel: (String) -> Unit,
    onSplit: (String) -> Unit = {},
) {
    Text("${sportLabel(sport)} goal", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (GOALS_BY_SPORT[sport] ?: emptyList()).forEach { g ->
            FilterChip(selected = goals.contains(g), onClick = { onGoalToggle(g) }, label = { Text(g) })
        }
    }
    Text("${sportLabel(sport)} level", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (EXPERIENCE_BY_SPORT[sport] ?: LEVELS).forEach { lvl ->
            FilterChip(selected = level == lvl, onClick = { onLevel(lvl) }, label = { Text(lvl) })
        }
    }
    // The gym's own unique question: how to split the week. (Periodization
    // moved next to the effort chips on the availability step, where the
    // weekly target it visualizes is actually chosen.)
    if (sport == "strength") {
        Text("Preferred split", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SPLIT_STYLES.forEach { s ->
                FilterChip(selected = (splitStyle ?: "Auto") == s, onClick = { onSplit(s) }, label = { Text(s) })
            }
        }
    }
}

// Periodization: a plain-language choice (Steady vs Periodized) with a bar
// chart of the athlete's own next 8 weeks under whichever option is selected.
// Shared by onboarding (availability step, below the effort chips that set the
// weekly target it visualizes) + Settings.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PeriodizationControl(
    periodized: Boolean,
    onChange: (Boolean) -> Unit,
    // The athlete's own weekly TSS target, when they have set one. Drives the
    // numbers graph; null falls back to a typical week.
    weeklyTssTarget: Int? = null,
) {
    Text("How should your weeks progress?", style = MaterialTheme.typography.labelLarge)
    Text(
        "Periodized training builds for a few weeks, then eases off with a lighter week so you " +
            "absorb the work and come back stronger. Steady keeps a similar load every week.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = !periodized, onClick = { onChange(false) }, label = { Text("Steady") })
        FilterChip(selected = periodized, onClick = { onChange(true) }, label = { Text("Periodized") })
    }
    // BOTH choices get the chart, so the decision is a visual comparison
    // (flat bars vs build-and-recover waves), not a labeled mystery.
    PeriodizationNumbers(weeklyTssTarget, periodized, Modifier.padding(top = 8.dp))
}

// The athlete's next 8 weeks as bars, in their OWN numbers — the same rules
// plan-week plans with (Periodization.projectedWeeks). Deload weeks are amber
// with a "rest" tag; the caption spells out how the effort choice above feeds
// this chart, because that coupling is exactly what users found confusing.
@Composable
internal fun PeriodizationNumbers(weeklyTssTarget: Int?, periodized: Boolean, modifier: Modifier = Modifier) {
    val base = weeklyTssTarget ?: Periodization.DEFAULT_WEEKLY_TSS
    val weeks = remember(base, periodized) {
        if (periodized) Periodization.projectedWeeks(base)
        else (1..8).map { Periodization.Week(it, base, deload = false) }
    }
    val max = remember(weeks) { weeks.maxOf { it.tss }.toFloat() }
    val buildColor = MaterialTheme.colorScheme.primary
    val deloadColor = amberAccent()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Your next 8 weeks (TSS)", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().height(128.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            weeks.forEach { w ->
                val frac = (w.tss / max).coerceIn(0.06f, 1f)
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight((1f - frac).coerceAtLeast(0.001f)))
                    Text(
                        "${w.tss}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (w.deload) deloadColor else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Box(
                        Modifier.fillMaxWidth().weight(frac)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (w.deload) deloadColor.copy(alpha = 0.75f) else buildColor),
                    )
                    Text(
                        if (w.deload) "rest" else "W${w.number}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (w.deload) deloadColor else muted,
                        maxLines = 1,
                    )
                }
            }
        }
        val rampPct = ((Periodization.BUILD_RAMP - 1f) * 100).toInt()
        val cutPct = (Periodization.DELOAD_CUT * 100).toInt()
        val caption = if (periodized) {
            "Your weekly load choice above sets the starting point (~$base TSS). Each build " +
                "week adds ~$rampPct%, and every ${Periodization.DELOAD_AFTER}th week eases " +
                "off ~$cutPct% (amber) so you absorb the work and come back stronger. The " +
                "coach re-reads what you actually did each week and adjusts."
        } else {
            "Steady keeps every week near your ~$base TSS choice above. Pick Periodized to " +
                "build up over a few weeks with regular lighter weeks in between."
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

// Weekly availability, "few questions" model: days per week + one typical length
// + an optional longer day. The app spreads the week automatically (no day-by-day
// tedium, no time-of-day). The optional long day is pinned to a real weekday so
// the marathon long-run budget still reaches the planner.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeeklyAvailabilityEditor(
    availability: List<DayAvailability>,
    onChange: (List<DayAvailability>) -> Unit,
) {
    // Seed the simple questions once from any existing per-day data.
    val seed = remember { availabilityToQuestions(availability) }
    var daysPerWeek by remember { mutableStateOf(seed.daysPerWeek) }
    var typical by remember { mutableStateOf(seed.typicalMin) }
    var longDays by remember { mutableStateOf(seed.longDays) }
    var longMin by remember { mutableStateOf(seed.longMin) }

    fun push() = onChange(buildAvailability(daysPerWeek, typical, longDays, longMin))

    Text("How many days a week can you train?", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DAYS_PER_WEEK.forEach { n ->
            FilterChip(selected = daysPerWeek == n, onClick = { daysPerWeek = n; push() }, label = { Text("$n") })
        }
    }
    Text("Typical session length", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TYPICAL_MINUTES.forEach { m ->
            FilterChip(selected = typical == m, onClick = { typical = m; push() }, label = { Text(durationLabel(m)) })
        }
    }
    // Plural on purpose: a runner has one long day, a triathlete often two
    // (long ride Saturday, long run Sunday). Tap as many as apply.
    Text("Longer days? (long run, long ride...)", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = longDays.isEmpty(),
            onClick = { longDays = emptyList(); push() },
            label = { Text("None") },
        )
        DAYS.forEach { d ->
            FilterChip(
                selected = d in longDays,
                onClick = {
                    longDays = if (d in longDays) longDays - d else longDays + d
                    push()
                },
                label = { Text(d) },
            )
        }
    }
    if (longDays.isNotEmpty()) {
        Text("How long are they?", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LONG_MINUTES.forEach { m ->
                FilterChip(selected = longMin == m, onClick = { longMin = m; push() }, label = { Text(durationLabel(m)) })
            }
        }
    }
    Text(
        "We'll spread your week automatically. Fine-tune anything later in Settings.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// Multi-select equipment (only surfaced when the athlete lifts).
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EquipmentSelector(selected: List<String>, onToggle: (String) -> Unit) {
    Text("What equipment can you use?", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EQUIPMENT_ITEMS.forEach { e ->
            FilterChip(selected = selected.contains(e), onClick = { onToggle(e) }, label = { Text(e) })
        }
    }
}

// Theme palette + light/dark, shared by onboarding Appearance and Settings.
@Composable
internal fun AppearancePicker(
    themeMode: ThemeMode,
    palette: ThemePalette,
    onMode: (ThemeMode) -> Unit,
    onPalette: (ThemePalette) -> Unit,
) {
    Text("Palette", style = MaterialTheme.typography.labelLarge)
    Text(
        "Re-skin the whole app. “Serene Vanguard” is the original sage look; the rest are experiments you can switch any time.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ThemePalette.entries.forEach { p ->
        Row(
            Modifier.fillMaxWidth().clickable { onPalette(p) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = palette == p, onClick = { onPalette(p) })
            Text(p.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
            Box(Modifier.weight(1f))
            PaletteSwatches(p)
        }
    }
    Text("Light / Dark", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    ThemeMode.entries.forEach { mode ->
        Row(
            Modifier.fillMaxWidth().clickable { onMode(mode) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = themeMode == mode, onClick = { onMode(mode) })
            Text(mode.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// Three dots previewing a palette's primary/secondary/surface (its dark scheme).
@Composable
internal fun PaletteSwatches(p: ThemePalette) {
    val scheme = p.palette().dark
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(scheme.primary, scheme.secondary, scheme.surface).forEach { c ->
            Box(
                Modifier.size(16.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
        }
    }
}

// --- Availability helpers (few-questions <-> per-day list) ------------------

internal data class AvailQuestions(
    val daysPerWeek: Int,
    val typicalMin: Int,
    // Every day whose budget clearly exceeds the typical one (long run day,
    // long ride day, ...). Plural: a triathlete's weekend has two long days.
    val longDays: List<String>,
    val longMin: Int,
)

// Reverse-map an existing per-day list back to the simple questions so the
// editor opens pre-filled (for existing accounts / re-editing).
internal fun availabilityToQuestions(availability: List<DayAvailability>): AvailQuestions {
    if (availability.isEmpty()) return AvailQuestions(daysPerWeek = 4, typicalMin = 60, longDays = emptyList(), longMin = 120)
    val maxMin = availability.maxOf { it.max_minutes }
    val nonLong = availability.filter { it.max_minutes < maxMin }
    val basis = (if (nonLong.isNotEmpty()) nonLong else availability).map { it.max_minutes }.sorted()
    val typical = nearestChip(basis[(basis.size - 1) / 2], TYPICAL_MINUTES)
    // Long days are every day clearly over the typical budget (not just one).
    val longEntries = availability.filter { it.max_minutes > typical }
    return AvailQuestions(
        daysPerWeek = availability.size.coerceIn(DAYS_PER_WEEK.first(), DAYS_PER_WEEK.last()),
        typicalMin = typical,
        longDays = longEntries.map { it.day },
        longMin = longEntries.maxOfOrNull { nearestChip(it.max_minutes, LONG_MINUTES) } ?: 120,
    )
}

// Build the per-day list from the simple answers: spread N days over the week,
// force the long day in (keeping the count), and cap each day.
internal fun buildAvailability(daysPerWeek: Int, typical: Int, longDays: List<String>, longMin: Int): List<DayAvailability> {
    val base = DAY_PATTERNS[daysPerWeek] ?: DAY_PATTERNS.getValue(4)
    // Every long day IS a training day; the remaining slots fill from the base
    // pattern's regular days in order. (The naive "swap the last base day"
    // version could evict one long day to make room for another.)
    val longs = longDays.distinct().take(daysPerWeek)
    val regulars = base.filterNot { it in longs }.take((daysPerWeek - longs.size).coerceAtLeast(0))
    return (regulars + longs).sortedBy { DAYS.indexOf(it) }.map { d ->
        DayAvailability(day = d, max_minutes = if (d in longs) longMin else typical)
    }
}

private fun nearestChip(v: Int, options: List<Int>): Int =
    options.minByOrNull { kotlin.math.abs(it - v) } ?: v

// Add/remove an item from a multi-select list.
internal fun List<String>.toggled(item: String): List<String> =
    if (contains(item)) this - item else this + item

// Add/remove an item from a per-key multi-select map (per-activity goals).
internal fun Map<String, List<String>>.toggleIn(key: String, item: String): Map<String, List<String>> {
    val cur = this[key].orEmpty()
    return this + (key to (if (cur.contains(item)) cur - item else cur + item))
}

// "45m" / "1h" / "1h30" / "2h" / "3h" for the availability chips.
internal fun durationLabel(min: Int): String = when {
    min < 60 -> "${min}m"
    min % 60 == 0 -> "${min / 60}h"
    else -> "${min / 60}h${min % 60}"
}

// --- "Your numbers" ----------------------------------------------------------
// Optional performance anchors, one compact section per selected sport. These
// are the inputs generation was starving for: threshold pace (run zones), FTP
// (ride power), CSS (swim pace), top sets (strength progression floor), LTHR
// (HR zones). Every field skippable; the copy sells why it's worth 30 seconds.

/** "mm:ss" or "h:mm:ss" → seconds, null when unparseable. */
internal fun parseTimeSeconds(text: String): Int? {
    val parts = text.trim().split(":").map { it.toIntOrNull() ?: return null }
    return when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> null
    }
}

private fun formatPace(secondsPerKm: Int): String = "%d:%02d".format(secondsPerKm / 60, secondsPerKm % 60)

/**
 * Threshold pace from a recent race result. Standard approximations: threshold
 * (the pace you can hold ~1h) is a touch slower than 5K race pace, roughly 10K
 * race pace, and a touch faster than half-marathon pace.
 */
internal fun thresholdPaceFromRace(distanceKm: Double, timeSeconds: Int): String? {
    if (timeSeconds <= 0 || distanceKm <= 0) return null
    val racePaceSecPerKm = timeSeconds / distanceKm
    val factor = when {
        distanceKm <= 6.0 -> 1.05  // 5K: threshold ~5% slower
        distanceKm <= 12.0 -> 1.00 // 10K: threshold ~= race pace
        else -> 0.97               // half: threshold ~3% faster
    }
    val sec = (racePaceSecPerKm * factor).toInt()
    // Sanity: 2:30-12:00 /km covers every plausible runner; outside is a typo.
    return if (sec in 150..720) formatPace(sec) else null
}

private val RACE_DISTANCES = listOf("5K" to 5.0, "10K" to 10.0, "Half" to 21.0975)

@Composable
private fun PerfSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PerformanceEditor(
    profile: TrainingProfile,
    intervalsConnected: Boolean,
    onUpdate: ((TrainingProfile) -> TrainingProfile) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            if (intervalsConnected) {
                "Intervals.icu is connected, so your zones and thresholds sync automatically. Anything you add here fills the gaps."
            } else {
                "All optional, but every number here makes your workouts noticeably more accurate. Skip anything you don't know."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (profile.sports.contains("run")) {
            var raceDist by remember { mutableStateOf<String?>(null) }
            var raceTime by remember { mutableStateOf("") }
            PerfSection("Running") {
                Text(
                    "A recent race result sets your training paces.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RACE_DISTANCES.forEach { (label, _) ->
                        FilterChip(
                            selected = raceDist == label,
                            onClick = {
                                raceDist = if (raceDist == label) null else label
                                val km = RACE_DISTANCES.firstOrNull { it.first == raceDist }?.second
                                val secs = parseTimeSeconds(raceTime)
                                val pace = if (km != null && secs != null) thresholdPaceFromRace(km, secs) else null
                                onUpdate { it.copy(threshold_pace_per_km = pace ?: it.threshold_pace_per_km) }
                            },
                            label = { Text(label) },
                        )
                    }
                }
                OutlinedTextField(
                    raceTime,
                    { v ->
                        raceTime = v
                        val km = RACE_DISTANCES.firstOrNull { it.first == raceDist }?.second
                        val secs = parseTimeSeconds(v)
                        if (km != null && secs != null) {
                            thresholdPaceFromRace(km, secs)?.let { pace ->
                                onUpdate { it.copy(threshold_pace_per_km = pace) }
                            }
                        }
                    },
                    label = { Text("Finish time") },
                    placeholder = { Text(if (raceDist == "Half") "1:45:00" else "25:30") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = profile.threshold_pace_per_km?.let {
                        { Text("Threshold pace ~$it /km") }
                    },
                )
            }
        }
        if (profile.sports.contains("ride")) {
            PerfSection("Cycling") {
                OutlinedTextField(
                    profile.ftp?.toString() ?: "",
                    { v -> onUpdate { it.copy(ftp = v.toIntOrNull()?.takeIf { w -> w in 50..600 }) } },
                    label = { Text("FTP") }, suffix = { Text("watts") }, singleLine = true,
                    placeholder = { Text("Leave empty if unsure") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (profile.sports.contains("swim")) {
            PerfSection("Swimming") {
                OutlinedTextField(
                    profile.css_per_100m ?: "",
                    { v -> onUpdate { it.copy(css_per_100m = v.ifBlank { null }) } },
                    label = { Text("Comfortable 100m pace") }, placeholder = { Text("1:55") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("The pace you could hold for a steady 400m") },
                )
            }
        }
        if (profile.sports.contains("strength")) {
            PerfSection("Strength") {
                Text(
                    "Your current top set per lift. First workouts start from these instead of conservative guesses.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf("Back Squat", "Barbell Bench Press", "Deadlift").forEach { lift ->
                    StartingLiftRow(lift, profile, onUpdate)
                }
            }
        }
        if (!intervalsConnected && (profile.sports.contains("run") || profile.sports.contains("ride"))) {
            PerfSection("Heart rate") {
                OutlinedTextField(
                    profile.lthr?.toString() ?: "",
                    { v -> onUpdate { it.copy(lthr = v.toIntOrNull()?.takeIf { h -> h in 120..210 }) } },
                    label = { Text("Threshold heart rate") }, suffix = { Text("bpm") }, singleLine = true,
                    placeholder = { Text("The HR you can hold ~1 hour") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Sets your HR zones. Without it, zones are estimated from your age.") },
                )
            }
        }
    }
}

@Composable
internal fun StartingLiftRow(lift: String, profile: TrainingProfile, onUpdate: ((TrainingProfile) -> TrainingProfile) -> Unit) {
    val current = profile.starting_lifts.firstOrNull { it.exercise == lift }
    fun write(weight: Double?, reps: Int?) {
        onUpdate { p ->
            val rest = p.starting_lifts.filterNot { it.exercise == lift }
            val w = weight ?: current?.weight_kg
            val r = reps ?: current?.reps
            p.copy(
                starting_lifts = if (w != null && w > 0) {
                    rest + StartingLift(lift, w, r ?: 5)
                } else rest,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            lift.removePrefix("Barbell "),
            Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            current?.weight_kg?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "",
            { v -> write(v.toDoubleOrNull(), null) },
            label = { Text("kg") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            current?.reps?.toString() ?: "",
            { v -> write(null, v.toIntOrNull()) },
            label = { Text("reps") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
}

