package ca.devdcs.backup.domain.repository

import ca.devdcs.backup.domain.model.BackupProfile
import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for backup profiles. The UI and scheduler talk to this, never to Room, so
 * the storage can be swapped (or faked in tests) without touching callers. Timestamps are supplied
 * by the caller so the domain stays clock-agnostic and testable.
 */
interface ProfileRepository {
    fun observeAll(): Flow<List<BackupProfile>>
    fun observe(id: String): Flow<BackupProfile?>
    fun observeCount(): Flow<Int>
    suspend fun getAll(): List<BackupProfile>
    suspend fun get(id: String): BackupProfile?
    /** Enabled profiles carrying a schedule; what the scheduler must register on boot/upgrade. */
    suspend fun getScheduled(): List<BackupProfile>
    suspend fun getByDestination(destinationId: String): List<BackupProfile>
    /** Insert or replace by id. */
    suspend fun save(profile: BackupProfile)
    suspend fun saveAll(profiles: List<BackupProfile>)
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long)
    suspend fun markRun(id: String, ranAt: Long)
    suspend fun delete(id: String)
}
