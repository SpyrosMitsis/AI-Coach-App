package com.workoutmaker.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** A day's wellness snapshot read from Health Connect. All fields are optional. */
data class HealthSnapshot(
    val date: String,
    val hrvRmssd: Double? = null,
    val restingHr: Int? = null,
    val sleepMinutes: Int? = null,
    val sleepDeepMin: Int? = null,
    val sleepRemMin: Int? = null,
    val steps: Int? = null,
    val vo2max: Double? = null,
) {
    val hasAny: Boolean get() =
        hrvRmssd != null || restingHr != null || sleepMinutes != null || steps != null || vo2max != null
}

@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
    )

    /** SDK_AVAILABLE / SDK_UNAVAILABLE / SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED. */
    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    val isAvailable: Boolean get() = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    /** Play Store / system URL to install or update the Health Connect provider. */
    val providerPackage: String get() = "com.google.android.apps.healthdata"

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasAllPermissions(): Boolean =
        runCatching { client.permissionController.getGrantedPermissions().containsAll(permissions) }
            .getOrDefault(false)

    /** The read permissions actually granted (empty on any failure). */
    suspend fun grantedPermissions(): Set<String> =
        runCatching { client.permissionController.getGrantedPermissions().intersect(permissions) }
            .getOrDefault(emptySet())

    /**
     * Read the most recent ~36h of metrics and reduce to today's snapshot:
     * latest HRV rmssd & resting HR, last night's sleep duration, today's steps.
     */
    suspend fun readSnapshot(): HealthSnapshot {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val startOfToday = today.atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val window = TimeRangeFilter.between(now.minus(Duration.ofHours(36)), now)
        val todayWindow = TimeRangeFilter.between(startOfToday, now)

        // Last night's sleep session — used to scope the overnight HRV average.
        val sleepSession = runCatching {
            client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, window))
                .records.maxByOrNull { it.endTime }
        }.getOrNull()
        val sleep = sleepSession?.let { sessionToSleep(it) }

        // HRV: Zepp logs one RMSSD sample per minute across the night. Average the
        // samples within the sleep window (the standard overnight HRV figure)
        // instead of grabbing a single noisy reading.
        val hrv = runCatching {
            val recs = client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, window)).records
            avgRmssdOverNight(recs, sleepSession?.startTime, sleepSession?.endTime)
        }.getOrNull()

        val rhr = runCatching {
            val recs = client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, window)).records
            avgRestingHr(recs)
        }.getOrNull()

        val steps = runCatching {
            client.readRecords(ReadRecordsRequest(StepsRecord::class, todayWindow))
                .records.sumOf { it.count }.toInt()
        }.getOrNull()

        // VO2 max is updated occasionally (e.g. after a run) — look back further.
        val vo2 = runCatching {
            val wide = TimeRangeFilter.between(now.minus(Duration.ofDays(30)), now)
            client.readRecords(ReadRecordsRequest(Vo2MaxRecord::class, wide))
                .records.maxByOrNull { it.time }?.vo2MillilitersPerMinuteKilogram
        }.getOrNull()

        return HealthSnapshot(
            date = today.toString(),
            hrvRmssd = hrv,
            restingHr = rhr,
            sleepMinutes = sleep?.total,
            sleepDeepMin = sleep?.deep,
            sleepRemMin = sleep?.rem,
            steps = steps,
            vo2max = vo2,
        )
    }

    /**
     * Read the last [days] days as one snapshot per day, so the backend can
     * compute HRV baseline/deviation and sleep-debt trends rather than relying
     * on a single value.
     */
    suspend fun readWeek(days: Int = 7): List<HealthSnapshot> {
        val zone = ZoneId.systemDefault()
        val out = mutableListOf<HealthSnapshot>()
        for (offset in 0 until days) {
            val day = LocalDate.now().minusDays(offset.toLong())
            val start = day.atStartOfDay(zone).toInstant()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
            val range = TimeRangeFilter.between(start, end)

            // Sleep that ENDED on this calendar day (i.e. the night before).
            val sleepSession = runCatching {
                client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range))
                    .records.maxByOrNull { it.endTime }
            }.getOrNull()
            val sleep = sleepSession?.let { sessionToSleep(it) }

            // Average the night's per-minute RMSSD samples (see readSnapshot).
            val hrv = runCatching {
                val recs = client.readRecords(ReadRecordsRequest(HeartRateVariabilityRmssdRecord::class, range)).records
                avgRmssdOverNight(recs, sleepSession?.startTime, sleepSession?.endTime)
            }.getOrNull()
            val rhr = runCatching {
                val recs = client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range)).records
                avgRestingHr(recs)
            }.getOrNull()
            val steps = runCatching {
                client.readRecords(ReadRecordsRequest(StepsRecord::class, range))
                    .records.sumOf { it.count }.toInt()
            }.getOrNull()

            val vo2 = runCatching {
                client.readRecords(ReadRecordsRequest(Vo2MaxRecord::class, range))
                    .records.maxByOrNull { it.time }?.vo2MillilitersPerMinuteKilogram
            }.getOrNull()
            val snap = HealthSnapshot(
                date = day.toString(),
                hrvRmssd = hrv, restingHr = rhr,
                sleepMinutes = sleep?.total, sleepDeepMin = sleep?.deep, sleepRemMin = sleep?.rem,
                steps = steps, vo2max = vo2,
            )
            if (snap.hasAny) out.add(snap)
        }
        return out
    }

    private data class Sleep(val total: Int, val deep: Int?, val rem: Int?)

    private fun sessionToSleep(s: SleepSessionRecord): Sleep {
        val total = Duration.between(s.startTime, s.endTime).toMinutes().toInt()
        fun stageMinutes(stage: Int): Int? {
            val mins = s.stages.filter { it.stage == stage }
                .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
            return if (mins > 0) mins.toInt() else null
        }
        return Sleep(
            total = total,
            deep = stageMinutes(SleepSessionRecord.STAGE_TYPE_DEEP),
            rem = stageMinutes(SleepSessionRecord.STAGE_TYPE_REM),
        )
    }

    /**
     * Overnight HRV: the mean of the per-minute RMSSD samples taken during sleep.
     * Restricts to [sleepStart, sleepEnd] when available (so daytime/awake readings
     * don't skew it); otherwise averages everything passed in. Returns null if
     * there are no samples.
     */
    private fun avgRmssdOverNight(
        records: List<HeartRateVariabilityRmssdRecord>,
        sleepStart: Instant?,
        sleepEnd: Instant?,
    ): Double? {
        val night = if (sleepStart != null && sleepEnd != null) {
            records.filter { !it.time.isBefore(sleepStart) && !it.time.isAfter(sleepEnd) }
        } else {
            emptyList()
        }
        val pool = night.ifEmpty { records }
        val vals = pool.map { it.heartRateVariabilityMillis }
        if (vals.isEmpty()) return null
        // Round to 1 decimal — RMSSD is in ms; spurious precision helps no one.
        return Math.round(vals.average() * 10) / 10.0
    }

    /** Resting HR: mean of the resting-HR records in the window (one/day for most
     *  providers; averaging is a no-op then but smooths if several exist). */
    private fun avgRestingHr(records: List<RestingHeartRateRecord>): Int? {
        val vals = records.map { it.beatsPerMinute.toInt() }
        return if (vals.isEmpty()) null else Math.round(vals.average()).toInt()
    }
}
