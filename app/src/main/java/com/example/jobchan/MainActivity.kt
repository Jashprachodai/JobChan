package com.example.jobchan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        setContent {
            JobChanTheme {
                JobChanApp(::openJob)
            }
        }
    }

    private fun openJob(job: JobEntity) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(job.canonicalUrl)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobChanApp(openJob: (JobEntity) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val jobs by AppGraph.database.jobs().observeAll().collectAsStateWithLifecycle(emptyList())
    val sourceCountsList by AppGraph.database.jobs().observeSourceCounts().collectAsStateWithLifecycle(emptyList())
    val sourceCounts = remember(sourceCountsList) { sourceCountsList.associate { it.sourceId to it.count } }

    var isRefreshing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddCompanyDialog by remember { mutableStateOf(false) }
    var showLocationMenu by remember { mutableStateOf(false) }

    var sources by remember { mutableStateOf(JobSourceRegistry.sources(context)) }
    fun refreshSources() { sources = JobSourceRegistry.sources(context) }

    val allSourceIds = remember(sources) { sources.map { it.sourceId } }
    var selectedSourceIds by remember(sources) { mutableStateOf(AppGraph.preferences.selectedSources(allSourceIds)) }
    var selectedLocationGroups by remember { mutableStateOf(AppGraph.preferences.selectedLocationGroups()) }
    var selectedCountries by remember { mutableStateOf(AppGraph.preferences.selectedCountries()) }

    val displayedJobs = remember(jobs, selectedSourceIds, selectedLocationGroups, selectedCountries, searchQuery) {
        jobs.asSequence()
            .filter { it.sourceId in selectedSourceIds }
            .filter { it.matchesLocationFilters(selectedLocationGroups, selectedCountries) }
            .filter { job ->
                searchQuery.isBlank() ||
                job.title.contains(searchQuery, ignoreCase = true) ||
                job.companyName.contains(searchQuery, ignoreCase = true) ||
                job.sourceJobId.contains(searchQuery, ignoreCase = true) ||
                job.location.contains(searchQuery, ignoreCase = true)
            }
            .sortedWith(compareBy<JobEntity> { it.locationGroup().priority }.thenByDescending { it.postedAt }.thenByDescending { it.firstSeenAt })
            .toList()
    }

    if (showAddCompanyDialog) {
        AddCompanyDialog(
            onAdd = { newConfig ->
                AppGraph.preferences.addCustomSource(newConfig)
                refreshSources()
                val nextSelected = selectedSourceIds + newConfig.sourceId
                selectedSourceIds = nextSelected
                AppGraph.preferences.setSelectedSources(nextSelected)
                showAddCompanyDialog = false
                Toast.makeText(context, "Added ${newConfig.companyName}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showAddCompanyDialog = false }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp)
            ) {
                SidebarHeader(onAddCompanyClick = { showAddCompanyDialog = true })
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Text(
                    text = "COMPANIES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    items(sources, key = { it.sourceId }) { source ->
                        val isSelected = source.sourceId in selectedSourceIds
                        val count = sourceCounts[source.sourceId] ?: 0
                        val isCustom = source.sourceId.startsWith("custom_user:")

                        NavigationDrawerItem(
                            label = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CompanyAvatar(name = source.companyName, size = 28)
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(source.companyName, style = MaterialTheme.typography.titleSmall)
                                            Text(source.adapterType.uppercase(), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (count > 0) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            ) {
                                                Text("$count")
                                            }
                                        }
                                        if (isCustom) {
                                            IconButton(onClick = {
                                                AppGraph.preferences.removeCustomSource(source.sourceId)
                                                refreshSources()
                                            }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            },
                            selected = isSelected,
                            onClick = {
                                val next = selectedSourceIds.toMutableSet().apply {
                                    if (source.sourceId in this) remove(source.sourceId) else add(source.sourceId)
                                }
                                selectedSourceIds = next
                                AppGraph.preferences.setSelectedSources(next)
                            },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(onClick = { showLocationMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        val summary = buildList {
                            selectedLocationGroups.forEach { add(it.label) }
                            if (selectedCountries.isNotEmpty()) add("${selectedCountries.size} countries")
                        }.joinToString(" · ")
                        Text("Locations: $summary")
                    }
                    DropdownMenu(
                        expanded = showLocationMenu,
                        onDismissRequest = { showLocationMenu = false },
                        modifier = Modifier.heightIn(max = 440.dp)
                    ) {
                        Text("REGIONS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 8.dp))
                        LocationGroup.entries.forEach { group ->
                            DropdownMenuItem(
                                text = { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(group in selectedLocationGroups, null); Spacer(Modifier.width(8.dp)); Text(group.label) } },
                                onClick = {
                                    val next = selectedLocationGroups.toMutableSet().apply {
                                        if (group in this && size > 1) remove(group) else add(group)
                                    }
                                    selectedLocationGroups = next
                                    AppGraph.preferences.setSelectedLocationGroups(next)
                                }
                            )
                        }
                        HorizontalDivider()
                        Text("OTHER COUNTRIES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 8.dp))
                        CountryPreset.entries.forEach { country ->
                            DropdownMenuItem(
                                text = { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(country in selectedCountries, null); Spacer(Modifier.width(8.dp)); Text(country.label) } },
                                onClick = {
                                    val next = selectedCountries.toMutableSet().apply { if (country in this) remove(country) else add(country) }
                                    selectedCountries = next
                                    AppGraph.preferences.setSelectedCountries(next)
                                }
                            )
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("JobChan", style = MaterialTheme.typography.titleLarge)
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    isRefreshing = true
                                    try {
                                        val activeSourceIds = sources.filter { it.supported && it.sourceId in selectedSourceIds }.map { it.sourceId }
                                        if (activeSourceIds.isEmpty()) {
                                            Toast.makeText(context, "Select at least one active company from sidebar", Toast.LENGTH_SHORT).show()
                                            return@launch
                                        }
                                        val newJobsCount = AppGraph.repository.refresh(
                                            activeSourceIds.toSet(),
                                            AppGraph.preferences.initializedSources(activeSourceIds),
                                            selectedLocationGroups,
                                            selectedCountries
                                        )
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
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                // Welcome Mascot Banner
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.mascot),
                            contentDescription = "Mascot",
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Let's find you a job",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Tracking live postings across company portals",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                // Search & Filter Header
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search title, company, ID or location...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                // Stats Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${displayedJobs.size} postings found",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${selectedSourceIds.size} companies active",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(displayedJobs, key = { it.jobKey }) { job ->
                        ElevatedCard(
                            onClick = { openJob(job) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CompanyAvatar(name = job.companyName, size = 36)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = job.companyName,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = job.title,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "ID: ${job.sourceJobId}",
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                    job.postedOn?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }

                                Spacer(Modifier.height(4.dp))
                                Text("📍 ${job.location}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarHeader(onAddCompanyClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("JobChan", style = MaterialTheme.typography.displayLarge, fontSize = 26.sp)
                Text("Real-time Job Watcher", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            IconButton(onClick = onAddCompanyClick) {
                Icon(Icons.Default.Add, contentDescription = "Add Company", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun CompanyAvatar(name: String, size: Int) {
    val char = name.firstOrNull()?.uppercaseChar() ?: 'J'
    val bgColors = listOf(
        Color(0xFF8E24AA), Color(0xFF1E88E5), Color(0xFF3949AB),
        Color(0xFF00897B), Color(0xFFD81B60), Color(0xFFF4511E)
    )
    val colorIndex = (name.hashCode() and 0x7FFFFFFF) % bgColors.size
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(bgColors[colorIndex]),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$char",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size / 2.2).sp,
            fontFamily = OswaldFamily
        )
    }
}

@Composable
private fun AddCompanyDialog(
    onAdd: (JobSourceConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var companyName by remember { mutableStateOf("") }
    var adapterType by remember { mutableStateOf("greenhouse") }
    var endpoint by remember { mutableStateOf("") }
    var requestJson by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Company", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = companyName,
                    onValueChange = { companyName = it },
                    label = { Text("Company Name (e.g. Google)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Adapter Type:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = adapterType == "greenhouse",
                        onClick = { adapterType = "greenhouse" },
                        label = { Text("Greenhouse") }
                    )
                    FilterChip(
                        selected = adapterType == "workday",
                        onClick = { adapterType = "workday" },
                        label = { Text("Workday") }
                    )
                }

                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("API Endpoint URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (adapterType == "workday") {
                    Text(
                        "Location facets in this body are ignored. JobChan fetches broadly and applies your readable location filters locally.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = requestJson,
                        onValueChange = { requestJson = it },
                        label = { Text("Request Body JSON") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (companyName.isNotBlank() && endpoint.isNotBlank()) {
                        val companyId = companyName.lowercase().replace(" ", "_")
                        val sourceId = "custom_user:$companyId"
                        onAdd(
                            JobSourceConfig(
                                sourceId = sourceId,
                                companyId = companyId,
                                companyName = companyName.trim(),
                                adapterType = adapterType,
                                endpoint = endpoint.trim(),
                                requestJson = requestJson.trim(),
                                clientSideLocationFiltering = true,
                                supported = true
                            )
                        )
                    }
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

class JobWatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val allSources = JobSourceRegistry.sources(applicationContext)
        val selectedSources = AppGraph.preferences.selectedSources(allSources.map { it.sourceId })
        val activeSourceIds = allSources.filter { it.supported && it.sourceId in selectedSources }.map { it.sourceId }
        AppGraph.repository.refresh(
            activeSourceIds.toSet(),
            AppGraph.preferences.initializedSources(activeSourceIds),
            AppGraph.preferences.selectedLocationGroups(),
            AppGraph.preferences.selectedCountries()
        )
        AppGraph.preferences.setInitialized(activeSourceIds)
        Result.success()
    } catch (_: ConfigurationException) {
        Result.failure(workDataOf("reason" to "Check the source endpoint and request body"))
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "jobchan-periodic-refresh"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<JobWatchWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
