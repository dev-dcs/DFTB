package ca.devdcs.backup.ui.profiles

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.ui.destinations.displayName
import ca.devdcs.backup.ui.destinations.icon
import ca.devdcs.backup.ui.discovery.StatusBadge
import ca.devdcs.backup.ui.discovery.Tone
import ca.devdcs.backup.ui.discovery.currentLocale
import ca.devdcs.backup.ui.discovery.formatDateTime

/**
 * Backup profiles list (top-level, so no up button and no bottom bar of its own): what each profile
 * backs up, where to, its schedule and last result, and the per-profile actions (back up now, edit,
 * duplicate, delete, pause/resume schedule). Entry to the wizard and to the schedules overview.
 */
@Composable
fun ProfilesScreen(
    onCreate: () -> Unit,
    onEdit: (profileId: String, step: WizardStep) -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenJobMonitor: (jobId: String?) -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is ProfilesEvent.Started) {
                // The user just typed the passphrase for this run: follow it in the job monitor.
                onOpenJobMonitor(event.jobId)
                return@collect
            }
            val (message, offerView) = when (event) {
                is ProfilesEvent.Queued -> context.getString(R.string.profiles_event_queued, event.name) to true
                is ProfilesEvent.ResumeQueued -> context.getString(R.string.profiles_event_resume_queued, event.name) to true
                is ProfilesEvent.Started -> context.getString(R.string.profiles_event_started, event.name) to true
                is ProfilesEvent.AlreadyRunning -> context.getString(R.string.profiles_event_already_running, event.name) to true
                is ProfilesEvent.Duplicated -> context.getString(R.string.profiles_event_duplicated, event.name) to false
                is ProfilesEvent.Deleted -> context.getString(R.string.profiles_event_deleted, event.name) to false
                is ProfilesEvent.ScheduleChanged -> context.getString(
                    if (event.enabled) R.string.profiles_event_schedule_on else R.string.profiles_event_schedule_off,
                    event.name,
                ) to false
                is ProfilesEvent.RunFinished -> runFinishedText(context, event.name, event.status, event.failure) to false
                ProfilesEvent.ActionFailed -> context.getString(R.string.profiles_event_failed) to false
                is ProfilesEvent.StorageFailed -> context.getString(
                    R.string.profiles_event_failed_reason,
                    context.getString(event.reasonRes),
                ) to false
            }
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (offerView) context.getString(R.string.profiles_action_view_progress) else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onOpenJobMonitor(null)
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profiles_title)) },
                actions = {
                    IconButton(onClick = onOpenSchedules) {
                        Icon(Icons.Outlined.Schedule, contentDescription = stringResource(R.string.profiles_open_schedules))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.profiles_new)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val items = state.items
        val layoutDirection = LocalLayoutDirection.current
        when {
            items == null -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize().padding(innerPadding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.profiles_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.profiles_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns(rememberWidthClass())),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                    end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.profile.id }) { item ->
                    ProfileCard(
                        item = item,
                        busy = item.profile.id in state.busy,
                        onRunNow = { viewModel.runNow(item.profile) },
                        onResume = { viewModel.resume(item) },
                        onEdit = { onEdit(item.profile.id, WizardStep.NAME) },
                        onEditSchedule = { onEdit(item.profile.id, WizardStep.SCHEDULE) },
                        onDuplicate = {
                            viewModel.duplicate(item.profile, context.getString(R.string.profiles_copy_name, item.profile.name))
                        },
                        onDelete = { viewModel.requestDelete(item.profile) },
                        onScheduleEnabled = { enabled -> viewModel.setScheduleEnabled(item.profile, enabled) },
                        onViewProgress = { onOpenJobMonitor(item.lastBackup?.id) },
                    )
                }
            }
        }
    }

    state.passphraseRequest?.let { request ->
        PassphraseDialog(
            profileName = request.profile.name,
            resume = request.resume != null,
            onSubmit = viewModel::submitPassphrase,
            onDismiss = viewModel::dismissPassphrase,
        )
    }
    state.pendingDelete?.let { profile ->
        DeleteProfileDialog(
            profile = profile,
            destinationName = state.items?.firstOrNull { it.profile.id == profile.id }?.destination?.displayName(),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

private fun runFinishedText(context: android.content.Context, name: String, status: JobStatus, failure: String?): String {
    val base = context.getString(R.string.profiles_event_finished, name, context.getString(status.profileLabelRes()))
    return if (failure.isNullOrBlank()) base else context.getString(R.string.profiles_event_finished_detail, base, failure)
}

@Composable
private fun DeleteProfileDialog(profile: BackupProfile, destinationName: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.profiles_delete_title, profile.name),
        consequence = stringResource(R.string.profiles_delete_consequence, profile.name),
        confirmLabel = stringResource(R.string.profiles_delete_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        extra = {
            Text(
                if (destinationName != null) {
                    stringResource(R.string.profiles_delete_backups_kept, destinationName)
                } else {
                    stringResource(R.string.profiles_delete_backups_kept_generic)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (profile.pipeline.isEncrypted && profile.pipeline.encryptionKeyRef != null) {
                Text(
                    stringResource(R.string.profiles_delete_key_kept),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun ProfileCard(
    item: ProfileListItem,
    busy: Boolean,
    onRunNow: () -> Unit,
    onResume: () -> Unit,
    onEdit: () -> Unit,
    onEditSchedule: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onScheduleEnabled: (Boolean) -> Unit,
    onViewProgress: () -> Unit,
) {
    val profile = item.profile
    val locale = currentLocale()
    val wording = rememberScheduleWording()
    var menuOpen by rememberSaveable(profile.id) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (profile.description.isNotBlank()) {
                        Text(
                            profile.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.profiles_more_actions, profile.name))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profiles_action_edit)) },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profiles_action_duplicate)) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            enabled = !busy,
                            onClick = {
                                menuOpen = false
                                onDuplicate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profiles_action_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            enabled = !busy && !item.running,
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (profile.pipeline.isEncrypted) StatusBadge(stringResource(R.string.profiles_badge_encrypted), Tone.INFO)
                if (profile.pipeline.twrpCompatible) StatusBadge(stringResource(R.string.profiles_badge_twrp), Tone.INFO)
                if (profile.schedule != null && !profile.enabled) StatusBadge(stringResource(R.string.profiles_badge_paused), Tone.WARNING)
                if (item.needsPassphrase) StatusBadge(stringResource(R.string.profiles_badge_needs_passphrase), Tone.WARNING)
            }

            InfoRow(Icons.Outlined.Inventory2, targetsSummary(profile.targets))
            val destination = item.destination
            if (destination != null) {
                InfoRow(destination.type.icon(), destination.displayName())
            } else {
                InfoRow(Icons.Outlined.ErrorOutline, stringResource(R.string.profiles_no_destination), error = true)
            }
            InfoRow(
                Icons.Outlined.Schedule,
                ScheduleSummary.describe(
                    schedule = profile.schedule,
                    enabled = profile.enabled,
                    networkDestination = destination?.type?.isNetwork == true,
                    wording = wording,
                    firstDayOfWeek = firstDayOfWeek(locale),
                ),
            )
            item.nextRunAt?.let { next ->
                Text(
                    stringResource(R.string.profiles_next_run, formatDateTime(next, locale)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LastRunLine(item.lastBackup)
            if (item.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (item.needsPassphrase && profile.isScheduled) {
                HintLine(stringResource(R.string.profiles_hint_scheduled_passphrase), warning = true)
            }
            if (item.resume != null && !item.running) {
                ResumeRow(needsPassphrase = item.needsPassphrase, busy = busy, onResume = onResume)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (profile.schedule != null) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .toggleable(value = profile.enabled, enabled = !busy, role = Role.Switch, onValueChange = onScheduleEnabled),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Switch(checked = profile.enabled, onCheckedChange = null, enabled = !busy)
                        Text(
                            stringResource(if (profile.enabled) R.string.profiles_schedule_on else R.string.profiles_schedule_off),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                } else {
                    TextButton(onClick = onEditSchedule, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.profiles_add_schedule), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (item.running) {
                    TextButton(onClick = onViewProgress) { Text(stringResource(R.string.profiles_action_view_progress)) }
                } else {
                    FilledTonalButton(onClick = onRunNow, enabled = !busy && profile.destinationId != null) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Text(stringResource(R.string.profiles_action_run_now), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** An interrupted backup of the profile can be continued where it stopped. */
@Composable
private fun ResumeRow(needsPassphrase: Boolean, busy: Boolean, onResume: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(if (needsPassphrase) R.string.profiles_resume_needs_passphrase else R.string.profiles_resume_available),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResume, enabled = !busy) { Text(stringResource(R.string.profiles_resume_action)) }
        }
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, error: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
