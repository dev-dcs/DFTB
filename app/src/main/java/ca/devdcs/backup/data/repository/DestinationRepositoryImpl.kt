package ca.devdcs.backup.data.repository

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.data.db.dao.DestinationDao
import ca.devdcs.backup.data.db.entity.DestinationEntity
import ca.devdcs.backup.data.db.mappers.toDomainOrNull
import ca.devdcs.backup.data.db.mappers.toEntity
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.repository.DestinationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [DestinationRepository]. Rows with an unrecognised type key (written by a newer
 * build) are logged and skipped rather than crashing the destination list.
 */
@Singleton
class DestinationRepositoryImpl @Inject constructor(
    private val dao: DestinationDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DestinationRepository {

    override fun observeAll(): Flow<List<Destination>> =
        dao.observeAll().map { rows -> rows.mapNotNull(::mapRow) }.flowOn(ioDispatcher)

    override fun observe(id: String): Flow<Destination?> =
        dao.observeById(id).map { row -> row?.let(::mapRow) }.flowOn(ioDispatcher)

    override fun observeCount(): Flow<Int> = dao.observeCount()

    override suspend fun getAll(): List<Destination> = withContext(ioDispatcher) {
        dao.getAll().mapNotNull(::mapRow)
    }

    override suspend fun get(id: String): Destination? = withContext(ioDispatcher) {
        dao.getById(id)?.let(::mapRow)
    }

    override suspend fun getByType(type: DestinationType): List<Destination> = withContext(ioDispatcher) {
        dao.getByType(type.storageKey).mapNotNull(::mapRow)
    }

    override suspend fun save(destination: Destination) = withContext(ioDispatcher) {
        dao.upsert(destination.toEntity())
        DftbLog.d(TAG, "Saved destination ${destination.id} (${destination.type.storageKey})")
    }

    override suspend fun saveAll(destinations: List<Destination>) = withContext(ioDispatcher) {
        if (destinations.isEmpty()) return@withContext
        dao.upsertAll(destinations.map { it.toEntity() })
        DftbLog.d(TAG, "Saved ${destinations.size} destinations")
    }

    override suspend fun recordVerification(id: String, verifiedAt: Long, freeBytes: Long?, totalBytes: Long?) =
        withContext(ioDispatcher) {
            if (dao.setVerification(id, verifiedAt, freeBytes, totalBytes, verifiedAt) == 0) {
                DftbLog.w(TAG, "recordVerification: destination $id not found")
            }
        }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        val removed = dao.deleteById(id)
        DftbLog.i(TAG, "Deleted destination $id (rows=$removed)")
    }

    private fun mapRow(row: DestinationEntity): Destination? {
        val mapped = row.toDomainOrNull()
        if (mapped == null) DftbLog.w(TAG, "Skipping destination ${row.id}: unknown type \"${row.type}\"")
        return mapped
    }

    private companion object {
        const val TAG = "DestinationRepo"
    }
}
