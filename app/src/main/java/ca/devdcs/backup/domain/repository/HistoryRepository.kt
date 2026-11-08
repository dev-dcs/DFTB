package ca.devdcs.backup.domain.repository

import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.RetentionPolicy
import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for the job history / audit log. Retention is split in two steps on
 * purpose: [expiredRecords] tells the caller which artifacts to delete on the destination, and
 * [deleteByIds] drops the rows only once that succeeded.
 */
interface HistoryRepository {
    fun observeRecent(limit: Int = DEFAULT_RECENT_LIMIT): Flow<List<HistoryRecord>>
    fun observeForProfile(profileId: String): Flow<List<HistoryRecord>>
    /** Records in RUNNING or INTERRUPTED state. */
    fun observeActive(): Flow<List<HistoryRecord>>
    fun observe(id: String): Flow<HistoryRecord?>
    suspend fun get(id: String): HistoryRecord?
    suspend fun getByIds(ids: List<String>): List<HistoryRecord>
    suspend fun latestForProfile(profileId: String): HistoryRecord?
    /** Newest COMPLETED / COMPLETED_WITH_WARNINGS run, the natural "restore from" candidate. */
    suspend fun latestSuccessfulForProfile(profileId: String): HistoryRecord?
    suspend fun getByStatus(statuses: Set<JobStatus>): List<HistoryRecord>
    suspend fun countForProfile(profileId: String): Int
    suspend fun artifactBytesForDestination(destinationId: String): Long
    /** Insert or replace by id. */
    suspend fun save(record: HistoryRecord)
    suspend fun setOutcome(id: String, status: JobStatus, finishedAt: Long?, errorMessage: String?)
    /** Startup repair: every RUNNING row becomes INTERRUPTED. Returns how many were repaired. */
    suspend fun markRunningAsInterrupted(): Int
    /** Records of [profileId] that violate [policy] at [nowEpochMillis], newest first. */
    suspend fun expiredRecords(profileId: String, policy: RetentionPolicy, nowEpochMillis: Long): List<HistoryRecord>
    suspend fun deleteOlderThan(profileId: String, cutoffEpochMillis: Long): Int
    suspend fun delete(id: String)
    suspend fun deleteByIds(ids: List<String>): Int
    suspend fun deleteForProfile(profileId: String): Int
    suspend fun deleteAll(): Int

    companion object {
        const val DEFAULT_RECENT_LIMIT = 100
    }
}
