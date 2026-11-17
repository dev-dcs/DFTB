package ca.devdcs.backup.domain.model

/**
 * One finished or in-flight job as the user sees it in History. Profile and destination links are
 * nullable because deleting either must not erase the audit trail (foreign keys SET NULL).
 * [logExcerpt] is a bounded tail of the job log so failures remain diagnosable after the in-memory
 * buffer is gone.
 */
data class HistoryRecord(
    val id: String,
    val profileId: String? = null,
    val destinationId: String? = null,
    val type: JobType,
    val status: JobStatus,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val bytesProcessed: Long = 0L,
    val itemCount: Int = 0,
    val artifactPath: String? = null,
    val artifactSizeBytes: Long? = null,
    val archiveFormat: ArchiveFormat? = null,
    val checksumAlgorithm: ChecksumAlgorithm? = null,
    val checksumManifestPath: String? = null,
    val errorMessage: String? = null,
    val logExcerpt: String? = null,
) {
    val isTerminal: Boolean get() = status.isTerminal

    /** Wall-clock duration, or null while the job is still running. */
    val durationMillis: Long? get() = finishedAt?.let { it - startedAt }

    val isSuccessful: Boolean get() = status == JobStatus.COMPLETED || status == JobStatus.COMPLETED_WITH_WARNINGS
}
