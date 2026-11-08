package ca.devdcs.backup.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import ca.devdcs.backup.data.db.entity.BackupProfileEntity
import kotlinx.coroutines.flow.Flow

/** Backup profile table access. Flows re-emit on any change to the table. */
@Dao
interface ProfileDao {

    @Query("SELECT * FROM backup_profiles ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<BackupProfileEntity>>

    @Query("SELECT * FROM backup_profiles WHERE id = :id")
    fun observeById(id: String): Flow<BackupProfileEntity?>

    @Query("SELECT * FROM backup_profiles ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<BackupProfileEntity>

    @Query("SELECT * FROM backup_profiles WHERE id = :id")
    suspend fun getById(id: String): BackupProfileEntity?

    /** Profiles the scheduler must own: enabled and carrying a schedule. */
    @Query("SELECT * FROM backup_profiles WHERE enabled = 1 AND schedule IS NOT NULL ORDER BY name COLLATE NOCASE ASC")
    suspend fun getScheduled(): List<BackupProfileEntity>

    @Query("SELECT * FROM backup_profiles WHERE destinationId = :destinationId ORDER BY name COLLATE NOCASE ASC")
    suspend fun getByDestination(destinationId: String): List<BackupProfileEntity>

    @Query("SELECT COUNT(*) FROM backup_profiles")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsert(profile: BackupProfileEntity)

    @Upsert
    suspend fun upsertAll(profiles: List<BackupProfileEntity>)

    @Query("UPDATE backup_profiles SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long): Int

    @Query("UPDATE backup_profiles SET lastRunAt = :lastRunAt WHERE id = :id")
    suspend fun setLastRun(id: String, lastRunAt: Long): Int

    @Query("DELETE FROM backup_profiles WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM backup_profiles")
    suspend fun deleteAll(): Int
}
