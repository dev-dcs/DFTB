package ca.devdcs.backup.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import ca.devdcs.backup.data.db.entity.JobCheckpointEntity
import kotlinx.coroutines.flow.Flow

/** Job checkpoint journal access. Statuses are JobStatus storageKeys. */
@Dao
interface CheckpointDao {

    @Query("SELECT * FROM job_checkpoints ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<JobCheckpointEntity>>

    @Query("SELECT * FROM job_checkpoints WHERE jobId = :jobId")
    fun observeById(jobId: String): Flow<JobCheckpointEntity?>

    @Query("SELECT * FROM job_checkpoints WHERE jobId = :jobId")
    suspend fun getById(jobId: String): JobCheckpointEntity?

    @Query("SELECT * FROM job_checkpoints WHERE profileId = :profileId ORDER BY updatedAt DESC")
    suspend fun getForProfile(profileId: String): List<JobCheckpointEntity>

    /** Jobs that did not reach a terminal state: killed mid-run or explicitly interrupted. */
    @Query("SELECT * FROM job_checkpoints WHERE status IN ('running', 'interrupted') ORDER BY updatedAt DESC")
    suspend fun resumable(): List<JobCheckpointEntity>

    @Query(
        "SELECT * FROM job_checkpoints WHERE status IN ('running', 'interrupted') AND resumeAfterReboot = 1 " +
            "ORDER BY updatedAt DESC",
    )
    suspend fun resumableAfterReboot(): List<JobCheckpointEntity>

    @Query("SELECT COUNT(*) FROM job_checkpoints WHERE status IN ('running', 'interrupted')")
    fun observeResumableCount(): Flow<Int>

    @Upsert
    suspend fun upsert(checkpoint: JobCheckpointEntity)

    @Query("UPDATE job_checkpoints SET status = :status, updatedAt = :updatedAt WHERE jobId = :jobId")
    suspend fun setStatus(jobId: String, status: String, updatedAt: Long): Int

    /**
     * Startup repair: a `running` row not touched since [staleBefore] (this process's start) cannot
     * belong to a live job, so it is interrupted. Rows written after process start are left alone.
     * updatedAt is deliberately kept: it is the job's last real progress, which the resume age
     * limit (and the dashboard) must see, not the time of the repair.
     */
    @Query("UPDATE job_checkpoints SET status = 'interrupted' WHERE status = 'running' AND updatedAt < :staleBefore")
    suspend fun markRunningAsInterrupted(staleBefore: Long): Int

    @Query("DELETE FROM job_checkpoints WHERE jobId = :jobId")
    suspend fun deleteById(jobId: String): Int

    @Query("DELETE FROM job_checkpoints WHERE status NOT IN ('running', 'interrupted')")
    suspend fun deleteTerminal(): Int

    @Query("DELETE FROM job_checkpoints WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: String): Int

    @Query("DELETE FROM job_checkpoints")
    suspend fun deleteAll(): Int
}
