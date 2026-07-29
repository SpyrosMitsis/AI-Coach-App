package com.workoutmaker.app.ui.screens.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.str(vararg keys: String): String? {
    for (k in keys) {
        val v = (this[k] as? JsonPrimitive)?.contentOrNull
        if (!v.isNullOrBlank() && v != "null") return v
    }
    return null
}

// A workout payload either is a Workout ({title,type,sections}) or wraps one in
// `structure` (the finalize shape: {name,description,structure,coach_note}).
internal fun isWorkoutShape(obj: JsonObject): Boolean {
    val core = obj.obj("structure") ?: obj
    return core.arr("sections") != null
}

@Composable
internal fun WorkoutCard(obj: JsonObject) {
    val core = obj.obj("structure") ?: obj
    val title = core.str("title") ?: obj.str("name") ?: "Workout"
    val type = core.str("type") ?: obj.str("kind")
    val duration = core.str("duration_minutes")
    val tss = core.str("tss_estimate")
    val rpe = core.str("rpe_target")
    val desc = obj.str("description") ?: core.str("description")
    val note = obj.str("coach_note", "coach note") ?: core.str("coach_note", "coach note")
    val sections = core.arr("sections").orEmpty()
    val shape = RoundedCornerShape(16.dp)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(16.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Meta chips: type · duration · TSS · RPE
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                type?.let { MetaChip(it.replaceFirstChar { c -> c.uppercase() }) }
                duration?.let { MetaChip("$it min") }
                tss?.let { MetaChip("TSS $it") }
                rpe?.let { MetaChip("RPE $it") }
            }
            desc?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            sections.forEachIndexed { i, secEl ->
                val sec = secEl as? JsonObject ?: return@forEachIndexed
                val secName = sec.str("name") ?: "Section ${i + 1}"
                val secDur = sec.str("duration_minutes")
                Text(
                    if (secDur != null) "$secName · $secDur min" else secName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
                sec.arr("exercises").orEmpty().forEach { exEl ->
                    val ex = exEl as? JsonObject ?: return@forEach
                    ExerciseLine(ex)
                }
            }
            note?.let {
                Text(
                    "💡 $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ExerciseLine(ex: JsonObject) {
    val name = ex.str("name") ?: "Exercise"
    val sets = ex.str("sets")
    val reps = ex.str("reps")
    val weight = ex.str("weight_kg")
    val zone = ex.str("pace_zone", "hr_zone", "zone")
    val rest = ex.str("rest_seconds")
    val notes = ex.str("notes")

    // "3×8" / "1×continuous" / "8" depending on what's present.
    val dose = when {
        sets != null && reps != null -> "$sets×$reps"
        reps != null -> reps
        sets != null -> "$sets sets"
        else -> null
    }
    val meta = listOfNotNull(
        dose,
        weight?.let { "$it kg" },
        zone,
        rest?.let { "${it}s rest" },
    ).joinToString(" · ")

    Column(Modifier.padding(top = 4.dp)) {
        Row {
            Text(
                "•  ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (meta.isNotBlank()) {
                Text(
                    "  $meta",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        notes?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// Renders a leaked JSON data blob as a collapsed card the user can expand into
// readable "Label: value" rows instead of dumping raw braces into the chat.
@Composable
internal fun DataCard(raw: String) {
    var expanded by remember { mutableStateOf(false) }
    val rows = remember(raw) { flattenJson(raw) }
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                if (expanded) "📊 Coach data  ▴" else "📊 Coach data  ▾",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (expanded) {
                if (rows.isEmpty()) {
                    Text(
                        raw,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Column(Modifier.padding(top = 8.dp)) {
                        rows.forEach { (label, value) ->
                            Row(Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    "$label  ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    value,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal val coachJson = Json { ignoreUnknownKeys = true; isLenient = true }

// Heuristic: is this assistant reply proposing a concrete workout or week plan
// (structure markers / day-by-day breakdown) rather than analysis or Q&A?
internal fun looksLikeWorkoutProposal(text: String): Boolean {
    if (looksLikeJson(text)) {
        val obj = runCatching { coachJson.parseToJsonElement(text) }.getOrNull() as? JsonObject
        return obj != null && isWorkoutShape(obj)
    }
    if (text.length < 80) return false
    val t = text.lowercase()
    val structure = listOf(
        "warm-up", "warmup", "main set", "cool-down", "cooldown", "×", " sets",
        " reps", "interval", "tempo", "easy run", "long run", "rest day",
    )
    val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    return structure.count { t.contains(it) } >= 2 || days.count { t.contains(it) } >= 3
}

internal fun looksLikeJson(s: String): Boolean {
    val t = s.trim()
    return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
}

// A readable one-liner for history previews: unwrap envelopes, name workouts,
// otherwise show the prose.
internal fun previewText(content: String): String {
    if (!looksLikeJson(content)) return content
    val obj = runCatching { coachJson.parseToJsonElement(content) }.getOrNull() as? JsonObject
        ?: return "📊 Coach data"
    (obj["message"] as? JsonPrimitive)?.contentOrNull?.let { return it }
    if (isWorkoutShape(obj)) {
        val core = obj.obj("structure") ?: obj
        return "🏋️ " + (core.str("title") ?: obj.str("name") ?: "Workout")
    }
    return "📊 Coach data"
}

private fun prettyKey(k: String): String =
    k.replace('_', ' ').replaceFirstChar { it.uppercase() } + ":"

// Flatten a JSON value into readable label/value rows (one level of nesting),
// turning snake_case keys into Title Case. Returns empty on parse failure.
private fun flattenJson(raw: String): List<Pair<String, String>> {
    val el = runCatching { coachJson.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
    val out = mutableListOf<Pair<String, String>>()
    fun scalar(e: JsonElement): String? = (e as? JsonPrimitive)?.let {
        if (it.isString) it.content else it.content
    }
    when (el) {
        is JsonObject -> el.forEach { (k, v) ->
            when (v) {
                is JsonPrimitive -> out += prettyKey(k) to (scalar(v) ?: v.toString())
                is JsonArray -> out += prettyKey(k) to "${v.size} item(s)"
                is JsonObject -> v.forEach { (k2, v2) ->
                    val label = "${prettyKey(k)} ${prettyKey(k2).removeSuffix(":")}"
                    out += label to when (v2) {
                        is JsonPrimitive -> scalar(v2) ?: v2.toString()
                        is JsonArray -> "${v2.size} item(s)"
                        else -> "…"
                    }
                }
            }
        }
        is JsonArray -> el.take(20).forEachIndexed { i, item ->
            val summary = (item as? JsonObject)?.entries?.joinToString(", ") { (k, v) ->
                "${k.replace('_', ' ')} ${(v as? JsonPrimitive)?.let { scalar(it) } ?: v}"
            } ?: scalar(item) ?: item.toString()
            out += "${i + 1}." to summary
        }
        else -> {}
    }
    return out
}
