package com.workoutmaker.app.strength

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.workoutmaker.app.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry

// Runs the EXACT parse + Room-insert path on the real device runtime, to find
// out whether the on-device import discrepancy is in parsing or persistence.
@RunWith(AndroidJUnit4::class)
class ImportOnDeviceTest {

    private fun realCsv(): String {
        // Asset lives in the TEST apk, so read from the instrumentation context.
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return ctx.assets.open("strong_real.csv").bufferedReader().use { it.readText() }
    }

    @Test fun parseOnDevice() {
        val r = StrengthCsvImport.parse(realCsv())
        Log.i("IMPORT_TEST", "format=${r.format} workouts=${r.workoutCount} sets=${r.setCount} cardio=${r.cardioRows} skipped=${r.skippedRows}")
        assertEquals("Strong", r.format)
        assertTrue("device parse returned ${r.workoutCount} workouts", r.workoutCount > 50)
    }

    @Test fun parseThenInsertOnDevice() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val dao = db.strengthDao()
        val r = StrengthCsvImport.parse(realCsv())
        var inserted = 0
        for (w in r.workouts) {
            val wid = UUID.randomUUID().toString()
            val sets = mutableListOf<SetEntity>()
            var vol = 0.0
            for (ex in w.exercises) {
                ex.sets.forEachIndexed { i, s ->
                    if (!s.isWarmup) vol += s.weightKg * s.reps
                    sets.add(SetEntity(UUID.randomUUID().toString(), wid, ex.name, ExerciseCatalog.muscleOf(ex.name), i + 1, s.weightKg, s.reps, s.rpe, s.isWarmup))
                }
            }
            dao.insertWorkout(WorkoutEntity(wid, w.name, w.startedAt, w.startedAt, 0, vol, "Imported", synced = false))
            if (sets.isNotEmpty()) dao.insertSets(sets)
            inserted++
        }
        val count = dao.recentWorkouts(200).size
        Log.i("IMPORT_TEST", "parsed=${r.workoutCount} inserted=$inserted readBack=$count")
        db.close()
        assertEquals(r.workoutCount, count)
        assertTrue(count > 50)
    }
}
