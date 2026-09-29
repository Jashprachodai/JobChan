package com.example.abjob

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.serialization.json.*
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url
import okhttp3.MediaType.Companion.toMediaType
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

class ConfigurationException : IllegalStateException()

private interface WorkdayApi { @POST suspend fun jobs(@Url url: String, @Body body: JsonObject): JsonObject }

class WorkdayRepository(private val dao: JobDao, private val notifications: NotificationHelper) {
    private val json = Json { ignoreUnknownKeys = true }
    private val api: WorkdayApi = Retrofit.Builder()
        .baseUrl("https://placeholder.invalid/")
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(WorkdayApi::class.java)

    suspend fun refresh(notify: Boolean): Int {
        if (BuildConfig.AIRBUS_API_URL.isBlank() || BuildConfig.AIRBUS_REQUEST_JSON.isBlank()) throw ConfigurationException()
        val configured = json.parseToJsonElement(BuildConfig.AIRBUS_REQUEST_JSON).jsonObject
        val pageSize = configured["limit"]?.jsonPrimitive?.intOrNull ?: throw ConfigurationException()
        var offset = configured["offset"]?.jsonPrimitive?.intOrNull ?: 0
        var total: Int
        val now = System.currentTimeMillis()
        val all = mutableListOf<JobEntity>()
        do {
            val body = JsonObject(configured + mapOf("offset" to JsonPrimitive(offset)))
            val response = api.jobs(BuildConfig.AIRBUS_API_URL, body)
            val postings = response["jobPostings"]?.jsonArray.orEmpty()
            postings.forEach { item ->
                toJob(item, now - all.size)?.let { all.add(it) }
            }
            total = response["total"]?.jsonPrimitive?.intOrNull ?: postings.size
            offset += pageSize
        } while (offset < total && postings.isNotEmpty())

        val existing = dao.existingIds(all.map { it.id }).toSet()
        val newJobs = all.filter { it.id !in existing }
        dao.insertAll(newJobs)
        if (notify && newJobs.isNotEmpty()) notifications.showNewJobs(newJobs)
        return newJobs.size
    }

    private fun toJob(value: JsonElement, timestamp: Long): JobEntity? {
        val item = value.jsonObject
        val path = item["externalPath"]?.jsonPrimitive?.contentOrNull ?: return null
        val fields = item["bulletFields"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        
        val bulletReqId = fields.firstOrNull { it.startsWith("JR") || it.contains("JR") }
        val pathReqId = if (path.contains("_")) path.substringAfterLast('_') else null
        val reqId = item["jobReqId"]?.jsonPrimitive?.contentOrNull
            ?: bulletReqId
            ?: pathReqId
            ?: path

        val location = item["locationsText"]?.jsonPrimitive?.contentOrNull
            ?: fields.firstOrNull { it != reqId && !it.startsWith("JR") }
            ?: "Airbus"

        return JobEntity(
            id = reqId,
            title = item["title"]?.jsonPrimitive?.contentOrNull ?: "Untitled job",
            location = location,
            postedOn = item["postedOn"]?.jsonPrimitive?.contentOrNull,
            externalPath = path,
            firstSeenAt = timestamp
        )
    }

    companion object {
        fun jobUrl(path: String): String {
            val cleanPath = if (path.startsWith("/")) path else "/$path"
            return when {
                cleanPath.startsWith("/en-US/Airbus") || cleanPath.startsWith("/Airbus") ->
                    "https://ag.wd3.myworkdayjobs.com$cleanPath"
                cleanPath.startsWith("/en-US/") ->
                    "https://ag.wd3.myworkdayjobs.com/en-US/Airbus${cleanPath.removePrefix("/en-US")}"
                else ->
                    "https://ag.wd3.myworkdayjobs.com/en-US/Airbus$cleanPath"
            }
        }
    }
}

class NotificationHelper(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val channel = "new_jobs"
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(
            NotificationChannel(channel, "New Airbus jobs", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun showNewJobs(jobs: List<JobEntity>) {
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(1001, NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${jobs.size} new Airbus Bangalore job${if (jobs.size == 1) "" else "s"}")
            .setContentText(jobs.take(3).joinToString { it.title })
            .setStyle(NotificationCompat.BigTextStyle().bigText(jobs.joinToString("\n") { it.title }))
            .setContentIntent(intent).setAutoCancel(true).build())
    }
}
