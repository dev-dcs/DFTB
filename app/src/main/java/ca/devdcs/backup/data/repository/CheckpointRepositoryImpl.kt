package ca.devdcs.backup.data.repository

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.data.db.dao.CheckpointDao
import ca.devdcs.backup.data.db.mappers.toDomain
import ca.devdcs.backup.data.db.mappers.toEntity
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.repository.CheckpointRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed [CheckpointRepository]. */
@Singleton
class CheckpointRepositoryImpl @Inject constructor(
    private val dao: CheckpointDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CheckpointRepository {

    override fun observeAll(): Flow<List<JobCheckpoint>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }.flowOn(ioDispatcher)

    override fun observe(jobId: String): Flow<JobCheckpoint?> =
        dao.observeById(jobId).map { it?.toDomain() }.flowOn(ioDispatcher)

    override fun observeResumableCount(): Flow<Int> = dao.observeResumableCount()

    override suspend fun get(jobId: String): JobCheckpoint? = withContext(ioDispatcher) {
        dao.getById(jobId)?.toDomain()
    }

    override suspend fun getForProfile(profileId: String): List<JobCheckpoint> = withContext(ioDispatcher) {
        dao.getForProfile(profileId).map { it.toDomain() }
    }

    override suspend fun resumable(): List<JobCheckpoint> = withContext(ioDispatcher) {
        dao.resumable().map { it.toDomain() }
    }

    override suspend fun resumableAfterReboot(): List<JobCheckpoint> = withContext(ioDispatcher) {
        dao.resumableAfterReboot().map { it.toDomain() }
    }

    override suspend fun save(checkpoint: JobCheckpoint) = withContext(ioDispatcher) {
        dao.upsert(checkpoint.toEntity())
    }

    override suspend fun setStatus(jobId: String, status: JobStatus, updatedAt: Long) = withContext(ioDispatcher) {
        if (dao.setStatus(jobId, status.storageKey, updatedAt) == 0) {
            DftbLog.w(TAG, "setStatus: checkpoint $jobId not found")
        }
    }

    /** [updatedAt] is not written: the row keeps its last-progress time (see the DAO). */
    override suspend fun markRunningAsInterrupted(staleBefore: Long, updatedAt: Long): Int = withContext(ioDispatcher) {
        val repaired = dao.markRunningAsInterrupted(staleBefore)
        if (repaired > 0) DftbLog.i(TAG, "Marked $repaired stale running checkpoint(s) as interrupted")
        repaired
    }

    override suspend fun delete(jobId: String) = withContext(ioDispatcher) {
        dao.deleteById(jobId)
        Unit
    }

    override suspend fun clearTerminal(): Int = withContext(ioDispatcher) {
        val removed = dao.deleteTerminal()
        if (removed > 0) DftbLog.d(TAG, "Cleared $removed finished checkpoint(s)")
        removed
    }

    override suspend fun deleteForProfile(profileId: String): Int = withContext(ioDispatcher) {
        dao.deleteForProfile(profileId)
    }

    private companion object {
        const val TAG = "CheckpointRepo"
    }
}
