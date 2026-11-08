package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.progress.JobProgress
import ca.devdcs.backup.core.storage.PreflightResult
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.CompressionCodec
import ca.devdcs.backup.domain.model.JobStatus

/**
 * Per-run knobs that are not part of the saved profile. Exists so the UI ("back up anyway"),
 * passphrase prompts and resume can steer one run without editing the profile.
 */
class BackupRunOptions(
    /** Proceed even when a free-space pre-flight reports too little room. */
    val ignoreInsufficientSpace: Boolean = false,
    /** Passphrase typed for this run; overrides the profile's vault key. Wiped after the run. */
    val passphrase: CharArray? = null,
    /** Sources already completed by an earlier attempt of the same job (resume). */
    val skipSourceIds: Set<String> = emptySet(),
    /** Reuse this job folder name (resume) instead of minting a new timestamped one. */
    val jobDirName: String? = null,
    val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
) {
    companion object {
        val DEFAULT = BackupRunOptions()
    }
}

/** Which storage a pre-flight looked at. */
enum class PreflightTarget { DESTINATION, LOCAL_STAGING }

/** Machine-readable cause of a failed job or artifact; the UI maps it to a localized string. */
enum class BackupErrorCode {
    NO_DESTINATION,
    DESTINATION_NOT_FOUND,
    NOTHING_TO_BACK_UP,
    /** Every selected source is unavailable here (e.g. partitions without root). */
    SOURCES_UNAVAILABLE,
    /** TWRP folders must be stored unencrypted, but the profile asks for encryption. */
    TWRP_ENCRYPTION_CONFLICT,
    /** Filename masking is only safe together with encryption (the manifest must be encrypted too). */
    MASKING_REQUIRES_ENCRYPTION,
    /** The resume folder name is not a single safe path segment. */
    RESUME_FOLDER_INVALID,
    INSUFFICIENT_DESTINATION_SPACE,
    INSUFFICIENT_STAGING_SPACE,
    ENCRYPTION_UNSUPPORTED,
    ENCRYPTION_KEY_MISSING,
    TRANSFER_MODE_UNSUPPORTED,
    SOURCE_FAILED,
    SOURCE_SHAPE,
    STORAGE,
    VERIFY_MISMATCH,
    MANIFEST_FAILED,
    ALL_ARTIFACTS_FAILED,
    INTERNAL,
}

/** Why a job or artifact failed. [detail] is English, log-grade technical context. */
data class BackupFailure(
    val code: BackupErrorCode,
    val detail: String? = null,
    val storageReason: StorageException.Reason? = null,
)

/** Non-fatal conditions worth surfacing in History and the result notification. */
enum class BackupWarningCode(
    /** False for purely informational notices that must not turn COMPLETED into "with warnings". */
    val degradesStatus: Boolean = true,
) {
    FREE_SPACE_UNKNOWN,
    FREE_SPACE_OVERRIDDEN,
    FORMAT_SUBSTITUTED,
    SOURCE_UNAVAILABLE,
    SOURCE_PARTIAL,
    TWRP_STORED_VERBATIM(degradesStatus = false),
    TWRP_NOT_ON_DEVICE_STORAGE,
    ESTIMATE_FAILED,
    ITEM_SIZE_MISMATCH,
    ARTIFACT_FAILED,
    HASH_RECORDS_FAILED,
    CHECKPOINT_WRITE_FAILED,
    CLEANUP_FAILED,
    RESUME_SKIPPED(degradesStatus = false),
    /** A skipped (already done) source has no entry in any earlier manifest of the folder. */
    RESUME_UNINDEXED,
    PRIOR_MANIFEST_UNREADABLE,
}

/** One non-fatal condition; [subject] names the artifact/source/target, [detail] is technical. */
data class BackupWarning(val code: BackupWarningCode, val subject: String? = null, val detail: String? = null)

/** One artifact that reached the destination under its final name. */
data class CompletedArtifact(
    val sourceId: String,
    val logicalName: String,
    /** Destination-relative path of the stored file (FILE_TREE: the folder). */
    val path: String,
    val format: ArchiveFormat,
    val codec: CompressionCodec,
    val storedBytes: Long,
    val sourceBytes: Long,
    val itemCount: Int,
    /** Digests of the stored bytes (empty for FILE_TREE, whose digests are per file). */
    val digests: Map<ChecksumAlgorithm, String>,
    /** True when re-read and matched; null when verification was not requested. */
    val verified: Boolean?,
)

/** Final result of one backup run. */
data class BackupOutcome(
    val jobId: String,
    val profileId: String,
    val status: JobStatus,
    /** Destination-relative job folder, or null when nothing was written. */
    val jobDir: String?,
    val manifestPath: String?,
    val artifacts: List<CompletedArtifact>,
    val storedBytes: Long,
    val sourceBytes: Long,
    val itemCount: Int,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val warnings: List<BackupWarning>,
    val failure: BackupFailure?,
) {
    val durationMillis: Long get() = finishedAtMillis - startedAtMillis
    val isSuccessful: Boolean get() = status == JobStatus.COMPLETED || status == JobStatus.COMPLETED_WITH_WARNINGS
}

/** What [BackupEngine.run] emits, in order; [Finished] is always last. */
sealed interface BackupEvent {
    val jobId: String

    data class Planned(
        override val jobId: String,
        val jobDir: String,
        val artifactCount: Int,
        val totalBytes: Long,
        val totalItems: Long,
        val estimatesExact: Boolean,
    ) : BackupEvent

    data class PreflightChecked(
        override val jobId: String,
        val target: PreflightTarget,
        val requiredBytes: Long,
        val result: PreflightResult,
        val overridden: Boolean,
    ) : BackupEvent

    data class ArtifactStarted(override val jobId: String, val index: Int, val logicalName: String, val attempt: Int) : BackupEvent

    data class Retrying(
        override val jobId: String,
        val logicalName: String,
        val nextAttempt: Int,
        val delayMillis: Long,
        val reason: String,
    ) : BackupEvent

    data class ArtifactCompleted(override val jobId: String, val artifact: CompletedArtifact) : BackupEvent

    data class ArtifactFailed(override val jobId: String, val logicalName: String, val failure: BackupFailure) : BackupEvent

    data class Warning(override val jobId: String, val warning: BackupWarning) : BackupEvent

    data class Progress(override val jobId: String, val progress: JobProgress) : BackupEvent

    data class Finished(override val jobId: String, val outcome: BackupOutcome) : BackupEvent
}

/** Internal control-flow exception carrying a [BackupFailure]; never escapes the engine. */
internal class BackupStepException(val failure: BackupFailure, cause: Throwable? = null) :
    Exception(failure.detail ?: failure.code.name, cause)
