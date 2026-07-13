package com.workoutmaker.app.data

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
    private val backend: BackendConfig,
    private val health: com.workoutmaker.app.health.HealthConnectManager,
    // The DAO, not StrengthRepository (which depends on this class).
    private val strengthDao: com.workoutmaker.app.strength.StrengthDao,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Separate raw ktor client for SSE streaming (functions SDK buffers the body).
    private val streamingHttp = HttpClient(OkHttp)

    val auth get() = supabase.auth

    // Swallowed errors are still logged so failures aren't invisible in logcat.
    private fun <T> Result<T>.logFailure(op: String): Result<T> =
        onFailure { com.workoutmaker.app.util.AppLog.w("repo", "$op failed", it) }

    suspend fun signIn(email: String, password: String) {
        supabase.auth.signInWith(Email) { this.email = email; this.password = password }
        invalidateProfileCache()
    }

    suspend fun signUp(email: String, password: String) =
        // The confirmation email deep-links back into the app (and signs the
        // user straight in, so onboarding starts immediately).
        supabase.auth.signUpWith(Email, redirectUrl = "workoutmaker://auth/confirmed") {
            this.email = email
            this.password = password
        }

    // Sends the Supabase recovery email; the link deep-links back into the
    // app, which then shows the set-new-password dialog.
    suspend fun resetPassword(email: String) {
        supabase.auth.resetPasswordForEmail(email, redirectUrl = "workoutmaker://auth/reset")
    }

    // After a recovery deep link imported a session, this sets the new password.
    suspend fun updatePassword(newPassword: String) {
        supabase.auth.updateUser { password = newPassword }
    }

    suspend fun signOut() {
        supabase.auth.signOut()
        // Don't leak one account's local data into the next sign-in.
        clearLocalAccountData()
    }

    // --- Account scoping -------------------------------------------------
    // Local state (Room strength tables, offline caches, onboarding flag, the
    // in-memory exercise registry) belongs to exactly one account. Called on
    // every entry into the authenticated app; wipes when the signed-in user
    // differs from the data's owner, so a new account on the same device never
    // sees the previous account's rows and the sync workers never push them
    // into the wrong cloud.
    suspend fun ensureAccountScope() {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return
        val owner = runCatching { prefs.lastAccountUid() }.getOrNull()
        if (owner == uid) return
        if (owner == null) {
            // Pre-guard install: adopt the current user instead of wiping, so
            // updating the app doesn't discard unsynced local data.
            runCatching { prefs.setLastAccountUid(uid) }
            return
        }
        com.workoutmaker.app.util.AppLog.i("repo", "account changed, wiping per-account local state")
        clearLocalAccountData()
        runCatching { prefs.setLastAccountUid(uid) }
    }

    private suspend fun clearLocalAccountData() {
        invalidateProfileCache()
        runCatching { prefs.setOnboardingComplete(false) }
        runCatching { cache.clearWorkouts(); cache.clearSummaries(); cache.clearBriefs(); cache.clearWeekReviews() }
        runCatching {
            strengthDao.clearSets(); strengthDao.clearWorkouts()
            strengthDao.clearRoutineItems(); strengthDao.clearRoutines()
            strengthDao.clearCustomExercises(); strengthDao.clearFavorites()
            strengthDao.clearTombstones()
            com.workoutmaker.app.strength.ExerciseCatalog.resetCustom()
        }.logFailure("clearLocalAccountData/strength")
        // A half-finished logger session from the previous account must not
        // resume under the new one.
        runCatching { java.io.File(appContext.filesDir, "active_session.json").delete() }
    }

    // A deep link (email confirm / recovery) imported a session without going
    // through signIn(), so the profile-row cache may belong to someone else.
    fun onSessionImported() {
        invalidateProfileCache()
    }

    // Upload crash files captured by CrashReporter. Best effort: a file stays
    // on disk until its insert succeeds (offline, or migration not pushed yet)
    // or it goes stale; a failed insert stops the batch until next start.
    suspend fun uploadPendingCrashes() {
        for (f in com.workoutmaker.app.util.CrashReporter.pending(appContext)) {
            val rec = com.workoutmaker.app.util.CrashReporter.parse(f)
            if (rec == null) { f.delete(); continue }
            val ok = runCatching { supabase.postgrest.from("crash_reports").insert(rec) }
                .logFailure("uploadPendingCrashes").isSuccess
            if (ok) f.delete() else break
        }
    }

    // Permanent server-side account deletion (Play requirement). The edge
    // function cascades through every owned row; afterwards the local session
    // is dead anyway, so clear it like a sign-out.
    suspend fun deleteAccount() {
        supabase.functions.invoke("delete-account")
        runCatching { signOut() }
    }

    // --- Profile row cache ----------------------------------------------------
    // loadProfile / isOnboardingComplete / loadKnowledge / autoPlanEnabled all
    // used to fire their own full-row select; serve them from one cached fetch,
    // invalidated whenever this client writes the profile.
    @Volatile
    private var profileRowCache: Map<String, JsonElement>? = null
    @Volatile
    private var profileRowFetchedAt: Long = 0L

    // TTL because the row also changes server-side (coach memory/soul evolution,
    // web-app profile edits) — without it those stay invisible until app restart.
    private val profileTtlMs = 5 * 60_000L

    private suspend fun profileRow(): Map<String, JsonElement>? {
        profileRowCache
            ?.takeIf { System.currentTimeMillis() - profileRowFetchedAt < profileTtlMs }
            ?.let { return it }
        val rows: List<Map<String, JsonElement>> =
            supabase.postgrest.from("user_profiles").select { filter { eq("id", uid()) } }.decodeList()
        return rows.firstOrNull()?.also {
            profileRowCache = it
            profileRowFetchedAt = System.currentTimeMillis()
        }
    }

    private fun invalidateProfileCache() {
        profileRowCache = null
    }

    // --- Edge functions ------------------------------------------------------
    // Write-through cached: a successful fetch is persisted so an offline cold
    // start can still show the last dashboard instead of a bare error.
    // Sends the device's LOCAL date — the server's UTC clock is yesterday for
    // tz-ahead users until mid-morning, which made Home show the wrong day.
    suspend fun dailySummary(date: java.time.LocalDate = java.time.LocalDate.now()): DailySummary {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("date", JsonPrimitive(date.toString()))
        }.toString()
        val s: DailySummary = json.decodeFromString(
            supabase.functions.invoke("daily-summary") { setBody(body) }.body(),
        )
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

    // The coach's proactive daily note. Generated server-side at most once per
    // calendar day; cached in Room keyed by date so re-opening Home is free and
    // offline. Returns null when disabled, not yet generated offline, or empty.
    suspend fun coachBrief(date: String = java.time.LocalDate.now().toString()): String? {
        runCatching { cache.brief(date) }.getOrNull()?.let { return it.text }
        val body = kotlinx.serialization.json.buildJsonObject {
            put("date", JsonPrimitive(date))
        }.toString()
        val resp: CoachBriefResponse = runCatching {
            json.decodeFromString<CoachBriefResponse>(
                supabase.functions.invoke("coach-brief") { setBody(body) }.body(),
            )
        }.logFailure("coachBrief").getOrNull() ?: return null
        val text = resp.brief?.takeIf { it.isNotBlank() }
        if (text != null) runCatching { cache.upsertBrief(CachedBrief(date, text)) }.logFailure("coachBrief/cache")
        return text
    }

    // The coach's weekly recap, generated once per week (one LLM call) and cached
    // in Room keyed by the week-start so re-opening Home is free. `weekStart` must
    // be the Monday of the target week. Null when not generated / empty / offline.
    suspend fun weekReview(weekStart: String): String? {
        runCatching { cache.weekReview(weekStart) }.getOrNull()?.let { return it.text }
        val body = kotlinx.serialization.json.buildJsonObject {
            put("week_start", JsonPrimitive(weekStart))
        }.toString()
        val resp: CoachWeekReviewResponse = runCatching {
            json.decodeFromString<CoachWeekReviewResponse>(
                supabase.functions.invoke("coach-week-review") { setBody(body) }.body(),
            )
        }.logFailure("weekReview").getOrNull() ?: return null
        val text = resp.review?.takeIf { it.isNotBlank() }
        if (text != null) runCatching { cache.upsertWeekReview(CachedWeekReview(weekStart, text)) }.logFailure("weekReview/cache")
        return text
    }

    // Last successfully-fetched summary + when it was fetched (offline fallback).
    suspend fun cachedDailySummary(): Pair<DailySummary, Long>? = runCatching {
        cache.latestSummary()?.let { row ->
            json.decodeFromString(DailySummary.serializer(), row.json) to row.fetchedAt
        }
    }.logFailure("cachedDailySummary").getOrNull()

    suspend fun generateWorkout(req: GenerateRequest): String =
        com.workoutmaker.app.util.AppLog.time("gen", "generate-workout date=${req.date} type=${req.type}") {
            supabase.functions.invoke("generate-workout") { setBody(json.encodeToString(GenerateRequest.serializer(), req)) }.body()
        }

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
        // Row-tolerant decode: one schema-drifted workout_json must drop that row,
        // not fail the whole Calendar/Coach plan fetch (the cached path below
        // already does the same per-row).
        val rows: List<PlannedWorkout> = supabase.postgrest.from("planned_workouts").select {
            filter { gte("date", fromDate) }
            order("date", Order.DESCENDING)
        }.decodeList<kotlinx.serialization.json.JsonObject>().mapNotNull { el ->
            runCatching { json.decodeFromJsonElement(PlannedWorkout.serializer(), el) }
                .logFailure("plannedWorkouts/row").getOrNull()
        }
        runCatching {
            cache.clearWorkoutsFrom(fromDate)
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

    // Manually-entered HRV / resting HR / sleep for a day the watch didn't sync —
    // writes only the provided columns onto that day's wellness row (source=manual),
    // exactly like the Health Connect path so energy/soreness aren't clobbered.
    suspend fun upsertManualRecovery(date: String, hrvMs: Double?, restingHr: Int?, sleepMinutes: Int?) {
        val row = WellnessHealthUpdate(
            date = date,
            hrv_rmssd = hrvMs,
            resting_hr = restingHr,
            zepp_sleep_minutes = sleepMinutes,
            source = "manual",
        )
        supabase.postgrest.from("wellness_checkins").upsert(row, onConflict = "user_id,date")
    }

    // HRV / resting-HR / sleep history for the recovery-trends screen, oldest→newest.
    suspend fun recoveryHistory(fromDate: String): List<RecoveryHistoryPoint> =
        supabase.postgrest.from("wellness_checkins").select {
            filter { gte("date", fromDate) }
            order("date", Order.ASCENDING)
        }.decodeList()

    // Today's subjective check-in (energy/soreness/sleep quality), if answered.
    // The row may exist with only Health Connect metrics — energy == null means
    // the morning questions haven't been answered yet.
    suspend fun wellnessCheckin(date: String): WellnessCheckin? =
        supabase.postgrest.from("wellness_checkins").select {
            filter { eq("date", date) }
        }.decodeList<WellnessCheckin>().firstOrNull()

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

    data class HealthSyncResult(
        val week: List<com.workoutmaker.app.health.HealthSnapshot> = emptyList(),
        val activitiesUpserted: Int = 0,
    )

    // One Health Connect sync for every trigger (Home pull-to-refresh, Settings,
    // Calendar): pushes the 7-day wellness trend, and — only when intervals.icu
    // is NOT connected — ingests exercise sessions as fallback activities with
    // an estimated training load. Intervals users get richer versions of the
    // same sessions from sync-intervals, so the gate avoids cross-source dupes.
    suspend fun syncHealth(): HealthSyncResult {
        if (!health.isAvailable) return HealthSyncResult()
        val week = health.readWeek(7)
        if (week.isNotEmpty()) submitHealthSnapshots(week)
        val intervalsConnected = runCatching { intervalsConnection() != null }.getOrDefault(true)
        if (intervalsConnected) return HealthSyncResult(week)
        return HealthSyncResult(week, ingestHcExercises())
    }

    // Health Connect exercise sessions → completed_activities rows ("hc:<uid>").
    // Upsert on (user_id, intervals_id) keeps re-syncs idempotent.
    private suspend fun ingestHcExercises(): Int {
        val sessions = health.readExerciseSessions(30)
        if (sessions.isEmpty()) return 0
        // Skip watch-recorded gym sessions the athlete also logged in the app.
        val earliest = sessions.minOf { it.startMs }
        val logged = runCatching { strengthDao.workoutsSince(earliest) }.getOrDefault(emptyList())
        val slackMs = 30 * 60 * 1000L
        fun overlapsLoggedStrength(s: com.workoutmaker.app.health.HcExercise): Boolean =
            logged.any { w -> s.startMs < w.endedAt + slackMs && w.startedAt < s.endMs + slackMs }
        val lthr = runCatching { loadProfile()?.lthr }.getOrNull()
        val rows = sessions
            .filterNot { it.type == "Weight training" && overlapsLoggedStrength(it) }
            .map { s ->
                val hours = s.durationSec / 3600.0
                // HR-based estimate when we can (TRIMP-style: an hour at LTHR
                // ≈ 100 TSS); otherwise the same duration heuristic manual
                // logging uses (≈ 50 TSS/hour, a moderate effort).
                val tss = if (s.avgHr != null && lthr != null && lthr > 0) {
                    val r = s.avgHr.toDouble() / lthr
                    hours * r * r * 100
                } else {
                    (s.durationSec / 60) * 5 / 6.0
                }
                HcActivityInsert(
                    intervals_id = "hc:${s.uid}",
                    type = s.type,
                    date = s.date,
                    duration_seconds = s.durationSec,
                    distance_m = s.distanceM,
                    avg_hr = s.avgHr,
                    tss = Math.round(tss * 10) / 10.0,
                )
            }
        if (rows.isNotEmpty()) {
            supabase.postgrest.from("completed_activities")
                .upsert(rows, onConflict = "user_id,intervals_id")
        }
        com.workoutmaker.app.util.AppLog.d(
            "health",
            "hc exercise ingest: ${sessions.size} sessions, ${rows.size} upserted",
        )
        return rows.size
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
    // Invalidates the profile cache so a following loadMemory() reads the new notes.
    suspend fun refreshMemory() {
        runCatching { supabase.functions.invoke("refresh-memory") }.logFailure("refreshMemory")
        invalidateProfileCache()
    }

    // Wide window so the diagnostics screen can total real 30-day spend; the UI
    // aggregates client-side. 500 recent rows is ample for one user.
    suspend fun generationLogs(limit: Long = 500): List<GenerationLogRow> =
        supabase.postgrest.from("generation_logs").select {
            order("created_at", Order.DESCENDING)
            limit(limit)
        }.decodeList()

    // --- Pro plan / hosted AI ------------------------------------------------

    // Whether THIS deployment offers hosted AI, from the last cached summary —
    // offline-friendly and never blocks Settings on a network call.
    suspend fun serverHostedAi(): Boolean = runCatching {
        cache.latestSummary()?.let {
            json.decodeFromString(DailySummary.serializer(), it.json).server?.hosted_ai
        } ?: false
    }.logFailure("serverHostedAi").getOrDefault(false)

    suspend fun planStatus(): PlanStatus = runCatching {
        val row = profileRow()
        PlanStatus(
            plan = (row?.get("plan") as? JsonPrimitive)?.contentOrNull ?: "free",
            expiresAt = (row?.get("plan_expires_at") as? JsonPrimitive)?.contentOrNull,
            useHostedAi = (row?.get("use_hosted_ai") as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: true,
        )
    }.logFailure("planStatus").getOrDefault(PlanStatus())

    suspend fun setUseHostedAi(on: Boolean) {
        supabase.postgrest.from("user_profiles").update({ set("use_hosted_ai", on) }) {
            filter { eq("id", uid()) }
        }
        invalidateProfileCache()
    }

    // Server-side verification of a Play purchase; the fn is the only writer
    // of the plan columns. Returns the resulting plan ("pro" on success).
    suspend fun verifyPurchase(purchaseToken: String): String {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("purchase_token", JsonPrimitive(purchaseToken))
        }.toString()
        val res: Map<String, JsonElement> = json.decodeFromString(
            supabase.functions.invoke("verify-purchase") { setBody(body) }.body(),
        )
        invalidateProfileCache()
        (res["error"] as? JsonPrimitive)?.contentOrNull?.let { throw IllegalStateException(it) }
        return (res["plan"] as? JsonPrimitive)?.contentOrNull ?: "free"
    }

    // Per-1M-token prices for the custom (BYO) provider, so its spend isn't $0.
    suspend fun customLlmPricing(): Pair<Double?, Double?> = runCatching {
        val row = profileRow()
        val inp = (row?.get("llm_custom_input_per_1m") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        val out = (row?.get("llm_custom_output_per_1m") as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        inp to out
    }.logFailure("customLlmPricing").getOrDefault(null to null)

    suspend fun setCustomLlmPricing(inputPer1M: Double?, outputPer1M: Double?) {
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("llm_custom_input_per_1m", inputPer1M?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
            put("llm_custom_output_per_1m", outputPer1M?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        }
        supabase.postgrest.from("user_profiles").update(obj) { filter { eq("id", uid()) } }
        invalidateProfileCache()
    }

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
        com.workoutmaker.app.util.AppLog.time("coach", "coach-chat mode=${req.mode} turns=${req.messages.size}") {
            json.decodeFromString<CoachReply>(
                supabase.functions.invoke("coach-chat") {
                    setBody(json.encodeToString(CoachChatRequest.serializer(), req))
                }.body(),
            ).also {
                // The coach may have evolved memory/soul server-side this turn.
                invalidateProfileCache()
            }
        }

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

    // --- Coach memory (rolling notes the coach carries between sessions) ------
    suspend fun loadMemory(): String =
        runCatching {
            (profileRow()?.get("training_memory") as? JsonPrimitive)?.content ?: ""
        }.logFailure("loadMemory").getOrDefault("")

    suspend fun saveMemory(text: String) {
        supabase.postgrest.from("user_profiles").update(
            mapOf("training_memory" to kotlinx.serialization.json.JsonPrimitive(text)),
        ) { filter { eq("id", uid()) } }
        invalidateProfileCache()
    }

    // --- Coach soul (the coach's identity + its evolving relationship with you) -
    // Falls back to "" when the coach_soul column is absent (pre-migration 30) or
    // unseeded; the backend serves a default persona until the first auto-evolve.
    suspend fun loadSoul(): String =
        runCatching {
            (profileRow()?.get("coach_soul") as? JsonPrimitive)?.content ?: ""
        }.logFailure("loadSoul").getOrDefault("")

    suspend fun saveSoul(text: String) {
        supabase.postgrest.from("user_profiles").update(
            mapOf("coach_soul" to kotlinx.serialization.json.JsonPrimitive(text)),
        ) { filter { eq("id", uid()) } }
        invalidateProfileCache()
    }

    suspend fun connectIntervalsVerified(athleteId: String, apiKey: String): ConnectIntervalsResult {
        val r: ConnectIntervalsResult = json.decodeFromString(connectIntervals(athleteId, apiKey))
        invalidateProfileCache() // athlete id + key hint changed on the profile
        return r
    }

    // Saved Intervals.icu connection: athlete id + masked key hint (null when
    // not connected). The hint column arrives with migration 27.
    suspend fun intervalsConnection(): Pair<String, String?>? {
        val row = profileRow() ?: return null
        val athlete = (row["intervals_athlete_id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val hint = (row["intervals_api_key_hint"] as? JsonPrimitive)?.contentOrNull
        return athlete to hint
    }

    // Saved LLM keys (provider, validity, masked hint) for the settings UI.
    // key_hint may not exist before migration 27 — fall back to a hint-less select.
    suspend fun llmKeyRows(): List<LlmKeyRow> = runCatching {
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
        }.getOrElse { e ->
            // Pre-migration-26 fallback: no `skipped` column yet. Only for that
            // specific error — a blanket fallback would mask network failures and
            // still write feedback below for an update that never happened.
            if (e.message?.contains("skipped", ignoreCase = true) != true) throw e
            supabase.postgrest.from("planned_workouts")
                .update(mapOf("completed" to JsonPrimitive(completed))) { filter { eq("id", plannedId) } }
        }
        // Last-write-wins per planned session: a double-tap or a re-rate must not
        // stack duplicate rows (they skew the generator's autoregulation).
        runCatching {
            supabase.postgrest.from("workout_feedback").delete { filter { eq("planned_workout_id", plannedId) } }
        }.logFailure("markPlannedComplete/dedupe")
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
            "swim" -> a.contains("swim")
            // rest + unknown planned types: never auto-complete from an activity.
            else -> false
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
        val attempt = runCatching { planWeek(PlanWeekRequest(start_date = today)) }.logFailure("adaptWeek/planWeek")
        val r = attempt.getOrNull()
        if (r == null || r.error != null) {
            // Surface the function's real error (it travels in the exception body)
            // instead of a blind "re-plan failed".
            val why = r?.error
                ?: attempt.exceptionOrNull()?.let { fnErrorMessage(it) }
                ?: "re-plan failed"
            return AdaptResult(reconciled, missed, false,
                "Reconciled $reconciled · $missed missed", why)
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
        val url = backend.url.trimEnd('/') + "/functions/v1/coach-chat"
        val payload = json.encodeToString(
            CoachChatRequest.serializer(),
            CoachChatRequest(messages = messages, mode = "chat", conversationId = conversationId, purpose = "setup", stream = true),
        )

        var convId: String? = null
        var tools: List<String> = emptyList()
        var error: String? = null
        var gotReply = false
        val streamStarted = System.currentTimeMillis()
        com.workoutmaker.app.util.AppLog.i("coach", "coach-stream start turns=${messages.size}")
        streamingHttp.preparePost(url) {
            header("Authorization", "Bearer $token")
            header("apikey", backend.anonKey)
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
        com.workoutmaker.app.util.AppLog.i(
            "coach",
            "coach-stream done ${System.currentTimeMillis() - streamStarted}ms reply=$gotReply tools=$tools" +
                (error?.let { " error=$it" } ?: ""),
        )
        // The coach may have evolved memory/soul server-side this turn.
        invalidateProfileCache()
        return CoachStreamResult(convId, tools, error, gotReply)
    }

    // Edge-function failures throw with the JSON error body in the message —
    // pull out the human-readable part ({"error": "..."} / {"detail": "..."}).
    private fun fnErrorMessage(t: Throwable): String {
        val m = t.message ?: return "request failed"
        return Regex("\"(?:error|detail|message)\"\\s*:\\s*\"([^\"]+)\"")
            .find(m)?.groupValues?.get(1) ?: m.take(200)
    }

    private fun uid(): String = supabase.auth.currentUserOrNull()?.id ?: ""

    private companion object {
        const val TAG = "WorkoutRepo"
    }
}
