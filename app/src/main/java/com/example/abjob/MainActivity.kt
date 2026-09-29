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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(job.canonicalUrl)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobWatchScreen(openJob: (JobEntity) -> Unit) {
    val context = LocalContext.current
    val jobs by AppGraph.database.jobs().observeAll().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var showCompanies by remember { mutableStateOf(false) }
    val sources = remember { JobSourceRegistry.sources }
    val sourceIds = remember { sources.map { it.sourceId } }
    var selectedSourceIds by remember { mutableStateOf(AppGraph.preferences.selectedSources(sourceIds)) }
    var selectedLocationGroups by remember { mutableStateOf(AppGraph.preferences.selectedLocationGroups()) }
    val displayedJobs = jobs.asSequence()
        .filter { it.sourceId in selectedSourceIds }
        .filter { it.locationGroup() in selectedLocationGroups }
        .sortedWith(compareBy<JobEntity> { it.locationGroup().priority }.thenByDescending { it.postedAt }.thenByDescending { it.firstSeenAt })
        .toList()

    if (showCompanies) {
        CompanySelector(
            sources = sources,
            selectedIds = selectedSourceIds,
            onSave = { selected ->
                selectedSourceIds = selected
                AppGraph.preferences.setSelectedSources(selected)
                showCompanies = false
            },
            onDismiss = { showCompanies = false }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Job Watcher") }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Companies", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { showCompanies = true }) {
                    Text("${selectedSourceIds.size} selected · Manage companies")
                }
                Spacer(Modifier.height(12.dp))
                Text("Locations", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocationGroup.entries.forEach { group ->
                        FilterChip(
                            selected = group in selectedLocationGroups,
                            onClick = {
                                val next = selectedLocationGroups.toMutableSet().apply {
                                    if (group in this && size > 1) remove(group) else add(group)
                                }
                                selectedLocationGroups = next
                                AppGraph.preferences.setSelectedLocationGroups(next)
                            },
                            label = { Text(group.label) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            isRefreshing = true
                            try {
                                val activeSourceIds = sources.filter { it.supported && it.sourceId in selectedSourceIds }.map { it.sourceId }
                                if (activeSourceIds.isEmpty()) {
                                    Toast.makeText(context, "Select at least one supported company", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val newJobsCount = AppGraph.repository.refresh(activeSourceIds.toSet(), AppGraph.preferences.initializedSources(activeSourceIds))
                                AppGraph.preferences.setInitialized(activeSourceIds)
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
                Text("India is ranked first, then remote and other countries. Each company is checked independently.", style = MaterialTheme.typography.bodySmall)
            }
            items(displayedJobs, key = { it.jobKey }) { job ->
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
                                text = "${job.companyName} · ${job.sourceJobId}",
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

@Composable
private fun CompanySelector(
    sources: List<JobSourceConfig>,
    selectedIds: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(selectedIds) { mutableStateOf(selectedIds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose companies") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(sources, key = { it.sourceId }) { source ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = source.supported) {
                        selected = selected.toMutableSet().apply { if (source.sourceId in this) remove(source.sourceId) else add(source.sourceId) }
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = source.sourceId in selected, onCheckedChange = null, enabled = source.supported)
                        Column {
                            Text(source.companyName)
                            Text(if (source.supported) source.adapterType.replaceFirstChar { it.uppercase() } else "Coming soon", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun JobEntity.locationGroup(): LocationGroup = when {
    location.contains("india", ignoreCase = true) || location.contains("bangalore", ignoreCase = true) || location.contains("bengaluru", ignoreCase = true) -> LocationGroup.INDIA
    location.contains("remote", ignoreCase = true) -> LocationGroup.REMOTE
    else -> LocationGroup.OTHER
}

private val LocationGroup.priority: Int get() = when (this) {
    LocationGroup.INDIA -> 0
    LocationGroup.REMOTE -> 1
    LocationGroup.OTHER -> 2
}

class JobWatchWorker(appContext: android.content.Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val selectedSources = AppGraph.preferences.selectedSources(JobSourceRegistry.sources.map { it.sourceId })
        val activeSourceIds = JobSourceRegistry.sources.filter { it.supported && it.sourceId in selectedSources }.map { it.sourceId }
        AppGraph.repository.refresh(activeSourceIds.toSet(), AppGraph.preferences.initializedSources(activeSourceIds))
        AppGraph.preferences.setInitialized(activeSourceIds)
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
