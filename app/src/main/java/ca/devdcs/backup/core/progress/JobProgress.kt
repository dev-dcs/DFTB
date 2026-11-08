package ca.devdcs.backup.core.progress

import androidx.annotation.StringRes
import ca.devdcs.backup.R

enum class JobKind(@StringRes val labelRes: Int) {
    BACKUP(R.string.job_kind_backup),
    RESTORE(R.string.job_kind_restore),
    VERIFY(R.string.job_kind_verify),
    SYNC(R.string.job_kind_sync),
}

enum class JobPhase(@StringRes val labelRes: Int, val isTerminal: Boolean) {
    QUEUED(R.string.job_phase_queued, isTerminal = false),
    PREFLIGHT(R.string.job_phase_preflight, isTerminal = false),
    RUNNING(R.string.job_phase_running, isTerminal = false),
    FINALIZING(R.string.job_phase_finalizing, isTerminal = false),
    COMPLETED(R.string.job_phase_completed, isTerminal = true),
    FAILED(R.string.job_phase_failed, isTerminal = true),
    CANCELLED(R.string.job_phase_cancelled, isTerminal = true),
}

/**
 * Point-in-time state of one backup/restore/verify job. Published by the pipeline engines, read by
 * the foreground-service notification, the dashboard and the live terminal.
 */
data class JobProgress(
    val jobId: String,
    val kind: JobKind,
    val title: String,
    val phase: JobPhase,
    val currentItem: String? = null,
    val processedBytes: Long = 0,
    val totalBytes: Long = 0,
    val processedItems: Int = 0,
    val totalItems: Int = 0,
    val bytesPerSecond: Long = 0,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val message: String? = null,
    val indeterminate: Boolean = totalBytes <= 0 && totalItems <= 0,
) {
    /** 0f..1f, byte-based when sizes are known, else item-based. */
    val fraction: Float
        get() = when {
            totalBytes > 0 -> (processedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
            totalItems > 0 -> (processedItems.toDouble() / totalItems).coerceIn(0.0, 1.0).toFloat()
            else -> 0f
        }

    val percent: Int get() = (fraction * 100).toInt()

    val isTerminal: Boolean get() = phase.isTerminal

    /** Estimated seconds remaining, or null when speed or totals are unknown. */
    val etaSeconds: Long?
        get() = if (bytesPerSecond > 0 && totalBytes > processedBytes) {
            (totalBytes - processedBytes) / bytesPerSecond
        } else {
            null
        }
}
