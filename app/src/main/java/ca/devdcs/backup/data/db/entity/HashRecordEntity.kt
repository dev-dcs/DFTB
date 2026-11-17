package ca.devdcs.backup.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Row form of [ca.devdcs.backup.domain.model.HashRecord]. Intentionally no foreign key to history:
 * hash tables can be large and are rebuilt from the on-disk manifest when a backup is re-imported,
 * so a dangling `backupId` is tolerable and cheap to purge.
 */
@Entity(
    tableName = HashRecordEntity.TABLE,
    indices = [
        Index(value = ["backupId"]),
        Index(value = ["relativePath", "algorithm"]),
    ],
)
data class HashRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val backupId: String,
    val relativePath: String,
    val algorithm: String,
    val hash: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
) {
    companion object {
        const val TABLE = "hash_records"
    }
}
