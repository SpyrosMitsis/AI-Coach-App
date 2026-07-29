package com.workoutmaker.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// Manual recovery entry for a day the watch didn't sync. All three fields are
// optional — saving any one writes it onto today's wellness row.
@Composable
internal fun ManualRecoveryDialog(onDismiss: () -> Unit, onSave: (Double?, Int?, Int?) -> Unit) {
    var hrv by rememberSaveable { mutableStateOf("") }
    var rhr by rememberSaveable { mutableStateOf("") }
    var sleepH by rememberSaveable { mutableStateOf("") }
    val hrvVal = hrv.trim().toDoubleOrNull()
    val rhrVal = rhr.trim().toIntOrNull()
    val sleepMin = sleepH.trim().toDoubleOrNull()?.let { (it * 60).roundToInt() }
    val canSave = hrvVal != null || rhrVal != null || sleepMin != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log recovery manually") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Fill in what you know, leave the rest blank. Saved for today.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = hrv, onValueChange = { hrv = it }, label = { Text("HRV (ms)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = rhr, onValueChange = { rhr = it }, label = { Text("Resting HR (bpm)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = sleepH, onValueChange = { sleepH = it }, label = { Text("Sleep (hours, e.g. 7.5)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(hrvVal, rhrVal, sleepMin) }, enabled = canSave) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// Month calendar picker: jump to any past day. Future days are disabled; days
// with a completed activity get a dot; today and the selected day are marked.
@Composable
internal fun DayPickerDialog(
    selected: LocalDate,
    marked: Set<LocalDate>,
    onPick: (LocalDate) -> Unit,
    onToday: () -> Unit,
    onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    val canGoNextMonth = month.isBefore(YearMonth.from(today))
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Header: Today shortcut · month nav · close.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onToday) {
                        Text("TODAY", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                    }
                    Text(
                        month.format(DateTimeFormatter.ofPattern("MMMM yyyy")).uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = { if (canGoNextMonth) month = month.plusMonths(1) }, enabled = canGoNextMonth) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month",
                            tint = if (canGoNextMonth) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                // Weekday header (Sunday-first, matching the grid).
                Row(Modifier.fillMaxWidth()) {
                    listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT").forEach { d ->
                        Text(d, Modifier.weight(1f), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 6×7 grid. First cell = the Sunday on/before the 1st.
                val first = month.atDay(1)
                val lead = first.dayOfWeek.value % 7 // Mon=1..Sun=7 → Sun=0 offset
                val firstCell = first.minusDays(lead.toLong())
                for (week in 0 until 6) {
                    Row(Modifier.fillMaxWidth()) {
                        for (dow in 0 until 7) {
                            val d = firstCell.plusDays((week * 7 + dow).toLong())
                            DayCell(
                                day = d,
                                inMonth = d.monthValue == month.monthValue,
                                isToday = d == today,
                                isSelected = d == selected,
                                isFuture = d.isAfter(today),
                                marked = d in marked,
                                onClick = { if (!d.isAfter(today)) onPick(d) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    isFuture: Boolean,
    marked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .clickable(enabled = !isFuture, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${day.dayOfMonth}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    isFuture || !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    isToday -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        // Activity dot under days with a completed session (hidden on the
        // selected day, where it'd clash with the filled circle).
        Box(
            Modifier.size(5.dp).clip(CircleShape)
                .background(if (marked && !isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}
