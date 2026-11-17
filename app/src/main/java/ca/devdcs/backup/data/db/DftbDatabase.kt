package ca.devdcs.backup.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ca.devdcs.backup.data.db.converters.DftbTypeConverters
import ca.devdcs.backup.data.db.dao.CheckpointDao
import ca.devdcs.backup.data.db.dao.DestinationDao
import ca.devdcs.backup.data.db.dao.HashRecordDao
import ca.devdcs.backup.data.db.dao.HistoryDao
import ca.devdcs.backup.data.db.dao.ProfileDao
import ca.devdcs.backup.data.db.entity.BackupProfileEntity
import ca.devdcs.backup.data.db.entity.DestinationEntity
import ca.devdcs.backup.data.db.entity.HashRecordEntity
import ca.devdcs.backup.data.db.entity.HistoryRecordEntity
import ca.devdcs.backup.data.db.entity.JobCheckpointEntity

/**
 * The single on-device database: profiles, destinations, history, resumable job checkpoints and
 * the per-file hash registry. Secrets are never stored here (see credentialRef / encryptionKeyRef).
 * Schema JSON is exported to app/schemas so every future migration can be tested against v1.
 */
@Database(
    entities = [
        BackupProfileEntity::class,
        DestinationEntity::class,
        HistoryRecordEntity::class,
        JobCheckpointEntity::class,
        HashRecordEntity::class,
    ],
    version = DftbDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(DftbTypeConverters::class)
abstract class DftbDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao
    abstract fun destinationDao(): DestinationDao
    abstract fun historyDao(): HistoryDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun hashRecordDao(): HashRecordDao

    companion object {
        const val NAME = "dftb.db"
        const val VERSION = 1
    }
}
