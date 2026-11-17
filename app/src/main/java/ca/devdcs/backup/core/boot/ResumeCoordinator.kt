package ca.devdcs.backup.core.boot

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.core.schedule.BackupScheduler
import ca.devdcs.backup.core.schedule.ScheduleNotifier
import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType
import ca.devdcs.backup.domain.repository.CheckpointRepository
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.domain.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** What one boot-time resume pass did, for logs and tests. */
data class ResumeSummary(
    val repairedStale: Int,
    val resumed: List<String>,
    val needPassphrase: List<String>,
    val notified: List<String>,
    val skipped: Map<String, ResumeSkipReason>,
)

/**
 * Continues interrupted work after a reboot or process death.
 *
 * Exists so a backup cut short (reboot, OOM kill, Android 15's dataSync time limit, a network that
 * stayed down) picks up where it stopped instead of starting over or being forgotten: it repairs
 * stale RUNNING rows, then for every INTERRUPTED backup checkpoint the user allowed to continue it
 * enqueues the profile's backup worker in resume mode (same job id, job folder and destination,
 * completed sources skipped). Passphrase-encrypted profiles and restores are never run
 * unattended; the user is notified once per interrupted attempt instead.
 *
 * Safety rule: only RUNNING rows not updated since this process started are treated as
 * stale; a job genuinely running in this process has written its row after process start.
 */
@Singleton
class ResumeCoordinator @Inject constructor(
    private val checkpointRepository: CheckpointRepository,
    private val profileRepository: ProfileRepository,
    private val destinationRepository: DestinationRepository,
    private val scheduler: BackupScheduler,
    private val staleJobRepair: StaleJobRepair,
    private val notifier: ScheduleNotifier,
    private val clock: EngineClock,
) {

    suspend fun resumeAfterBoot(processStartedAtMillis: Long): ResumeSummary {
        val now = clock.nowMillis()
        val repaired = staleJobRepair.repair(processStartedAtMillis).size

        val resumed = ArrayList<String>()
        val needPassphrase = ArrayList<String>()
        val notified = ArrayList<String>()
        val skipped = LinkedHashMap<String, ResumeSkipReason>()

        // Backups continue only when flagged resume-after-reboot (the planner checks); other job
        // types are announced whatever the flag, since they are never re-run.
        val candidates = checkpointRepository.resumable()
        val destinationIds = destinationRepository.getAll().map { it.id }.toSet()

        for (checkpoint in candidates) {
            try {
                val profile = checkpoint.profileId?.let { profileRepository.get(it) }
                val decision = ResumePlanner.decide(
                    checkpoint = checkpoint,
                    profile = profile,
                    nowMillis = now,
                    requireRebootFlag = true,
                    destinationExists = { it in destinationIds },
                )
                when (decision) {
                    is ResumeDecision.Resume -> {
                        scheduler.enqueueResume(decision.profile.id, decision.jobId)
                        resumed += decision.jobId
                    }
                    is ResumeDecision.NeedsPassphrase -> {
                        if (markNotifiedOnce(checkpoint)) notifier.passphraseRequired(decision.profile.id, decision.profile.name)
                        needPassphrase += decision.jobId
                    }
                    is ResumeDecision.NotifyOnly -> {
                        // Already INTERRUPTED (repaired above or by the engine); never re-run unattended.
                        if (markNotifiedOnce(checkpoint)) {
                            notifier.jobInterrupted(decision.jobId, decision.type)
                            notified += decision.jobId
                        }
                    }
                    is ResumeDecision.Skip -> skipped[decision.jobId] = decision.reason
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.e(TAG, "Could not handle interrupted job ${checkpoint.jobId}", e)
            }
        }
        if (skipped.isNotEmpty()) DftbLog.i(TAG, "Not resumed: $skipped")
        DftbLog.i(TAG, "Resume pass: ${resumed.size} resumed, ${needPassphrase.size} need a passphrase, ${notified.size} notified")
        return ResumeSummary(repaired, resumed, needPassphrase, notified, skipped)
    }

    /**
     * After a fresh (non-resume) backup of [profileId] succeeded, older interrupted backups of the
     * profile are superseded: they are closed as CANCELLED so no later pass resumes a stale job.
     * Their partial folders stay on the destination (they have no manifest, retention ignores them).
     */
    suspend fun supersedeInterrupted(profileId: String, exceptJobId: String) {
        val now = clock.nowMillis()
        checkpointRepository.getForProfile(profileId)
            .filter { it.jobId != exceptJobId && it.type == JobType.BACKUP && it.status == JobStatus.INTERRUPTED }
            .forEach { checkpointRepository.setStatus(it.jobId, JobStatus.CANCELLED, now) }
    }

    /**
     * Records in the checkpoint extras that this attempt was already announced, so each reboot
     * does not repeat the same alert. Returns true when the caller should notify now.
     */
    private suspend fun markNotifiedOnce(checkpoint: JobCheckpoint): Boolean {
        val marker = checkpoint.attempt.toString()
        if (checkpoint.state.extras[EXTRA_NOTIFIED_ATTEMPT] == marker) return false
        val updated = checkpoint.copy(state = checkpoint.state.copy(extras = checkpoint.state.extras + (EXTRA_NOTIFIED_ATTEMPT to marker)))
        checkpointRepository.save(updated)
        return true
    }

    companion object {
        private const val TAG = "Resume"

        /** Checkpoint extras key: attempt number whose interruption the user was already told about. */
        const val EXTRA_NOTIFIED_ATTEMPT = "resumeNotifiedAttempt"
    }
}
