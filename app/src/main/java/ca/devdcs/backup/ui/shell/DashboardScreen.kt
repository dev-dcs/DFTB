package ca.devdcs.backup.ui.shell

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.schedule.NetworkRequirement
import ca.devdcs.backup.domain.model.JobType
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.displayName
import ca.devdcs.backup.ui.destinations.icon
import ca.devdcs.backup.ui.mapping.label
import ca.devdcs.backup.ui.onboarding.DiagnosticIntents
import kotlinx.coroutines.launch

/**
 * Navigation callbacks of the dashboard, bundled so the NavHost wires them in one place. Every
 * callback only navigates; work (back up now, resume) goes through [DashboardViewModel].
 */
data class DashboardActions(
    /** Job monitor for one job; null = all jobs. */
    val openJob: (String?) -> Unit,
    val openProfile: (String) -> Unit,
    val newProfile: () -> Unit,
    val openProfiles: () -> Unit,
    val openSchedules: () -> Unit,
    val openRestore: () -> Unit,
    val openDestinations: () -> Unit,
    val addDestination: () -> Unit,
    val openModeSettings: () -> Unit,
    val openSystemInfo: () -> Unit,
    val openDiscovery: () -> Unit,
    val openLicenses: () -> Unit,
)

/**
 * Home screen: warnings that need the user, running jobs, quick actions, the last backup and next
 * run of every profile, and the cached health of every destination. Cards flow into one column on
 * phones and several on tablets.
 */
@Composable
fun DashboardScreen(actions: DashboardActions, viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val autoRequest by viewModel.autoRequestNotifications.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = LocalActivity.current
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var userAskedForNotifications by rememberSaveable { mutableStateOf(false) }
    val noAppMessage = stringResource(R.string.onboarding_no_app_for_action)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshEnvironment() }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // A permanently denied permission (no dialog any more) or notifications switched off in
        // system settings can only be fixed on the settings page; only redirect when the user asked.
        val stillOff = !NotificationManagerCompat.from(context).areNotificationsEnabled()
        val canAskAgain = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
        if (userAskedForNotifications && stillOff && (granted || !canAskAgain)) {
            if (!DiagnosticIntents.openNotificationSettings(activity ?: context)) {
                scope.launch { snackbarHostState.showSnackbar(noAppMessage) }
            }
        }
        userAskedForNotifications = false
        viewModel.refreshEnvironment()
    }
    LaunchedEffect(autoRequest) {
        if (autoRequest && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            viewModel.onNotificationPromptLaunched()
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val requestNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            userAskedForNotifications = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!DiagnosticIntents.openNotificationSettings(activity ?: context)) {
            scope.launch { snackbarHostState.showSnackbar(noAppMessage) }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is DashboardEvent.BackupQueued -> context.getString(R.string.shell_backup_queued, event.profileName)
                is DashboardEvent.ResumeQueued -> context.getString(R.string.shell_resume_queued, event.profileName)
                is DashboardEvent.QueueFailed -> context.getString(R.string.shell_queue_failed, event.profileName)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shell_dashboard_title)) },
                actions = {
                    IconButton(onClick = { actions.openJob(null) }) {
                        Icon(Icons.Outlined.Terminal, contentDescription = stringResource(R.string.shell_open_job_monitor))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.shell_more_options))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shell_menu_system_info)) },
                                onClick = {
                                    menuOpen = false
                                    actions.openSystemInfo()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shell_menu_discovery)) },
                                onClick = {
                                    menuOpen = false
                                    actions.openDiscovery()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shell_menu_schedules)) },
                                onClick = {
                                    menuOpen = false
                                    actions.openSchedules()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shell_menu_licenses)) },
                                onClick = {
                                    menuOpen = false
                                    actions.openLicenses()
                                },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val model = state.model
        if (model == null) {
            val loadingLabel = stringResource(R.string.shell_loading)
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = loadingLabel })
            }
        } else {
            DashboardContent(
                model = model,
                nowMillis = state.nowMillis,
                queueing = state.queueing,
                innerPadding = innerPadding,
                actions = actions,
                onBackUpNow = viewModel::backUpNow,
                onResume = viewModel::resume,
                onCheckEnvironment = viewModel::refreshEnvironment,
                onRequestNotifications = requestNotifications,
            )
        }
    }
}

@Composable
private fun DashboardContent(
    model: DashboardModel,
    nowMillis: Long,
    queueing: Set<String>,
    innerPadding: PaddingValues,
    actions: DashboardActions,
    onBackUpNow: (String, String) -> Unit,
    onResume: (String, String, String) -> Unit,
    onCheckEnvironment: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val cardsById = remember(model.profiles) { model.profiles.associateBy { it.profile.id } }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(minSize = CARD_MIN_WIDTH),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
            end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
            top = innerPadding.calculateTopPadding() + 8.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalItemSpacing = 12.dp,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(model.warnings, key = { it.key }, span = { StaggeredGridItemSpan.FullLine }) { warning ->
            WarningCard(
                warning = warning,
                nowMillis = nowMillis,
                cardsById = cardsById,
                queueing = queueing,
                actions = actions,
                onBackUpNow = onBackUpNow,
                onResume = onResume,
                onCheckEnvironment = onCheckEnvironment,
                onRequestNotifications = onRequestNotifications,
            )
        }
        if (model.activeJobs.isNotEmpty()) {
            item(key = "active", span = StaggeredGridItemSpan.FullLine) {
                ActiveJobsCard(model.activeJobs, onOpenJob = actions.openJob)
            }
        }
        item(key = "quick", span = StaggeredGridItemSpan.FullLine) {
            QuickActions(actions)
        }
        item(key = "profiles") {
            ProfilesCard(model.profiles, nowMillis, queueing, actions, onBackUpNow)
        }
        item(key = "upcoming") {
            UpcomingCard(model.upcoming, nowMillis, onOpenProfile = actions.openProfile, onOpenSchedules = actions.openSchedules)
        }
        item(key = "destinations") {
            DestinationsHealthCard(model.destinations, nowMillis, onOpen = actions.openDestinations, onAdd = actions.addDestination)
        }
    }
}

// --- Warnings ------------------------------------------------------------------------------------

private enum class WarningTone { ERROR, CAUTION, INFO }

@Composable
private fun WarningCard(
    warning: DashboardWarning,
    nowMillis: Long,
    cardsById: Map<String, ProfileCard>,
    queueing: Set<String>,
    actions: DashboardActions,
    onBackUpNow: (String, String) -> Unit,
    onResume: (String, String, String) -> Unit,
    onCheckEnvironment: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    when (warning) {
        is DashboardWarning.ElevationUnavailable -> WarningLayout(
            tone = WarningTone.ERROR,
            icon = Icons.Outlined.AdminPanelSettings,
            title = stringResource(R.string.shell_warn_elevation_title, stringResource(warning.mode.labelRes)),
            body = stringResource(
                if (warning.mode == OperatingMode.SHIZUKU) R.string.shell_warn_elevation_shizuku_body else R.string.shell_warn_elevation_root_body,
            ),
        ) {
            TextButton(onClick = onCheckEnvironment) { Text(stringResource(R.string.shell_warn_check_again)) }
            TextButton(onClick = actions.openModeSettings) { Text(stringResource(R.string.shell_warn_open_settings)) }
        }

        DashboardWarning.NotificationsDisabled -> WarningLayout(
            tone = WarningTone.INFO,
            icon = Icons.Outlined.NotificationsOff,
            title = stringResource(R.string.shell_warn_notifications_title),
            body = stringResource(R.string.shell_warn_notifications_body),
        ) {
            TextButton(onClick = onRequestNotifications) { Text(stringResource(R.string.shell_warn_allow)) }
        }

        is DashboardWarning.PassphraseRequired -> WarningLayout(
            tone = WarningTone.CAUTION,
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.shell_warn_passphrase_title, warning.profileName),
            body = stringResource(R.string.shell_warn_passphrase_body),
        ) {
            // The Profiles list is where a passphrase run is started (it asks for the passphrase).
            TextButton(onClick = actions.openProfiles) { Text(stringResource(R.string.shell_open_profiles)) }
        }

        is DashboardWarning.InterruptedBackup -> WarningLayout(
            tone = WarningTone.CAUTION,
            icon = Icons.Outlined.History,
            title = stringResource(R.string.shell_warn_interrupted_title, warning.profileName),
            body = if (warning.resumable) {
                stringResource(R.string.shell_warn_interrupted_resumable)
            } else {
                stringResource(R.string.shell_warn_interrupted_stale, stringResource(ShellText.skipReason(warning.reason)))
            },
        ) {
            val busy = warning.profileId in queueing
            val card = cardsById[warning.profileId]
            TextButton(onClick = { actions.openProfile(warning.profileId) }) { Text(stringResource(R.string.shell_open_profile)) }
            if (warning.resumable) {
                TextButton(onClick = { onResume(warning.profileId, warning.profileName, warning.jobId) }, enabled = !busy) {
                    Text(stringResource(R.string.shell_warn_resume))
                }
            } else if (card?.runNow == RunNowAvailability.AVAILABLE && card.profile.enabled) {
                TextButton(onClick = { onBackUpNow(warning.profileId, warning.profileName) }, enabled = !busy) {
                    Text(stringResource(R.string.shell_back_up_now))
                }
            }
        }

        is DashboardWarning.InterruptedJob -> WarningLayout(
            tone = WarningTone.CAUTION,
            icon = Icons.Outlined.History,
            title = stringResource(R.string.shell_warn_job_interrupted_title, stringResource(jobKindLabel(warning.type))),
            body = stringResource(
                if (warning.type == JobType.RESTORE) R.string.shell_warn_restore_interrupted_body else R.string.shell_warn_job_interrupted_body,
            ),
        ) {
            if (warning.type == JobType.RESTORE) {
                TextButton(onClick = actions.openRestore) { Text(stringResource(R.string.shell_action_restore)) }
            }
        }

        is DashboardWarning.LastRunFailed -> WarningLayout(
            tone = WarningTone.ERROR,
            icon = Icons.Outlined.ErrorOutline,
            title = stringResource(R.string.shell_warn_failed_title, warning.profileName),
            body = listOfNotNull(
                warning.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.shell_warn_failed_no_details),
                warning.finishedAtMillis?.let { ShellText.relative(it, nowMillis) },
            ).joinToString("\n"),
        ) {
            val busy = warning.profileId in queueing
            TextButton(onClick = { actions.openProfile(warning.profileId) }) { Text(stringResource(R.string.shell_open_profile)) }
            if (cardsById[warning.profileId]?.runNow == RunNowAvailability.AVAILABLE) {
                TextButton(onClick = { onBackUpNow(warning.profileId, warning.profileName) }, enabled = !busy) {
                    Text(stringResource(R.string.shell_warn_retry))
                }
            }
        }
    }
}

@Composable
private fun WarningLayout(
    tone: WarningTone,
    icon: ImageVector,
    title: String,
    body: String,
    buttons: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        WarningTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
        WarningTone.CAUTION -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        WarningTone.INFO -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                val severity = stringResource(
                    when (tone) {
                        WarningTone.ERROR -> R.string.shell_warn_severity_error
                        WarningTone.CAUTION -> R.string.shell_warn_severity_caution
                        WarningTone.INFO -> R.string.shell_warn_severity_info
                    },
                )
                Icon(icon, contentDescription = severity)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                buttons()
            }
        }
    }
}

private fun jobKindLabel(type: JobType): Int = when (type) {
    JobType.BACKUP -> R.string.job_kind_backup
    JobType.RESTORE -> R.string.job_kind_restore
    JobType.VERIFY -> R.string.job_kind_verify
    JobType.SYNC -> R.string.job_kind_sync
}

// --- Active jobs ---------------------------------------------------------------------------------

@Composable
private fun ActiveJobsCard(jobs: List<ActiveJobCard>, onOpenJob: (String?) -> Unit) {
    SectionCard(title = stringResource(R.string.shell_section_active_jobs), icon = Icons.Outlined.Sync) {
        jobs.forEachIndexed { index, job ->
            if (index > 0) HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = stringResource(R.string.shell_open_job_monitor), role = Role.Button) { onOpenJob(job.progress.jobId) }
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.shell_job_title, stringResource(job.progress.kind.labelRes), job.progress.title),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                JobProgressDetails(job.progress, cancelRequested = job.cancelRequested, detailed = false)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onOpenJob(null) }) { Text(stringResource(R.string.shell_open_job_monitor)) }
        }
    }
}

// --- Quick actions -------------------------------------------------------------------------------

@Composable
private fun QuickActions(actions: DashboardActions) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = actions.newProfile) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.shell_action_new_profile), modifier = Modifier.padding(start = 8.dp))
        }
        FilledTonalButton(onClick = actions.openRestore) {
            Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.shell_action_restore), modifier = Modifier.padding(start = 8.dp))
        }
        FilledTonalButton(onClick = actions.addDestination) {
            Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.shell_action_add_destination), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// --- Profiles ------------------------------------------------------------------------------------

@Composable
private fun ProfilesCard(
    profiles: List<ProfileCard>,
    nowMillis: Long,
    queueing: Set<String>,
    actions: DashboardActions,
    onBackUpNow: (String, String) -> Unit,
) {
    SectionCard(title = stringResource(R.string.shell_section_profiles), icon = Icons.Outlined.Backup) {
        if (profiles.isEmpty()) {
            Text(stringResource(R.string.shell_profiles_empty_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.shell_profiles_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = actions.newProfile) { Text(stringResource(R.string.shell_action_create_profile)) }
            }
            return@SectionCard
        }
        val shown = profiles.take(MAX_PROFILES_SHOWN)
        shown.forEachIndexed { index, card ->
            if (index > 0) HorizontalDivider()
            ProfileRow(
                card = card,
                nowMillis = nowMillis,
                queueing = card.profile.id in queueing,
                onOpen = { actions.openProfile(card.profile.id) },
                onOpenJob = { card.runningJobId?.let { actions.openJob(it) } },
                onBackUpNow = { onBackUpNow(card.profile.id, card.profile.name) },
                onRunWithPassphrase = actions.openProfiles,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val hidden = profiles.size - shown.size
            TextButton(onClick = actions.openProfiles) {
                Text(
                    if (hidden > 0) {
                        pluralStringResource(R.plurals.shell_profiles_show_more, hidden, hidden)
                    } else {
                        stringResource(R.string.shell_profiles_manage)
                    },
                )
            }
        }
    }
}

@Composable
private fun ProfileRow(
    card: ProfileCard,
    nowMillis: Long,
    queueing: Boolean,
    onOpen: () -> Unit,
    onOpenJob: () -> Unit,
    onBackUpNow: () -> Unit,
    onRunWithPassphrase: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.shell_open_profile), role = Role.Button, onClick = onOpen)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(card.profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val statusColor = when (card.lastStatus) {
                LastRunStatus.FAILED -> scheme.error
                LastRunStatus.INTERRUPTED, LastRunStatus.WARNINGS, LastRunStatus.CANCELLED -> scheme.tertiary
                LastRunStatus.SUCCESS, LastRunStatus.RUNNING -> scheme.primary
                LastRunStatus.NEVER -> scheme.onSurfaceVariant
            }
            val last = card.lastBackup
            val statusText = stringResource(ShellText.lastRunLabel(card.lastStatus))
            val lastLine = if (last == null) {
                statusText
            } else {
                listOfNotNull(
                    statusText,
                    ShellText.relative(last.finishedAt ?: last.startedAt, nowMillis),
                    last.artifactSizeBytes?.takeIf { it > 0 }?.let { ShellText.bytes(it) },
                ).joinToString(SEPARATOR)
            }
            Text(lastLine, style = MaterialTheme.typography.bodySmall, color = statusColor)
            Text(
                destinationLine(card),
                style = MaterialTheme.typography.bodySmall,
                color = if (card.destination == null) scheme.error else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            nextRunLine(card, nowMillis)?.let { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        when (card.runNow) {
            RunNowAvailability.AVAILABLE -> OutlinedButton(onClick = onBackUpNow, enabled = !queueing) {
                Text(stringResource(R.string.shell_back_up_now))
            }
            RunNowAvailability.RUNNING -> TextButton(onClick = onOpenJob, enabled = card.runningJobId != null) {
                Text(stringResource(R.string.shell_running))
            }
            RunNowAvailability.NEEDS_PASSPHRASE -> TextButton(onClick = onRunWithPassphrase) {
                Text(stringResource(R.string.shell_needs_passphrase))
            }
            RunNowAvailability.NO_DESTINATION -> TextButton(onClick = onOpen) {
                Text(stringResource(R.string.shell_set_destination))
            }
        }
    }
}

@Composable
private fun destinationLine(card: ProfileCard): String {
    val destination = card.destination
    return when {
        destination != null -> {
            val base = stringResource(R.string.shell_profile_destination, destination.displayName())
            if (card.profile.enabled) base else base + SEPARATOR + stringResource(R.string.shell_profile_disabled)
        }
        card.destinationMissing -> stringResource(R.string.shell_profile_destination_missing)
        else -> stringResource(R.string.shell_profile_no_destination)
    }
}

@Composable
private fun nextRunLine(card: ProfileCard, nowMillis: Long): String? {
    val next = card.nextRun ?: return null
    return when (next.kind) {
        UpcomingKind.RUNNING -> null
        UpcomingKind.QUEUED -> stringResource(R.string.shell_profile_next, stringResource(R.string.shell_upcoming_queued))
        else -> next.atMillis?.let { stringResource(R.string.shell_profile_next, ShellText.relative(it, nowMillis)) }
    }
}

// --- Upcoming ------------------------------------------------------------------------------------

@Composable
private fun UpcomingCard(
    upcoming: List<UpcomingRun>,
    nowMillis: Long,
    onOpenProfile: (String) -> Unit,
    onOpenSchedules: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.shell_section_upcoming), icon = Icons.Outlined.Schedule) {
        if (upcoming.isEmpty()) {
            Text(
                stringResource(R.string.shell_upcoming_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            upcoming.take(MAX_UPCOMING_SHOWN).forEachIndexed { index, run ->
                if (index > 0) HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = stringResource(R.string.shell_open_profile), role = Role.Button) { onOpenProfile(run.profileId) }
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(run.profileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(upcomingWhen(run, nowMillis), style = MaterialTheme.typography.bodyMedium)
                    conditionsLine(run)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpenSchedules) { Text(stringResource(R.string.shell_upcoming_see_schedules)) }
        }
    }
}

@Composable
private fun upcomingWhen(run: UpcomingRun, nowMillis: Long): String {
    val at = run.atMillis
    return when (run.kind) {
        UpcomingKind.QUEUED, UpcomingKind.RUNNING -> stringResource(R.string.shell_upcoming_queued)
        UpcomingKind.SCHEDULED -> if (at == null) {
            stringResource(R.string.shell_upcoming_queued)
        } else {
            stringResource(R.string.shell_upcoming_at, ShellText.dateTime(at), ShellText.relative(at, nowMillis))
        }
        UpcomingKind.RETRY -> if (at == null) {
            stringResource(R.string.shell_upcoming_queued)
        } else {
            stringResource(R.string.shell_upcoming_retry, ShellText.relative(at, nowMillis), run.attempt)
        }
        UpcomingKind.EXPECTED -> if (at == null) {
            stringResource(R.string.shell_upcoming_queued)
        } else {
            stringResource(R.string.shell_upcoming_expected, ShellText.dateTime(at))
        }
    }
}

@Composable
private fun conditionsLine(run: UpcomingRun): String? {
    val parts = ArrayList<String>(3)
    when (run.network) {
        NetworkRequirement.UNMETERED -> parts += stringResource(R.string.shell_constraint_unmetered)
        NetworkRequirement.CONNECTED -> parts += stringResource(R.string.shell_constraint_network)
        NetworkRequirement.NONE -> Unit
    }
    if (run.requiresCharging) parts += stringResource(R.string.shell_constraint_charging)
    if (run.requiresDeviceIdle) parts += stringResource(R.string.shell_constraint_idle)
    return parts.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.shell_constraints, it.joinToString(SEPARATOR)) }
}

// --- Destinations --------------------------------------------------------------------------------

@Composable
private fun DestinationsHealthCard(
    destinations: List<DestinationHealth>,
    nowMillis: Long,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.shell_section_destinations), icon = Icons.Outlined.Storage) {
        if (destinations.isEmpty()) {
            Text(
                stringResource(R.string.shell_destinations_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onAdd) { Text(stringResource(R.string.shell_action_add_destination)) }
            }
            return@SectionCard
        }
        destinations.forEachIndexed { index, health ->
            if (index > 0) HorizontalDivider()
            DestinationHealthRow(health, nowMillis, onOpen)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.shell_destinations_manage)) }
        }
    }
}

@Composable
private fun DestinationHealthRow(health: DestinationHealth, nowMillis: Long, onOpen: () -> Unit) {
    val destination = health.destination
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.shell_destinations_manage), role = Role.Button, onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(destination.type.icon(), contentDescription = null, tint = scheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(destination.displayName(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(destination.type.label(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        val verifiedAt = destination.lastVerifiedAt
        Text(
            when (health.verification) {
                VerificationState.NEVER -> stringResource(R.string.shell_destination_never_verified)
                VerificationState.RECENT -> stringResource(R.string.shell_destination_verified, ShellText.relative(verifiedAt ?: nowMillis, nowMillis))
                VerificationState.STALE -> stringResource(R.string.shell_destination_stale, ShellText.relative(verifiedAt ?: nowMillis, nowMillis))
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (health.verification == VerificationState.RECENT) scheme.onSurfaceVariant else scheme.tertiary,
        )
        val free = destination.lastKnownFreeBytes
        val total = destination.lastKnownTotalBytes
        val used = health.usedFraction
        val barColor: Color = if (health.lowSpace) scheme.error else scheme.primary
        when {
            used != null && free != null && total != null -> {
                LinearProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth(), color = barColor)
                Text(
                    stringResource(R.string.shell_destination_space, ShellText.bytes(free), ShellText.bytes(total)) +
                        if (health.lowSpace) SEPARATOR + stringResource(R.string.shell_destination_low_space) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (health.lowSpace) scheme.error else scheme.onSurface,
                )
            }
            free != null -> Text(
                stringResource(R.string.shell_destination_space_free, ShellText.bytes(free)),
                style = MaterialTheme.typography.bodySmall,
            )
            else -> Text(
                stringResource(R.string.shell_destination_space_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

private val CARD_MIN_WIDTH = 340.dp
private const val MAX_PROFILES_SHOWN = 6
private const val MAX_UPCOMING_SHOWN = 5
