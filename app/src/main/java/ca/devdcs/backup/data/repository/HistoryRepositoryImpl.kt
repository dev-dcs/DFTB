package ca.devdcs.backup.data.repository

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.data.db.dao.HistoryDao
import ca.devdcs.backup.data.db.mappers.toDomain
import ca.devdcs.backup.data.db.mappers.toEntity
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.RetentionPolicy
import ca.devdcs.backup.domain.repository.HistoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed [HistoryRepository]. */
@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val dao: HistoryDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : HistoryRepository {

    override fun observeRecent(limit: Int): Flow<List<HistoryRecord>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }.flowOn(ioDispatcher)

    override fun observeForProfile(profileId: String): Flow<List<HistoryRecord>> =
        dao.observeForProfile(profileId).map { rows -> rows.map { it.toDomain() } }.flowOn(ioDispatcher)

    override fun observeActive(): Flow<List<HistoryRecord>> =
        dao.observeByStatus(ACTIVE_KEYS).map { rows -> rows.map { it.toDomain() } }.flowOn(ioDispatcher)

    override fun observe(id: String): Flow<HistoryRecord?> =
        dao.observeById(id).map { it?.toDomain() }.flowOn(ioDispatcher)

    override suspend fun get(id: String): HistoryRecord? = withContext(ioDispatcher) {
        dao.getById(id)?.toDomain()
    }

    override suspend fun getByIds(ids: List<String>): List<HistoryRecord> = withContext(ioDispatcher) {
        if (ids.isEmpty()) return@withContext emptyList()
        ids.chunked(HistoryDao.SQLITE_VARIABLE_BATCH)
            .flatMap { chunk -> dao.getByIds(chunk) }
            .map { it.toDomain() }
    }

    override suspend fun latestForProfile(profileId: String): HistoryRecord? = withContext(ioDispatcher) {
        dao.getLatestForProfile(profileId)?.toDomain()
    }

    override suspend fun latestSuccessfulForProfile(profileId: String): HistoryRecord? = withContext(ioDispatcher) {
        dao.getLatestForProfileWithStatus(profileId, SUCCESS_KEYS)?.toDomain()
    }

    override suspend fun getByStatus(statuses: Set<JobStatus>): List<HistoryRecord> = withContext(ioDispatcher) {
        if (statuses.isEmpty()) return@withContext emptyList()
        dao.getByStatus(statuses.map { it.storageKey }).map { it.toDomain() }
    }

    override suspend fun countForProfile(profileId: String): Int = withContext(ioDispatcher) {
        dao.countForProfile(profileId)
    }

    override suspend fun artifactBytesForDestination(destinationId: String): Long = withContext(ioDispatcher) {
        dao.artifactBytesForDestination(destinationId)
    }

    override suspend fun save(record: HistoryRecord) = withContext(ioDispatcher) {
        dao.upsert(record.toEntity())
    }

    override suspend fun setOutcome(id: String, status: JobStatus, finishedAt: Long?, errorMessage: String?) =
        withContext(ioDispatcher) {
            if (dao.setOutcome(id, status.storageKey, finishedAt, errorMessage) == 0) {
                DftbLog.w(TAG, "setOutcome: history record $id not found")
            }
        }

    override suspend fun markRunningAsInterrupted(): Int = withContext(ioDispatcher) {
        val repaired = dao.replaceStatus(JobStatus.RUNNING.storageKey, JobStatus.INTERRUPTED.storageKey)
        if (repaired > 0) DftbLog.i(TAG, "Marked $repaired stale running record(s) as interrupted")
        repaired
    }

    override suspend fun expiredRecords(
        profileId: String,
        policy: RetentionPolicy,
        nowEpochMillis: Long,
    ): List<HistoryRecord> = withContext(ioDispatcher) {
        if (policy.isUnlimited) return@withContext emptyList()
        val ids = LinkedHashSet<String>()
        policy.cutoffMillis(nowEpochMillis)?.let { cutoff -> ids += dao.idsOlderThan(profileId, cutoff) }
        policy.keepLast?.let { keep -> ids += dao.idsBeyondKeepLast(profileId, keep.coerceAtLeast(0)) }
        if (ids.isEmpty()) return@withContext emptyList()
        ids.toList().chunked(HistoryDao.SQLITE_VARIABLE_BATCH)
            .flatMap { chunk -> dao.getByIds(chunk) }
            .map { it.toDomain() }
            .sortedByDescending { it.startedAt }
    }

    override suspend fun deleteOlderThan(profileId: String, cutoffEpochMillis: Long): Int = withContext(ioDispatcher) {
        val removed = dao.deleteOlderThan(profileId, cutoffEpochMillis)
        if (removed > 0) DftbLog.i(TAG, "Pruned $removed history record(s) of $profileId older than $cutoffEpochMillis")
        removed
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        dao.deleteById(id)
        Unit
    }

    override suspend fun deleteByIds(ids: List<String>): Int = withContext(ioDispatcher) {
        val removed = dao.prune(ids)
        if (removed > 0) DftbLog.i(TAG, "Pruned $removed history record(s)")
        removed
    }

    override suspend fun deleteForProfile(profileId: String): Int = withContext(ioDispatcher) {
        dao.deleteForProfile(profileId)
    }

    override suspend fun deleteAll(): Int = withContext(ioDispatcher) {
        val removed = dao.deleteAll()
        DftbLog.i(TAG, "Cleared history ($removed rows)")
        removed
    }

    private companion object {
        const val TAG = "HistoryRepo"
        val ACTIVE_KEYS = listOf(JobStatus.RUNNING.storageKey, JobStatus.INTERRUPTED.storageKey)
        val SUCCESS_KEYS = listOf(JobStatus.COMPLETED.storageKey, JobStatus.COMPLETED_WITH_WARNINGS.storageKey)
    }
}
