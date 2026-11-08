package ca.devdcs.backup.ui.restore

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.ExpandableCard
import ca.devdcs.backup.ui.discovery.NoticeCard
import ca.devdcs.backup.ui.discovery.Tone

/**
 * Live view of one restore run: progress, the "do not reboot" banners, pending install prompts
 * (shown with an Open button only; the app shell launches prompts itself, so nothing here starts
 * them automatically), per-unit results, warnings, and cancel (confirmed) or dismiss.
 */
@Composable
fun RestoreRunPanel(
    run: RestoreRunState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpenIntent: (Intent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingCancel by rememberSaveable(run.jobId) { mutableStateOf(false) }
    val summary = remember(run.units, run.started, run.results, run.preflight, run.warnings, run.outcome, run.cancelRequested, run.crashDetail) { RunSummarizer.summarize(run) }
    val headline = remember(run.units, run.started, run.results, run.preflight, run.warnings, run.outcome, run.cancelRequested, run.crashDetail) { RunSummarizer.headline(run) }
    val writingPartition = remember(run.units, run.started, run.results, run.preflight, run.warnings, run.outcome, run.cancelRequested, run.crashDetail) { RunSummarizer.writingPartition(run) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (writingPartition) DoNotRebootBanner(writingNow = true)
        if (summary.doNotReboot) DoNotRebootBanner(writingNow = false)
        SectionCard(
            title = stringResource(R.string.restore_ui_run_title),
            icon = Icons.Outlined.Restore,
            subtitle = stringResource(headline.labelRes()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                run.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (!run.finished) JobProgressBlock(run.progress)
                run.outcome?.failure?.let { failure ->
                    Text(
                        withDetail(stringResource(failure.code.messageRes()), failure.detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                run.crashDetail?.let {
                    val typed = run.crashFailure?.takeIf { f -> f !is OpenFailure.Other }
                    Text(typed?.text() ?: withDetail(stringResource(R.string.restore_engine_error_internal), it), color = MaterialTheme.colorScheme.error)
                }
                if (run.finished) {
                    Text(
                        stringResource(R.string.restore_ui_run_summary, summary.restored, summary.failed, summary.blocked + summary.notRun),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (run.finished) {
                        Button(onClick = onDismiss) { Text(stringResource(R.string.restore_ui_run_done)) }
                    } else {
                        OutlinedButton(onClick = { confirmingCancel = true }, enabled = !run.cancelRequested) {
                            Text(stringResource(if (run.cancelRequested) R.string.restore_ui_run_cancelling else R.string.restore_ui_run_cancel))
                        }
                    }
                }
            }
        }
        if (run.pendingInstalls.isNotEmpty()) {
            NoticeCard(
                title = stringResource(R.string.restore_ui_install_pending_title),
                body = stringResource(R.string.restore_ui_install_pending_body),
                tone = Tone.INFO,
                icon = Icons.Outlined.InstallMobile,
                actions = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        run.pendingInstalls.forEach { prompt ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(prompt.label.ifBlank { prompt.packageName }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onOpenIntent(prompt.intent) }) { Text(stringResource(R.string.restore_ui_install_open)) }
                            }
                        }
                    }
                },
            )
        }
        if (run.permissionPrompts.isNotEmpty()) {
            NoticeCard(
                title = stringResource(R.string.restore_ui_install_permission_title),
                body = stringResource(R.string.restore_ui_install_permission_body, run.permissionPrompts.joinToString(", ") { it.packageName }),
                tone = Tone.WARNING,
                icon = Icons.Outlined.Settings,
                actions = {
                    TextButton(onClick = { onOpenIntent(run.permissionPrompts.last().intent) }) {
                        Text(stringResource(R.string.restore_ui_install_permission_open))
                    }
                },
            )
        }
        SectionCard(title = stringResource(R.string.restore_ui_run_units)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                run.units.forEachIndexed { index, unit ->
                    if (index > 0) HorizontalDivider()
                    UnitRunRow(unit, run)
                }
            }
        }
        if (run.warnings.isNotEmpty()) {
            ExpandableCard(
                title = stringResource(R.string.restore_ui_run_warnings),
                subtitle = pluralStringResource(R.plurals.restore_ui_warning_count, run.warnings.size, run.warnings.size),
                initiallyExpanded = run.finished && run.warnings.size <= INLINE_WARNINGS,
            ) {
                run.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
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

private const val INLINE_WARNINGS = 5

@Composable
private fun UnitRunRow(unit: RestoreUnitInfo, run: RestoreRunState) {
    val status = RunSummarizer.status(run, unit.id)
    val result = run.resultOf(unit.id)
    val blockedReason = if (status == UnitRunStatus.BLOCKED) result?.error ?: run.preflight.firstOrNull { it.unitId == unit.id }?.reason else null
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.padding(top = 2.dp).size(24.dp), contentAlignment = Alignment.Center) {
            when (status) {
                UnitRunStatus.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                UnitRunStatus.RESTORED -> Icon(Icons.Outlined.CheckCircle, contentDescription = stringResource(status.labelRes()), tint = MaterialTheme.colorScheme.primary)
                UnitRunStatus.FAILED -> Icon(Icons.Outlined.ErrorOutline, contentDescription = stringResource(status.labelRes()), tint = MaterialTheme.colorScheme.error)
                UnitRunStatus.BLOCKED -> Icon(Icons.Outlined.Block, contentDescription = stringResource(status.labelRes()), tint = MaterialTheme.colorScheme.error)
                UnitRunStatus.NOT_RUN -> Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = stringResource(status.labelRes()), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                UnitRunStatus.WAITING -> Icon(Icons.Outlined.HourglassEmpty, contentDescription = stringResource(status.labelRes()), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(unit.kind.icon(), contentDescription = stringResource(unit.kind.groupTitleRes()), modifier = Modifier.size(16.dp))
                Text(unit.displayName, style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(status.labelRes()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (blockedReason ?: result?.error)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            result?.warnings?.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
