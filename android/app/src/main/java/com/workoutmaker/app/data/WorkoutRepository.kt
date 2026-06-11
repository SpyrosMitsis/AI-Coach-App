package com.workoutmaker.app.data

import com.workoutmaker.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkoutRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val cache: CacheDao,
    private val prefs: AppPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Separate raw ktor client for SSE streaming (functions SDK buffers the body).
    private val streamingHttp = HttpClient(OkHttp)

    val auth get() = supabase.auth

    // Swallowed errors are still logged so failures aren't invisible in logcat.
    private fun <T> Result<T>.logFailure(op: String): Result<T> =
        onFailure { android.util.Log.w(TAG, "$op failed", it) }

    suspend fun signIn(email: String, password: String) {
        supabase.auth.signInWith(Email) { this.email = email; this.password = password }
        invalidateProfileCache()
    }

    suspend fun signUp(email: String, password: String) =
        supabase.auth.signUpWith(Email) { this.email = email; this.password = password }

    // Sends the Supabase recovery email; the link opens the web app's
    // /reset-password page where a new password can be set.
    suspend fun resetPassword(email: String) {
        supabase.auth.resetPasswordForEmail(email)
    }

    suspend fun signOut() {
        supabase.auth.signOut()
        invalidateProfileCache()
        runCatching { prefs.setOnboardingComplete(false) }
        // Don't leak one account's cached data into the next sign-in.
        runCatching { cache.clearWorkouts(); cache.clearSummaries() }
    }

    // --- Profile row cache ----------------------------------------------------
    // loadProfile / isOnboardingComplete / loadKnowledge / autoPlanEnabled all
    // used to fire their own full-row select; serve them from one cached fetch,
    // invalidated whenever this client writes the profile.
    @Volatile
    private var profileRowCache: Map<String, JsonElement>? = null

    private suspend fun profileRow(): Map<String, JsonElement>? {
        profileRowCache?.let { return it }
        val rows: List<Map<String, JsonElement>> =
            supabase.postgrest.from("user_profiles").select { filter { eq("id", uid()) } }.decodeList()
        return rows.firstOrNull()?.also { profileRowCache = it }
    }

    private fun invalidateProfileCache() {
        profileRowCache = null
    }

    // --- Edge functions ------------------------------------------------------
    // Write-through cached: a successful fetch is persisted so an offline cold
    // start can still show the last dashboard instead of a bare error.
    suspend fun dailySummary(): DailySummary {
        val s: DailySummary = json.decodeFromString(supabase.functions.invoke("daily-summary").body())
        runCatching {
            cache.upsertSummary(
                CachedSummary(
                    date = s.date,
                    json = json.encodeToString(DailySummary.serializer(), s),
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
        }.logFailure("dailySummary/cache")
        return s
    }

    // Last successfully-fetched summary + when it was fetched (offline fallback).
    suspend fun cachedDailySummary(): Pair<DailySummary, Long>? = runCatching {
        cache.latestSummary()?.let { row ->
            json.decodeFromString(DailySummary.serializer(), row.json) to row.fetchedAt
        }
    }.logFailure("cachedDailySummary").getOrNull()

    suspend fun generateWorkout(req: GenerateRequest): String =
        supabase.functions.invoke("generate-workout") { setBody(json.encodeToString(GenerateRequest.serializer(), req)) }.body()

    suspend fun syncIntervals(): String =
        supabase.functions.invoke("sync-intervals").body()

    suspend fun intervalsStats(): IntervalsStats =
        json.decodeFromString(supabase.functions.invoke("intervals-stats").body())

    suspend fun connectIntervals(athleteId: String, apiKey: String): String =
        supabase.functions.invoke("connect-intervals") {
            setBody(
                kotlinx.serialization.json.buildJsonObject {
                    put("athleteId", JsonPrimitive(athleteId))
                    put("apiKey", JsonPrimitive(apiKey))
                }.toString(),
            )
        }.body()

    suspend fun testLlmKey(req: TestKeyRequest): TestKeyResponse =
        json.decodeFromString(
            supabase.functions.invoke("test-llm-key") {
                setBody(json.encodeToString(TestKeyRequest.serializer(), req))
            }.body(),
        )

    private fun workoutIdBody(workoutId: String): String =
        kotlinx.serialization.json.buildJsonObject { put("workout_id", JsonPrimitive(workoutId)) }.toString()

    suspend fun pushWorkout(workoutId: String): String =
        supabase.functions.invoke("push-workout") { setBody(workoutIdBody(workoutId)) }.body()

    // Delete a planned workout (and its Intervals.icu/watch event) server-side.
    suspend fun deletePlannedWorkout(workoutId: String): String =
        supabase.functions.invoke("delete-workout") { setBody(workoutIdBody(workoutId)) }.body()

    // Garmin-style execution analysis (score, pace-vs-target series, AI
    // feedback). Cached server-side; force = true recomputes.
    // peek = true returns only an already-cached analysis (never runs the LLM).
    suspend fun analyzeActivity(activityId: String, force: Boolean = false, peek: Boolean = false): ActivityAnalysis =
        json.decodeFromString(
            supabase.functions.invoke("analyze-activity") {
                setBody(
                    kotlinx.serialization.json.buildJsonObject {
                        put("activity_id", JsonPrimitive(activityId))
                        put("force", JsonPrimitive(force))
                        put("peek", JsonPrimitive(peek))
                    }.toString(),
                )
            }.body(),
        )

    // Execution analysis for a logged strength session (planned vs lifted).
    suspend fun analyzeStrength(date: String, force: Boolean = false, peek: Boolean = false): StrengthAnalysis =
        json.decodeFromString(
            supabase.functions.invoke("analyze-strength") {
                setBody(
                    kotlinx.serialization.json.buildJsonObject {
                        put("date", JsonPrimitive(date))
                        put("force", JsonPrimitive(force))
                        put("peek", JsonPrimitive(peek))
                    }.toString(),
                )
            }.body(),
        )

    // Delete a logged/manual completed activity (and its Intervals event if it was one).
    suspend fun deleteCompletedActivity(intervalsId: String) {
        supabase.postgrest.from("completed_activities").delete { filter { eq("intervals_id", intervalsId) } }
    }

    suspend fun pushStrengthWorkout(req: PushStrengthRequest): PushResult =
        json.decodeFromString(
            supabase.functions.invoke("push-strength") {
                setBody(json.encodeToString(PushStrengthRequest.serializer(), req))
            }.body(),
        )

    // --- Direct table access (RLS-scoped to the signed-in user) --------------
    // Write-through cached so the Calendar still renders the plan offline.
    suspend fun plannedWorkouts(fromDate: String): List<PlannedWorkout> {
        val rows: List<PlannedWorkout> = supabase.postgrest.from("planned_workouts").select {
            filter { gte("date", fromDate) }
            order("date", Order.DESCENDING)
        }.decodeList()
        runCatching {
            cache.clearWorkouts()
            cache.upsertWorkouts(
                rows.map {
                    CachedWorkout(
                        id = it.id,
                        date = it.date,
                        type = it.type,
                        workoutJson = json.encodeToString(PlannedWorkout.serializer(), it),
                    )
                },
            )
        }.logFailure("plannedWorkouts/cache")
        return rows
    }

    // Last successfully-fetched plan (offline fallback for the Calendar).
    suspend fun cachedPlannedWorkouts(): List<PlannedWorkout> = runCatching {
        cache.workouts().mapNotNull { row ->
            runCatching {
                json.decodeFromString(PlannedWorkout.serializer(), row.workoutJson)
            }.getOrNull()
        }
    }.logFailure("cachedPlannedWorkouts").getOrDefault(emptyList())

    suspend fun upsertWellness(checkin: WellnessCheckin) {
        supabase.postgrest.from("wellness_checkins").upsert(checkin, onConflict = "user_id,date")
    }

    // Persist a Health Connect snapshot onto today's wellness row.
    suspend fun submitHealthSnapshot(snap: com.workoutmaker.app.health.HealthSnapshot) {
        submitHealthSnapshots(listOf(snap))
    }

    // Upsert a multi-day Health Connect series (7-day trend) in one call.
    suspend fun submitHealthSnapshots(snaps: List<com.workoutmaker.app.health.HealthSnapshot>) {
        val rows = snaps.filter { it.hasAny }.map { snap ->
            WellnessHealthUpdate(
                date = snap.date,
                hrv_rmssd = snap.hrvRmssd,
                resting_hr = snap.restingHr,
                zepp_sleep_minutes = snap.sleepMinutes,
                steps = snap.steps,
                sleep_deep_min = snap.sleepDeepMin,
                sleep_rem_min = snap.sleepRemMin,
                vo2max = snap.vo2max,
            )
        }
        if (rows.isNotEmpty()) {
            supabase.postgrest.from("wellness_checkins").upsert(rows, onConflict = "user_id,date")
        }
    }

    // --- Strength logs -------------------------------------------------------
    suspend fun logStrengthSet(log: StrengthLogInsert) {
        supabase.postgrest.from("strength_logs").insert(log)
    }

    suspend fun recentStrengthLogs(limit: Long = 20): List<StrengthLogRow> =
        supabase.postgrest.from("strength_logs").select {
            order("date", Order.DESCENDING)
            limit(limit)
        }.decodeList()

    // Fire-and-forget refresh of the rolling athlete "training memory".
    suspend fun refreshMemory() {
        runCatching { supabase.functions.invoke("refresh-memory") }.logFailure("refreshMemory")
    }

    suspend fun generationLogs(limit: Long = 20): List<GenerationLogRow> =
        supabase.postgrest.from("generation_logs").select {
            order("created_at", Order.DESCENDING)
            limit(limit)
        }.decodeList()

    suspend fun setActiveProvider(provider: LlmProvider) {
        supabase.postgrest.from("user_profiles").update(mapOf("active_llm_provider" to provider.key)) {
            filter { eq("id", uid()) }
        }
        invalidateProfileCache()
    }

    // --- Dynamic model selection ---------------------------------------------
    suspend fun listModels(provider: LlmProvider): ModelListResponse =
        json.decodeFromString(
            supabase.functions.invoke("list-models") {
                setBody(
                    kotlinx.serialization.json.buildJsonObject {
                        put("provider", JsonPrimitive(provider.key))
                    }.toString(),
                )
            }.body(),
        )

    // Per-provider model override (user_profiles.llm_models). Empty map → defaults.
    suspend fun modelOverrides(): Map<String, String> = runCatching {
        (profileRow()?.get("llm_models") as? JsonObject)
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
            ?.toMap() ?: emptyMap()
    }.logFailure("modelOverrides").getOrDefault(emptyMap())

    suspend fun setModelOverride(provider: LlmProvider, model: String?) {
        val current = modelOverrides().toMutableMap()
        if (model.isNullOrBlank()) current.remove(provider.key) else current[provider.key] = model
        val obj = kotlinx.serialization.json.buildJsonObject {
            current.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        supabase.postgrest.from("user_profiles").update(mapOf("llm_models" to obj)) {
            filter { eq("id", uid()) }
        }
        invalidateProfileCache()
    }

    // --- Coach chat ----------------------------------------------------------
    suspend fun coachChat(req: CoachChatRequest): CoachReply =
        json.decodeFromString(
            supabase.functions.invoke("coach-chat") {
                setBody(json.encodeToString(CoachChatRequest.serializer(), req))
            }.body(),
        )

    // Past coach conversations for the history list. Ordered by recency here;
    // the UI sorts pinned-first client-side so this query keeps working even
    // before the `pinned` column migration is pushed.
    suspend fun coachConversations(): List<CoachConversation> =
        supabase.postgrest.from("coach_conversations").select {
            order("updated_at", Order.DESCENDING)
            limit(100)
        }.decodeList()

    suspend fun deleteCoachConversation(id: String) {
        supabase.postgrest.from("coach_conversations").delete { filter { eq("id", id) } }
    }

    suspend fun setCoachConversationPinned(id: String, pinned: Boolean) {
        supabase.postgrest.from("coach_conversations")
            .update(mapOf("pinned" to JsonPrimitive(pinned))) { filter { eq("id", id) } }
    }

    // Raw JSON string back (used when finalizing a template).
    suspend fun coachFinalizeRaw(req: CoachChatRequest): String =
        supabase.functions.invoke("coach-chat") {
            setBody(json.encodeToString(CoachChatRequest.serializer(), req))
        }.body()

    // --- Training profile + Intervals ---------------------------------------
    suspend fun loadProfile(): TrainingProfile? =
        runCatching {
            val onboarding = profileRow()?.get("onboarding") as? JsonObject ?: return@runCatching null
            json.decodeFromJsonElement(TrainingProfile.serializer(), onboarding)
        }.logFailure("loadProfile").getOrNull()

    suspend fun isOnboardingComplete(): Boolean = runCatching {
        (profileRow()?.get("onboarding_complete") as? JsonPrimitive)?.content?.toBoolean() ?: false
    }.logFailure("isOnboardingComplete").fold(
        onSuccess = { v -> runCatching { prefs.setOnboardingComplete(v) }; v },
        // Network failure ≠ new user: fall back to the last known state so an
        // offline cold start doesn't show the onboarding welcome again.
        onFailure = { runCatching { prefs.onboardingCompleteCached() }.getOrDefault(false) },
    )

    suspend fun saveProfile(profile: TrainingProfile) {
        val onboarding = json.encodeToJsonElement(TrainingProfile.serializer(), profile)
        supabase.postgrest.from("user_profiles").update(
            mapOf(
                "onboarding" to onboarding,
                "onboarding_complete" to kotlinx.serialization.json.JsonPrimitive(true),
            ),
        ) { filter { eq("id", uid()) } }
        invalidateProfileCache()
    }

    // --- Coach knowledge (durable injuries/equipment/preferences) -----------
    suspend fun loadKnowledge(): String =
        runCatching {
            (profileRow()?.get("coach_knowledge") as? JsonPrimitive)?.content ?: ""
        }.logFailure("loadKnowledge").getOrDefault("")

    suspend fun saveKnowledge(text: String) {
        supabase.postgrest.from("user_profiles").update(
            mapOf("coach_knowledge" to kotlinx.serialization.json.JsonPrimitive(text)),
        ) { filter { eq("id", uid()) } }
        invalidateProfileCache()
    }

    suspend fun connectIntervalsVerified(athleteId: String, apiKey: String): ConnectIntervalsResult =
        json.decodeFromString(connectIntervals(athleteId, apiKey))

    suspend fun templates(): List<WorkoutTemplate> =
        supabase.postgrest.from("workout_templates").select {
            order("created_at", Order.DESCENDING)
        }.decodeList()

    suspend fun planWeek(req: PlanWeekRequest): PlanWeekResult =
        json.decodeFromString(
            supabase.functions.invoke("plan-week") {
                setBody(json.encodeToString(PlanWeekRequest.serializer(), req))
            }.body(),
        )

    // P2: generate a full periodized block (multi-week) toward the goal race.
    suspend fun planBlock(req: PlanBlockRequest): PlanBlockResult =
        json.decodeFromString(
            supabase.functions.invoke("plan-block") {
                setBody(json.encodeToString(PlanBlockRequest.serializer(), req))
            }.body(),
        )

    // P7: move a planned workout to another date — server-side, so the
    // Intervals.icu/watch event moves with it.
    suspend fun reschedulePlanned(plannedId: String, newDate: String) {
        supabase.functions.invoke("move-workout") {
            setBody(
                kotlinx.serialization.json.buildJsonObject {
                    put("workout_id", JsonPrimitive(plannedId))
                    put("new_date", JsonPrimitive(newDate))
                }.toString(),
            )
        }
    }

    // Lock/unlock a planned session so the weekly re-planner leaves it fixed.
    suspend fun setLocked(plannedId: String, locked: Boolean) {
        supabase.postgrest.from("planned_workouts")
            .update(mapOf("locked" to JsonPrimitive(locked))) { filter { eq("id", plannedId) } }
    }

    // Ask the AI for a session on a specific date from a free-text request
    // (e.g. "social 10k run with friends, keep it easy"), locked by default so
    // the weekly re-planner plans around it instead of replacing it.
    suspend fun requestSession(date: String, request: String, type: String, lock: Boolean = true): GenerateResult =
        json.decodeFromString(
            supabase.functions.invoke("generate-workout") {
                setBody(json.encodeToString(GenerateRequest.serializer(),
                    GenerateRequest(date = date, type = type, request = request, lock = lock, push = true)))
            }.body(),
        )

    // E5: persist a manually-built structured workout into the plan (client-side),
    // returning its id so it can optionally be pushed to Intervals.icu.
    suspend fun savePlannedWorkout(date: String, workout: Workout): String {
        val id = java.util.UUID.randomUUID().toString()
        val row = kotlinx.serialization.json.buildJsonObject {
            put("id", JsonPrimitive(id))
            put("user_id", JsonPrimitive(uid()))
            put("date", JsonPrimitive(date))
            put("type", JsonPrimitive(workout.type))
            put("workout_json", json.encodeToJsonElement(Workout.serializer(), workout))
        }
        supabase.postgrest.from("planned_workouts").insert(row)
        return id
    }

    // --- P1: goal races -----------------------------------------------------
    suspend fun races(): List<Race> =
        supabase.postgrest.from("races").select { order("date", Order.ASCENDING) }.decodeList()

    suspend fun addRace(race: Race) {
        val row = kotlinx.serialization.json.buildJsonObject {
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

    suspend fun deleteRace(race: Race) {
        race.id?.let { id ->
            supabase.postgrest.from("races").delete { filter { eq("id", id) } }
        }
        // Deleting the active goal also clears it from the profile (and Home).
        val p = loadProfile()
        if (p != null && p.goal == race.name && p.goal_date == race.date) {
            val pace = if (race.target != null && p.target_pace == race.target) null else p.target_pace
            saveProfile(p.copy(goal = null, goal_date = null, target_pace = pace))
        }
    }

    // Make a goal the periodization anchor: drives weeks-to-goal / phase / taper.
    // Run goals with a pace-shaped target also become the profile's target pace.
    suspend fun setGoalRace(race: Race) {
        val p = loadProfile() ?: TrainingProfile()
        val pace = race.target?.takeIf { race.sport == "run" && it.isNotBlank() }
        saveProfile(p.copy(goal = race.name, goal_date = race.date, target_pace = pace ?: p.target_pace))
    }

    // --- E1 + E4: thresholds & tests ----------------------------------------
    suspend fun thresholdTests(): List<ThresholdTest> =
        supabase.postgrest.from("threshold_tests").select { order("date", Order.DESCENDING) }.decodeList()

    suspend fun addThresholdTest(t: ThresholdTest) {
        val row = kotlinx.serialization.json.buildJsonObject {
            put("date", JsonPrimitive(t.date))
            put("kind", JsonPrimitive(t.kind))
            put("value", JsonPrimitive(t.value))
            t.notes?.let { put("notes", JsonPrimitive(it)) }
        }
        supabase.postgrest.from("threshold_tests").insert(row)
        applyThreshold(t.kind, t.value)
    }

    // Update the athlete's current threshold (feeds zone calculation).
    suspend fun applyThreshold(kind: String, value: Double) {
        val p = loadProfile() ?: TrainingProfile()
        val updated = when (kind) {
            "lthr" -> p.copy(lthr = value.toInt())
            "ftp" -> p.copy(ftp = value.toInt())
            "threshold_pace" -> p.copy(threshold_pace_per_km = Zones.formatPace(value.toInt()))
            else -> p
        }
        saveProfile(updated)
    }

    // E1: directly edit thresholds from the zones screen.
    suspend fun saveThresholds(lthr: Int?, ftp: Int?, pace: String?) {
        val p = loadProfile() ?: TrainingProfile()
        saveProfile(p.copy(lthr = lthr, ftp = ftp, threshold_pace_per_km = pace))
    }

    suspend fun weekPlan(start: String): WeekPlanRow? =
        runCatching {
            supabase.postgrest.from("week_plans").select {
                filter { eq("start_date", start) }
            }.decodeList<WeekPlanRow>().firstOrNull()
        }.logFailure("weekPlan").getOrNull()

    suspend fun setAutoPlan(enabled: Boolean) {
        supabase.postgrest.from("user_profiles").update(mapOf("auto_plan" to enabled)) {
            filter { eq("id", uid()) }
        }
        invalidateProfileCache()
    }

    suspend fun autoPlanEnabled(): Boolean = runCatching {
        (profileRow()?.get("auto_plan") as? JsonPrimitive)?.content?.toBoolean() ?: false
    }.logFailure("autoPlanEnabled").getOrDefault(false)

    suspend fun scheduleTemplate(req: ScheduleRequest): ScheduleResult =
        json.decodeFromString(
            supabase.functions.invoke("schedule-template") {
                setBody(json.encodeToString(ScheduleRequest.serializer(), req))
            }.body(),
        )

    suspend fun submitFeedback(fb: WorkoutFeedback) {
        supabase.postgrest.from("workout_feedback").insert(fb)
    }

    // Mark a planned workout done/skipped + log the feedback in one go.
    suspend fun markPlannedComplete(plannedId: String, date: String, completed: Boolean, difficulty: String?, rpe: Int?) {
        runCatching {
            supabase.postgrest.from("planned_workouts")
                .update(mapOf("completed" to JsonPrimitive(completed), "skipped" to JsonPrimitive(!completed))) {
                    filter { eq("id", plannedId) }
                }
        }.getOrElse { // pre-migration-26 fallback: no `skipped` column yet
            supabase.postgrest.from("planned_workouts")
                .update(mapOf("completed" to JsonPrimitive(completed))) { filter { eq("id", plannedId) } }
        }
        supabase.postgrest.from("workout_feedback").insert(
            WorkoutFeedback(planned_workout_id = plannedId, date = date, completed = completed, actual_rpe = rpe, difficulty = difficulty),
        )
    }

    // Undo a skip: restore the session and drop the skip feedback so the
    // planner doesn't count it against adherence.
    suspend fun undoSkip(plannedId: String) {
        supabase.postgrest.from("planned_workouts")
            .update(mapOf("skipped" to JsonPrimitive(false))) { filter { eq("id", plannedId) } }
        runCatching {
            supabase.postgrest.from("workout_feedback").delete {
                filter { eq("planned_workout_id", plannedId); eq("completed", false) }
            }
        }.logFailure("undoSkip/feedback")
    }

    // Past activities pulled from Intervals.icu (or logged manually).
    suspend fun completedActivities(fromDate: String): List<CompletedActivity> =
        supabase.postgrest.from("completed_activities").select {
            filter { gte("date", fromDate) }
            order("date", Order.DESCENDING)
        }.decodeList()

    // Does a completed activity's type satisfy a planned session's type?
    private fun typeMatches(plannedType: String, actualType: String?): Boolean {
        val a = (actualType ?: "").lowercase()
        return when (plannedType.lowercase()) {
            "run" -> a.contains("run") || a.contains("walk")
            "strength" -> a.contains("weight") || a.contains("strength") || a.contains("workout") || a.contains("gym")
            "ride", "bike" -> a.contains("ride") || a.contains("bike") || a.contains("cycl")
            "rest" -> false
            else -> a.isNotEmpty()
        }
    }

    // Adaptive re-planning: reconcile this week's plan against what was actually
    // done (per Intervals.icu), then re-plan from today factoring real load.
    // plan-week already feeds actual load + adherence into the LLM prompt, so
    // re-planning here intelligently accounts for over/under-training and swaps.
    suspend fun adaptWeek(weekStart: String, today: String): AdaptResult {
        val planned = runCatching { plannedWorkouts(weekStart) }.logFailure("adaptWeek/planned").getOrDefault(emptyList())
            .filter { it.date in weekStart..today }
        val actsByDate = runCatching { completedActivities(weekStart) }.logFailure("adaptWeek/activities").getOrDefault(emptyList())
            .groupBy { it.date }
        var reconciled = 0
        var missed = 0
        for (p in planned) {
            if (p.completed || p.locked || p.type == "rest") continue
            if (p.date >= today) continue   // only reconcile the past
            val matched = actsByDate[p.date].orEmpty().any { typeMatches(p.type, it.type) }
            if (matched) {
                runCatching { markPlannedComplete(p.id, p.date, true, "from_actual", null) }.logFailure("adaptWeek/markComplete")
                reconciled++
            } else {
                missed++
            }
        }
        // Re-plan today → +6: deletes non-completed/non-locked in range and
        // regenerates around what actually happened and your current fitness.
        val r = runCatching { planWeek(PlanWeekRequest(start_date = today)) }.logFailure("adaptWeek/planWeek").getOrNull()
        if (r == null || r.error != null) {
            return AdaptResult(reconciled, missed, false,
                "Reconciled $reconciled · $missed missed", r?.error ?: "re-plan failed")
        }
        val parts = buildList {
            if (reconciled > 0) add("✓ matched $reconciled to your actual sessions")
            if (missed > 0) add("$missed planned session(s) you skipped")
            add("re-planned the rest of your week around what you did")
        }
        return AdaptResult(reconciled, missed, true, parts.joinToString(" · "))
    }

    // Log a session done off-watch so it feeds load/ACWR/adherence.
    suspend fun logManualActivity(date: String, type: String, durationMin: Int, distanceKm: Double?, rpe: Int?) {
        val tss = durationMin * (rpe ?: 5) / 6.0
        supabase.postgrest.from("completed_activities").insert(
            ManualActivityInsert(
                intervals_id = "manual:" + java.util.UUID.randomUUID(),
                type = type,
                date = date,
                duration_seconds = durationMin * 60,
                distance_m = distanceKm?.let { it * 1000 },
                tss = tss,
            ),
        )
    }

    // "Adjust this workout" — revise an existing workout via the generator.
    suspend fun adjustWorkout(base: Workout, instruction: String, date: String?): GenerateResult =
        json.decodeFromString(
            supabase.functions.invoke("generate-workout") {
                setBody(
                    json.encodeToString(
                        GenerateRequest.serializer(),
                        GenerateRequest(date = date, adjustment = instruction, base_workout = base, push = true),
                    ),
                )
            }.body(),
        )

    // Streaming agentic coach chat (SSE): emits a {tool} progress event for each
    // tool the coach runs, then the reply text, then {done}.
    data class CoachStreamResult(
        val conversationId: String?,
        val toolsUsed: List<String>,
        val error: String?,
        val gotReply: Boolean,
    )

    suspend fun coachAgenticStream(
        messages: List<ChatMessage>,
        conversationId: String?,
        onTool: (String) -> Unit,
        onToken: (String) -> Unit,
    ): CoachStreamResult {
        val token = supabase.auth.currentSessionOrNull()?.accessToken
        val url = BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/coach-chat"
        val payload = json.encodeToString(
            CoachChatRequest.serializer(),
            CoachChatRequest(messages = messages, mode = "chat", conversationId = conversationId, purpose = "setup", stream = true),
        )

        var convId: String? = null
        var tools: List<String> = emptyList()
        var error: String? = null
        var gotReply = false
        streamingHttp.preparePost(url) {
            header("Authorization", "Bearer $token")
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.execute { resp ->
            val channel: ByteReadChannel = resp.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                obj["tool"]?.let { onTool(it.jsonPrimitive.content) }
                obj["token"]?.let { gotReply = true; onToken(it.jsonPrimitive.content) }
                obj["error"]?.let { error = it.jsonPrimitive.contentOrNull }
                if (obj["done"] != null) {
                    convId = obj["conversation_id"]?.jsonPrimitive?.contentOrNull
                    tools = (obj["tools_used"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
                }
            }
        }
        return CoachStreamResult(convId, tools, error, gotReply)
    }

    private fun uid(): String = supabase.auth.currentUserOrNull()?.id ?: ""

    private companion object {
        const val TAG = "WorkoutRepo"
    }
}
