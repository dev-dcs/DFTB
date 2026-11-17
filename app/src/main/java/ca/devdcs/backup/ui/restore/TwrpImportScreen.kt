package ca.devdcs.backup.ui.restore

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.restore.twrp.TwrpBackup
import ca.devdcs.backup.core.restore.twrp.TwrpCheckStatus
import ca.devdcs.backup.core.restore.twrp.TwrpFileCheck
import ca.devdcs.backup.core.restore.twrp.TwrpPartitionBackup
import ca.devdcs.backup.core.restore.twrp.TwrpPayloadFormat
import ca.devdcs.backup.ui.components.KeyValueRow
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.FormTextField
import ca.devdcs.backup.ui.destinations.displayName
import ca.devdcs.backup.ui.discovery.NoticeCard
import ca.devdcs.backup.ui.discovery.StatusBadge
import ca.devdcs.backup.ui.discovery.Tone
import ca.devdcs.backup.ui.discovery.formatSize
import ca.devdcs.backup.ui.discovery.sizeText

/**
 * Import of a TWRP / OrangeFox backup folder from a destination: search, verify the checksum
 * sidecars, then per partition either write the raw image (slot choice + each override behind its
 * own confirmation, then a final consequence dialog) or extract the filesystem archive to a folder.
 */
@Composable
fun TwrpImportScreen(
    onBack: () -> Unit,
    viewModel: TwrpImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val twoPane = useTwoPanes()
    val selected = state.selected
    BackHandler(enabled = selected != null && !twoPane) { viewModel.closeBackup() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.restore_ui_twrp_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null && !twoPane) viewModel.closeBackup() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.restore_ui_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (twoPane) {
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { SearchPane(state, viewModel) }
                VerticalDivider()
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (selected != null) {
                        BackupPane(state, selected, viewModel)
                    } else {
                        Text(stringResource(R.string.restore_ui_twrp_pick_backup), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (selected != null) BackupPane(state, selected, viewModel) else SearchPane(state, viewModel)
            }
        }
    }
}

@Composable
private fun SearchPane(state: TwrpImportUiState, viewModel: TwrpImportViewModel) {
    Text(stringResource(R.string.restore_ui_twrp_intro), style = MaterialTheme.typography.bodyMedium)
    val destinations = state.destinations
    when {
        destinations == null -> CircularProgressIndicator()
        destinations.isEmpty() -> Text(stringResource(R.string.restore_ui_no_destinations_body), style = MaterialTheme.typography.bodyMedium)
        else -> {
            DestinationChips(destinations, state.destinationId, onSelect = viewModel::selectDestination)
            FormTextField(
                value = state.searchRoot,
                onValueChange = viewModel::setSearchRoot,
                label = stringResource(R.string.restore_ui_twrp_root_label),
                hint = stringResource(R.string.restore_ui_twrp_root_hint),
            )
            Button(onClick = { viewModel.search() }, enabled = !state.searching && state.destinationId != null) {
                Icon(Icons.Outlined.Search, contentDescription = null)
                Text(stringResource(R.string.restore_ui_twrp_search), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    if (state.searching) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.restore_ui_twrp_searching), style = MaterialTheme.typography.bodyMedium)
        }
    }
    state.scanFailure?.let { failure ->
        NoticeCard(title = stringResource(R.string.restore_ui_list_failed), body = failure.text(), tone = Tone.ERROR, icon = Icons.Outlined.ErrorOutline)
    }
    val scan = state.scan ?: return
    scan.error?.let { NoticeCard(title = stringResource(R.string.restore_ui_twrp_search_incomplete), body = it, tone = Tone.WARNING, icon = Icons.Outlined.WarningAmber) }
    if (scan.backups.isEmpty() && scan.error == null) {
        Text(stringResource(R.string.restore_ui_twrp_none_found), style = MaterialTheme.typography.bodyMedium)
    }
    scan.backups.forEach { backup -> TwrpBackupCard(backup, selected = backup.folderPath == state.selected?.folderPath, onOpen = { viewModel.openBackup(backup.folderPath) }) }
}

@Composable
private fun TwrpBackupCard(backup: TwrpBackup, selected: Boolean, onOpen: () -> Unit) {
    val context = LocalContext.current
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(backup.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (selected) StatusBadge(stringResource(R.string.restore_ui_twrp_selected), Tone.INFO)
            }
            Text(backup.folderPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val total = backup.partitions.mapNotNull { it.totalBytes }.sum()
            Text(
                pluralStringResource(R.plurals.restore_ui_twrp_partitions, backup.partitions.size, backup.partitions.size, formatSize(context, total)),
                style = MaterialTheme.typography.bodySmall,
            )
            if (backup.problems.isNotEmpty()) StatusBadge(stringResource(R.string.restore_ui_twrp_has_problems), Tone.WARNING)
        }
    }
}

@Composable
private fun BackupPane(state: TwrpImportUiState, backup: TwrpBackup, viewModel: TwrpImportViewModel) {
    val task = state.task
    if (task != null) TwrpTaskPanel(task, onCancel = viewModel::cancelTask, onDismiss = viewModel::dismissTask)
    SectionCard(title = backup.name, icon = Icons.Outlined.Inventory2, subtitle = backup.folderPath) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyValueRow(
                stringResource(R.string.restore_ui_twrp_device_folder),
                backup.deviceId ?: stringResource(R.string.restore_ui_twrp_device_unknown),
            )
            if (backup.deviceId == null) {
                Text(stringResource(R.string.restore_ui_twrp_device_unknown_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            backup.problems.forEach { problem ->
                Text(
                    stringResource(problem.kind.labelRes(), problem.subject),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
    TwrpVerifySection(state.verify, enabled = state.destination != null, onVerify = viewModel::verify, onCancel = viewModel::cancelVerify)
    SectionCard(title = stringResource(R.string.restore_ui_twrp_partitions_title), icon = Icons.Outlined.Memory) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            backup.partitions.forEachIndexed { index, partition ->
                if (index > 0) HorizontalDivider()
                TwrpPartitionRow(
                    partition = partition,
                    format = state.formats[partition.backupName],
                    sniffed = state.formats.containsKey(partition.backupName),
                    checks = state.verify.checks?.let { TwrpImportRules.checksOf(it, partition) },
                    enabled = !state.taskActive,
                    onAction = { action -> viewModel.startDraft(partition, action) },
                )
            }
        }
    }
    val draft = state.draft
    val draftPartition = state.draftPartition
    if (draft != null && draftPartition != null && draft.folderPath == backup.folderPath) {
        TwrpDraftPanel(state, draft, draftPartition, viewModel)
    }
}

@Composable
private fun TwrpVerifySection(verify: TwrpVerifyUiState, enabled: Boolean, onVerify: () -> Unit, onCancel: () -> Unit) {
    SectionCard(title = stringResource(R.string.restore_ui_twrp_verify_title), icon = Icons.Outlined.VerifiedUser, subtitle = stringResource(R.string.restore_ui_twrp_verify_subtitle)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (verify.running) {
                BytesProgress(verify.doneBytes, verify.totalBytes)
                TextButton(onClick = onCancel) { Text(stringResource(R.string.restore_ui_cancel)) }
            } else {
                OutlinedButton(onClick = onVerify, enabled = enabled) { Text(stringResource(R.string.restore_ui_twrp_verify)) }
            }
            verify.failure?.let { Text(it.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            verify.summary?.let { summary ->
                Text(
                    stringResource(R.string.restore_ui_twrp_verify_summary, summary.ok, summary.total, summary.mismatched, summary.missingSidecar, summary.unreadable),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (summary.damaged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                if (summary.damaged) {
                    Text(stringResource(R.string.restore_ui_twrp_verify_damaged), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TwrpPartitionRow(
    partition: TwrpPartitionBackup,
    format: TwrpPayloadFormat?,
    sniffed: Boolean,
    checks: List<TwrpFileCheck>?,
    enabled: Boolean,
    onAction: (TwrpAction) -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(partition.backupName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            partition.totalBytes?.let { Text(formatSize(context, it), style = MaterialTheme.typography.labelMedium) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusBadge(stringResource(partition.kind.labelRes()), Tone.NEUTRAL)
            StatusBadge(partition.fileSystem, Tone.NEUTRAL)
            if (partition.isSplit) StatusBadge(pluralStringResource(R.plurals.restore_ui_twrp_parts, partition.parts.size, partition.parts.size), Tone.NEUTRAL)
            if (partition.parts.any { it.sidecar == null }) StatusBadge(stringResource(R.string.restore_ui_twrp_no_sidecar), Tone.WARNING)
            when {
                !sniffed -> Unit
                format == null -> StatusBadge(stringResource(R.string.restore_ui_twrp_format_unreadable), Tone.WARNING)
                else -> StatusBadge(stringResource(format.labelRes()), if (format == TwrpPayloadFormat.ENCRYPTED) Tone.ERROR else Tone.INFO)
            }
            if (TwrpImportRules.isCriticalIdentity(partition.partitionName)) StatusBadge(stringResource(R.string.restore_ui_badge_critical), Tone.ERROR)
        }
        checks?.forEach { check ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (check.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    contentDescription = stringResource(check.status.labelRes()),
                    tint = when (check.status) {
                        TwrpCheckStatus.OK -> MaterialTheme.colorScheme.primary
                        TwrpCheckStatus.NO_SIDECAR -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    withDetail("${check.file.fileName}: ${stringResource(check.status.labelRes())}", check.detail),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TwrpImportRules.actionsFor(partition).forEach { action ->
                TextButton(onClick = { onAction(action) }, enabled = enabled && format != TwrpPayloadFormat.ENCRYPTED) {
                    Icon(if (action == TwrpAction.RESTORE_IMAGE) Icons.Outlined.Memory else Icons.Outlined.Unarchive, contentDescription = null)
                    Text(stringResource(action.labelRes()), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun TwrpDraftPanel(state: TwrpImportUiState, draft: TwrpActionDraft, partition: TwrpPartitionBackup, viewModel: TwrpImportViewModel) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val problems = state.draftProblems
    SectionCard(
        title = stringResource(draft.action.labelRes()),
        icon = if (draft.action == TwrpAction.RESTORE_IMAGE) Icons.Outlined.Memory else Icons.Outlined.FolderOpen,
        subtitle = partition.backupName,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (draft.action) {
                TwrpAction.RESTORE_IMAGE -> {
                    if (!state.rootAvailable) {
                        NoticeCard(
                            title = stringResource(R.string.restore_ui_partitions_need_root),
                            body = stringResource(R.string.restore_ui_partitions_need_root_body),
                            tone = Tone.WARNING,
                            icon = Icons.Outlined.Shield,
                        )
                    }
                    FormTextField(
                        value = draft.targetPartition,
                        onValueChange = { value -> viewModel.editDraft { it.copy(targetPartition = value) } },
                        label = stringResource(R.string.restore_ui_twrp_target_partition),
                        placeholder = partition.partitionName,
                        hint = stringResource(R.string.restore_ui_twrp_target_partition_hint, partition.partitionName),
                        error = if (TwrpDraftProblem.INVALID_PARTITION_NAME in problems) stringResource(TwrpDraftProblem.INVALID_PARTITION_NAME.messageRes()) else null,
                    )
                    val target = TwrpImportRules.targetPartition(draft, partition)
                    val critical = TwrpImportRules.isCriticalIdentity(target)
                    if (critical) {
                        NoticeCard(
                            title = stringResource(R.string.restore_ui_critical_selected_title),
                            body = stringResource(R.string.restore_ui_critical_selected_body, target),
                            tone = Tone.ERROR,
                            icon = Icons.Outlined.WarningAmber,
                        )
                    }
                    Text(stringResource(R.string.restore_ui_twrp_device_check_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PartitionOverridesEditor(
                        overrides = draft.overrides,
                        onChange = { next -> viewModel.editDraft { it.copy(overrides = next) } },
                        criticalNames = if (critical) listOf(target) else emptyList(),
                        showBuildOverride = false,
                    )
                }
                TwrpAction.EXTRACT_FILES -> {
                    Text(stringResource(R.string.restore_ui_twrp_extract_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DestinationChips(
                        destinations = state.destinations.orEmpty(),
                        selectedId = draft.extractDestinationId,
                        onSelect = { id -> viewModel.editDraft { it.copy(extractDestinationId = id) } },
                        label = stringResource(R.string.restore_ui_twrp_extract_destination),
                    )
                    FormTextField(
                        value = draft.extractFolder,
                        onValueChange = { value -> viewModel.editDraft { it.copy(extractFolder = value) } },
                        label = stringResource(R.string.restore_ui_twrp_extract_folder),
                        hint = stringResource(R.string.restore_ui_twrp_extract_folder_hint),
                        error = if (TwrpDraftProblem.FOLDER_TRAVERSAL in problems) stringResource(TwrpDraftProblem.FOLDER_TRAVERSAL.messageRes()) else null,
                    )
                }
            }
            problems.filter { it != TwrpDraftProblem.INVALID_PARTITION_NAME && it != TwrpDraftProblem.FOLDER_TRAVERSAL }.forEach { problem ->
                Text(stringResource(problem.messageRes()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = viewModel::cancelDraft) { Text(stringResource(R.string.restore_ui_cancel)) }
                val canRun = problems.isEmpty() && !state.taskActive && (draft.action != TwrpAction.RESTORE_IMAGE || state.rootAvailable)
                Button(onClick = { confirming = true }, enabled = canRun) { Text(stringResource(draft.action.labelRes())) }
            }
        }
    }
    if (confirming) {
        val folder = TwrpImportRules.normalizeFolder(draft.extractFolder)
        val targetName = state.destinations?.firstOrNull { it.id == draft.extractDestinationId }?.displayName().orEmpty()
        val (title, body) = when (draft.action) {
            TwrpAction.RESTORE_IMAGE -> stringResource(R.string.restore_ui_twrp_confirm_restore_title) to
                stringResource(R.string.restore_ui_twrp_confirm_restore_body, partition.parts.first().fileName, TwrpImportRules.targetPartition(draft, partition))
            TwrpAction.EXTRACT_FILES -> stringResource(R.string.restore_ui_twrp_confirm_extract_title) to
                stringResource(R.string.restore_ui_twrp_confirm_extract_body, partition.backupName, targetName, folder)
        }
        val overrides = RiskyOption.entries.filter { it.partition && draft.overrides.isOn(it) }.map { stringResource(it.titleRes()) }
        ConsequenceDialog(
            title = title,
            consequence = body,
            confirmLabel = stringResource(draft.action.labelRes()),
            details = if (draft.action == TwrpAction.RESTORE_IMAGE) overrides else emptyList(),
            onConfirm = {
                confirming = false
                viewModel.runDraft()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun TwrpTaskPanel(task: TwrpTaskState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    var confirmingCancel by rememberSaveable(task.id) { mutableStateOf(false) }
    if (task.writingDevice) DoNotRebootBanner(writingNow = true)
    if (task.doNotReboot) DoNotRebootBanner(writingNow = false)
    SectionCard(
        title = stringResource(task.action.labelRes()),
        icon = if (task.action == TwrpAction.RESTORE_IMAGE) Icons.Outlined.Memory else Icons.Outlined.Unarchive,
        subtitle = stringResource(R.string.restore_ui_twrp_task_target, task.backupName, task.target),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!task.finished) {
                BytesProgress(task.processedBytes, task.totalBytes)
                task.currentItem?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                if (task.bytesPerSecond > 0) {
                    Text(stringResource(R.string.restore_ui_progress_speed, sizeText(task.bytesPerSecond)), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val (label, color) = when {
                    task.cancelled -> stringResource(R.string.restore_ui_run_cancelled) to MaterialTheme.colorScheme.onSurface
                    task.ok == true -> stringResource(R.string.restore_ui_run_completed) to MaterialTheme.colorScheme.primary
                    else -> stringResource(R.string.restore_ui_run_failed) to MaterialTheme.colorScheme.error
                }
                Text(label, style = MaterialTheme.typography.titleSmall, color = color)
                (task.errorFailure?.takeIf { it !is OpenFailure.Other }?.text() ?: task.error)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                task.extract?.let { result ->
                    Text(
                        stringResource(R.string.restore_ui_twrp_extract_result, result.filesWritten, result.directoriesCreated, sizeText(result.bytesWritten)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            task.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                if (task.finished) {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.restore_ui_run_done)) }
                } else {
                    OutlinedButton(onClick = { confirmingCancel = true }, enabled = !task.cancelRequested) {
                        Text(stringResource(if (task.cancelRequested) R.string.restore_ui_run_cancelling else R.string.restore_ui_run_cancel))
                    }
                }
            }
        }
    }
    if (confirmingCancel) {
        ConsequenceDialog(
            title = stringResource(R.string.restore_ui_cancel_title),
            consequence = stringResource(R.string.restore_ui_cancel_body),
            confirmLabel = stringResource(R.string.restore_ui_run_cancel),
            onConfirm = {
                confirmingCancel = false
                onCancel()
            },
            onDismiss = { confirmingCancel = false },
        )
    }
}
