package com.workoutmaker.app.data

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject

// --- Training profile + Intervals ---------------------------------------
suspend fun WorkoutRepository.loadProfile(): TrainingProfile? =
    runCatching {
        val onboarding = profileRow()?.get("onboarding") as? JsonObject ?: return@runCatching null
        json.decodeFromJsonElement(TrainingProfile.serializer(), onboarding)
    }.logFailure("loadProfile").getOrNull()

suspend fun WorkoutRepository.isOnboardingComplete(): Boolean = runCatching {
    (profileRow()?.get("onboarding_complete") as? JsonPrimitive)?.content?.toBoolean() ?: false
}.logFailure("isOnboardingComplete").fold(
    onSuccess = { v -> runCatching { prefs.setOnboardingComplete(v) }; v },
    // Network failure ≠ new user: fall back to the last known state so an
    // offline cold start doesn't show the onboarding welcome again.
    onFailure = { runCatching { prefs.onboardingCompleteCached() }.getOrDefault(false) },
)

suspend fun WorkoutRepository.saveProfile(profile: TrainingProfile) {
    val onboarding = json.encodeToJsonElement(TrainingProfile.serializer(), profile)
    supabase.postgrest.from("user_profiles").update(
        mapOf(
            "onboarding" to onboarding,
            "onboarding_complete" to JsonPrimitive(true),
            // Mirror the name to the top-level column the backend already reads.
            "display_name" to (profile.display_name?.let { JsonPrimitive(it) }
                ?: JsonNull),
        ),
    ) { filter { eq("id", uid()) } }
    invalidateProfileCache()
}

// Tells "no account yet" apart from "wrong password" after a failed sign-in.
// Backed by a SECURITY DEFINER RPC granted to anon; null when undeterminable.
suspend fun WorkoutRepository.accountExists(email: String): Boolean? = runCatching {
    supabase.postgrest.rpc(
        "account_exists",
        buildJsonObject { put("p_email", email.trim()) },
    ).decodeAs<Boolean>()
}.logFailure("accountExists").getOrNull()

// --- Coach knowledge (durable injuries/equipment/preferences) -----------
suspend fun WorkoutRepository.loadKnowledge(): String =
    runCatching {
        (profileRow()?.get("coach_knowledge") as? JsonPrimitive)?.content ?: ""
    }.logFailure("loadKnowledge").getOrDefault("")

suspend fun WorkoutRepository.saveKnowledge(text: String) {
    supabase.postgrest.from("user_profiles").update(
        mapOf("coach_knowledge" to JsonPrimitive(text)),
    ) { filter { eq("id", uid()) } }
    invalidateProfileCache()
}

// --- Coach memory (rolling notes the coach carries between sessions) ------
suspend fun WorkoutRepository.loadMemory(): String =
    runCatching {
        (profileRow()?.get("training_memory") as? JsonPrimitive)?.content ?: ""
    }.logFailure("loadMemory").getOrDefault("")

suspend fun WorkoutRepository.saveMemory(text: String) {
    supabase.postgrest.from("user_profiles").update(
        mapOf("training_memory" to JsonPrimitive(text)),
    ) { filter { eq("id", uid()) } }
    invalidateProfileCache()
}

// --- Coach soul (the coach's identity + its evolving relationship with you) -
// Falls back to "" when the coach_soul column is absent (pre-migration 30) or
// unseeded; the backend serves a default persona until the first auto-evolve.
suspend fun WorkoutRepository.loadSoul(): String =
    runCatching {
        (profileRow()?.get("coach_soul") as? JsonPrimitive)?.content ?: ""
    }.logFailure("loadSoul").getOrDefault("")

suspend fun WorkoutRepository.saveSoul(text: String) {
    supabase.postgrest.from("user_profiles").update(
        mapOf("coach_soul" to JsonPrimitive(text)),
    ) { filter { eq("id", uid()) } }
    invalidateProfileCache()
}

suspend fun WorkoutRepository.connectIntervalsVerified(athleteId: String, apiKey: String): ConnectIntervalsResult {
    val r: ConnectIntervalsResult = json.decodeFromString(connectIntervals(athleteId, apiKey))
    invalidateProfileCache() // athlete id + key hint changed on the profile
    return r
}

// Saved Intervals.icu connection: athlete id + masked key hint (null when
// not connected). The hint column arrives with migration 27.
suspend fun WorkoutRepository.intervalsConnection(): Pair<String, String?>? {
    val row = profileRow() ?: return null
    val athlete = (row["intervals_athlete_id"] as? JsonPrimitive)?.contentOrNull ?: return null
    val hint = (row["intervals_api_key_hint"] as? JsonPrimitive)?.contentOrNull
    return athlete to hint
}

// Saved LLM keys (provider, validity, masked hint) for the settings UI.
// key_hint may not exist before migration 27 — fall back to a hint-less select.
suspend fun WorkoutRepository.llmKeyRows(): List<LlmKeyRow> = runCatching {
    supabase.postgrest.from("llm_api_keys").select(
        columns = io.github.jan.supabase.postgrest.query.Columns.list("provider", "is_valid", "last_tested_at", "key_hint", "base_url"),
    ).decodeList<LlmKeyRow>()
}.getOrElse {
    // Pre-migration fallback (no key_hint / base_url columns yet).
    runCatching {
        supabase.postgrest.from("llm_api_keys").select(
            columns = io.github.jan.supabase.postgrest.query.Columns.list("provider", "is_valid", "last_tested_at", "key_hint"),
        ).decodeList<LlmKeyRow>()
    }.getOrElse {
        supabase.postgrest.from("llm_api_keys").select(
            columns = io.github.jan.supabase.postgrest.query.Columns.list("provider", "is_valid", "last_tested_at"),
        ).decodeList()
    }
}

// --- P1: goal races -----------------------------------------------------
suspend fun WorkoutRepository.races(): List<Race> =
    supabase.postgrest.from("races").select { order("date", Order.ASCENDING) }.decodeList()

suspend fun WorkoutRepository.addRace(race: Race) {
    val row = buildJsonObject {
        put("name", JsonPrimitive(race.name))
        put("date", JsonPrimitive(race.date))
        put("priority", JsonPrimitive(race.priority))
        put("sport", JsonPrimitive(race.sport))
        race.distance?.let { put("distance", JsonPrimitive(it)) }
        race.target?.let { put("target", JsonPrimitive(it)) }
        race.notes?.let { put("notes", JsonPrimitive(it)) }
    }
    supabase.postgrest.from("races").insert(row)
}

suspend fun WorkoutRepository.deleteRace(race: Race) {
    race.id?.let { id ->
        supabase.postgrest.from("races").delete { filter { eq("id", id) } }
    }
    // Deleting the active goal also clears the anchor from the profile, which is
    // what periodization, phase and taper read. Matched on the DATE alone: the
    // rest of the app identifies the goal race that way too, and `goal` is a
    // field deriveLegacyFields rewrites from the training goals, so a name
    // comparison silently stopped matching and left the anchor pointing at a
    // race that no longer exists.
    val p = loadProfile() ?: return
    if (p.goal_date != race.date) return
    // Promote the soonest remaining A goal rather than leaving no anchor at all,
    // the same way the coach's remove_goal_race does.
    val today = java.time.LocalDate.now().toString()
    val next = runCatching { races() }.getOrDefault(emptyList())
        .filter { it.priority.uppercase() == "A" && it.date >= today }
        .minByOrNull { it.date }
    val pace = if (race.target != null && p.target_pace == race.target) null else p.target_pace
    val reanchored = p.copy(goal = next?.name, goal_date = next?.date, target_pace = pace)
    // No race left to anchor to: hand `goal` back to the training goals it also
    // stores, rather than leaving the prompts with a null where a goal was.
    saveProfile(if (next == null) reanchored.deriveLegacyFields() else reanchored)
}

// Make a goal the periodization anchor: drives weeks-to-goal / phase / taper.
// Run goals with a pace-shaped target also become the profile's target pace.
suspend fun WorkoutRepository.setGoalRace(race: Race) {
    val p = loadProfile() ?: TrainingProfile()
    val pace = race.target?.takeIf { race.sport == "run" && it.isNotBlank() }
    saveProfile(p.copy(goal = race.name, goal_date = race.date, target_pace = pace ?: p.target_pace))
}

// --- E1 + E4: thresholds & tests ----------------------------------------
suspend fun WorkoutRepository.thresholdTests(): List<ThresholdTest> =
    supabase.postgrest.from("threshold_tests").select { order("date", Order.DESCENDING) }.decodeList()

suspend fun WorkoutRepository.addThresholdTest(t: ThresholdTest) {
    val row = buildJsonObject {
        put("date", JsonPrimitive(t.date))
        put("kind", JsonPrimitive(t.kind))
        put("value", JsonPrimitive(t.value))
        t.notes?.let { put("notes", JsonPrimitive(it)) }
    }
    supabase.postgrest.from("threshold_tests").insert(row)
    applyThreshold(t.kind, t.value)
}

// Update the athlete's current threshold (feeds zone calculation).
suspend fun WorkoutRepository.applyThreshold(kind: String, value: Double) {
    val p = loadProfile() ?: TrainingProfile()
    val updated = when (kind) {
        "lthr" -> p.copy(lthr = value.toInt())
        "ftp" -> p.copy(ftp = value.toInt())
        "threshold_pace" -> p.copy(threshold_pace_per_km = Zones.formatPace(value.toInt()))
        else -> p
    }
    saveProfile(updated)
}

// (A saveThresholds() that wrote all three at once lived here. The zones screen
// no longer edits them directly: applyThreshold above, driven by logging a
// test, is the single writer, so a threshold always carries a measured date.)
