package ca.devdcs.backup.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import ca.devdcs.backup.data.db.entity.HistoryRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * History table access. Status/type parameters are JobStatus/JobType storageKeys. Retention
 * helpers return ids rather than deleting blindly so the caller can remove the artifacts on the
 * destination first and only then drop the rows.
 */
@Dao
interface HistoryDao {

    @Query("SELECT * FROM history_records ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryRecordEntity>>

    @Query("SELECT * FROM history_records WHERE profileId = :profileId ORDER BY startedAt DESC")
    fun observeForProfile(profileId: String): Flow<List<HistoryRecordEntity>>

    @Query("SELECT * FROM history_records WHERE status IN (:statuses) ORDER BY startedAt DESC")
    fun observeByStatus(statuses: List<String>): Flow<List<HistoryRecordEntity>>

    @Query("SELECT * FROM history_records WHERE id = :id")
    fun observeById(id: String): Flow<HistoryRecordEntity?>

    @Query("SELECT * FROM history_records WHERE id = :id")
    suspend fun getById(id: String): HistoryRecordEntity?

    @Query("SELECT * FROM history_records WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<HistoryRecordEntity>

    @Query("SELECT * FROM history_records WHERE profileId = :profileId ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatestForProfile(profileId: String): HistoryRecordEntity?

    /**
     * Latest row for [profileId] whose status is in [statuses], scoped to completed BACKUP jobs that
     * produced an artifact so the result is always a restorable backup, never a verify/sync run.
     */
    @Query(
        "SELECT * FROM history_records WHERE profileId = :profileId AND status IN (:statuses) " +
            "AND type = 'backup' AND artifactPath IS NOT NULL ORDER BY startedAt DESC LIMIT 1",
    )
    suspend fun getLatestForProfileWithStatus(profileId: String, statuses: List<String>): HistoryRecordEntity?

    @Query("SELECT * FROM history_records WHERE status IN (:statuses) ORDER BY startedAt DESC")
    suspend fun getByStatus(statuses: List<String>): List<HistoryRecordEntity>

    @Query("SELECT COUNT(*) FROM history_records WHERE profileId = :profileId")
    suspend fun countForProfile(profileId: String): Int

    @Query("SELECT COALESCE(SUM(artifactSizeBytes), 0) FROM history_records WHERE destinationId = :destinationId")
    suspend fun artifactBytesForDestination(destinationId: String): Long

    @Upsert
    suspend fun upsert(record: HistoryRecordEntity)

    @Query(
        "UPDATE history_records SET status = :status, finishedAt = :finishedAt, errorMessage = :errorMessage " +
            "WHERE id = :id",
    )
    suspend fun setOutcome(id: String, status: String, finishedAt: Long?, errorMessage: String?): Int

    /** Startup repair: anything still marked [fromStatus] cannot be running after a process restart. */
    @Query("UPDATE history_records SET status = :toStatus WHERE status = :fromStatus")
    suspend fun replaceStatus(fromStatus: String, toStatus: String): Int

    /** Rows of [profileId] that started strictly before [cutoff] (epoch millis). */
    @Query("DELETE FROM history_records WHERE profileId = :profileId AND startedAt < :cutoff")
    suspend fun deleteOlderThan(profileId: String, cutoff: Long): Int

    /**
     * Ids of the completed backup artifacts of [profileId] that started strictly before [cutoff].
     * Scoped to completed BACKUP rows with an artifact so restores/verifies and failed runs are not
     * returned as expired (retention governs stored artifacts, not audit rows).
     */
    @Query(
        "SELECT id FROM history_records WHERE profileId = :profileId AND startedAt < :cutoff " +
            "AND type = 'backup' AND status IN ('completed', 'completed_with_warnings') " +
            "AND artifactPath IS NOT NULL",
    )
    suspend fun idsOlderThan(profileId: String, cutoff: Long): List<String>

    /**
     * Ids of the completed backup artifacts of [profileId] that fall outside the newest [keep].
     * Scoped to completed BACKUP rows with an artifact so restores/verifies and failed runs never
     * consume the keep-last window. `LIMIT -1` is SQLite's "no limit", required for OFFSET.
     */
    @Query(
        "SELECT id FROM history_records WHERE profileId = :profileId AND type = 'backup' " +
            "AND status IN ('completed', 'completed_with_warnings') AND artifactPath IS NOT NULL " +
            "ORDER BY startedAt DESC LIMIT -1 OFFSET :keep",
    )
    suspend fun idsBeyondKeepLast(profileId: String, keep: Int): List<String>

    @Query("DELETE FROM history_records WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM history_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("DELETE FROM history_records WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: String): Int

    @Query("DELETE FROM history_records")
    suspend fun deleteAll(): Int

    /** Removes the given rows and returns how many actually existed; one transaction per batch. */
    @Transaction
    suspend fun prune(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        var removed = 0
        ids.chunked(SQLITE_VARIABLE_BATCH).forEach { chunk -> removed += deleteByIds(chunk) }
        return removed
    }

    companion object {
        /** Stay well under SQLite's default 999 bound-variable limit. */
        const val SQLITE_VARIABLE_BATCH = 500
    }
}
