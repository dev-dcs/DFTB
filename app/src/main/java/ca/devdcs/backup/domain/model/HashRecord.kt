package ca.devdcs.backup.domain.model

/**
 * Checksum of one file inside one backup. Powers verification (recompute and compare), incremental
 * runs (skip files whose hash is unchanged) and dedupe across destinations. [id] is a Room
 * auto-increment key; 0 means "not yet inserted".
 */
data class HashRecord(
    val id: Long = 0L,
    val backupId: String,
    val relativePath: String,
    val algorithm: ChecksumAlgorithm,
    val hash: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
) {
    /** Cheap pre-check before hashing: same size and mtime almost always means same content. */
    fun looksUnchanged(sizeBytes: Long, modifiedAt: Long): Boolean =
        this.sizeBytes == sizeBytes && this.modifiedAt == modifiedAt
}
