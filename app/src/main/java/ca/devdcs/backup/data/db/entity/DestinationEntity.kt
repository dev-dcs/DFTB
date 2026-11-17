package ca.devdcs.backup.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ca.devdcs.backup.domain.model.DestinationConfig

/** Row form of [ca.devdcs.backup.domain.model.Destination]; `type` is a DestinationType storageKey. */
@Entity(tableName = DestinationEntity.TABLE)
data class DestinationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val config: DestinationConfig,
    val credentialRef: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastVerifiedAt: Long? = null,
    val lastKnownFreeBytes: Long? = null,
    val lastKnownTotalBytes: Long? = null,
) {
    companion object {
        const val TABLE = "destinations"
    }
}
