package ca.devdcs.backup.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.RetentionPolicy
import ca.devdcs.backup.domain.model.SchedulePolicy
import ca.devdcs.backup.domain.model.TargetSelection

/**
 * Row form of [ca.devdcs.backup.domain.model.BackupProfile]. Deleting a destination nulls the
 * link instead of cascading: the user's carefully built profile survives and the UI asks for a
 * new destination.
 */
@Entity(
    tableName = BackupProfileEntity.TABLE,
    indices = [
        Index(value = ["destinationId"]),
        Index(value = ["name"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = DestinationEntity::class,
            parentColumns = ["id"],
            childColumns = ["destinationId"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
)
data class BackupProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val targets: TargetSelection,
    val destinationId: String? = null,
    val pipeline: PipelineOptions,
    val schedule: SchedulePolicy? = null,
    val retention: RetentionPolicy,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long? = null,
    val enabled: Boolean = true,
) {
    companion object {
        const val TABLE = "backup_profiles"
    }
}
