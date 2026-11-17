package ca.devdcs.backup.ui.profiles

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.retention.RetentionDeletion
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.ui.destinations.displayName
import ca.devdcs.backup.ui.discovery.currentLocale
import ca.devdcs.backup.ui.discovery.formatDateTime

/**
 * Schedules & retention across all profiles: when each runs next, its last result, pause/resume,
 * run now, and "apply retention now" with a dry-run preview of exactly which backups would be
 * deleted before anything is touched.
 */
@Composable
fun SchedulesScreen(
    onBack: () -> Unit,
    onEditSchedule: (profileId: String) -> Unit,
    onOpenJobMonitor: (jobId: String?) -> Unit,
    viewModel: SchedulesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is SchedulesEvent.Started) {
                // The user just typed the passphrase for this run: follow it in the job monitor.
                onOpenJobMonitor(event.jobId)
                return@collect
            }
            val (message, offerView) = eventText(context, event)
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (offerView) context.getString(R.string.profiles_action_view_progress) else null,
            )
            if (result == SnackbarResult.ActionPerformed) onOpenJobMonitor(null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profiles_schedules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.profiles_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val items = state.items
        val layoutDirection = LocalLayoutDirection.current
        when {
            items == null -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> Box(Modifier.fillMaxSize().padding(innerPadding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.profiles_schedules_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                val groups = remember(items) { items.groupBy { ScheduleOrdering.groupOf(it) }.toSortedMap() }
                LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns(rememberWidthClass())),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                    end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                groups.forEach { (group, members) ->
                    item(key = "header:$group", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(
                                when (group) {
                                    ScheduleOrdering.GROUP_ACTIVE -> R.string.profiles_schedules_group_active
                                    ScheduleOrdering.GROUP_PAUSED -> R.string.profiles_schedules_group_paused
                                    else -> R.string.profiles_schedules_group_manual
                                },
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(members, key = { it.profile.id }) { item ->
                        ScheduleCard(
                            item = item,
                            busy = item.profile.id in state.busy,
                            retentionBusy = item.profile.id in state.retentionBusy,
                            onToggle = { enabled -> viewModel.setScheduleEnabled(item.profile, enabled) },
                            onEditSchedule = { onEditSchedule(item.profile.id) },
                            onRunNow = { viewModel.runNow(item.profile) },
                            onRetention = { viewModel.previewRetention(item.profile) },
                        )
                    }
                }
            }
            }
        }
    }

    state.passphraseFor?.let { profile ->
        PassphraseDialog(profileName = profile.name, onSubmit = viewModel::submitPassphrase, onDismiss = viewModel::dismissPassphrase)
    }
    state.preview?.let { preview ->
        val destinations = state.items.orEmpty().mapNotNull { it.destination }.associateBy { it.id }
        RetentionPreviewDialog(
            preview = preview,
            destinations = destinations,
            onConfirm = viewModel::confirmRetention,
            onDismiss = viewModel::dismissPreview,
        )
    }
}

private fun eventText(context: Context, event: SchedulesEvent): Pair<String, Boolean> = when (event) {
    is SchedulesEvent.Queued -> context.getString(R.string.profiles_event_queued, event.name) to true
    is SchedulesEvent.Started -> context.getString(R.string.profiles_event_started, event.name) to true
    is SchedulesEvent.AlreadyRunning -> context.getString(R.string.profiles_event_already_running, event.name) to true
    is SchedulesEvent.ScheduleChanged -> context.getString(
        if (event.enabled) R.string.profiles_event_schedule_on else R.string.profiles_event_schedule_off,
        event.name,
    ) to false
    is SchedulesEvent.RetentionApplied -> {
        val deleted = context.resources.getQuantityString(R.plurals.profiles_retention_deleted, event.deleted, event.deleted, event.name)
        val text = if (event.failed > 0) {
            context.getString(
                R.string.profiles_retention_deleted_with_failures,
                deleted,
                context.resources.getQuantityString(R.plurals.profiles_retention_failed, event.failed, event.failed),
            )
        } else {
            deleted
        }
        text to false
    }
    is SchedulesEvent.RunFinished -> {
        val base = context.getString(R.string.profiles_event_finished, event.name, context.getString(event.status.profileLabelRes()))
        (if (event.failure.isNullOrBlank()) base else context.getString(R.string.profiles_event_finished_detail, base, event.failure)) to false
    }
    SchedulesEvent.ActionFailed -> context.getString(R.string.profiles_event_failed) to false
    is SchedulesEvent.StorageFailed -> context.getString(
        R.string.profiles_event_failed_reason,
        context.getString(event.reasonRes),
    ) to false
}

@Composable
private fun ScheduleCard(
    item: ProfileListItem,
    busy: Boolean,
    retentionBusy: Boolean,
    onToggle: (Boolean) -> Unit,
    onEditSchedule: () -> Unit,
    onRunNow: () -> Unit,
    onRetention: () -> Unit,
) {
    val profile = item.profile
    val wording = rememberScheduleWording()
    val locale = currentLocale()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                item.destination?.displayName() ?: stringResource(R.string.profiles_no_destination),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.destination == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ScheduleSummary.describe(
                            schedule = profile.schedule,
                            enabled = profile.enabled,
                            networkDestination = item.destination?.type?.isNetwork == true,
                            wording = wording,
                            firstDayOfWeek = firstDayOfWeek(locale),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    item.nextRunAt?.let { next ->
                        Text(
                            stringResource(R.string.profiles_next_run, formatDateTime(next, locale)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            LastRunLine(item.lastBackup)
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoDelete, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(
                    ScheduleSummary.retention(profile.retention, wording),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (retentionBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (!profile.retention.isUnlimited) {
                    TextButton(onClick = onRetention, enabled = !busy) { Text(stringResource(R.string.profiles_retention_apply)) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (profile.schedule != null) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .toggleable(value = profile.enabled, enabled = !busy, role = Role.Switch, onValueChange = onToggle),
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
                FilledTonalButton(onClick = onRunNow, enabled = !busy && !item.running && profile.destinationId != null) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.profiles_action_run_now), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun RetentionPreviewDialog(
    preview: RetentionPreview,
    destinations: Map<String, Destination>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = currentLocale()
    val report = preview.report
    val deletions = report.plan.deletions
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_retention_preview_title, preview.profile.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preview.changed) {
                    Text(
                        stringResource(R.string.profiles_retention_preview_changed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (deletions.isEmpty()) {
                    Text(stringResource(R.string.profiles_retention_preview_nothing), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        pluralStringResource(R.plurals.profiles_retention_preview_count, deletions.size, deletions.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = PREVIEW_LIST_MAX_HEIGHT), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(deletions) { deletion ->
                            DeletionLine(deletion, destinations, locale)
                        }
                    }
                }
                if (report.plan.kept.isNotEmpty()) {
                    val holds = report.plan.kept.map { it.hold }.distinct().sortedBy { it.ordinal }
                    Text(
                        pluralStringResource(R.plurals.profiles_retention_preview_kept, report.plan.kept.size, report.plan.kept.size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    holds.forEach { hold -> HintLine(stringResource(hold.labelRes())) }
                }
                if (report.unlistedDestinations.isNotEmpty()) {
                    val names = report.unlistedDestinations.map { id -> destinations[id]?.displayName() ?: id }
                    HintLine(stringResource(R.string.profiles_retention_preview_unlisted, names.joinToString(", ")), warning = true)
                }
            }
        },
        confirmButton = {
            if (deletions.isEmpty()) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.profiles_ok)) }
            } else {
                TextButton(onClick = onConfirm) {
                    Text(
                        pluralStringResource(R.plurals.profiles_retention_preview_confirm, deletions.size, deletions.size),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = if (deletions.isEmpty()) {
            null
        } else {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.profiles_cancel)) } }
        },
    )
}

private val PREVIEW_LIST_MAX_HEIGHT = 320.dp

@Composable
private fun DeletionLine(deletion: RetentionDeletion, destinations: Map<String, Destination>, locale: java.util.Locale) {
    val destination = deletion.destinationId?.let(destinations::get)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.profiles_retention_preview_line, formatDateTime(deletion.createdAtMillis, locale), stringResource(deletion.reason.labelRes())),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            when {
                deletion.jobDir == null -> stringResource(R.string.profiles_retention_preview_history_only)
                destination != null -> stringResource(R.string.profiles_retention_preview_where, deletion.jobDir.orEmpty(), destination.displayName())
                else -> deletion.jobDir.orEmpty()
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (deletion.extraFolders.isNotEmpty()) {
            Text(
                stringResource(R.string.profiles_retention_preview_twrp, deletion.extraFolders.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
