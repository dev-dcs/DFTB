package ca.devdcs.backup.core.restore.engine

import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.progress.JobProgress
import ca.devdcs.backup.core.restore.BackupSummary
import ca.devdcs.backup.core.restore.RestoreUnitResult
import ca.devdcs.backup.core.restore.apps.AppRestoreEvent
import ca.devdcs.backup.core.restore.files.ExtractIssue
import ca.devdcs.backup.core.restore.files.FileRestoreError
import ca.devdcs.backup.core.restore.files.FileRestoreSpec
import ca.devdcs.backup.domain.model.JobStatus
import java.util.UUID

/**
 * Everything one restore run needs. Not a data class: it carries key material. The engine copies
 * [key] for its own use and wipes the copy; the caller still owns (and wipes) its [key].
 */
class RestoreRequest(
    val destinationId: String,
    /** Job folder relative to the destination base (BackupSummary.jobDir). */
    val jobDir: String,
    val key: CipherKeySpec? = null,
    /** Artifact sourceIds to restore; null = every artifact of the backup. */
    val unitIds: Set<String>? = null,
    /** How file units are restored unless [fileSpecs] has an entry for the unit. */
    val fileSpec: FileRestoreSpec = FileRestoreSpec(),
    val fileSpecs: Map<String, FileRestoreSpec> = emptyMap(),
    val options: RestoreOptions = RestoreOptions(),
    val jobId: String = UUID.randomUUID().toString(),
    /** Existing profile id for History linkage (must exist in the database), else null. */
    val profileId: String? = null,
    /** Units already restored by an earlier attempt of this job (resume). */
    val skipUnitIds: Set<String> = emptySet(),
    /** Notification/History title; defaults to the backup's profile name. */
    val title: String? = null,
)

/** Machine-readable cause of a failed restore; the UI maps it to a localized string. */
enum class RestoreErrorCode {
    DESTINATION_NOT_FOUND,
    /** The job folder could not be listed / read (storage failure, no manifest, bad JSON). */
    OPEN_FAILED,
    KEY_REQUIRED,
    WRONG_KEY,
    /** Manifests of resumed runs disagree, or a newer manifest schema. */
    UNSUPPORTED_BACKUP,
    NOTHING_TO_RESTORE,
    /** Every selected unit is blocked here (tier, target, missing restorer). */
    PREFLIGHT_BLOCKED,
    INSUFFICIENT_SPACE,
    /** verifyBeforeRestore found damaged artifacts; nothing was written. */
    VERIFY_FAILED,
    ALL_UNITS_FAILED,
    INTERNAL,
}

data class RestoreFailure(val code: RestoreErrorCode, val detail: String? = null)

/** Final result of one restore run. */
data class RestoreOutcome(
    val jobId: String,
    val status: JobStatus,
    val destinationId: String,
    val jobDir: String,
    val results: List<RestoreUnitResult>,
    val bytesWritten: Long,
    val itemCount: Int,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    /** User-facing (localized) warnings. */
    val warnings: List<String>,
    val failure: RestoreFailure?,
) {
    val isSuccessful: Boolean get() = status == JobStatus.COMPLETED || status == JobStatus.COMPLETED_WITH_WARNINGS
}

/** What [RestoreEngine.restore] emits, in order; [Finished] is always last. */
sealed interface RestoreEvent {
    val jobId: String

    data class Opened(override val jobId: String, val summary: BackupSummary, val unitCount: Int, val totalBytes: Long) : RestoreEvent

    data class PreflightChecked(override val jobId: String, val units: List<UnitPreflight>) : RestoreEvent

    data class UnitStarted(override val jobId: String, val index: Int, val unitId: String, val kind: RestoreUnitKind, val name: String) : RestoreEvent

    data class UnitFinished(override val jobId: String, val result: RestoreUnitResult) : RestoreEvent

    data class Warning(override val jobId: String, val message: String) : RestoreEvent

    /**
     * The app restorer needs the device owner (install prompt, "install unknown apps" setting).
     * The UI starts the event's intent from an Activity; [pending] is every unanswered install
     * prompt, so a screen that appears late can still show them.
     */
    data class AppUserAction(
        override val jobId: String,
        val event: AppRestoreEvent,
        val pending: List<AppRestoreEvent.InstallConfirmationRequired>,
    ) : RestoreEvent

    data class Progress(override val jobId: String, val progress: JobProgress) : RestoreEvent

    data class Finished(override val jobId: String, val outcome: RestoreOutcome) : RestoreEvent
}

/** Localized text for restore progress, results and problems. */
interface RestoreMessages {
    fun opening(): String
    fun preflight(): String
    fun verifying(): String
    fun restoring(name: String): String
    fun completed(unitCount: Int): String
    fun completedWithWarnings(unitCount: Int, warningCount: Int): String
    fun cancelled(): String
    fun failure(failure: RestoreFailure): String
    fun fileIssue(issue: ExtractIssue): String
    fun fileError(error: FileRestoreError, detail: String?): String
    fun moreIssues(count: Int): String
    fun unmapped(count: Int): String
    fun skippedExisting(count: Int): String
    fun restorerMissing(kind: RestoreUnitKind): String
    fun unitBlocked(name: String, reason: String): String
    fun spaceUnknown(name: String): String
    fun spaceOverridden(name: String, shortfallBytes: Long): String
    fun crossDevice(backupModel: String, thisModel: String): String
    fun resumeSkipped(name: String): String
    fun checkpointFailed(): String
    fun partialBackup(): String
}
