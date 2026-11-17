package ca.devdcs.backup.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.LogLevel
import ca.devdcs.backup.core.logging.LogLine
import ca.devdcs.backup.core.progress.JobKind
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.ui.components.KeyValueRow
import ca.devdcs.backup.ui.licenses.ExternalLinks
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Live view of one job (or all jobs): phase, current item, bytes, rate and ETA, cancel with an
 * explicit confirmation, and a scrolling monospace terminal of the in-app log scoped to the job,
 * with follow / copy / share. All state lives in [JobMonitorViewModel] or saveable state, so it
 * survives rotation and process death (the route carries the job id).
 */
@Composable
fun JobMonitorScreen(onBack: () -> Unit, viewModel: JobMonitorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = LocalActivity.current
    var follow by rememberSaveable { mutableStateOf(true) }
    var pendingCancel by rememberSaveable { mutableStateOf<String?>(null) }

    val focus = state.focusJob
    val title = when {
        focus != null -> stringResource(R.string.shell_job_title, stringResource(focus.progress.kind.labelRes), focus.progress.title)
        state.focusJobId != null -> stringResource(R.string.shell_monitor_title)
        else -> stringResource(R.string.shell_monitor_title_all)
    }
    val header = when (val id = state.logJobId) {
        null -> stringResource(R.string.shell_log_header_all)
        else -> stringResource(R.string.shell_log_header_job, state.jobs.firstOrNull { it.progress.jobId == id }?.progress?.title ?: id, id)
    }
    val copiedMessage = stringResource(R.string.shell_terminal_copied)
    val shareSubject = stringResource(R.string.shell_terminal_share_subject, header)
    val shareTitle = stringResource(R.string.shell_terminal_share)
    val shareFailed = stringResource(R.string.shell_terminal_share_failed)
    val clipLabel = stringResource(R.string.shell_terminal_title)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.shell_back))
                    }
                },
                actions = {
                    val followState = stringResource(if (follow) R.string.shell_state_on else R.string.shell_state_off)
                    IconToggleButton(
                        checked = follow,
                        onCheckedChange = { follow = it },
                        modifier = Modifier.semantics { stateDescription = followState },
                    ) {
                        Icon(Icons.Outlined.VerticalAlignBottom, contentDescription = stringResource(R.string.shell_terminal_follow))
                    }
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText(clipLabel, viewModel.clipboardText(header)))
                            // Android 13+ confirms copies itself; a second message would be noise.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            }
                        },
                        enabled = state.lines.isNotEmpty(),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.shell_terminal_copy))
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                val send = viewModel.shareIntent(header, shareSubject)
                                if (!ExternalLinks.launch(activity ?: context, Intent.createChooser(send, shareTitle))) {
                                    snackbarHostState.showSnackbar(shareFailed)
                                }
                            }
                        },
                        enabled = state.lines.isNotEmpty(),
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = shareTitle)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH
            val panelMaxHeight = maxHeight * COMPACT_JOBS_FRACTION
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    JobsPanel(state.jobs, state.focusJobId, state.focusJob, state.focusRecord, state.logJobId, state.loaded, viewModel::selectLogJob, { pendingCancel = it }, Modifier.weight(0.42f))
                    TerminalPanel(state.lines, state.logJobId, state.minLevel, state.focusJobId, follow, { follow = it }, viewModel::selectLogJob, viewModel::setMinLevel, Modifier.weight(0.58f))
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    JobsPanel(state.jobs, state.focusJobId, state.focusJob, state.focusRecord, state.logJobId, state.loaded, viewModel::selectLogJob, { pendingCancel = it }, Modifier.heightIn(max = panelMaxHeight))
                    TerminalPanel(state.lines, state.logJobId, state.minLevel, state.focusJobId, follow, { follow = it }, viewModel::selectLogJob, viewModel::setMinLevel, Modifier.weight(1f))
                }
            }
        }
    }

    val cancelId = pendingCancel
    val latestState by rememberUpdatedState(state)
    val cancelJobState = remember(cancelId) {
        derivedStateOf { latestState.jobs.firstOrNull { it.progress.jobId == cancelId && it.active } }
    }
    val cancelJob = cancelJobState.value
    if (cancelId != null && cancelJob == null) {
        // The job ended while the dialog was open; nothing left to cancel.
        LaunchedEffect(cancelId) { pendingCancel = null }
    }
    if (cancelJob != null) {
        val kind = cancelJob.progress.kind
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            title = {
                Text(
                    stringResource(
                        when (kind) {
                            JobKind.BACKUP -> R.string.shell_cancel_backup_title
                            JobKind.RESTORE -> R.string.shell_cancel_restore_title
                            JobKind.VERIFY, JobKind.SYNC -> R.string.shell_cancel_job_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        when (kind) {
                            JobKind.BACKUP -> R.string.shell_cancel_backup_body
                            JobKind.RESTORE -> R.string.shell_cancel_restore_body
                            JobKind.VERIFY, JobKind.SYNC -> R.string.shell_cancel_job_body
                        },
                        cancelJob.progress.title,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingCancel = null
                    viewModel.cancel(cancelJob.progress.jobId)
                }) {
                    Text(stringResource(R.string.shell_cancel_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCancel = null }) { Text(stringResource(R.string.shell_cancel_keep)) }
            },
        )
    }
}

// --- Jobs ----------------------------------------------------------------------------------------

@Composable
private fun JobsPanel(
    jobs: List<MonitoredJob>,
    focusJobId: String?,
    focusJob: MonitoredJob?,
    focusRecord: HistoryRecord?,
    logJobId: String?,
    loaded: Boolean,
    onSelectLogJob: (String?) -> Unit,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (focusJobId != null) {
            val focus = focusJob
            val record = focusRecord
            when {
                focus != null -> JobCard(focus, selected = false, onClick = null, onCancel = onCancel)
                record != null -> FinishedRecordCard(record)
                !loaded -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    val loadingLabel = stringResource(R.string.shell_loading)
                    CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = loadingLabel })
                }
                else -> EmptyText(stringResource(R.string.shell_monitor_job_gone))
            }
        } else if (jobs.isEmpty()) {
            EmptyText(stringResource(R.string.shell_monitor_no_jobs))
        } else {
            jobs.forEach { job ->
                val selected = logJobId == job.progress.jobId
                JobCard(
                    job = job,
                    selected = selected,
                    onClick = { onSelectLogJob(if (selected) null else job.progress.jobId) },
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun JobCard(job: MonitoredJob, selected: Boolean, onClick: (() -> Unit)?, onCancel: (String) -> Unit) {
    val progress = job.progress
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    val selectedLabel = stringResource(R.string.shell_monitor_job_selected)
                    Modifier
                        .selectable(selected = selected, role = Role.Button, onClick = onClick)
                        .semantics {
                            if (selected) stateDescription = selectedLabel
                        }
                } else {
                    Modifier
                },
            ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.shell_job_title, stringResource(progress.kind.labelRes), progress.title),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            JobProgressDetails(progress, cancelRequested = job.cancelRequested, detailed = true)
            if (job.active) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(
                        onClick = { onCancel(progress.jobId) },
                        enabled = !job.cancelRequested,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.shell_monitor_cancel), color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                Text(
                    stringResource(R.string.shell_monitor_finished, ShellText.dateTime(progress.updatedAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A job that ended before the monitor opened (or before the process restarted): its History row. */
@Composable
private fun FinishedRecordCard(record: HistoryRecord) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(ShellText.jobStatusLabel(record.status)), style = MaterialTheme.typography.titleMedium)
            KeyValueRow(stringResource(R.string.shell_record_started), ShellText.dateTime(record.startedAt))
            record.finishedAt?.let { KeyValueRow(stringResource(R.string.shell_record_finished), ShellText.dateTime(it)) }
            record.durationMillis?.let { KeyValueRow(stringResource(R.string.shell_record_duration), ShellText.duration(it / 1_000)) }
            if (record.bytesProcessed > 0) KeyValueRow(stringResource(R.string.shell_record_processed), ShellText.bytes(record.bytesProcessed))
            if (record.itemCount > 0) KeyValueRow(stringResource(R.string.shell_record_items), record.itemCount.toString())
            record.artifactSizeBytes?.takeIf { it > 0 }?.let { KeyValueRow(stringResource(R.string.shell_record_stored), ShellText.bytes(it)) }
            val average = record.durationMillis?.let { JobFormatting.averageBytesPerSecond(record.bytesProcessed, it) }
            average?.let { KeyValueRow(stringResource(R.string.shell_record_average_rate), ShellText.rate(it)) }
            record.errorMessage?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// --- Terminal ------------------------------------------------------------------------------------

@Composable
private fun TerminalPanel(
    lines: List<LogLine>,
    logJobId: String?,
    minLevel: LogLevel,
    focusJobId: String?,
    follow: Boolean,
    onFollowChange: (Boolean) -> Unit,
    onSelectLogJob: (String?) -> Unit,
    onMinLevel: (LogLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val zone = remember { ZoneId.systemDefault() }

    LaunchedEffect(lines.lastOrNull(), lines.size, follow) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    LaunchedEffect(listState) {
        // A manual drag means the user is reading back: stop pulling the view to the bottom.
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) onFollowChange(false)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.shell_terminal_title), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val scopedId = focusJobId
                if (scopedId != null) {
                    FilterChip(
                        selected = logJobId == scopedId,
                        onClick = { onSelectLogJob(scopedId) },
                        label = { Text(stringResource(R.string.shell_terminal_filter_job)) },
                    )
                    FilterChip(
                        selected = logJobId == null,
                        onClick = { onSelectLogJob(null) },
                        label = { Text(stringResource(R.string.shell_terminal_filter_all)) },
                    )
                }
                LEVEL_CHOICES.forEach { level ->
                    FilterChip(
                        selected = minLevel == level,
                        onClick = { onMinLevel(level) },
                        label = { Text(stringResource(ShellText.levelLabel(level))) },
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (lines.isEmpty()) {
                    Text(
                        stringResource(R.string.shell_terminal_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 56.dp),
                        ) {
                            items(lines, key = { it.seq }) { line -> TerminalLine(line, zone) }
                        }
                    }
                }
                if (!follow && lines.isNotEmpty()) {
                    SmallFloatingActionButton(
                        onClick = { onFollowChange(true) },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    ) {
                        Icon(Icons.Outlined.VerticalAlignBottom, contentDescription = stringResource(R.string.shell_terminal_jump))
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalLine(line: LogLine, zone: ZoneId) {
    val scheme = MaterialTheme.colorScheme
    val color = when (line.level) {
        LogLevel.ERROR -> scheme.error
        LogLevel.WARN -> scheme.tertiary
        LogLevel.INFO -> scheme.onSurface
        LogLevel.DEBUG, LogLevel.VERBOSE -> scheme.onSurfaceVariant
    }
    Text(
        text = JobLogFilter.render(line, zone),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
    )
}

private val LEVEL_CHOICES = listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN)
private val WIDE_LAYOUT_MIN_WIDTH = 840.dp
private const val COMPACT_JOBS_FRACTION = 0.45f
