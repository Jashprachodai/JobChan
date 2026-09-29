package com.example.abjob

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

class ConfigurationException : IllegalStateException()

/** Configuration is data, so adding another company does not require changing the UI or database. */
data class JobSourceConfig(
    val sourceId: String,
    val companyId: String,
    val companyName: String,
    val adapterType: String,
    val endpoint: String,
    val requestJson: String = "",
    val supported: Boolean = true
)

object JobSourceRegistry {
    val sources = listOf(
        JobSourceConfig(
            sourceId = "workday:airbus",
            companyId = "airbus",
            companyName = "Airbus",
            adapterType = "workday",
            endpoint = BuildConfig.AIRBUS_API_URL,
            requestJson = BuildConfig.AIRBUS_REQUEST_JSON
        ),
        JobSourceConfig("greenhouse:razorpay", "razorpay", "Razorpay", "greenhouse", "https://boards-api.greenhouse.io/v1/boards/razorpaysoftwareprivatelimited/jobs?content=true"),
        JobSourceConfig("greenhouse:discord", "discord", "Discord", "greenhouse", "https://boards-api.greenhouse.io/v1/boards/discord/jobs?content=true"),
        JobSourceConfig("greenhouse:anthropic", "anthropic", "Anthropic", "greenhouse", "https://boards-api.greenhouse.io/v1/boards/anthropic/jobs?content=true"),
        JobSourceConfig("greenhouse:stripe", "stripe", "Stripe", "greenhouse", "https://boards-api.greenhouse.io/v1/boards/stripe/jobs?content=true"),
        JobSourceConfig("smartrecruiters:phonepe", "phonepe", "PhonePe", "smartrecruiters", "", supported = false),
        JobSourceConfig("sense:groww", "groww", "Groww", "sense", "", supported = false),
        JobSourceConfig("custom:myntra", "myntra", "Myntra", "custom", "", supported = false),
        JobSourceConfig("custom:github", "github", "GitHub", "custom", "", supported = false)
    )
}

data class FetchedJob(
    val sourceJobId: String,
    val title: String,
    val location: String,
    val postedOn: String?,
    val postedAt: Long,
    val canonicalUrl: String
)

interface JobSourceAdapter {
    val adapterType: String
    suspend fun fetch(config: JobSourceConfig): List<FetchedJob>
}

private interface WorkdayApi { @POST suspend fun jobs(@Url url: String, @Body body: JsonObject): JsonObject }
private interface GreenhouseApi { @GET suspend fun jobs(@Url url: String): JsonObject }

class WorkdayAdapter : JobSourceAdapter {
    override val adapterType = "workday"
    private val json = Json { ignoreUnknownKeys = true }
    private val api = Retrofit.Builder().baseUrl("https://placeholder.invalid/")
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(WorkdayApi::class.java)

    override suspend fun fetch(config: JobSourceConfig): List<FetchedJob> {
        if (config.endpoint.isBlank() || config.requestJson.isBlank()) throw ConfigurationException()
        val configured = json.parseToJsonElement(config.requestJson).jsonObject
        val pageSize = configured["limit"]?.jsonPrimitive?.intOrNull ?: throw ConfigurationException()
        var offset = configured["offset"]?.jsonPrimitive?.intOrNull ?: 0
        var total: Int
        val jobs = mutableListOf<FetchedJob>()
        do {
            val response = api.jobs(config.endpoint, JsonObject(configured + ("offset" to JsonPrimitive(offset))))
            val postings = response["jobPostings"]?.jsonArray.orEmpty()
            jobs += postings.mapNotNull(::toJob)
            total = response["total"]?.jsonPrimitive?.intOrNull ?: postings.size
            offset += pageSize
        } while (offset < total && postings.isNotEmpty())
        return jobs
    }

    private fun toJob(value: JsonElement): FetchedJob? {
        val item = value.jsonObject
        val path = item["externalPath"]?.jsonPrimitive?.contentOrNull ?: return null
        val fields = item["bulletFields"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val sourceJobId = item["jobReqId"]?.jsonPrimitive?.contentOrNull
            ?: fields.firstOrNull { it.startsWith("JR") }
            ?: path.substringAfterLast('_', path)
        val location = item["locationsText"]?.jsonPrimitive?.contentOrNull ?: ""
        val postedOn = item["postedOn"]?.jsonPrimitive?.contentOrNull
        return FetchedJob(
            sourceJobId = sourceJobId,
            title = item["title"]?.jsonPrimitive?.contentOrNull ?: "Untitled job",
            location = location,
            postedOn = postedOn,
            postedAt = parsePostedAt(postedOn),
            canonicalUrl = toAirbusUrl(path)
        )
    }

    private fun parsePostedAt(postedOn: String?): Long {
        val now = System.currentTimeMillis()
        val value = postedOn.orEmpty().lowercase()
        val amount = Regex("(\\d+)").find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return when {
            "today" in value -> now
            "yesterday" in value -> now - TimeUnit.DAYS.toMillis(1)
            "hour" in value -> now - TimeUnit.HOURS.toMillis(amount)
            "week" in value -> now - TimeUnit.DAYS.toMillis(amount * 7)
            "month" in value -> now - TimeUnit.DAYS.toMillis(amount * 30)
            "day" in value -> now - TimeUnit.DAYS.toMillis(amount)
            else -> 0L
        }
    }

    private fun toAirbusUrl(path: String): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return when {
            cleanPath.startsWith("/en-US/Airbus") || cleanPath.startsWith("/Airbus") -> "https://ag.wd3.myworkdayjobs.com$cleanPath"
            cleanPath.startsWith("/en-US/") -> "https://ag.wd3.myworkdayjobs.com/en-US/Airbus${cleanPath.removePrefix("/en-US")}" 
            else -> "https://ag.wd3.myworkdayjobs.com/en-US/Airbus$cleanPath"
        }
    }
}

class GreenhouseAdapter : JobSourceAdapter {
    override val adapterType = "greenhouse"
    private val json = Json { ignoreUnknownKeys = true }
    private val api = Retrofit.Builder().baseUrl("https://placeholder.invalid/")
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(GreenhouseApi::class.java)

    override suspend fun fetch(config: JobSourceConfig): List<FetchedJob> {
        if (config.endpoint.isBlank()) throw ConfigurationException()
        return api.jobs(config.endpoint)["jobs"]?.jsonArray.orEmpty().mapNotNull { item ->
            val job = item.jsonObject
            val id = job["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val location = job["location"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            val updatedAt = job["updated_at"]?.jsonPrimitive?.contentOrNull
            FetchedJob(
                sourceJobId = id,
                title = job["title"]?.jsonPrimitive?.contentOrNull ?: "Untitled job",
                location = location,
                postedOn = updatedAt?.let { "Updated $it" },
                postedAt = updatedAt?.let { java.time.Instant.parse(it).toEpochMilli() } ?: 0L,
                canonicalUrl = job["absolute_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            )
        }
    }
}

class JobRepository(
    private val dao: JobDao,
    private val notifications: NotificationHelper,
    sourceConfigs: List<JobSourceConfig>
) {
    private val mutex = Mutex()
    private val configs = sourceConfigs
    private val adapters = listOf(WorkdayAdapter(), GreenhouseAdapter()).associateBy { it.adapterType }

    suspend fun refresh(selectedSourceIds: Set<String>, notifyForSources: Set<String>): Int = mutex.withLock {
        var newJobsCount = 0
        configs.filter { it.supported && it.sourceId in selectedSourceIds }.forEach { config ->
            val adapter = adapters[config.adapterType] ?: throw ConfigurationException()
            val fetched = adapter.fetch(config)
            val jobs = fetched.map { it.toEntity(config) }
            val existing = dao.existingKeys(jobs.map { it.jobKey }).toSet()
            val newJobs = jobs.filter { it.jobKey !in existing }
            dao.insertAll(newJobs)
            jobs.forEach { job ->
                dao.updateSnapshot(job.jobKey, job.title, job.location, job.postedOn, job.postedAt, job.canonicalUrl)
            }
            if (jobs.isEmpty()) dao.deleteSource(config.sourceId) else dao.deleteNotInSource(config.sourceId, jobs.map { it.jobKey })
            if (config.sourceId in notifyForSources && newJobs.isNotEmpty()) notifications.showNewJobs(newJobs)
            newJobsCount += newJobs.size
        }
        newJobsCount
    }

    private fun FetchedJob.toEntity(config: JobSourceConfig) = JobEntity(
        jobKey = "${config.sourceId}:$sourceJobId",
        sourceId = config.sourceId,
        companyId = config.companyId,
        companyName = config.companyName,
        sourceJobId = sourceJobId,
        title = title,
        location = location,
        postedOn = postedOn,
        postedAt = postedAt,
        canonicalUrl = canonicalUrl
    )
}

class NotificationHelper(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val channel = "new_jobs"
    init { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(channel, "New job matches", NotificationManager.IMPORTANCE_DEFAULT)) }
    fun showNewJobs(jobs: List<JobEntity>) {
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(1001, NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${jobs.size} new job${if (jobs.size == 1) "" else "s"}")
            .setContentText(jobs.take(3).joinToString { "${it.companyName}: ${it.title}" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(jobs.joinToString("\n") { "${it.companyName}: ${it.title}" }))
            .setContentIntent(intent).setAutoCancel(true).build())
    }
}
