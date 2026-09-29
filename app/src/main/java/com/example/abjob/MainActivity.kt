package com.example.abjob

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JobWatchWorker.schedule(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { JobWatchScreen(::openJob) }
    }

    private fun openJob(job: JobEntity) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WorkdayRepository.jobUrl(job.externalPath))))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobWatchScreen(openJob: (JobEntity) -> Unit) {
    val context = LocalContext.current
    val jobs by AppGraph.database.jobs().observeAll().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Airbus Bangalore jobs") }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Button(
                    onClick = {
                        scope.launch {
                            isRefreshing = true
                            try {
                                val newJobsCount = AppGraph.repository.refresh(notify = AppGraph.preferences.isInitialized())
                                AppGraph.preferences.setInitialized()
                                if (newJobsCount == 0) {
                                    Toast.makeText(context, "That's all postings as of now", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Found $newJobsCount new job${if (newJobsCount == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                                }
                            } catch (_: Exception) {
                                Toast.makeText(context, "Failed to refresh jobs", Toast.LENGTH_SHORT).show()
                            } finally {
                                isRefreshing = false
                            }
                        }
                    },
                    enabled = !isRefreshing
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Checking...")
                    } else {
                        Text("Check now")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Current Bangalore postings are sorted newest first. The first successful check creates the baseline without alerts.", style = MaterialTheme.typography.bodySmall)
            }
            items(jobs, key = { it.id }) { job ->
                ElevatedCard(Modifier.fillMaxWidth().clickable { openJob(job) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(job.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ID: ${job.id}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            job.postedOn?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(job.location, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

class JobWatchWorker(appContext: android.content.Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        AppGraph.repository.refresh(notify = AppGraph.preferences.isInitialized())
        AppGraph.preferences.setInitialized()
        Result.success()
    } catch (_: ConfigurationException) {
        Result.failure(workDataOf("reason" to "Configure AIRBUS_API_URL and AIRBUS_REQUEST_JSON"))
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "airbus-job-watch"
        fun schedule(context: android.content.Context) {
            val request = PeriodicWorkRequestBuilder<JobWatchWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
        suspend fun refreshNow(context: android.content.Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("$UNIQUE_NAME-manual", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<JobWatchWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }
}
