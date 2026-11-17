package ca.devdcs.backup.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import ca.devdcs.backup.data.db.entity.DestinationEntity
import kotlinx.coroutines.flow.Flow

/** Destination table access. */
@Dao
interface DestinationDao {

    @Query("SELECT * FROM destinations ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<DestinationEntity>>

    @Query("SELECT * FROM destinations WHERE id = :id")
    fun observeById(id: String): Flow<DestinationEntity?>

    @Query("SELECT * FROM destinations ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<DestinationEntity>

    @Query("SELECT * FROM destinations WHERE id = :id")
    suspend fun getById(id: String): DestinationEntity?

    /** [type] is a DestinationType storageKey. */
    @Query("SELECT * FROM destinations WHERE type = :type ORDER BY name COLLATE NOCASE ASC")
    suspend fun getByType(type: String): List<DestinationEntity>

    @Query("SELECT COUNT(*) FROM destinations")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsert(destination: DestinationEntity)

    @Upsert
    suspend fun upsertAll(destinations: List<DestinationEntity>)

    @Query(
        "UPDATE destinations SET lastVerifiedAt = :verifiedAt, lastKnownFreeBytes = :freeBytes, " +
            "lastKnownTotalBytes = :totalBytes, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun setVerification(id: String, verifiedAt: Long, freeBytes: Long?, totalBytes: Long?, updatedAt: Long): Int

    @Query("DELETE FROM destinations WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM destinations")
    suspend fun deleteAll(): Int
}
