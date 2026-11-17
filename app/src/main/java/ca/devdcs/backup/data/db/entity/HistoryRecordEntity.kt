package ca.devdcs.backup.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Row form of [ca.devdcs.backup.domain.model.HistoryRecord]. `type`, `status`, `archiveFormat`
 * and `checksumAlgorithm` are storageKey strings so retention and dashboard queries can filter in
 * SQL. Both foreign keys SET NULL: history is an audit log and outlives its profile/destination.
 */
@Entity(
    tableName = HistoryRecordEntity.TABLE,
    indices = [
        Index(value = ["profileId", "startedAt"]),
        Index(value = ["status"]),
        Index(value = ["destinationId"]),
        Index(value = ["startedAt"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = BackupProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DestinationEntity::class,
            parentColumns = ["id"],
            childColumns = ["destinationId"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
)
data class HistoryRecordEntity(
    @PrimaryKey val id: String,
    val profileId: String? = null,
    val destinationId: String? = null,
    val type: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val bytesProcessed: Long = 0L,
    val itemCount: Int = 0,
    val artifactPath: String? = null,
    val artifactSizeBytes: Long? = null,
    val archiveFormat: String? = null,
    val checksumAlgorithm: String? = null,
    val checksumManifestPath: String? = null,
    val errorMessage: String? = null,
    val logExcerpt: String? = null,
) {
    companion object {
        const val TABLE = "history_records"
    }
}
