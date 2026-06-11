package com.workoutmaker.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.workoutmaker.app.strength.CustomExerciseEntity
import com.workoutmaker.app.strength.FavoriteEntity
import com.workoutmaker.app.strength.RoutineEntity
import com.workoutmaker.app.strength.RoutineItemEntity
import com.workoutmaker.app.strength.SetEntity
import com.workoutmaker.app.strength.StrengthDao
import com.workoutmaker.app.strength.TombstoneEntity
import com.workoutmaker.app.strength.WorkoutEntity
import javax.inject.Singleton

// Offline-first cache: planned workouts + the last dashboard summary are stored
// as JSON so the Home/Calendar screens render instantly while a refresh runs.

@Entity(tableName = "cached_workout")
data class CachedWorkout(
    @PrimaryKey val id: String,
    val date: String,
    val type: String,
    val workoutJson: String,
)

@Entity(tableName = "cached_summary")
data class CachedSummary(
    @PrimaryKey val date: String,
    val json: String,
    // Epoch millis of the successful fetch — lets the Home header say how old
    // the offline data is.
    val fetchedAt: Long = 0,
)

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkouts(items: List<CachedWorkout>)

    @Query("SELECT * FROM cached_workout ORDER BY date DESC")
    suspend fun workouts(): List<CachedWorkout>

    // The cache mirrors the latest successful fetch; clearing before re-insert
    // drops rows for workouts deleted on the server.
    @Query("DELETE FROM cached_workout")
    suspend fun clearWorkouts()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(summary: CachedSummary)

    @Query("SELECT * FROM cached_summary ORDER BY date DESC LIMIT 1")
    suspend fun latestSummary(): CachedSummary?

    @Query("DELETE FROM cached_summary")
    suspend fun clearSummaries()
}

@Database(
    entities = [
        CachedWorkout::class, CachedSummary::class,
        WorkoutEntity::class, SetEntity::class, RoutineEntity::class, RoutineItemEntity::class,
        CustomExerciseEntity::class, FavoriteEntity::class, TombstoneEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
    abstract fun strengthDao(): StrengthDao
}

// v3 → v4: offline sync support. Adds `synced` flags (existing rows are already
// in the cloud, so they start as 1) and the tombstone table for pending deletes.
// Provided as a real migration so existing local strength data is NOT wiped.
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE strength_workout ADD COLUMN synced INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE strength_routine ADD COLUMN synced INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE strength_custom_exercise ADD COLUMN synced INTEGER NOT NULL DEFAULT 1")
        db.execSQL("CREATE TABLE IF NOT EXISTS strength_tombstone (tbl TEXT NOT NULL, rowId TEXT NOT NULL, PRIMARY KEY(tbl, rowId))")
    }
}

// v4 → v5: per-set notes (Q9). Default empty so existing rows are untouched.
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE strength_set ADD COLUMN note TEXT NOT NULL DEFAULT ''")
    }
}

// v5 → v6: offline cold-start support — remember when the dashboard summary
// was fetched so it can be served (with its age) when the app starts offline.
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cached_summary ADD COLUMN fetchedAt INTEGER NOT NULL DEFAULT 0")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object CacheModule {
    @Provides @Singleton
    fun provideDb(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "workoutmaker.db")
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideDao(db: AppDatabase): CacheDao = db.cacheDao()

    @Provides
    fun provideStrengthDao(db: AppDatabase): StrengthDao = db.strengthDao()
}
