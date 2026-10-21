package com.example.jobchan

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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

data class SourceCount(val sourceId: String, val count: Int)

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs ORDER BY postedAt DESC, firstSeenAt DESC") fun observeAll(): Flow<List<JobEntity>>
    @Query("SELECT sourceId, COUNT(*) as count FROM jobs GROUP BY sourceId") fun observeSourceCounts(): Flow<List<SourceCount>>
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

@Database(entities = [JobEntity::class], version = 1, exportSchema = false)
abstract class JobDatabase : RoomDatabase() { abstract fun jobs(): JobDao }

class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("watcher", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun selectedSources(allSourceIds: List<String>): Set<String> =
        prefs.getStringSet("selected_sources", null) ?: allSourceIds.toSet()
    fun setSelectedSources(sourceIds: Set<String>) = prefs.edit().putStringSet("selected_sources", sourceIds).apply()
    fun selectedLocationGroups(): Set<LocationGroup> =
        prefs.getStringSet("location_groups", null)?.mapNotNull { value -> LocationGroup.entries.find { it.name == value } }?.toSet()
            ?: setOf(LocationGroup.INDIA)
    fun setSelectedLocationGroups(groups: Set<LocationGroup>) =
        prefs.edit().putStringSet("location_groups", groups.map { it.name }.toSet()).apply()
    fun selectedCountries(): Set<CountryPreset> =
        prefs.getStringSet("countries", emptySet())?.mapNotNull { value -> CountryPreset.entries.find { it.name == value } }?.toSet().orEmpty()
    fun setSelectedCountries(countries: Set<CountryPreset>) =
        prefs.edit().putStringSet("countries", countries.map { it.name }.toSet()).apply()
    fun initializedSources(sourceIds: List<String>): Set<String> = sourceIds.filterTo(mutableSetOf()) { sourceId ->
        prefs.getBoolean("initialized:$sourceId", false)
    }
    fun setInitialized(sourceIds: List<String>) = prefs.edit().apply {
        sourceIds.forEach { putBoolean("initialized:$it", true) }
        remove("initialized")
    }.apply()

    fun customSources(): List<JobSourceConfig> {
        val raw = prefs.getString("custom_sources_json", null) ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addCustomSource(config: JobSourceConfig) {
        val list = customSources().filter { it.sourceId != config.sourceId } + config
        prefs.edit().putString("custom_sources_json", json.encodeToString(ListSerializer(JobSourceConfig.serializer()), list)).apply()
    }

    fun removeCustomSource(sourceId: String) {
        val list = customSources().filter { it.sourceId != sourceId }
        prefs.edit().putString("custom_sources_json", json.encodeToString(ListSerializer(JobSourceConfig.serializer()), list)).apply()
    }
}

enum class LocationGroup(val label: String) { INDIA("India"), REMOTE("Remote"), OTHER("Other countries") }

enum class CountryPreset(val label: String, vararg val aliases: String) {
    UNITED_STATES("United States", "united states", "usa", "u.s.", "us-", "san francisco", "new york", "seattle", "atlanta", "chicago"),
    UNITED_KINGDOM("United Kingdom", "united kingdom", "uk", "london", "manchester"),
    CANADA("Canada", "canada", "toronto", "vancouver", "montreal"),
    GERMANY("Germany", "germany", "berlin", "munich", "münchen", "hamburg"),
    SINGAPORE("Singapore", "singapore"),
    AUSTRALIA("Australia", "australia", "sydney", "melbourne"),
    JAPAN("Japan", "japan", "tokyo"),
    NETHERLANDS("Netherlands", "netherlands", "amsterdam")
}

private val INDIA_LOCATION_ALIASES = listOf(
    "india", "ind", "bangalore", "bengaluru", "mumbai", "bombay", "delhi", "new delhi",
    "gurugram", "gurgaon", "noida", "hyderabad", "pune", "chennai", "madras", "kolkata",
    "calcutta", "ahmedabad", "kochi", "cochin", "jaipur", "indore", "bhubaneswar",
    "trivandrum", "thiruvananthapuram", "chandigarh", "mohali", "coimbatore", "nagpur",
    "vadodara", "surat", "visakhapatnam", "vizag", "ghaziabad", "faridabad",
    "gurgaon area", "bangalore area", "gurugram area", "karnataka", "maharashtra",
    "telangana", "tamil nadu", "haryana", "uttar pradesh"
)

private val INDIA_REGEX = Regex("""\b(IN|IND|India|Bangalore|Bengaluru|Gurugram|Gurgaon|Noida|Hyderabad|Pune|Mumbai|Delhi|Chennai|Kolkata|Ahmedabad|Kochi|Jaipur)\b""", RegexOption.IGNORE_CASE)

fun JobEntity.locationGroup(): LocationGroup = when {
    INDIA_LOCATION_ALIASES.any { location.contains(it, ignoreCase = true) } || INDIA_REGEX.containsMatchIn(location) -> LocationGroup.INDIA
    location.contains("remote", ignoreCase = true) || location.contains("work from home", ignoreCase = true) -> LocationGroup.REMOTE
    else -> LocationGroup.OTHER
}

fun JobEntity.matchesLocationFilters(groups: Set<LocationGroup>, countries: Set<CountryPreset>): Boolean {
    val group = locationGroup()
    if (group !in groups) return false
    return group != LocationGroup.OTHER ||
        countries.isEmpty() ||
        countries.any { country -> country.aliases.any { location.contains(it, ignoreCase = true) } }
}

val LocationGroup.priority: Int get() = when (this) {
    LocationGroup.INDIA -> 0
    LocationGroup.REMOTE -> 1
    LocationGroup.OTHER -> 2
}

object AppGraph {
    private lateinit var context: Context
    lateinit var database: JobDatabase; private set
    lateinit var preferences: Preferences; private set
    lateinit var repository: JobRepository; private set
    fun initialize(appContext: Context) {
        if (::context.isInitialized) return
        context = appContext
        database = Room.databaseBuilder(context, JobDatabase::class.java, "jobs.db")
            .build()
        preferences = Preferences(context)
        repository = JobRepository(database.jobs(), NotificationHelper(context), context)
    }
}
