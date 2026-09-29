package com.example.abjob

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,
    val title: String,
    val location: String,
    val postedOn: String?,
    /** Normalized from Workday's human-readable `postedOn` value. */
    val postedAt: Long,
    val externalPath: String,
    val firstSeenAt: Long = System.currentTimeMillis()
)

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs ORDER BY postedAt DESC, firstSeenAt DESC") fun observeAll(): Flow<List<JobEntity>>
    @Query("SELECT id FROM jobs WHERE id IN (:ids)") suspend fun existingIds(ids: List<String>): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(jobs: List<JobEntity>)
    @Query("""
        UPDATE jobs SET title = :title, location = :location, postedOn = :postedOn,
        postedAt = :postedAt, externalPath = :externalPath WHERE id = :id
    """)
    suspend fun updateSnapshot(id: String, title: String, location: String, postedOn: String?, postedAt: Long, externalPath: String)
    @Query("DELETE FROM jobs WHERE id NOT IN (:ids)") suspend fun deleteNotIn(ids: List<String>)
    @Query("DELETE FROM jobs") suspend fun deleteAll()
}

@Database(entities = [JobEntity::class], version = 4, exportSchema = false)
abstract class JobDatabase : RoomDatabase() { abstract fun jobs(): JobDao }

private fun addPostedAtMigration(fromVersion: Int) = object : Migration(fromVersion, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE jobs ADD COLUMN postedAt INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_1_4 = addPostedAtMigration(1)
private val MIGRATION_2_4 = addPostedAtMigration(2)
private val MIGRATION_3_4 = addPostedAtMigration(3)

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("watcher", Context.MODE_PRIVATE)
    fun isInitialized() = prefs.getBoolean("initialized", false)
    fun setInitialized() = prefs.edit().putBoolean("initialized", true).apply()
}

object AppGraph {
    private lateinit var context: Context
    lateinit var database: JobDatabase; private set
    lateinit var preferences: Preferences; private set
    lateinit var repository: WorkdayRepository; private set
    fun initialize(appContext: Context) {
        if (::context.isInitialized) return
        context = appContext
        database = Room.databaseBuilder(context, JobDatabase::class.java, "jobs.db")
            .addMigrations(MIGRATION_1_4, MIGRATION_2_4, MIGRATION_3_4)
            .build()
        preferences = Preferences(context)
        repository = WorkdayRepository(database.jobs(), NotificationHelper(context))
    }
}
