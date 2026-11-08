package ca.devdcs.backup.core.boot

import android.os.Process
import android.os.SystemClock
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.repository.CheckpointRepository
import ca.devdcs.backup.domain.repository.HistoryRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns RUNNING journal and History rows left behind by a dead process into INTERRUPTED, so they
 * become resumable (and stop blocking retention) without waiting for a reboot. Runs on process
 * start, at the top of every backup worker and in the boot pass.
 *
 * Safety rule: only rows not updated since this process started are stale; a job running
 * in this process wrote its row after start. History rows are repaired only for those same job ids
 * (HistoryRepository's own repair would also hit live jobs). Rows keep their last-progress time.
 */
@Singleton
class StaleJobRepair @Inject constructor(
    private val checkpointRepository: CheckpointRepository,
    private val historyRepository: HistoryRepository,
) {
    private val lock = Mutex()

    /** Wall-clock time this process started. */
    fun processStartedAtMillis(): Long =
        System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())

    /** Returns the repaired job ids. */
    suspend fun repair(processStartedAt: Long = processStartedAtMillis()): List<String> = lock.withLock {
        val stale = checkpointRepository.resumable()
            .filter { it.status == JobStatus.RUNNING && it.updatedAt < processStartedAt }
            .map { it.jobId }
        if (stale.isEmpty()) return@withLock emptyList()
        checkpointRepository.markRunningAsInterrupted(processStartedAt, System.currentTimeMillis())
        historyRepository.getByIds(stale)
            .filter { it.status == JobStatus.RUNNING }
            .forEach { historyRepository.setOutcome(it.id, JobStatus.INTERRUPTED, finishedAt = null, errorMessage = null) }
        DftbLog.i(TAG, "Repaired ${stale.size} stale RUNNING job(s) to INTERRUPTED")
        stale
    }

    private companion object {
        const val TAG = "StaleRepair"
    }
}
