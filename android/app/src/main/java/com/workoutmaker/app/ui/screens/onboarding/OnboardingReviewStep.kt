package com.workoutmaker.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.workoutmaker.app.data.TrainingProfile
import com.workoutmaker.app.ui.collectAsStateSafe
import com.workoutmaker.app.ui.components.SectionCard
import androidx.compose.material3.CircularProgressIndicator
import com.workoutmaker.app.ui.components.SectionLabel
import com.workoutmaker.app.ui.screens.settings.SPORTS
import com.workoutmaker.app.ui.screens.settings.availabilityToQuestions
import com.workoutmaker.app.ui.screens.settings.durationLabel
import com.workoutmaker.app.ui.screens.settings.sportLabel

@Composable
internal fun StepReview(profile: TrainingProfile, vm: OnboardingViewModel) {
    StepColumn {
        Text("You're all set", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        profile.display_name?.takeIf { it.isNotBlank() }?.let { ReviewLine("Name", it) }
        val trained = SPORTS.filter { profile.sports.contains(it) }
        if (trained.isEmpty()) {
            ReviewLine("Activities", "-")
        } else trained.forEach { sport ->
            val goals = profile.goals_by_sport[sport].orEmpty().joinToString(", ")
            val level = profile.experience_by_sport[sport]
            val detail = listOfNotNull(goals.ifBlank { null }, level).joinToString(" · ").ifBlank { "-" }
            ReviewLine(sportLabel(sport), detail)
        }
        if (profile.day_availability.isNotEmpty()) {
            val q = availabilityToQuestions(profile.day_availability)
            val summary = "${q.daysPerWeek} days/wk · ${durationLabel(q.typicalMin)}" +
                (q.longDays.takeIf { it.isNotEmpty() }
                    ?.let { " · long ${it.joinToString("+")} ${durationLabel(q.longMin)}" } ?: "")
            ReviewLine("Availability", summary)
        }
        val numbers = listOfNotNull(
            profile.threshold_pace_per_km?.let { "threshold $it/km" },
            profile.ftp?.let { "FTP ${it}w" },
            profile.css_per_100m?.let { "CSS $it/100m" },
            profile.lthr?.let { "LTHR $it" },
            profile.starting_lifts.takeIf { it.isNotEmpty() }?.let { "${it.size} lifts" },
        )
        if (numbers.isNotEmpty()) ReviewLine("Your numbers", numbers.joinToString(" · "))
        profile.weekly_tss_target?.let { ReviewLine("Weekly load", "~$it TSS") }
        if (profile.sports.contains("strength")) {
            ReviewLine("Progression", if (profile.periodized) "Periodized" else "Steady")
        }
        if (profile.equipment_list.isNotEmpty()) {
            ReviewLine("Equipment", profile.equipment_list.joinToString(", "))
        }
        injuriesSummary(profile.injuries).takeIf { it.isNotBlank() }?.let { ReviewLine("Injuries", it) }
        profile.goal_date?.let { ReviewLine("Goal race", it) }

        // The payoff: build the actual first week from these answers, right
        // here — setup stops being a form and becomes a result.
        val preview by vm.previewWeek.collectAsStateSafe()
        val previewBusy by vm.previewBusy.collectAsStateSafe()
        val previewError by vm.previewError.collectAsStateSafe()
        when {
            preview != null -> SectionCard {
                SectionLabel("YOUR FIRST WEEK")
                preview!!.week_focus?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                preview!!.days.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            d.weekday,
                            Modifier.width(44.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (d.type == "rest") "Rest" else d.title,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                        if (d.tss > 0) {
                            Text(
                                "${d.tss} TSS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    "Already on your calendar. Tap Finish and start training.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                )
            }
            previewBusy -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp,
                )
                Text(
                    "Building your first week (about 30 seconds)…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            else -> {
                previewError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(
                    onClick = { vm.previewFirstWeek() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Build my first week now") }
            }
        }
        Text(
            "Tap Finish to save. You can fine-tune everything later in Settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ReviewLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
