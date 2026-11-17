package ca.devdcs.backup.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import ca.devdcs.backup.domain.model.CheckpointState

/**
 * Row form of [ca.devdcs.backup.domain.model.JobCheckpoint]. One row per job; `status` is a
 * JobStatus storageKey so the resume query is a plain `WHERE status IN (...)`.
 */
@Entity(
    tableName = JobCheckpointEntity.TABLE,
    indices = [
        Index(value = ["status"]),
        Index(value = ["profileId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = BackupProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
)
data class JobCheckpointEntity(
    @PrimaryKey val jobId: String,
    val profileId: String? = null,
    val type: String,
    val status: String,
    val attempt: Int = 1,
    val state: CheckpointState,
    val createdAt: Long,
    val updatedAt: Long,
    val resumeAfterReboot: Boolean = true,
) {
    companion object {
        const val TABLE = "job_checkpoints"
    }
}
