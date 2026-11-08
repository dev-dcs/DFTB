package ca.devdcs.backup.data.repository

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.data.db.dao.HashRecordDao
import ca.devdcs.backup.data.db.mappers.toDomain
import ca.devdcs.backup.data.db.mappers.toEntity
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.HashRecord
import ca.devdcs.backup.domain.repository.HashRegistryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [HashRegistryRepository]. Inserts are chunked so a backup with a hundred thousand
 * files never builds one giant statement or transaction.
 */
@Singleton
class HashRegistryRepositoryImpl @Inject constructor(
    private val dao: HashRecordDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : HashRegistryRepository {

    override fun observeForBackup(backupId: String): Flow<List<HashRecord>> =
        dao.observeForBackup(backupId).map { rows -> rows.map { it.toDomain() } }.flowOn(ioDispatcher)

    override suspend fun forBackup(backupId: String): List<HashRecord> = withContext(ioDispatcher) {
        dao.getForBackup(backupId).map { it.toDomain() }
    }

    override suspend fun find(backupId: String, relativePath: String, algorithm: ChecksumAlgorithm): HashRecord? =
        withContext(ioDispatcher) {
            dao.find(backupId, relativePath, algorithm.storageKey)?.toDomain()
        }

    override suspend fun latestForPath(relativePath: String, algorithm: ChecksumAlgorithm): HashRecord? =
        withContext(ioDispatcher) {
            dao.latestForPath(relativePath, algorithm.storageKey)?.toDomain()
        }

    override suspend fun findByHash(hash: String, algorithm: ChecksumAlgorithm): List<HashRecord> =
        withContext(ioDispatcher) {
            dao.findByHash(hash, algorithm.storageKey).map { it.toDomain() }
        }

    override suspend fun countForBackup(backupId: String): Int = withContext(ioDispatcher) {
        dao.countForBackup(backupId)
    }

    override suspend fun count(): Long = withContext(ioDispatcher) { dao.count() }

    override suspend fun record(records: List<HashRecord>): List<HashRecord> = withContext(ioDispatcher) {
        if (records.isEmpty()) return@withContext emptyList()
        val stored = ArrayList<HashRecord>(records.size)
        records.chunked(INSERT_BATCH).forEach { chunk ->
            val ids = dao.insertAll(chunk.map { it.toEntity() })
            chunk.forEachIndexed { index, record -> stored += record.copy(id = ids[index]) }
        }
        DftbLog.d(TAG, "Recorded ${stored.size} hash(es)")
        stored
    }

    override suspend fun replaceForBackup(backupId: String, records: List<HashRecord>) = withContext(ioDispatcher) {
        dao.replaceForBackup(backupId, records.map { it.toEntity() })
        DftbLog.d(TAG, "Replaced hash registry of $backupId with ${records.size} record(s)")
    }

    override suspend fun deleteForBackup(backupId: String): Int = withContext(ioDispatcher) {
        dao.deleteForBackup(backupId)
    }

    override suspend fun deleteAll(): Int = withContext(ioDispatcher) {
        val removed = dao.deleteAll()
        DftbLog.i(TAG, "Cleared hash registry ($removed rows)")
        removed
    }

    private companion object {
        const val TAG = "HashRepo"
        const val INSERT_BATCH = 1_000
    }
}
