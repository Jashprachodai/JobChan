package com.example.abjob

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val jobKey: String,
    val sourceId: String,
    val companyId: String,
    val companyName: String,
    val sourceJobId: String,
    val title: String,
    val location: String,
    val postedOn: String?,
    /** Normalized from Workday's human-readable `postedOn` value. */
    val postedAt: Long,
    val canonicalUrl: String,
    val firstSeenAt: Long = System.currentTimeMillis()
)

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs ORDER BY postedAt DESC, firstSeenAt DESC") fun observeAll(): Flow<List<JobEntity>>
    @Query("SELECT jobKey FROM jobs WHERE jobKey IN (:keys)") suspend fun existingKeys(keys: List<String>): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(jobs: List<JobEntity>)
    @Query("""
        UPDATE jobs SET title = :title, location = :location, postedOn = :postedOn, postedAt = :postedAt,
        canonicalUrl = :canonicalUrl WHERE jobKey = :jobKey
    """)
    suspend fun updateSnapshot(jobKey: String, title: String, location: String, postedOn: String?, postedAt: Long, canonicalUrl: String)
    @Query("DELETE FROM jobs WHERE sourceId = :sourceId AND jobKey NOT IN (:keys)") suspend fun deleteNotInSource(sourceId: String, keys: List<String>)
    @Query("DELETE FROM jobs WHERE sourceId = :sourceId") suspend fun deleteSource(sourceId: String)
}

@Database(entities = [JobEntity::class], version = 6, exportSchema = false)
abstract class JobDatabase : RoomDatabase() { abstract fun jobs(): JobDao }

private fun addPostedAtMigration(fromVersion: Int) = object : Migration(fromVersion, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE jobs ADD COLUMN postedAt INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_1_4 = addPostedAtMigration(1)
private val MIGRATION_2_4 = addPostedAtMigration(2)
private val MIGRATION_3_4 = addPostedAtMigration(3)

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS jobs_new (
                jobKey TEXT NOT NULL PRIMARY KEY,
                sourceId TEXT NOT NULL,
                companyId TEXT NOT NULL,
                companyName TEXT NOT NULL,
                sourceJobId TEXT NOT NULL,
                title TEXT NOT NULL,
                location TEXT NOT NULL,
                postedOn TEXT,
                postedAt INTEGER NOT NULL,
                canonicalUrl TEXT NOT NULL,
                firstSeenAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO jobs_new (jobKey, sourceId, companyId, companyName, sourceJobId, title, location, postedOn, postedAt, canonicalUrl, firstSeenAt)
            SELECT 'workday:airbus:' || id, 'workday:airbus', 'airbus', 'Airbus', id, title, location, postedOn, postedAt,
                CASE
                    WHEN externalPath LIKE '/en-US/Airbus%' OR externalPath LIKE '/Airbus%' THEN 'https://ag.wd3.myworkdayjobs.com' || externalPath
                    WHEN externalPath LIKE '/en-US/%' THEN 'https://ag.wd3.myworkdayjobs.com/en-US/Airbus' || substr(externalPath, 7)
                    WHEN externalPath LIKE '/%' THEN 'https://ag.wd3.myworkdayjobs.com/en-US/Airbus' || externalPath
                    ELSE 'https://ag.wd3.myworkdayjobs.com/en-US/Airbus/' || externalPath
                END,
                firstSeenAt
            FROM jobs
        """.trimIndent())
        db.execSQL("DROP TABLE jobs")
        db.execSQL("ALTER TABLE jobs_new RENAME TO jobs")
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            UPDATE jobs SET canonicalUrl =
                CASE
                    WHEN canonicalUrl LIKE '/en-US/Airbus%' OR canonicalUrl LIKE '/Airbus%' THEN 'https://ag.wd3.myworkdayjobs.com' || canonicalUrl
                    WHEN canonicalUrl LIKE '/en-US/%' THEN 'https://ag.wd3.myworkdayjobs.com/en-US/Airbus' || substr(canonicalUrl, 7)
                    WHEN canonicalUrl LIKE '/%' THEN 'https://ag.wd3.myworkdayjobs.com/en-US/Airbus' || canonicalUrl
                    ELSE canonicalUrl
                END
            WHERE sourceId = 'workday:airbus' AND canonicalUrl NOT LIKE 'http%'
        """.trimIndent())
    }
}

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("watcher", Context.MODE_PRIVATE)
    fun selectedSources(allSourceIds: List<String>): Set<String> =
        prefs.getStringSet("selected_sources", null) ?: allSourceIds.toSet()
    fun setSelectedSources(sourceIds: Set<String>) = prefs.edit().putStringSet("selected_sources", sourceIds).apply()
    fun selectedLocationGroups(): Set<LocationGroup> =
        prefs.getStringSet("location_groups", null)?.mapNotNull { value -> LocationGroup.entries.find { it.name == value } }?.toSet()
            ?: setOf(LocationGroup.INDIA)
    fun setSelectedLocationGroups(groups: Set<LocationGroup>) =
        prefs.edit().putStringSet("location_groups", groups.map { it.name }.toSet()).apply()
    fun initializedSources(sourceIds: List<String>): Set<String> = sourceIds.filterTo(mutableSetOf()) { sourceId ->
        prefs.getBoolean("initialized:$sourceId", sourceId == "workday:airbus" && prefs.getBoolean("initialized", false))
    }
    fun setInitialized(sourceIds: List<String>) = prefs.edit().apply {
        sourceIds.forEach { putBoolean("initialized:$it", true) }
        remove("initialized")
    }.apply()
}

enum class LocationGroup(val label: String) { INDIA("India"), REMOTE("Remote"), OTHER("Other countries") }

object AppGraph {
    private lateinit var context: Context
    lateinit var database: JobDatabase; private set
    lateinit var preferences: Preferences; private set
    lateinit var repository: JobRepository; private set
    fun initialize(appContext: Context) {
        if (::context.isInitialized) return
        context = appContext
        database = Room.databaseBuilder(context, JobDatabase::class.java, "jobs.db")
            .addMigrations(MIGRATION_1_4, MIGRATION_2_4, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()
        preferences = Preferences(context)
        repository = JobRepository(database.jobs(), NotificationHelper(context), JobSourceRegistry.sources)
    }
}
