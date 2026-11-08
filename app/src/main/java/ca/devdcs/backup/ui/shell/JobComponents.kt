package ca.devdcs.backup.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.core.progress.JobPhase
import ca.devdcs.backup.core.progress.JobProgress

/**
 * Progress block shared by the dashboard's running-jobs card and the job monitor: phase, bar,
 * processed vs total, throughput and ETA. Times are measured against the job's own last update
 * so the estimate moves with the engine's publishes and needs no ticker.
 *
 * @param detailed adds the current item and the engine's message (job monitor).
 */
@Composable
internal fun JobProgressDetails(
    progress: JobProgress,
    cancelRequested: Boolean,
    detailed: Boolean,
    modifier: Modifier = Modifier,
) {
    val now = progress.updatedAtMillis
    val percent = JobFormatting.percent(progress)
    val phase = stringResource(progress.phase.labelRes)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (cancelRequested) stringResource(R.string.shell_job_cancelling) else phase,
                style = MaterialTheme.typography.labelLarge,
                color = if (cancelRequested) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (percent != null) {
                Text(stringResource(R.string.shell_job_percent, percent), style = MaterialTheme.typography.labelLarge)
            }
        }
        // The adjacent texts already convey phase and percent; only an indeterminate bar needs a label.
        val workingLabel = stringResource(R.string.shell_progress_working)
        val barModifier = Modifier
            .fillMaxWidth()
            .then(
                if (percent != null) {
                    Modifier.clearAndSetSemantics { }
                } else {
                    Modifier.semantics { contentDescription = workingLabel }
                },
            )
        if (progress.indeterminate && !progress.isTerminal) {
            LinearProgressIndicator(modifier = barModifier)
        } else {
            LinearProgressIndicator(progress = { if (progress.phase == JobPhase.COMPLETED) 1f else progress.fraction }, modifier = barModifier)
        }
        amountLine(progress)?.let { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
        val timing = timingLine(progress, now)
        if (timing.isNotEmpty()) {
            Text(timing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (detailed) {
            progress.currentItem?.takeIf { it.isNotBlank() }?.let { item ->
                Text(
                    stringResource(R.string.shell_job_current_item, item),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            progress.message?.takeIf { it.isNotBlank() }?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "1.2 GB of 4 GB · 120 of 300 items", whichever totals the engine knows. */
@Composable
private fun amountLine(progress: JobProgress): String? {
    val parts = ArrayList<String>(2)
    if (progress.totalBytes > 0) {
        parts += stringResource(R.string.shell_job_progress_bytes, ShellText.bytes(progress.processedBytes), ShellText.bytes(progress.totalBytes))
    } else if (progress.processedBytes > 0) {
        parts += ShellText.bytes(progress.processedBytes)
    }
    if (progress.totalItems > 0) {
        parts += stringResource(R.string.shell_job_progress_items, progress.processedItems, progress.totalItems)
    } else if (progress.processedItems > 0) {
        parts += stringResource(R.string.shell_job_items_done, progress.processedItems)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)
}

/** "12 MB/s · 3 min 20 s left · elapsed 1 h 2 min" (only the parts that are known). */
@Composable
private fun timingLine(progress: JobProgress, now: Long): String {
    val parts = ArrayList<String>(3)
    if (!progress.isTerminal) {
        if (progress.bytesPerSecond > 0) parts += ShellText.rate(progress.bytesPerSecond)
        val eta = JobFormatting.etaSeconds(progress, now)
        parts += if (eta != null) stringResource(R.string.shell_job_eta, ShellText.duration(eta)) else stringResource(R.string.shell_job_eta_unknown)
    }
    parts += stringResource(R.string.shell_job_elapsed, ShellText.duration(JobFormatting.elapsedSeconds(progress, now)))
    return parts.joinToString(SEPARATOR)
}

internal const val SEPARATOR = " · "
