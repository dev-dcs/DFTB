package ca.devdcs.backup.domain.repository

import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.HashRecord
import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for per-file checksums. Verification compares against [forBackup];
 * incremental backups consult [latestForPath] to skip unchanged files; dedupe uses [findByHash].
 */
interface HashRegistryRepository {
    fun observeForBackup(backupId: String): Flow<List<HashRecord>>
    suspend fun forBackup(backupId: String): List<HashRecord>
    suspend fun find(backupId: String, relativePath: String, algorithm: ChecksumAlgorithm): HashRecord?
    suspend fun latestForPath(relativePath: String, algorithm: ChecksumAlgorithm): HashRecord?
    suspend fun findByHash(hash: String, algorithm: ChecksumAlgorithm): List<HashRecord>
    suspend fun countForBackup(backupId: String): Int
    suspend fun count(): Long
    /** Appends records; ids are assigned by the database. Returns the stored copies with ids. */
    suspend fun record(records: List<HashRecord>): List<HashRecord>
    /** Replaces every record of [backupId] atomically. */
    suspend fun replaceForBackup(backupId: String, records: List<HashRecord>)
    suspend fun deleteForBackup(backupId: String): Int
    suspend fun deleteAll(): Int
}
