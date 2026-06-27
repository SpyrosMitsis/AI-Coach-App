package com.workoutmaker.app.strength

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.workoutmaker.app.data.AppPreferences
import com.workoutmaker.app.data.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

// --- Active-session UI state (Compose-observable holders) -------------------
// File-scoped so it's available no matter when the VM's init runs (the restore
// init block executes before class-level vals would be initialized).
private val sessionJson = Json { ignoreUnknownKeys = true }

class UiSet(
    weight: String = "",
    reps: String = "",
    rpe: String = "",
    done: Boolean = false,
    warmup: Boolean = false,
    note: String = "",
    // AI / progression / repeat-last hints shown as greyed placeholders. They are
    // NOT logged values until the user accepts them (types, or taps the checkmark).
    suggestedWeight: String = "",
    suggestedReps: String = "",
) {
    var weight by mutableStateOf(weight)
    var reps by mutableStateOf(reps)
    var rpe by mutableStateOf(rpe)
    var done by mutableStateOf(done)
    var warmup by mutableStateOf(warmup)
    var note by mutableStateOf(note)
    var suggestedWeight by mutableStateOf(suggestedWeight)
    var suggestedReps by mutableStateOf(suggestedReps)
}

class UiExercise(val name: String) {
    val muscle: String = ExerciseCatalog.muscleOf(name)
    var restSec by mutableStateOf(ExerciseCatalog.restOf(name))
    val sets = mutableStateListOf<UiSet>()
    var previous by mutableStateOf<List<SetEntity>>(emptyList())
    var suggestion by mutableStateOf<ProgressionSuggestion?>(null) // B1 next-session target

    // Cardio entries log MINUTES (in the reps slot) and no weight.
    val isCardio: Boolean get() = ExerciseCatalog.isCardio(name)
}

// Format a kg value tersely (no trailing ".0").
private fun kg(v: Double): String =
    if (kotlin.math.abs(v - v.toLong()) < 0.001) v.toLong().toString() else ((v * 100).toLong() / 100.0).toString()

// Single source of truth for the 🎯 headline and the greyed input placeholders:
// when a live progression suggestion exists it drives BOTH, so they can't
// disagree. Only fills sets the athlete hasn't typed into yet; a history-based
// suggestion overrides any plan-seeded placeholder.
private fun applySuggestion(ux: UiExercise, sug: ProgressionSuggestion?) {
    ux.suggestion = sug
    if (sug == null) return
    ux.sets.forEach { s ->
        if (s.weight.isBlank()) s.suggestedWeight = kg(sug.weightKg)
        if (s.reps.isBlank()) s.suggestedReps = sug.reps.toString()
    }
}

// --- Crash/kill-proof snapshot of the in-progress session -------------------
@Serializable
private data class SavedSet(
    val weight: String = "", val reps: String = "", val rpe: String = "",
    val done: Boolean = false, val warmup: Boolean = false, val note: String = "",
    val suggestedWeight: String = "", val suggestedReps: String = "",
)

@Serializable
private data class SavedExercise(val name: String, val restSec: Int, val sets: List<SavedSet>)

@Serializable
private data class SavedSession(
    val workoutName: String,
    val workoutNote: String = "",
    val startedAt: Long,
    val linkedPlannedId: String? = null,
    val linkedPlannedDate: String? = null,
    val editingWorkoutId: String? = null,
    val editingEndedAt: Long = 0L,
    val restEndAt: Long? = null, // absolute epoch ms the current rest ends
    val restTotal: Int = 0,
    val exercises: List<SavedExercise> = emptyList(),
)

sealed interface StrengthNav {
    data object Home : StrengthNav
    data object Active : StrengthNav
    data object Picker : StrengthNav
    data class Stats(val exercise: String) : StrengthNav
    data class WorkoutDetail(val workoutId: String) : StrengthNav
    data object RateEffort : StrengthNav
}

// A logged workout opened in the detail page: header + sets grouped per exercise.
data class WorkoutDetailUi(
    val workout: WorkoutEntity,
    val exercises: List<Pair<String, List<SetEntity>>>,
    val totalSets: Int,
)

@HiltViewModel
class StrengthViewModel @Inject constructor(
    private val repo: StrengthRepository,
    private val workoutRepo: com.workoutmaker.app.data.WorkoutRepository,
    private val prefs: AppPreferences,
    private val handoff: StrengthHandoff,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // When set, this session was opened from a planned calendar workout; on
    // finish we mark that plan complete (#: "auto-done when I press finish").
    private var linkedPlannedId: String? = null
    private var linkedPlannedDate: String? = null

    // A planned session waiting on the user's call to discard an in-progress
    // workout (the "keep or replace?" guard). Null when nothing's pending.
    val pendingPlannedStart = MutableStateFlow<StrengthHandoff.Start?>(null)

    // Observe the Calendar → Strength handoff: opening the tab with a pending
    // request seeds the logger from the plan — but guard an in-progress session.
    init {
        viewModelScope.launch {
            handoff.pending.collect { s ->
                if (s != null) {
                    handoff.clear()
                    if (exercises.isNotEmpty()) pendingPlannedStart.value = s // ask before discarding
                    else startPlanned(s)
                }
            }
        }
    }

    // History/Calendar → "edit this logged workout" handoff, with the same
    // in-progress-session guard as planned starts.
    val pendingEditStart = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            handoff.pendingEdit.collect { id ->
                if (id != null) {
                    handoff.clearEdit()
                    if (exercises.isNotEmpty()) pendingEditStart.value = id // ask before discarding
                    else editWorkout(id)
                }
            }
        }
    }

    /** User chose to discard the in-progress session and load the planned one. */
    fun confirmReplaceWithPlanned() {
        pendingPlannedStart.value?.let { startPlanned(it) }
        pendingPlannedStart.value = null
    }

    /** User chose to keep their current session; drop the planned request. */
    fun keepCurrentSession() { pendingPlannedStart.value = null }

    /** User chose to discard the in-progress session and edit the logged one. */
    fun confirmReplaceWithEdit() {
        pendingEditStart.value?.let { editWorkout(it) }
        pendingEditStart.value = null
    }

    /** User chose to keep their current session; drop the edit request. */
    fun keepCurrentSessionOverEdit() { pendingEditStart.value = null }

    // Today's planned strength sessions from the calendar, shown on the
    // Strength home so the plan is one tap from the logger.
    val todayPlanned = MutableStateFlow<List<com.workoutmaker.app.data.PlannedWorkout>>(emptyList())

    /** Start logging a planned calendar session from the Strength home card. */
    fun startPlannedFromHome(w: com.workoutmaker.app.data.PlannedWorkout) {
        val s = StrengthHandoff.Start(w.workout_json, w.id, w.date)
        if (exercises.isNotEmpty()) pendingPlannedStart.value = s else startPlanned(s)
    }

    // Device-local preferences (units, rest defaults, vibration…) for the UI,
    // plus a synchronous cache for timer/vibration decisions.
    val settings = prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private var cfg = AppSettings()
    init { viewModelScope.launch { prefs.settings.collect { cfg = it } } }

    val nav = MutableStateFlow<StrengthNav>(StrengthNav.Home)

    var workoutName by mutableStateOf("Workout")
    var workoutNote by mutableStateOf("")
    val exercises = mutableStateListOf<UiExercise>()
    var startedAt = 0L; private set

    // Q2: non-null while editing an already-logged workout (re-saved in place).
    private var editingWorkoutId: String? = null
    private var editingEndedAt = 0L
    val isEditing get() = editingWorkoutId != null

    val elapsedSec = MutableStateFlow(0L)
    private var tickJob: Job? = null

    val restRemaining = MutableStateFlow<Int?>(null)
    val restTotal = MutableStateFlow(0)
    private var restJob: Job? = null
    // Absolute epoch-ms the rest ends — countdown is derived from the clock so it
    // stays accurate across backgrounding and survives process death.
    private var restEndAt = 0L

    val history = MutableStateFlow<List<WorkoutEntity>>(emptyList())
    val workoutDetail = MutableStateFlow<WorkoutDetailUi?>(null)
    val routines = MutableStateFlow<List<RoutineWithItems>>(emptyList())
    val loggedExercises = MutableStateFlow<List<String>>(emptyList())
    val currentStats = MutableStateFlow<ExerciseStats?>(null)
    val status = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(false)

    // Restore any in-progress session left behind by a lock/kill — done here,
    // AFTER every property it touches (status, exercises, rest fields…) is
    // initialised, and synchronously so the workout is ready on first frame.
    init { restoreSavedSession() }

    // New surfaces: weekly volume/deload (B2/B5), PR celebration (C2),
    // picker favorites/recents/custom (D1/D5), programs (B4).
    val weeklyReport = MutableStateFlow<WeeklyReport?>(null)
    val lastPrs = MutableStateFlow<List<PrHit>>(emptyList())
    val favorites = MutableStateFlow<List<String>>(emptyList())
    val recentExercises = MutableStateFlow<List<String>>(emptyList())
    val customExercises = MutableStateFlow<List<Exercise>>(emptyList())
    val programs: List<StrengthProgram> = StrengthPrograms.all

    // Count of local strength changes not yet pushed to the cloud (offline queue).
    val pendingSync = MutableStateFlow(0)

    // Ask WorkManager to drain the offline queue (runs now if online, else when a
    // connection returns — even if the app is closed).
    private fun requestSync() {
        com.workoutmaker.app.work.StrengthSync.request(context)
        viewModelScope.launch { runCatching { pendingSync.value = repo.pendingSyncCount() } }
    }

    fun loadHome() = viewModelScope.launch {
        loading.value = true
        runCatching {
            repo.loadAndRegisterCustom()
            // One-time: collapse reworded AI/custom exercises onto their bundled
            // catalog twin so stats/muscle-grouping use the canonical entry.
            if (!prefs.customsCleanupV1Done()) {
                val tidied = runCatching { repo.cleanupMislabeledCustoms() }.getOrDefault(0)
                prefs.setCustomsCleanupV1Done()
                if (tidied > 0) status.value = "Tidied $tidied exercise name(s) to match the catalog"
            }
            val restored = repo.restoreIfEmpty()
            if (restored > 0) status.value = "Restored $restored workouts from the cloud"
            // Two-way sync: pull in sessions logged elsewhere (e.g. the web app).
            val merged = repo.mergeFromCloud()
            if (merged > 0) status.value = "Synced $merged new workout(s) from the cloud"
            history.value = repo.recentWorkouts()
            routines.value = repo.routines()
            loggedExercises.value = repo.loggedExercises()
            weeklyReport.value = repo.weeklyReport()
            pendingSync.value = repo.pendingSyncCount()
        }
        // Surface today's planned strength sessions (offline cache as fallback).
        runCatching {
            val today = java.time.LocalDate.now().toString()
            val rows = runCatching { workoutRepo.plannedWorkouts(today) }
                .getOrElse { workoutRepo.cachedPlannedWorkouts() }
            todayPlanned.value = rows
                .filter { it.date == today && it.type == "strength" }
                .sortedBy { it.completed }
        }
        loading.value = false
        requestSync()
    }

    fun loadPicker() = viewModelScope.launch {
        runCatching {
            repo.loadAndRegisterCustom()
            favorites.value = repo.favorites()
            recentExercises.value = repo.recentExercises()
            customExercises.value = ExerciseCatalog.custom()
        }
    }

    fun dismissPrs() { lastPrs.value = emptyList() }

    fun toggleFavorite(name: String) = viewModelScope.launch {
        val isFav = favorites.value.contains(name)
        repo.toggleFavorite(name, !isFav)
        favorites.value = repo.favorites()
    }

    fun addCustomExercise(name: String, muscle: String, category: String, compound: Boolean) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        repo.addCustomExercise(name, muscle, category, compound)
        customExercises.value = ExerciseCatalog.custom()
        status.value = "✓ Added “${name.trim()}”"
        requestSync()
    }

    fun deleteCustomExercise(name: String) = viewModelScope.launch {
        repo.deleteCustomExercise(name)
        customExercises.value = ExerciseCatalog.custom()
        requestSync()
    }

    fun createProgram(p: StrengthProgram) = viewModelScope.launch {
        runCatching { repo.createProgram(p) }
            .onSuccess { routines.value = repo.routines(); status.value = "✓ Added ${p.days.size} routines from “${p.name}”"; requestSync() }
            .onFailure { status.value = "Couldn't create program: ${it.message}" }
    }

    fun importCsv(text: String) = viewModelScope.launch {
        loading.value = true
        runCatching { repo.importCsv(text) }
            .onSuccess { s ->
                status.value = if (s.ok)
                    "✓ Imported ${s.workoutsAdded} ${s.format} workouts (${s.setsAdded} sets)" +
                        (if (s.duplicatesSkipped > 0) " · ${s.duplicatesSkipped} already there" else "")
                else "Import failed: ${s.error}"
                loadHome()
            }
            .onFailure { status.value = "Import failed: ${it.message}" }
        loading.value = false
    }

    // Open a logged workout's full detail (every exercise + set).
    fun openWorkout(id: String) = viewModelScope.launch {
        val pair = repo.workoutWithSets(id) ?: run { status.value = "Workout not found"; return@launch }
        val (w, sets) = pair
        val grouped = sets.groupBy { it.exerciseName }
            .map { (name, s) -> name to s.sortedBy { it.idx } }
        workoutDetail.value = WorkoutDetailUi(w, grouped, sets.size)
        nav.value = StrengthNav.WorkoutDetail(id)
    }

    // --- session lifecycle -------------------------------------------------
    private fun reset() {
        exercises.clear()
        workoutName = "Workout"
        workoutNote = ""
        editingWorkoutId = null
        editingEndedAt = 0L
        linkedPlannedId = null
        linkedPlannedDate = null
        startedAt = System.currentTimeMillis()
        elapsedSec.value = 0
        stopRest()
        clearSavedSession()
    }

    // Q4: reorder exercises in the active session.
    fun moveExercise(ux: UiExercise, up: Boolean) {
        val i = exercises.indexOf(ux)
        if (i < 0) return
        val j = if (up) i - 1 else i + 1
        if (j in exercises.indices) { exercises[i] = exercises[j]; exercises[j] = ux }
        persistSession()
    }

    // Q2: open an already-logged workout in the editor; finishing re-saves it in place.
    fun editWorkout(id: String) = viewModelScope.launch {
        val pair = repo.workoutWithSets(id) ?: run { status.value = "Workout not found"; return@launch }
        val (w, sets) = pair
        reset()
        editingWorkoutId = w.id
        editingEndedAt = w.endedAt
        startedAt = w.startedAt
        workoutName = w.name
        workoutNote = w.note
        sets.groupBy { it.exerciseName }.forEach { (name, group) ->
            val ux = UiExercise(name)
            group.sortedBy { it.idx }.forEach { s ->
                ux.sets.add(UiSet(weight = kg(s.weightKg), reps = s.reps.toString(),
                    rpe = s.rpe?.toString() ?: "", done = true, warmup = s.isWarmup, note = s.note))
            }
            exercises.add(ux)
            launch { ux.previous = repo.previousSets(name) }
        }
        workoutDetail.value = null
        nav.value = StrengthNav.Active
        startTick()
        // Persist right away so the edit session survives process death / the
        // ViewModel being recreated by tab navigation.
        persistSession()
    }

    // Q8: save a logged workout as a reusable routine.
    fun saveWorkoutAsRoutine(id: String) = viewModelScope.launch {
        runCatching { repo.saveWorkoutAsRoutine(id) }
            .onSuccess { name ->
                routines.value = repo.routines()
                status.value = if (name != null) "✓ Saved “$name” as a routine" else "Nothing to save"
                requestSync()
            }
            .onFailure { status.value = "Couldn't save routine: ${it.message}" }
    }

    fun startEmpty() { reset(); nav.value = StrengthNav.Active; startTick() }

    fun startFromRoutine(r: RoutineWithItems) {
        reset()
        workoutName = r.routine.name
        r.items.sortedBy { it.position }.forEach { addExercise(it.exerciseName, it.targetSets) }
        nav.value = StrengthNav.Active
        startTick()
        persistSession()
    }

    fun cancelWorkout() { stopTick(); reset(); nav.value = StrengthNav.Home }
    fun openPicker() { replaceTarget.value = null; nav.value = StrengthNav.Picker; loadPicker() }
    fun backToActive() { replaceTarget.value = null; nav.value = StrengthNav.Active }
    fun goHome() { nav.value = StrengthNav.Home; loadHome() }

    // --- replace an exercise mid-session (hamburger → "Replace exercise") ---
    // The picker opens in single-select mode; the chosen exercise takes over the
    // slot, keeping everything already typed/ticked.
    val replaceTarget = MutableStateFlow<UiExercise?>(null)

    fun openPickerForReplace(ux: UiExercise) {
        replaceTarget.value = ux
        nav.value = StrengthNav.Picker
        loadPicker()
    }

    fun replaceExercise(newName: String) {
        val target = replaceTarget.value
        replaceTarget.value = null
        nav.value = StrengthNav.Active
        val i = target?.let { exercises.indexOf(it) } ?: -1
        if (target == null || i < 0) return
        val ux = UiExercise(newName)
        // Keep the athlete's entered sets; drop the old exercise's suggestions.
        target.sets.forEach { s -> ux.sets.add(UiSet(s.weight, s.reps, s.rpe, s.done, s.warmup, s.note)) }
        if (ux.sets.isEmpty()) ux.sets.add(UiSet())
        exercises[i] = ux
        viewModelScope.launch {
            ux.previous = repo.previousSets(newName)
            applySuggestion(ux, repo.progressionFor(newName))
        }
        persistSession()
    }

    fun addExercise(name: String, targetSets: Int = 1) {
        val ux = UiExercise(name)
        // Use the user's default rest for exercises the catalog has no value for.
        if (ExerciseCatalog.find(name) == null) ux.restSec = cfg.defaultRestSec
        repeat(targetSets.coerceAtLeast(1)) { ux.sets.add(UiSet()) }
        exercises.add(ux)
        viewModelScope.launch {
            ux.previous = repo.previousSets(name)
            // B1: prefill empty sets with the auto-progression target.
            applySuggestion(ux, repo.progressionFor(name))
        }
    }

    // E3: clone the most recent workout (exercises + last weights) into a new session.
    fun startFromLastWorkout() = viewModelScope.launch {
        val last = repo.lastWorkoutWithSets()
        if (last == null) { status.value = "No previous workout to repeat."; return@launch }
        reset()
        workoutName = last.first.name
        last.second.groupBy { it.exerciseName }.forEach { (name, sets) ->
            val ux = UiExercise(name)
            sets.sortedBy { it.idx }.forEach {
                ux.sets.add(UiSet(suggestedWeight = kg(it.weightKg), suggestedReps = it.reps.toString(), warmup = it.isWarmup))
            }
            exercises.add(ux)
            launch { ux.previous = repo.previousSets(name); applySuggestion(ux, repo.progressionFor(name)) }
        }
        nav.value = StrengthNav.Active
        startTick()
        persistSession()
    }

    // B3: generate today's strength session with the AI, then open it pre-filled.
    fun generateAiLift(durationMin: Int = 60) = viewModelScope.launch {
        loading.value = true
        status.value = "Generating today's lift with AI…"
        val w = runCatching { repo.generateAiStrength(durationMin) }.getOrNull()
        if (w == null || w.type != "strength" || w.sections.isEmpty()) {
            status.value = "AI couldn't build a strength session — check your provider key & profile."
        } else {
            reset()
            workoutName = w.title.ifBlank { "AI Strength" }
            seedFromWorkout(w)
            status.value = "✓ AI session ready — adjust and log"
            nav.value = StrengthNav.Active
            startTick()
            persistSession()
        }
        loading.value = false
    }

    // Pre-fill the active session from a structured Workout (AI lift or a plan).
    private fun seedFromWorkout(w: com.workoutmaker.app.data.Workout) {
        w.sections.forEach { sec ->
            sec.exercises.forEach { e ->
                // An AI-introduced exercise outside the catalog: register it as a
                // custom entry with the AI's metadata so muscle grouping, stats
                // and future pickers recognise it.
                if (ExerciseCatalog.find(e.name) == null && e.name.isNotBlank()) {
                    viewModelScope.launch {
                        runCatching {
                            repo.addCustomExercise(
                                e.name,
                                e.muscle ?: "Other",
                                e.category ?: "Machine",
                                e.compound ?: false,
                            )
                        }
                    }
                }
                val ux = UiExercise(e.name)
                repeat(e.sets.coerceAtLeast(1)) {
                    ux.sets.add(UiSet(
                        suggestedWeight = e.weight_kg?.let { wk -> kg(wk) } ?: "",
                        suggestedReps = Regex("\\d+").find(e.reps)?.value ?: "",
                    ))
                }
                e.rest_seconds?.let { ux.restSec = it }
                exercises.add(ux)
                // History-based suggestion wins over the plan-prescribed weight so
                // the 🎯 target and the greyed placeholders always match.
                viewModelScope.launch {
                    ux.previous = repo.previousSets(e.name)
                    applySuggestion(ux, repo.progressionFor(e.name))
                }
            }
        }
    }

    // Open a planned calendar strength session in the logger, pre-filled and
    // linked so finishing marks the plan complete.
    private fun startPlanned(s: StrengthHandoff.Start) {
        reset()
        workoutName = s.workout.title.ifBlank { "Planned Strength" }
        linkedPlannedId = s.plannedId
        linkedPlannedDate = s.date
        seedFromWorkout(s.workout)
        status.value = "Logging your planned session — tick sets as you go"
        nav.value = StrengthNav.Active
        startTick()
        persistSession()
    }

    fun addExercises(names: List<String>) {
        names.forEach { addExercise(it) }
        nav.value = StrengthNav.Active
        persistSession()
    }

    fun removeExercise(ux: UiExercise) { exercises.remove(ux); persistSession() }

    fun addSet(ux: UiExercise) {
        val last = ux.sets.lastOrNull()
        // Suggest the previous set's value (entered or its own suggestion) as a placeholder.
        ux.sets.add(UiSet(
            suggestedWeight = last?.weight?.ifBlank { last.suggestedWeight } ?: "",
            suggestedReps = last?.reps?.ifBlank { last.suggestedReps } ?: "",
        ))
        persistSession()
    }

    // Insert a warm-up ramp scaled from the working weight (greyed placeholders,
    // not logged until the athlete ticks them). Re-running replaces the existing
    // leading warm-ups instead of stacking. Cardio has no load → no ramp.
    fun addWarmupRamp(ux: UiExercise) {
        if (ux.isCardio) return
        val workKg = ux.sets.firstOrNull { !it.warmup }
            ?.let { it.weight.toDoubleOrNull() ?: it.suggestedWeight.toDoubleOrNull() }
            ?: ux.suggestion?.weightKg
            ?: ux.previous.maxOfOrNull { it.weightKg }
        if (workKg == null || workKg <= 0.0) {
            status.value = "Enter a working weight first to build a warm-up ramp"
            return
        }
        // Classic ramp: progressively heavier, fewer reps, toward the work set.
        val scheme = listOf(0.40 to 5, 0.60 to 3, 0.80 to 2)
        val rows = mutableListOf<Pair<Double, Int>>()
        for ((pct, reps) in scheme) {
            val w = roundToStep(workKg * pct, 2.5)
            if (w <= 0.0) continue
            if (rows.isNotEmpty() && kotlin.math.abs(rows.last().first - w) < 0.001) continue
            rows.add(w to reps)
        }
        if (rows.isEmpty()) { status.value = "Working weight is too light for a warm-up ramp"; return }
        // Drop any existing leading warm-ups so re-runs replace rather than stack.
        while (ux.sets.firstOrNull()?.warmup == true) ux.sets.removeAt(0)
        rows.reversed().forEach { (w, reps) ->
            ux.sets.add(0, UiSet(suggestedWeight = kg(w), suggestedReps = reps.toString(), warmup = true))
        }
        persistSession()
    }

    fun removeSet(ux: UiExercise, s: UiSet) { ux.sets.remove(s); persistSession() }

    fun setRest(ux: UiExercise, sec: Int) { ux.restSec = sec.coerceAtLeast(0); persistSession() }

    fun toggleDone(ux: UiExercise, s: UiSet) {
        if (!s.done) {
            // A greyed suggestion is NOT a logged value: require real entered
            // weight & reps (typed, or tapped in from "PREV") before a set can
            // count as done — otherwise it'd save as 0×0.
            val needsWeight = !ux.isCardio && !s.warmup
            if (s.reps.isBlank() || (needsWeight && s.weight.isBlank())) {
                status.value = if (ux.isCardio) "Enter minutes before ticking the set"
                    else "Enter weight and reps before ticking the set"
                return
            }
            if (!s.warmup && ux.restSec > 0) startRest(ux.restSec)
        }
        s.done = !s.done
        persistSession()
    }

    // --- crash/kill-proof session persistence ------------------------------
    private val sessionFile: File get() = File(context.filesDir, "active_session.json")

    private var persistJob: Job? = null

    /** Save the in-progress session to disk. Safe to call on every change (incl.
     *  each keystroke): writes are debounced and atomic (temp file + rename) so
     *  rapid calls coalesce and a kill mid-write can never corrupt the file. */
    fun persistSession() {
        val snapshot = snapshotSession() // read Compose state on the caller (main) thread
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (snapshot == null) {
                    sessionFile.delete()
                } else {
                    val tmp = File(sessionFile.parentFile, "active_session.tmp")
                    tmp.writeText(sessionJson.encodeToString(SavedSession.serializer(), snapshot))
                    tmp.renameTo(sessionFile)
                }
            }
        }
    }

    private fun snapshotSession(): SavedSession? {
        if (nav.value != StrengthNav.Active || exercises.isEmpty()) return null
        val restEnd = if (restRemaining.value != null && restEndAt > System.currentTimeMillis()) restEndAt else null
        return SavedSession(
            workoutName = workoutName,
            workoutNote = workoutNote,
            startedAt = startedAt,
            linkedPlannedId = linkedPlannedId,
            linkedPlannedDate = linkedPlannedDate,
            editingWorkoutId = editingWorkoutId,
            editingEndedAt = editingEndedAt,
            restEndAt = restEnd,
            restTotal = restTotal.value,
            exercises = exercises.map { ux ->
                SavedExercise(ux.name, ux.restSec, ux.sets.map {
                    SavedSet(it.weight, it.reps, it.rpe, it.done, it.warmup, it.note, it.suggestedWeight, it.suggestedReps)
                })
            },
        )
    }

    /**
     * Synchronous save for the critical moment the app is backgrounded/closing —
     * the debounced async [persistSession] can be cancelled when the process is
     * killed before its coroutine runs, so on ON_STOP we write on the spot.
     */
    fun persistSessionNow() {
        val snapshot = snapshotSession()
        runCatching {
            if (snapshot == null) {
                sessionFile.delete()
            } else {
                val tmp = File(sessionFile.parentFile, "active_session.tmp")
                tmp.writeText(sessionJson.encodeToString(SavedSession.serializer(), snapshot))
                tmp.renameTo(sessionFile)
            }
        }
    }

    private fun clearSavedSession() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { sessionFile.delete() } }
    }

    // Read synchronously in init (the file is tiny) so nav/exercises are set
    // BEFORE first composition — the app opens straight into the live session
    // with no Home→workout flash.
    private fun restoreSavedSession() {
        val saved = runCatching {
            if (sessionFile.exists()) sessionJson.decodeFromString(SavedSession.serializer(), sessionFile.readText()) else null
        }.getOrNull() ?: return
        if (saved.exercises.isEmpty()) { clearSavedSession(); return }
        if (nav.value == StrengthNav.Active && exercises.isNotEmpty()) return

        workoutName = saved.workoutName
        workoutNote = saved.workoutNote
        startedAt = saved.startedAt
        linkedPlannedId = saved.linkedPlannedId
        linkedPlannedDate = saved.linkedPlannedDate
        editingWorkoutId = saved.editingWorkoutId
        editingEndedAt = saved.editingEndedAt
        exercises.clear()
        saved.exercises.forEach { se ->
            val ux = UiExercise(se.name).apply { restSec = se.restSec }
            se.sets.forEach { ss ->
                ux.sets.add(UiSet(ss.weight, ss.reps, ss.rpe, ss.done, ss.warmup, ss.note, ss.suggestedWeight, ss.suggestedReps))
            }
            exercises.add(ux)
            viewModelScope.launch { runCatching { ux.previous = repo.previousSets(se.name); applySuggestion(ux, repo.progressionFor(se.name)) } }
        }
        nav.value = StrengthNav.Active
        startTick()
        saved.restEndAt?.let { end ->
            val remain = ((end - System.currentTimeMillis()) / 1000).toInt()
            if (remain > 0) resumeRest(remain, saved.restTotal)
        }
        status.value = "Resumed your in-progress workout"
    }

    // --- timers ------------------------------------------------------------
    private fun startTick() {
        tickJob?.cancel()
        // Editing a past workout: freeze the clock at the session's original
        // duration — ticking from the old start would show days of "elapsed".
        if (isEditing) {
            elapsedSec.value = ((editingEndedAt - startedAt) / 1000).coerceAtLeast(0)
            return
        }
        // Keep the live session alive across backgrounding/swipe with a
        // foreground-service timer notification (like a watch workout).
        com.workoutmaker.app.work.WorkoutForegroundService.start(context, workoutName, startedAt)
        tickJob = viewModelScope.launch {
            while (true) {
                elapsedSec.value = (System.currentTimeMillis() - startedAt) / 1000
                delay(1000)
            }
        }
    }
    private fun stopTick() {
        tickJob?.cancel(); tickJob = null
        com.workoutmaker.app.work.WorkoutForegroundService.stop(context)
    }

    /** Manually start/restart a rest timer (the bottom "Rest" button). */
    fun startManualRest() = startRest(if (cfg.defaultRestSec > 0) cfg.defaultRestSec else 120)

    fun startRest(sec: Int) {
        if (sec <= 0) return
        restTotal.value = sec
        restEndAt = System.currentTimeMillis() + sec * 1000L
        restRemaining.value = sec
        scheduleRestAlarm(sec)
        runRestLoop()
        persistSession()
    }

    /** Resume a rest timer from a known remaining time (after restore). */
    private fun resumeRest(remaining: Int, total: Int) {
        restTotal.value = if (total > 0) total else remaining
        restEndAt = System.currentTimeMillis() + remaining * 1000L
        restRemaining.value = remaining
        scheduleRestAlarm(remaining)
        runRestLoop()
    }

    // Derive the countdown from the wall clock each tick, so a backgrounded /
    // throttled coroutine never makes the timer drift.
    private fun runRestLoop() {
        restJob?.cancel()
        restJob = viewModelScope.launch {
            while (true) {
                val remain = Math.ceil((restEndAt - System.currentTimeMillis()) / 1000.0).toInt()
                if (remain <= 0) {
                    restRemaining.value = null
                    cancelRestAlarm() // foreground: cue in-app instead of via the alarm
                    if (cfg.restVibrate) com.workoutmaker.app.notify.vibrateStrong(context)
                    if (cfg.restNotify) com.workoutmaker.app.notify.playRestOverSound(context)
                    break
                }
                restRemaining.value = remain
                delay(200) // smooth updates; value comes from the clock, not a decrement
            }
        }
    }

    fun adjustRest(delta: Int) {
        if (restRemaining.value == null) return
        restEndAt = (restEndAt + delta * 1000L).coerceAtLeast(System.currentTimeMillis())
        val next = Math.ceil((restEndAt - System.currentTimeMillis()) / 1000.0).toInt().coerceAtLeast(0)
        restRemaining.value = next
        if (delta > 0) restTotal.value = maxOf(restTotal.value, next)
        scheduleRestAlarm(next) // reschedule the backup alarm to the new end time
        persistSession()
    }
    fun skipRest() { restJob?.cancel(); restRemaining.value = null; restEndAt = 0L; cancelRestAlarm(); persistSession() }
    private fun stopRest() { restJob?.cancel(); restRemaining.value = null; restEndAt = 0L; cancelRestAlarm() }

    // Backup alarm so the "rest over" alert fires even if the app is killed.
    private fun restAlarmIntent(): android.app.PendingIntent {
        val i = android.content.Intent(context, com.workoutmaker.app.notify.RestAlarmReceiver::class.java)
        return android.app.PendingIntent.getBroadcast(
            context, 7001, i,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private fun scheduleRestAlarm(sec: Int) {
        if (!cfg.restNotify) return // user disabled rest-timer notifications
        val am = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
        val at = System.currentTimeMillis() + sec * 1000L
        try {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, restAlarmIntent())
        } catch (_: SecurityException) {
            am.set(android.app.AlarmManager.RTC_WAKEUP, at, restAlarmIntent())
        }
    }
    private fun cancelRestAlarm() {
        (context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager)?.cancel(restAlarmIntent())
    }

    // --- finish / routines / stats ----------------------------------------
    // Holds the finished workout between "Finish" and the Rate-your-effort screen,
    // since reset() clears the live session before the user rates it.
    private data class PendingFinish(
        val name: String,
        val started: Long,
        val ended: Long,
        val exercises: List<FinishedExercise>,
        val note: String,
        val editId: String?,
        val linkedId: String?,
    )
    private var pendingFinish: PendingFinish? = null

    fun finish() {
        val finished = exercises.map { ux ->
            FinishedExercise(
                name = ux.name,
                sets = ux.sets.filter { it.done }.map {
                    FinishedSet(
                        weightKg = it.weight.toDoubleOrNull() ?: 0.0,
                        reps = it.reps.toIntOrNull() ?: 0,
                        rpe = it.rpe.toIntOrNull(),
                        isWarmup = it.warmup,
                        note = it.note.trim(),
                    )
                },
            )
        }.filter { it.sets.isNotEmpty() }

        stopTick(); stopRest()
        val editId = editingWorkoutId
        val linkedId = linkedPlannedId
        if (finished.isEmpty()) { reset(); nav.value = StrengthNav.Home; status.value = "Discarded empty workout"; return }

        val started = startedAt
        // Keep the original end time when editing; otherwise stamp now.
        val ended = if (editId != null) editingEndedAt.coerceAtLeast(started) else System.currentTimeMillis()
        // Stash everything the save needs, then clear the live session and ask
        // the user to rate the session effort before we persist.
        pendingFinish = PendingFinish(workoutName, started, ended, finished, workoutNote.trim(), editId, linkedId)
        reset()
        nav.value = StrengthNav.RateEffort
    }

    /** Save the pending workout and (optionally) a session-RPE feedback row. */
    fun submitEffort(rpe: Int?, difficulty: String?) {
        val p = pendingFinish ?: run { nav.value = StrengthNav.Home; return }
        pendingFinish = null
        viewModelScope.launch {
            runCatching { repo.finishWorkout(p.name, p.started, p.ended, p.exercises, p.note, p.editId) }
                .onSuccess { result ->
                    lastPrs.value = result.prs
                    // Auto-complete the linked plan so the calendar reflects it.
                    if (p.linkedId != null) runCatching { repo.markPlannedWorkoutDone(p.linkedId, true) }
                    status.value = when {
                        p.editId != null -> "✓ Workout updated"
                        p.linkedId != null -> "✓ Logged — planned session marked done"
                        result.prs.isEmpty() -> "✓ Workout saved"
                        else -> "✓ Saved · ${result.prs.size} new PR${if (result.prs.size > 1) "s" else ""}! 🎉"
                    }
                    // Push the session to the cloud now (not at next launch) so the
                    // AI generator sees today's work immediately.
                    requestSync()
                    // Durably submit the session effort + refresh memory — survives
                    // being offline at the gym or an app kill (WorkManager retries).
                    val date = java.time.Instant.ofEpochMilli(p.ended)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
                    com.workoutmaker.app.work.FeedbackSyncWorker.request(
                        context, date, rpe, difficulty, p.note.ifBlank { null },
                    )
                }
                .onFailure { status.value = "Saved locally; sync failed: ${it.message}" }
            loadHome()
        }
        nav.value = StrengthNav.Home
    }

    /** Skip rating — still save the workout, just without a feedback row. */
    fun skipEffort() = submitEffort(null, null)

    fun saveAsRoutine() = viewModelScope.launch {
        if (exercises.isEmpty()) return@launch
        repo.saveRoutine(workoutName, exercises.map { it.name to it.sets.size })
        routines.value = repo.routines()
        status.value = "✓ Saved routine “$workoutName”"
        requestSync()
    }

    fun deleteRoutine(id: String) = viewModelScope.launch {
        repo.deleteRoutine(id)
        routines.value = repo.routines()
        requestSync()
    }

    // --- routine editing (long-press a routine) -----------------------------
    val editingRoutine = MutableStateFlow<RoutineWithItems?>(null)
    fun beginEditRoutine(r: RoutineWithItems) { editingRoutine.value = r }
    fun cancelEditRoutine() { editingRoutine.value = null }

    /** Persist an edited routine (renamed / reordered / changed items). */
    fun saveEditedRoutine(
        name: String,
        items: List<com.workoutmaker.app.strength.RoutineItemEntity>,
    ) = viewModelScope.launch {
        val current = editingRoutine.value ?: return@launch
        val cleanName = name.ifBlank { "Routine" }
        repo.updateRoutine(current.routine.copy(name = cleanName), items)
        routines.value = repo.routines()
        editingRoutine.value = null
        status.value = "✓ Updated “$cleanName”"
        requestSync()
    }

    fun deleteWorkout(id: String) = viewModelScope.launch {
        runCatching { repo.deleteWorkout(id) }
            .onSuccess {
                status.value = "Workout deleted"
                // If we were viewing this workout's detail, fall back to Home.
                if (nav.value is StrengthNav.WorkoutDetail) { workoutDetail.value = null; nav.value = StrengthNav.Home }
                loadHome()
            }
            .onFailure { status.value = "Couldn't delete: ${it.message}" }
    }

    fun openStats(name: String) = viewModelScope.launch {
        currentStats.value = repo.stats(name)
        nav.value = StrengthNav.Stats(name)
    }

    // Stats for the in-session insight peek (bottom sheet) — doesn't touch nav or
    // currentStats, so the active session stays put underneath.
    suspend fun statsFor(name: String): ExerciseStats = repo.stats(name)

    // --- push to Intervals.icu → watch (Zepp) ------------------------------
    private fun vibrate() {
        if (!cfg.restVibrate) return // user disabled rest-over vibration
        val vib = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching { vib?.vibrate(VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    override fun onCleared() { stopTick(); stopRest() }
}
