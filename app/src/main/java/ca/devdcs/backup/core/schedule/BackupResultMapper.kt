package ca.devdcs.backup.core.schedule

import ca.devdcs.backup.core.pipeline.engine.BackupErrorCode
import ca.devdcs.backup.core.pipeline.engine.BackupOutcome
import ca.devdcs.backup.domain.model.JobStatus

/** What a worker should report to WorkManager, without WorkManager types (testable). */
enum class WorkDecision { SUCCESS, RETRY, FAILURE }

/**
 * Maps a [BackupOutcome] to a [WorkDecision]. Exists so "which failures are worth a backoff
 * retry" is one tested rule: only transient storage failures (network, timeout, protocol) and
 * interruptions retry, and only while [maxAttempts] is not exhausted; configuration problems
 * (missing destination, key, space) fail immediately because retrying cannot fix them.
 */
object BackupResultMapper {

    const val DEFAULT_MAX_ATTEMPTS = 5

    fun decide(outcome: BackupOutcome, runAttemptCount: Int, maxAttempts: Int = DEFAULT_MAX_ATTEMPTS): WorkDecision {
        val decision = when (outcome.status) {
            JobStatus.COMPLETED, JobStatus.COMPLETED_WITH_WARNINGS -> WorkDecision.SUCCESS
            // The user pressed cancel: final, never retried.
            JobStatus.CANCELLED -> WorkDecision.SUCCESS
            JobStatus.INTERRUPTED, JobStatus.RUNNING -> WorkDecision.RETRY
            JobStatus.FAILED -> if (isTransient(outcome)) WorkDecision.RETRY else WorkDecision.FAILURE
        }
        // runAttemptCount is 0 for the first run, so attempts made = runAttemptCount + 1.
        return if (decision == WorkDecision.RETRY && runAttemptCount + 1 >= maxAttempts) WorkDecision.FAILURE else decision
    }

    /** Same rule the engine uses to keep the journal INTERRUPTED (resumable) instead of FAILED. */
    fun isTransient(outcome: BackupOutcome): Boolean = outcome.failure?.storageReason?.isRetryable == true

    /** Failures the user must fix (worth an alert rather than silent retries). */
    fun needsUserAction(outcome: BackupOutcome): Boolean = outcome.failure?.code in USER_ACTION_CODES

    private val USER_ACTION_CODES = setOf(
        BackupErrorCode.NO_DESTINATION,
        BackupErrorCode.DESTINATION_NOT_FOUND,
        BackupErrorCode.ENCRYPTION_KEY_MISSING,
        BackupErrorCode.INSUFFICIENT_DESTINATION_SPACE,
        BackupErrorCode.INSUFFICIENT_STAGING_SPACE,
    )
}
