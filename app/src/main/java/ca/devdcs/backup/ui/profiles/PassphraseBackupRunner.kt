package ca.devdcs.backup.ui.profiles

import ca.devdcs.backup.core.boot.ResumeCoordinator
import ca.devdcs.backup.core.boot.ResumeDecision
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.engine.BackupEngine
import ca.devdcs.backup.core.pipeline.engine.BackupErrorCode
import ca.devdcs.backup.core.pipeline.engine.BackupFailure
import ca.devdcs.backup.core.pipeline.engine.BackupOutcome
import ca.devdcs.backup.core.pipeline.engine.BackupRunOptions
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.core.retention.RetentionExecutor
import ca.devdcs.backup.core.schedule.ProfileRunLock
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Result of asking [PassphraseBackupRunner] to start a run. */
sealed interface PassphraseRunStart {
    data class Started(val jobId: String) : PassphraseRunStart

    /** The profile already has a run in this process (scheduled, queued or typed). */
    data object AlreadyRunning : PassphraseRunStart
}

/**
 * Runs a backup whose key is a passphrase typed right now. Exists because such a passphrase must
 * never be written to disk, and WorkManager input data is persisted: "Back up now" and "Resume"
 * for these profiles therefore run the [BackupEngine] directly in the application scope (the engine
 * itself promotes the process to its foreground service and publishes progress on the job bus, so
 * the job monitor shows it like any other run).
 *
 * Mirrors what the worker does around a run: one run per profile ([ProfileRunLock]); a resume keeps
 * the interrupted job's id, folder and completed sources; on success the start time is recorded,
 * older interrupted jobs are superseded (fresh runs only) and retention is applied. The typed
 * characters are wiped when the run ends.
 */
@Singleton
class PassphraseBackupRunner @Inject constructor(
    private val engine: BackupEngine,
    private val runLock: ProfileRunLock,
    private val profileRepository: ProfileRepository,
    private val retentionExecutor: RetentionExecutor,
    private val resumeCoordinator: ResumeCoordinator,
    private val clock: EngineClock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _outcomes = MutableSharedFlow<BackupOutcome>(extraBufferCapacity = OUTCOME_BUFFER)

    /** Every finished typed-passphrase run, for snackbars on whichever screen is showing. */
    val outcomes: SharedFlow<BackupOutcome> = _outcomes.asSharedFlow()

    /**
     * Starts [profile] with [passphrase], continuing [resume] when given (its profile has the job's
     * destination pinned). Takes ownership of the array and wipes it afterwards.
     */
    fun start(profile: BackupProfile, passphrase: CharArray, resume: ResumeDecision.Resume? = null): PassphraseRunStart {
        if (!runLock.tryAcquire(profile.id)) {
            passphrase.fill('\u0000')
            return PassphraseRunStart.AlreadyRunning
        }
        val jobId = resume?.jobId ?: UUID.randomUUID().toString()
        val runProfile = resume?.profile ?: profile
        val options = BackupRunOptions(
            passphrase = passphrase,
            skipSourceIds = resume?.skipSourceIds.orEmpty(),
            jobDirName = resume?.jobDirName,
        )
        scope.launch {
            val startedAt = clock.nowMillis()
            try {
                val outcome = engine.execute(runProfile, jobId, options)
                if (outcome.isSuccessful) {
                    guard("mark run") { profileRepository.markRun(profile.id, startedAt) }
                    if (resume == null) guard("supersede") { resumeCoordinator.supersedeInterrupted(profile.id, jobId) }
                    guard("retention") {
                        val report = retentionExecutor.apply(profile)
                        if (report.failed.isNotEmpty()) DftbLog.w(TAG, "Retention left ${report.failed.size} backup(s) for a later run")
                    }
                }
                _outcomes.emit(outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.e(TAG, "Typed-passphrase backup of ${profile.id} failed to run", e)
                val now = clock.nowMillis()
                _outcomes.emit(
                    BackupOutcome(
                        jobId = jobId,
                        profileId = profile.id,
                        status = JobStatus.FAILED,
                        jobDir = null,
                        manifestPath = null,
                        artifacts = emptyList(),
                        storedBytes = 0L,
                        sourceBytes = 0L,
                        itemCount = 0,
                        startedAtMillis = startedAt,
                        finishedAtMillis = now,
                        warnings = emptyList(),
                        failure = BackupFailure(
                            code = if (e is StorageException) BackupErrorCode.STORAGE else BackupErrorCode.INTERNAL,
                            detail = e.message,
                            storageReason = (e as? StorageException)?.reason,
                        ),
                    ),
                )
            } finally {
                passphrase.fill('\u0000')
                runLock.release(profile.id)
            }
        }
        DftbLog.i(TAG, "Started typed-passphrase backup of ${profile.id} (job $jobId${if (resume != null) ", resuming" else ""})")
        return PassphraseRunStart.Started(jobId)
    }

    private suspend fun guard(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "After-run step failed: $label", e)
        }
    }

    private companion object {
        const val TAG = "PassphraseRun"
        const val OUTCOME_BUFFER = 8
    }
}
