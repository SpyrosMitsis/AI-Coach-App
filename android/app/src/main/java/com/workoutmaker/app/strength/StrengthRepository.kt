package com.workoutmaker.app.strength

import com.workoutmaker.app.data.PushResult
import com.workoutmaker.app.data.PushStrengthRequest
import com.workoutmaker.app.data.StrengthLogInsert
import com.workoutmaker.app.data.StrengthSet
import com.workoutmaker.app.data.WorkoutRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// Domain types passed from the active-session ViewModel when a workout finishes.
data class FinishedSet(val weightKg: Double, val reps: Int, val rpe: Int?, val isWarmup: Boolean, val note: String = "")
data class FinishedExercise(val name: String, val sets: List<FinishedSet>)

// Cloud backup DTOs (timestamps are epoch millis to round-trip with Room).
@Serializable
data class CloudWorkout(val id: String, val name: String, val started_at: Long, val ended_at: Long, val duration_sec: Int, val total_volume_kg: Double, val note: String = "")
@Serializable
data class CloudSet(val id: String, val workout_id: String, val exercise_name: String, val muscle: String, val idx: Int, val weight_kg: Double, val reps: Int, val rpe: Int? = null, val is_warmup: Boolean = false)
@Serializable
data class CloudRoutine(val id: String, val name: String, val created_at: Long)
@Serializable
data class CloudRoutineItem(val id: String, val routine_id: String, val exercise_name: String, val position: Int, val target_sets: Int, val target_reps: String, val rest_sec: Int)
@Serializable
data class CloudCustomExercise(val name: String, val muscle: String, val category: String, val compound: Boolean = false)

// Result of finishing a workout — surfaces any PRs hit for a celebration.
data class FinishResult(val workoutId: String, val prs: List<PrHit>)

// Outcome of a CSV import — drives the result dialog so the user sees exactly
// what landed (and what was intentionally left out).
data class ImportSummary(
    val ok: Boolean = true,
    val format: String = "CSV",
    val workoutsAdded: Int = 0,
    val duplicatesSkipped: Int = 0,
    val setsAdded: Int = 0,
    val cardioRowsSkipped: Int = 0,
    val unparsedRows: Int = 0,
    val error: String? = null,
)

// Weekly volume / balance / deload snapshot for the strength home (B2 + B5).
data class WeeklyReport(
    val muscleVolume: List<MuscleVolume>,
    val balance: List<BalanceWarning>,
    val deload: DeloadAdvice,
    val totalHardSets: Int,
)

@Singleton
class StrengthRepository @Inject constructor(
    private val dao: StrengthDao,
    private val cloud: WorkoutRepository,
    private val supabase: SupabaseClient,
) {
    suspend fun recentWorkouts(limit: Int = 50) = dao.recentWorkouts(limit)
    suspend fun setsForWorkout(id: String) = dao.setsForWorkout(id)
    suspend fun loggedExercises() = dao.loggedExercises()

    /** Most recent prior performance of an exercise, ordered by set index. */
    suspend fun previousSets(exercise: String): List<SetEntity> {
        val rows = dao.lastSetsForExercise(exercise)
        val lastWorkout = rows.firstOrNull()?.workoutId ?: return emptyList()
        return rows.filter { it.workoutId == lastWorkout }.sortedBy { it.idx }
    }

    suspend fun stats(exercise: String): ExerciseStats =
        StrengthStats.compute(exercise, dao.setsHistoryForExercise(exercise))

    // --- mapping helpers ---------------------------------------------------
    private fun DatedSet.toLog() = LogSet(weightKg, reps, rpe, isWarmup, muscle, startedAt)
    private fun SetEntity.toLog() = LogSet(weightKg, reps, rpe, isWarmup, muscle, 0)
    private fun SetWithDate.toLog() = LogSet(weightKg, reps, null, isWarmup, "", startedAt)

    // --- B2 + B5: weekly volume / balance / deload -------------------------
    suspend fun weeklyReport(): WeeklyReport {
        val now = System.currentTimeMillis()
        val week7 = dao.datedSetsSince(now - 7L * 86_400_000).map { it.toLog() }
        val weeks8 = dao.datedSetsSince(now - 56L * 86_400_000).map { it.toLog() }
        val mv = VolumeBalance.byMuscle(week7)
        return WeeklyReport(
            muscleVolume = mv,
            balance = VolumeBalance.balance(mv),
            deload = Deload.analyze(Deload.weekly(weeks8)),
            totalHardSets = week7.count { !it.isWarmup && it.reps > 0 },
        )
    }

    // --- B1: next-session suggestion for an exercise -----------------------
    suspend fun progressionFor(exercise: String): ProgressionSuggestion? {
        val last = previousSets(exercise).map { it.toLog() }
        if (last.isEmpty()) return null
        val compound = ExerciseCatalog.find(exercise)?.compound ?: false
        val (lo, hi) = if (compound) 5 to 8 else 8 to 12
        return Progression.suggest(last, ProgressionRule.DOUBLE, lo, hi, compound)
    }

    // --- E3: repeat last workout ------------------------------------------
    suspend fun lastWorkoutWithSets(): Pair<WorkoutEntity, List<SetEntity>>? {
        val w = dao.recentWorkouts(1).firstOrNull() ?: return null
        return w to dao.setsForWorkout(w.id)
    }

    // Delete a logged workout locally; queue the cloud delete for the sync pass.
    suspend fun deleteWorkout(id: String) {
        dao.deleteSetsForWorkout(id)
        dao.deleteWorkout(id)
        dao.insertTombstone(TombstoneEntity("workout", id))
    }

    // Swallowed errors are still logged so failures aren't invisible in logcat.
    private fun <T> Result<T>.logFailure(op: String): Result<T> =
        onFailure { android.util.Log.w("StrengthRepo", "$op failed", it) }

    // --- D1: custom exercises ---------------------------------------------
    suspend fun loadAndRegisterCustom() {
        // Merge cloud → local (best-effort) so customs created elsewhere — the
        // web logger or the AI generator registering an off-catalog exercise —
        // show up here too. Names with a pending local delete stay deleted.
        runCatching {
            val cloud = supabase.postgrest.from("strength_custom_exercises").select().decodeList<CloudCustomExercise>()
            if (cloud.isNotEmpty()) {
                val pendingDeletes = dao.tombstones()
                    .filter { it.tbl == "custom" }.map { it.rowId }.toHashSet()
                val known = dao.customExercises().map { it.name }.toHashSet()
                val fresh = cloud.filter { it.name !in known && it.name !in pendingDeletes }
                if (fresh.isNotEmpty()) {
                    dao.insertCustomExercises(fresh.map { CustomExerciseEntity(it.name, it.muscle, it.category, it.compound) })
                }
            }
        }.logFailure("loadAndRegisterCustom/cloud")
        val local = dao.customExercises()
        ExerciseCatalog.registerCustom(local.map { Exercise(it.name, it.muscle, it.category, it.compound) })
    }

    suspend fun addCustomExercise(name: String, muscle: String, category: String, compound: Boolean) {
        val e = CustomExerciseEntity(name.trim(), muscle, category, compound, synced = false)
        dao.insertCustomExercise(e)
        loadAndRegisterCustom()
    }

    suspend fun deleteCustomExercise(name: String) {
        dao.deleteCustomExercise(name)
        dao.insertTombstone(TombstoneEntity("custom", name))
        loadAndRegisterCustom()
    }

    // --- D5: favorites + recents ------------------------------------------
    suspend fun favorites(): List<String> = dao.favorites()
    suspend fun recentExercises(limit: Int = 12): List<String> = dao.recentExercises(limit)
    suspend fun toggleFavorite(name: String, makeFavorite: Boolean) {
        if (makeFavorite) dao.addFavorite(FavoriteEntity(name)) else dao.removeFavorite(name)
    }

    // --- B4: instantiate a program as routines ----------------------------
    suspend fun createProgram(program: StrengthProgram): Int {
        for (day in program.days) {
            saveRoutine("${program.name} — ${day.name}", day.exercises)
        }
        return program.days.size
    }

    // --- F2: CSV import ----------------------------------------------------
    suspend fun importCsv(text: String): ImportSummary {
        val lineCount = text.count { it == '\n' } + 1
        val firstLine = text.lineSequence().firstOrNull()?.take(80) ?: ""
        android.util.Log.i("IMPORT", "read ${text.length} chars, ~$lineCount lines, header: $firstLine")
        val result = StrengthCsvImport.parse(text)
        android.util.Log.i("IMPORT", "parsed: format=${result.format} workouts=${result.workoutCount} sets=${result.setCount}")
        if (result.format == "unrecognized") {
            return ImportSummary(ok = false, format = "unrecognized",
                error = "Couldn't recognise this file (read ${text.length} chars, $lineCount lines). " +
                    "Header was: “$firstLine”. Export a CSV from Strong or Hevy and try again.")
        }
        if (result.workouts.isEmpty()) {
            return ImportSummary(ok = false, format = result.format,
                error = "Found the $lineCount-line file but no logged sets in it " +
                    "(${result.cardioRows} cardio, ${result.skippedRows} unreadable rows).",
                cardioRowsSkipped = result.cardioRows, unparsedRows = result.skippedRows)
        }
        // Skip workouts we already imported (same start time) so re-importing the
        // same export doesn't duplicate the entire history.
        val existing = dao.allStartedAts().toHashSet()
        var added = 0; var duplicates = 0; var setsAdded = 0
        for (w in result.workouts) {
            if (w.startedAt in existing) { duplicates++; continue }
            val workoutId = UUID.randomUUID().toString()
            val setEntities = mutableListOf<SetEntity>()
            var totalVolume = 0.0
            for (ex in w.exercises) {
                val muscle = ExerciseCatalog.muscleOf(ex.name)
                ex.sets.forEachIndexed { i, s ->
                    if (!s.isWarmup) totalVolume += s.weightKg * s.reps
                    setEntities.add(SetEntity(UUID.randomUUID().toString(), workoutId, ex.name, muscle, i + 1, s.weightKg, s.reps, s.rpe, s.isWarmup, s.note))
                }
            }
            val we = WorkoutEntity(workoutId, w.name, w.startedAt, w.startedAt, 0, totalVolume, "Imported", synced = false)
            dao.insertWorkout(we)
            if (setEntities.isNotEmpty()) dao.insertSets(setEntities)
            existing.add(w.startedAt)
            added++; setsAdded += setEntities.size
        }
        android.util.Log.i("IMPORT", "inserted $added workouts ($setsAdded sets), skipped $duplicates dup; db now ${dao.allStartedAts().size}")
        return ImportSummary(
            ok = true, format = result.format,
            workoutsAdded = added, duplicatesSkipped = duplicates, setsAdded = setsAdded,
            cardioRowsSkipped = result.cardioRows, unparsedRows = result.skippedRows,
        )
    }

    // Full detail for one logged workout (header + every set), for the detail page.
    suspend fun workoutWithSets(id: String): Pair<WorkoutEntity, List<SetEntity>>? {
        val w = dao.workout(id) ?: return null
        return w to dao.setsForWorkout(id)
    }

    // Q8: turn a logged workout into a reusable routine (exercise → set count).
    suspend fun saveWorkoutAsRoutine(id: String): String? {
        val (w, sets) = workoutWithSets(id) ?: return null
        // Preserve exercise order and count working+warmup sets per exercise.
        val perExercise = LinkedHashMap<String, Int>()
        sets.forEach { perExercise[it.exerciseName] = (perExercise[it.exerciseName] ?: 0) + 1 }
        if (perExercise.isEmpty()) return null
        saveRoutine(w.name, perExercise.toList())
        return w.name
    }

    // Q11: export the entire strength history as a Strong-compatible CSV so it can
    // be re-imported here or anywhere else. Semicolon-delimited, matching Strong.
    suspend fun exportCsv(): String {
        val sb = StringBuilder()
        sb.append("Date;Workout Name;Exercise Name;Set Order;Weight;Weight Unit;Reps;RPE;Notes\n")
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val zone = java.time.ZoneId.systemDefault()
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        for (w in dao.recentWorkouts(10_000)) {
            val date = java.time.Instant.ofEpochMilli(w.startedAt).atZone(zone).toLocalDateTime().format(fmt)
            val sets = dao.setsForWorkout(w.id)
            for (s in sets) {
                val weight = if (s.weightKg == s.weightKg.toLong().toDouble()) s.weightKg.toLong().toString()
                    else s.weightKg.toString()
                sb.append("$date;${q(w.name)};${q(s.exerciseName)};${s.idx};$weight;kg;${s.reps};${s.rpe ?: ""};${q(s.note)}\n")
            }
        }
        return sb.toString()
    }

    suspend fun routines(): List<RoutineWithItems> = dao.routines()

    suspend fun saveRoutine(name: String, exercises: List<Pair<String, Int>>) {
        val rid = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val items = exercises.mapIndexed { i, (ex, sets) ->
            RoutineItemEntity(
                id = UUID.randomUUID().toString(),
                routineId = rid,
                exerciseName = ex,
                position = i,
                targetSets = sets,
                targetReps = "8-12",
                restSec = ExerciseCatalog.restOf(ex),
            )
        }
        dao.insertRoutine(RoutineEntity(rid, name.ifBlank { "Routine" }, createdAt, synced = false))
        dao.insertRoutineItems(items)
    }

    // Edit an existing routine in place: keep its id (and createdAt), replace its
    // items. Marked unsynced so the next sync pushes the change.
    suspend fun updateRoutine(routine: RoutineEntity, items: List<RoutineItemEntity>) {
        dao.deleteRoutineItems(routine.id)
        dao.insertRoutine(routine.copy(synced = false))
        dao.insertRoutineItems(items.mapIndexed { i, it -> it.copy(routineId = routine.id, position = i) })
    }

    suspend fun deleteRoutine(id: String) {
        dao.deleteRoutineItems(id)
        dao.deleteRoutine(id)
        dao.insertTombstone(TombstoneEntity("routine", id))
    }

    suspend fun pushToWatch(req: PushStrengthRequest): PushResult = cloud.pushStrengthWorkout(req)

    // B3: ask the AI generator for a strength session (not pushed; the user logs it live).
    private val genJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
    suspend fun generateAiStrength(durationMin: Int = 60): com.workoutmaker.app.data.Workout? {
        val raw = cloud.generateWorkout(
            com.workoutmaker.app.data.GenerateRequest(type = "strength", duration = durationMin, push = false),
        )
        return runCatching {
            genJson.decodeFromString(com.workoutmaker.app.data.GenerateResult.serializer(), raw).workout
        }.logFailure("generateAiStrength/parse").getOrNull()
    }

    /** Restore strength history + routines from the cloud if the local DB is empty
     *  (e.g. after a reinstall). Returns the number of workouts restored. */
    suspend fun restoreIfEmpty(): Int {
        if (dao.recentWorkouts(1).isNotEmpty() || dao.routines().isNotEmpty()) return 0
        return runCatching {
            val cw = supabase.postgrest.from("strength_workouts").select {
                order("started_at", Order.DESCENDING); limit(300)
            }.decodeList<CloudWorkout>()
            if (cw.isNotEmpty()) {
                val ids = cw.map { it.id }
                val cs = supabase.postgrest.from("strength_workout_sets").select {
                    filter { isIn("workout_id", ids) }
                }.decodeList<CloudSet>()
                cw.forEach { dao.insertWorkout(WorkoutEntity(it.id, it.name, it.started_at, it.ended_at, it.duration_sec, it.total_volume_kg, it.note)) }
                if (cs.isNotEmpty()) dao.insertSets(cs.map { SetEntity(it.id, it.workout_id, it.exercise_name, it.muscle, it.idx, it.weight_kg, it.reps, it.rpe, it.is_warmup) })
            }
            val cr = supabase.postgrest.from("strength_routines").select {
                order("created_at", Order.DESCENDING); limit(100)
            }.decodeList<CloudRoutine>()
            if (cr.isNotEmpty()) {
                val rids = cr.map { it.id }
                val cri = supabase.postgrest.from("strength_routine_items").select {
                    filter { isIn("routine_id", rids) }
                }.decodeList<CloudRoutineItem>()
                cr.forEach { dao.insertRoutine(RoutineEntity(it.id, it.name, it.created_at)) }
                if (cri.isNotEmpty()) dao.insertRoutineItems(cri.map { RoutineItemEntity(it.id, it.routine_id, it.exercise_name, it.position, it.target_sets, it.target_reps, it.rest_sec) })
            }
            cw.size
        }.getOrDefault(0)
    }

    /** Flip a planned calendar workout's completed flag — used when a planned
     *  strength session is logged (or un-done) from the strength logger. */
    suspend fun markPlannedWorkoutDone(plannedId: String, done: Boolean) {
        supabase.postgrest.from("planned_workouts")
            .update(mapOf("completed" to kotlinx.serialization.json.JsonPrimitive(done))) {
                filter { eq("id", plannedId) }
            }
    }

    /** Pull-merge: insert any cloud workouts we don't already have locally (e.g.
     *  sessions logged on the web app). Unlike restoreIfEmpty this runs even when
     *  local history is non-empty, giving true two-way sync. Returns # merged in. */
    suspend fun mergeFromCloud(): Int = runCatching {
        val localIds = dao.allWorkoutIds().toHashSet()
        val cloud = supabase.postgrest.from("strength_workouts").select {
            order("started_at", Order.DESCENDING); limit(300)
        }.decodeList<CloudWorkout>()
        val missing = cloud.filter { it.id !in localIds }
        if (missing.isEmpty()) return@runCatching 0
        val ids = missing.map { it.id }
        val cs = supabase.postgrest.from("strength_workout_sets").select {
            filter { isIn("workout_id", ids) }
        }.decodeList<CloudSet>()
        // synced defaults true ⇒ a merged workout isn't queued for re-push.
        missing.forEach { dao.insertWorkout(WorkoutEntity(it.id, it.name, it.started_at, it.ended_at, it.duration_sec, it.total_volume_kg, it.note)) }
        if (cs.isNotEmpty()) dao.insertSets(cs.map { SetEntity(it.id, it.workout_id, it.exercise_name, it.muscle, it.idx, it.weight_kg, it.reps, it.rpe, it.is_warmup) })
        missing.size
    }.logFailure("mergeFromCloud").getOrDefault(0)

    /**
     * Persist a finished workout locally, then best-effort push each exercise to
     * Supabase strength_logs so the AI generator sees the real volume / e1RM.
     * Returns the new workout id.
     */
    suspend fun finishWorkout(
        name: String,
        startedAt: Long,
        endedAt: Long,
        exercises: List<FinishedExercise>,
        note: String = "",
        // Q2: when re-saving an edited workout, reuse its id (and delete the old
        // sets first) so the entry is updated in place rather than duplicated.
        existingId: String? = null,
    ): FinishResult {
        // In edit mode, drop the old version first so PR detection and history
        // don't compare the session against its own prior copy.
        if (existingId != null) {
            dao.deleteSetsForWorkout(existingId)
            dao.deleteWorkout(existingId)
        }

        // C2: detect PRs against prior history BEFORE this session is persisted.
        // (In edit mode the old copy was just deleted, so it isn't compared against.)
        val priorBestWorkoutVolume = dao.bestWorkoutVolume() ?: 0.0
        val prHits = mutableListOf<PrHit>()
        for (ex in exercises) {
            val session = ex.sets.map { LogSet(it.weightKg, it.reps, it.rpe, it.isWarmup) }
            val working = session.filter { !it.isWarmup && it.reps > 0 }
            if (working.isEmpty()) continue
            val prior = Prs.record(dao.setsHistoryForExercise(ex.name).map { it.toLog() })
            Prs.detect(prior, session).forEach { prHits.add(PrHit(it.type, "${ex.name}: ${it.detail}")) }
            // Per-exercise session-volume PR: most total volume for this lift in one session.
            val exSessionVol = working.sumOf { it.weightKg * it.reps }
            val priorExVol = dao.bestExerciseSessionVolume(ex.name) ?: 0.0
            if (priorExVol > 0.0 && exSessionVol > priorExVol + 1e-6) {
                prHits.add(PrHit("volume", "${ex.name}: ${exSessionVol.toInt()} kg volume"))
            }
        }

        val workoutId = existingId ?: UUID.randomUUID().toString()
        val setEntities = mutableListOf<SetEntity>()
        var totalVolume = 0.0
        for (ex in exercises) {
            val muscle = ExerciseCatalog.muscleOf(ex.name)
            ex.sets.forEachIndexed { i, s ->
                if (!s.isWarmup) totalVolume += s.weightKg * s.reps
                setEntities.add(
                    SetEntity(
                        id = UUID.randomUUID().toString(),
                        workoutId = workoutId,
                        exerciseName = ex.name,
                        muscle = muscle,
                        idx = i + 1,
                        weightKg = s.weightKg,
                        reps = s.reps,
                        rpe = s.rpe,
                        isWarmup = s.isWarmup,
                        note = s.note,
                    ),
                )
            }
        }
        // Session-volume PR: the whole workout out-totals your previous best ever.
        // Shown first as the headline. Skipped for a first-ever workout.
        if (priorBestWorkoutVolume > 0.0 && totalVolume > priorBestWorkoutVolume + 1e-6) {
            prHits.add(0, PrHit("session_volume", "Biggest workout yet — ${totalVolume.toInt()} kg total"))
        }

        val durationSec = ((endedAt - startedAt) / 1000).toInt().coerceAtLeast(0)
        val workoutEntity = WorkoutEntity(
            id = workoutId,
            name = name.ifBlank { "Workout" },
            startedAt = startedAt,
            endedAt = endedAt,
            durationSec = durationSec,
            totalVolumeKg = totalVolume,
            note = note,
            synced = false, // pushed to the cloud by the next sync pass (works offline)
        )
        dao.insertWorkout(workoutEntity)
        if (setEntities.isNotEmpty()) dao.insertSets(setEntities)
        return FinishResult(workoutId, prHits)
    }

    // ========================================================================
    // Offline sync: drain everything created/deleted while offline. Called by
    // StrengthSyncWorker (network-constrained) so it runs the moment a
    // connection is available — even if the app was closed. Throws on network
    // failure so WorkManager retries with backoff.
    // ========================================================================
    suspend fun pendingSyncCount(): Int = dao.pendingSyncCount()

    suspend fun syncPending(): Int {
        val analyzeDates = mutableSetOf<String>()
        // 1. Push workouts logged/imported offline (+ their sets + strength_logs).
        for (w in dao.unsyncedWorkouts()) {
            val sets = dao.setsForWorkout(w.id)
            supabase.postgrest.from("strength_workouts").upsert(
                CloudWorkout(w.id, w.name, w.startedAt, w.endedAt, w.durationSec, w.totalVolumeKg, w.note),
            )
            if (sets.isNotEmpty()) {
                supabase.postgrest.from("strength_workout_sets").upsert(
                    sets.map { CloudSet(it.id, it.workoutId, it.exerciseName, it.muscle, it.idx, it.weightKg, it.reps, it.rpe, it.isWarmup) },
                )
            }
            // strength_logs feed the AI generator (one row per exercise, working sets).
            val date = java.time.Instant.ofEpochMilli(w.startedAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            sets.filter { !it.isWarmup }.groupBy { it.exerciseName }.forEach { (name, exSets) ->
                runCatching {
                    cloud.logStrengthSet(
                        StrengthLogInsert(
                            date = date,
                            exercise_name = name,
                            muscle_groups = listOf(ExerciseCatalog.muscleOf(name)),
                            sets = exSets.map { StrengthSet(reps = it.reps, weight_kg = it.weightKg, rpe = it.rpe) },
                            estimated_1rm = exSets.maxOf { epley1rm(it.weightKg, it.reps) },
                        ),
                    )
                }
            }
            dao.markWorkoutSynced(w.id)
            analyzeDates += date
        }

        // Kick off the execution analysis for the just-synced day(s) so the
        // score + AI feedback are ready the moment the session is opened.
        // force = true because the logs for those dates just changed.
        // Best-effort — an analysis failure never fails the sync.
        for (d in analyzeDates) {
            runCatching { cloud.analyzeStrength(d, force = true) }.logFailure("syncPending/analyze")
        }

        // 2. Push routines.
        for (r in dao.unsyncedRoutines()) {
            supabase.postgrest.from("strength_routines").upsert(CloudRoutine(r.routine.id, r.routine.name, r.routine.createdAt))
            if (r.items.isNotEmpty()) {
                supabase.postgrest.from("strength_routine_items").upsert(
                    r.items.map { CloudRoutineItem(it.id, it.routineId, it.exerciseName, it.position, it.targetSets, it.targetReps, it.restSec) },
                )
            }
            dao.markRoutineSynced(r.routine.id)
        }

        // 3. Push custom exercises.
        for (c in dao.unsyncedCustom()) {
            supabase.postgrest.from("strength_custom_exercises").upsert(CloudCustomExercise(c.name, c.muscle, c.category, c.compound))
            dao.markCustomSynced(c.name)
        }

        // 4. Propagate deletions made offline.
        for (t in dao.tombstones()) {
            when (t.tbl) {
                "workout" -> {
                    supabase.postgrest.from("strength_workout_sets").delete { filter { eq("workout_id", t.rowId) } }
                    supabase.postgrest.from("strength_workouts").delete { filter { eq("id", t.rowId) } }
                }
                "routine" -> {
                    supabase.postgrest.from("strength_routine_items").delete { filter { eq("routine_id", t.rowId) } }
                    supabase.postgrest.from("strength_routines").delete { filter { eq("id", t.rowId) } }
                }
                "custom" -> supabase.postgrest.from("strength_custom_exercises").delete { filter { eq("name", t.rowId) } }
            }
            dao.deleteTombstone(t.tbl, t.rowId)
        }
        return dao.pendingSyncCount()
    }
}
