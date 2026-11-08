package ca.devdcs.backup.data.repository

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.data.db.dao.ProfileDao
import ca.devdcs.backup.data.db.mappers.toDomain
import ca.devdcs.backup.data.db.mappers.toEntity
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [ProfileRepository]. Room already executes queries off the main thread; the IO
 * dispatcher additionally hosts JSON decoding of the nested columns and keeps the mapping out of
 * the collector's context.
 */
@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val dao: ProfileDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ProfileRepository {

    override fun observeAll(): Flow<List<BackupProfile>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }.flowOn(ioDispatcher)

    override fun observe(id: String): Flow<BackupProfile?> =
        dao.observeById(id).map { it?.toDomain() }.flowOn(ioDispatcher)

    override fun observeCount(): Flow<Int> = dao.observeCount()

    override suspend fun getAll(): List<BackupProfile> = withContext(ioDispatcher) {
        dao.getAll().map { it.toDomain() }
    }

    override suspend fun get(id: String): BackupProfile? = withContext(ioDispatcher) {
        dao.getById(id)?.toDomain()
    }

    override suspend fun getScheduled(): List<BackupProfile> = withContext(ioDispatcher) {
        dao.getScheduled().map { it.toDomain() }
    }

    override suspend fun getByDestination(destinationId: String): List<BackupProfile> = withContext(ioDispatcher) {
        dao.getByDestination(destinationId).map { it.toDomain() }
    }

    override suspend fun save(profile: BackupProfile) = withContext(ioDispatcher) {
        dao.upsert(profile.toEntity())
        DftbLog.d(TAG, "Saved profile ${profile.id} (\"${profile.name}\")")
    }

    override suspend fun saveAll(profiles: List<BackupProfile>) = withContext(ioDispatcher) {
        if (profiles.isEmpty()) return@withContext
        dao.upsertAll(profiles.map { it.toEntity() })
        DftbLog.d(TAG, "Saved ${profiles.size} profiles")
    }

    override suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long) = withContext(ioDispatcher) {
        if (dao.setEnabled(id, enabled, updatedAt) == 0) DftbLog.w(TAG, "setEnabled: profile $id not found")
    }

    override suspend fun markRun(id: String, ranAt: Long) = withContext(ioDispatcher) {
        if (dao.setLastRun(id, ranAt) == 0) DftbLog.w(TAG, "markRun: profile $id not found")
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        val removed = dao.deleteById(id)
        DftbLog.i(TAG, "Deleted profile $id (rows=$removed)")
    }

    private companion object {
        const val TAG = "ProfileRepo"
    }
}
