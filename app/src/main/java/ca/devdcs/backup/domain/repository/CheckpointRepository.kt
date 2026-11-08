package ca.devdcs.backup.domain.repository

import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.model.JobStatus
import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for the resumable-job journal. Engines call [save] after every durable
 * step; the boot receiver and dashboard call [resumable] to offer or auto-start continuation.
 */
interface CheckpointRepository {
    fun observeAll(): Flow<List<JobCheckpoint>>
    fun observe(jobId: String): Flow<JobCheckpoint?>
    fun observeResumableCount(): Flow<Int>
    suspend fun get(jobId: String): JobCheckpoint?
    suspend fun getForProfile(profileId: String): List<JobCheckpoint>
    /** Checkpoints whose job did not finish (RUNNING or INTERRUPTED), newest first. */
    suspend fun resumable(): List<JobCheckpoint>
    /** Subset of [resumable] the user allowed to continue automatically after a reboot. */
    suspend fun resumableAfterReboot(): List<JobCheckpoint>
    /** Insert or replace by jobId. */
    suspend fun save(checkpoint: JobCheckpoint)
    suspend fun setStatus(jobId: String, status: JobStatus, updatedAt: Long)
    /**
     * Startup repair: RUNNING rows last updated before [staleBefore] become INTERRUPTED (a job that
     * is genuinely running in this process has written its row after process start). Returns how
     * many were repaired.
     */
    suspend fun markRunningAsInterrupted(staleBefore: Long, updatedAt: Long): Int
    suspend fun delete(jobId: String)
    /** Drops finished checkpoints; returns how many were removed. */
    suspend fun clearTerminal(): Int
    suspend fun deleteForProfile(profileId: String): Int
}
