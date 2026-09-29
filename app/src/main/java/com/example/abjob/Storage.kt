package com.example.abjob

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,
    val title: String,
    val location: String,
    val postedOn: String?,
    val externalPath: String,
    val firstSeenAt: Long = System.currentTimeMillis()
)

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs ORDER BY firstSeenAt DESC") fun observeAll(): Flow<List<JobEntity>>
    @Query("SELECT id FROM jobs WHERE id IN (:ids)") suspend fun existingIds(ids: List<String>): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(jobs: List<JobEntity>)
}

@Database(entities = [JobEntity::class], version = 3, exportSchema = false)
abstract class JobDatabase : RoomDatabase() { abstract fun jobs(): JobDao }

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
            .fallbackToDestructiveMigration()
            .build()
        preferences = Preferences(context)
        repository = WorkdayRepository(database.jobs(), NotificationHelper(context))
    }
}
