package com.workoutmaker.app.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.call.body
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton
import com.workoutmaker.app.calendar.DeviceCalendarManager
import com.workoutmaker.app.health.HealthConnectManager
import com.workoutmaker.app.strength.ExerciseCatalog
import com.workoutmaker.app.strength.StrengthDao
import com.workoutmaker.app.util.AppLog
import com.workoutmaker.app.util.CrashReporter
import com.workoutmaker.app.util.serverErrorText
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDate

@Singleton
class WorkoutRepository @Inject constructor(
    internal val supabase: SupabaseClient,
    internal val cache: CacheDao,
    internal val prefs: AppPreferences,
    internal val backend: BackendConfig,
    internal val health: HealthConnectManager,
    internal val deviceCalendar: DeviceCalendarManager,
    // The DAO, not StrengthRepository (which depends on this class).
    internal val strengthDao: StrengthDao,
    @ApplicationContext private val appContext: android.content.Context,
) {
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Separate raw ktor client for SSE streaming (functions SDK buffers the body).
    // Needs its own timeouts: a bare HttpClient(OkHttp) inherits OkHttp's 10s
    // readTimeout, which killed coach turns mid-think. The server heartbeats the
    // stream, so a quiet socket past this really is a dead one.
    internal val streamingHttp = HttpClient(OkHttp) {
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 150_000
            socketTimeoutMillis = 150_000
            connectTimeoutMillis = 15_000
        }
    }

    val auth get() = supabase.auth

    // Swallowed errors are still logged so failures aren't invisible in logcat.
    internal fun <T> Result<T>.logFailure(op: String): Result<T> =
        onFailure { AppLog.w("repo", "$op failed", it) }

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
        AppLog.i("repo", "account changed, wiping per-account local state")
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
            ExerciseCatalog.resetCustom()
        }.logFailure("clearLocalAccountData/strength")
        // A half-finished logger session from the previous account must not
        // resume under the new one.
        runCatching { File(appContext.filesDir, "active_session.json").delete() }
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
        for (f in CrashReporter.pending(appContext)) {
            val rec = CrashReporter.parse(f)
            if (rec == null) { f.delete(); continue }
            val ok = runCatching { supabase.postgrest.from("crash_reports").insert(rec) }
                .logFailure("uploadPendingCrashes").isSuccess
            if (ok) f.delete() else break
        }
    }

    // Opt-in upload of the local debug log (AppLog.file()) to the debug_logs
    // table. No-op unless the athlete has turned the setting on. The file is
    // truncated after a successful upload so the next call only sends what's
    // new since then, not the whole history again.
    suspend fun uploadDebugLogIfEnabled() {
        if (!prefs.settings.first().debugLogSharingEnabled) return
        val f = com.workoutmaker.app.util.AppLog.file() ?: return
        if (!f.exists() || f.length() == 0L) return
        val text = runCatching { f.readText() }.getOrNull() ?: return
        val rec = DebugLogRecord(
            version_name = com.workoutmaker.app.BuildConfig.VERSION_NAME,
            version_code = com.workoutmaker.app.BuildConfig.VERSION_CODE,
            flavor = com.workoutmaker.app.BuildConfig.FLAVOR,
            sdk_int = android.os.Build.VERSION.SDK_INT,
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            log_text = text.takeLast(200_000),
        )
        val ok = runCatching { supabase.postgrest.from("debug_logs").insert(rec) }
            .logFailure("uploadDebugLogIfEnabled").isSuccess
        if (ok) runCatching { f.writeText("") }
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

    internal suspend fun profileRow(): Map<String, JsonElement>? {
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

    internal fun invalidateProfileCache() {
        profileRowCache = null
    }







    // ---- Device calendar (opt-in, Settings → App) ---------------------------

    // Busy windows for [days] days from [fromDate] (default today), or null when
    // the toggle is off / permission missing / nothing busy. Times only.
    internal suspend fun calendarBusy(fromDate: String?, days: Int): List<BusyDay>? {
        val enabled = runCatching { prefs.settings.first().calendarRead }.getOrDefault(false)
        if (!enabled || !deviceCalendar.hasReadPermission()) return null
        val from = runCatching { LocalDate.parse(fromDate) }.getOrNull() ?: LocalDate.now()
        return runCatching { deviceCalendar.busyDays(from, days) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }





















































































    // Edge-function failures throw with the JSON error body in the message —
    // pull out the human-readable part ({"error": "..."} / {"detail": "..."}).
    // Keeps the truncated raw text as a fallback: this feeds a generation error
    // record, where the transport detail is worth having.
    internal fun fnErrorMessage(t: Throwable): String =
        serverErrorText(t)
            ?: t.message?.take(200)
            ?: "request failed"

    internal fun uid(): String = supabase.auth.currentUserOrNull()?.id ?: ""

    private companion object {
        const val TAG = "WorkoutRepo"
    }
}

// One row of the opt-in debug log upload (debug_logs table); see
// WorkoutRepository.uploadDebugLogIfEnabled.
@kotlinx.serialization.Serializable
data class DebugLogRecord(
    val version_name: String,
    val version_code: Int,
    val flavor: String,
    val sdk_int: Int,
    val device: String,
    val log_text: String,
)
