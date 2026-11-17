package ca.devdcs.backup.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import ca.devdcs.backup.data.db.entity.HashRecordEntity
import kotlinx.coroutines.flow.Flow

/** Per-file checksum registry access. `algorithm` parameters are ChecksumAlgorithm storageKeys. */
@Dao
interface HashRecordDao {

    @Query("SELECT * FROM hash_records WHERE backupId = :backupId ORDER BY relativePath ASC")
    fun observeForBackup(backupId: String): Flow<List<HashRecordEntity>>

    @Query("SELECT * FROM hash_records WHERE backupId = :backupId ORDER BY relativePath ASC")
    suspend fun getForBackup(backupId: String): List<HashRecordEntity>

    @Query(
        "SELECT * FROM hash_records WHERE backupId = :backupId AND relativePath = :relativePath " +
            "AND algorithm = :algorithm LIMIT 1",
    )
    suspend fun find(backupId: String, relativePath: String, algorithm: String): HashRecordEntity?

    /** Most recently recorded hash of a path across all backups; the incremental-skip lookup. */
    @Query(
        "SELECT * FROM hash_records WHERE relativePath = :relativePath AND algorithm = :algorithm " +
            "ORDER BY id DESC LIMIT 1",
    )
    suspend fun latestForPath(relativePath: String, algorithm: String): HashRecordEntity?

    @Query("SELECT * FROM hash_records WHERE hash = :hash AND algorithm = :algorithm ORDER BY id DESC")
    suspend fun findByHash(hash: String, algorithm: String): List<HashRecordEntity>

    @Query("SELECT COUNT(*) FROM hash_records WHERE backupId = :backupId")
    suspend fun countForBackup(backupId: String): Int

    @Query("SELECT COUNT(*) FROM hash_records")
    suspend fun count(): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: HashRecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(records: List<HashRecordEntity>): List<Long>

    @Upsert
    suspend fun upsert(record: HashRecordEntity)

    @Query("DELETE FROM hash_records WHERE backupId = :backupId")
    suspend fun deleteForBackup(backupId: String): Int

    @Query("DELETE FROM hash_records")
    suspend fun deleteAll(): Int

    /** Atomically swaps the registry of one backup; used when a verify pass recomputes everything. */
    @Transaction
    suspend fun replaceForBackup(backupId: String, records: List<HashRecordEntity>) {
        deleteForBackup(backupId)
        if (records.isNotEmpty()) insertAll(records)
    }
}
