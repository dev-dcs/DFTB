package ca.devdcs.backup.core.schedule

import android.content.Context
import android.os.BatteryManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import ca.devdcs.backup.core.boot.ResumeCoordinator
import ca.devdcs.backup.core.boot.ResumeDecision
import ca.devdcs.backup.core.boot.ResumePlanner
import ca.devdcs.backup.core.boot.StaleJobRepair
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.engine.BackupEngine
import ca.devdcs.backup.core.pipeline.engine.BackupMessages
import ca.devdcs.backup.core.pipeline.engine.BackupOutcome
import ca.devdcs.backup.core.pipeline.engine.BackupRunOptions
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.core.retention.RetentionExecutor
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.repository.CheckpointRepository
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.domain.repository.ProfileRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Runs one backup of one profile under WorkManager: scheduled (periodic), "Back up now"
 * (expedited one-off) or resume of an interrupted job.
 *
 * Exists so unattended backups get the same engine as interactive ones while respecting what only
 * a worker knows: it runs in the foreground (dataSync), gates scheduled runs to once per slot and
 * the policy's minimum battery level, maps the outcome to retry-with-backoff or failure, applies
 * retention after a success, and re-arms the next exact slot. When the system stops it (constraint
 * lost, quota, Android 15's 6-hour dataSync limit) the engine records the job INTERRUPTED and the
 * next run continues it via the checkpoint instead of starting over.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: BackupEngine,
    private val profileRepository: ProfileRepository,
    private val destinationRepository: DestinationRepository,
    private val checkpointRepository: CheckpointRepository,
    private val scheduler: BackupScheduler,
    private val resumeCoordinator: ResumeCoordinator,
    private val retentionExecutor: RetentionExecutor,
    private val runLock: ProfileRunLock,
    private val staleJobRepair: StaleJobRepair,
    private val notifier: ScheduleNotifier,
    private val messages: BackupMessages,
    private val clock: EngineClock,
) : CoroutineWorker(appContext, params) {

    private val profileId: String? get() = inputData.getString(KEY_PROFILE_ID)
    private val trigger: String get() = inputData.getString(KEY_TRIGGER) ?: TRIGGER_SCHEDULE

    /** Pre-minted so the foreground notification's cancel button can target the job. */
    private var plannedJobId: String = UUID.randomUUID().toString()
    private var profileName: String = ""

    override suspend fun doWork(): Result {
        val id = profileId ?: return Result.failure().also { DftbLog.e(TAG, "Backup work without a profile id") }
        val profile = profileRepository.get(id)
        if (profile == null) {
            DftbLog.w(TAG, "Profile $id no longer exists; cancelling its schedule")
            scheduler.cancel(id)
            return Result.success()
        }
        profileName = profile.name
        if (trigger == TRIGGER_SCHEDULE && !profile.isScheduled) {
            DftbLog.i(TAG, "Profile $id is no longer scheduled; cancelling")
            scheduler.cancel(id)
            return Result.success()
        }
        // A previous process may have died mid-backup without a reboot: make its rows resumable.
        runCatchingLogged("stale repair") { staleJobRepair.repair() }
        if (!runLock.tryAcquire(id)) {
            DftbLog.i(TAG, "Profile $id is already running; skipping this $trigger run")
            return Result.success()
        }
        return try {
            run(profile)
        } catch (e: CancellationException) {
            logStop(id)
            throw e
        } finally {
            runLock.release(id)
        }
    }

    private suspend fun run(profile: BackupProfile): Result {
        val now = clock.nowMillis()
        val resume = findResume(profile, now)
        if (resume is ResumeDecision.NeedsPassphrase || (resume == null && ResumePlanner.requiresTypedPassphrase(profile))) {
            DftbLog.w(TAG, "Profile ${profile.id} needs a typed passphrase; cannot run unattended")
            notifier.passphraseRequired(profile.id, profile.name)
            rearm(profile)
            return Result.failure()
        }
        val resumeRun = resume as? ResumeDecision.Resume
        if (trigger == TRIGGER_RESUME && resumeRun == null) {
            // The job finished, was cancelled or became stale since the resume was queued.
            DftbLog.i(TAG, "Nothing left to resume for profile ${profile.id}")
            return Result.success()
        }

        if (trigger == TRIGGER_SCHEDULE && resumeRun == null && runAttemptCount == 0) {
            val policy = profile.schedule
            if (policy != null && !ScheduleMath.isDue(policy, now, clock.zone(), profile.lastRunAt)) {
                DftbLog.i(TAG, "Profile ${profile.id}: slot already served (last run ${profile.lastRunAt}); skipping")
                rearm(profile)
                return Result.success()
            }
        }
        if (trigger != TRIGGER_MANUAL && !batteryAllows(profile)) {
            DftbLog.i(TAG, "Profile ${profile.id}: battery below ${profile.schedule?.minBatteryPercent}%; retrying later")
            return Result.retry()
        }

        val jobId = resumeRun?.jobId ?: plannedJobId
        plannedJobId = jobId
        val runProfile = resumeRun?.profile ?: profile
        val options = resumeRun?.runOptions() ?: BackupRunOptions.DEFAULT
        promoteToForeground(profile, jobId)
        DftbLog.i(
            TAG,
            "Starting $trigger backup of ${profile.id} (job $jobId" +
                (resumeRun?.let { ", resuming ${it.skipSourceIds.size} done source(s) in ${it.jobDirName}" } ?: "") + ")",
        )

        val outcome = engine.execute(runProfile, jobId, options)
        val decision = BackupResultMapper.decide(outcome, runAttemptCount)
        DftbLog.i(TAG, "Backup ${outcome.jobId} of ${profile.id} ended ${outcome.status} -> $decision")
        // The slot counts as served only when the run is final-good; a failed or retried run stays
        // due, so neither a re-arm nor a boot catch-up can silently skip it. Start time is recorded.
        if (decision == WorkDecision.SUCCESS) runCatchingLogged("mark run") { profileRepository.markRun(profile.id, now) }

        if (outcome.isSuccessful) {
            if (resumeRun == null) runCatchingLogged("supersede") { resumeCoordinator.supersedeInterrupted(profile.id, jobId) }
            runCatchingLogged("retention") {
                val report = retentionExecutor.apply(profile)
                if (report.failed.isNotEmpty()) DftbLog.w(TAG, "Retention left ${report.failed.size} backup(s) for a later run")
            }
        } else if (decision == WorkDecision.FAILURE && trigger != TRIGGER_MANUAL && BackupResultMapper.needsUserAction(outcome)) {
            notifier.backupFailed(profile.id, profile.name, failureText(outcome))
        }
        if (decision != WorkDecision.RETRY) rearm(profile)
        return when (decision) {
            WorkDecision.SUCCESS -> Result.success()
            WorkDecision.RETRY -> Result.retry()
            WorkDecision.FAILURE -> Result.failure()
        }
    }

    /**
     * The interrupted job this run continues: the one named in the input (boot resume), else, for
     * scheduled runs and retries of "Back up now", the profile's latest interrupted backup. A first
     * "Back up now" attempt starts fresh.
     */
    private suspend fun findResume(profile: BackupProfile, now: Long): ResumeDecision? {
        val explicit = inputData.getString(KEY_RESUME_JOB_ID)
        if (explicit == null && trigger == TRIGGER_MANUAL && runAttemptCount == 0) return null
        val checkpoints = checkpointRepository.getForProfile(profile.id)
        val checkpoint = if (explicit != null) {
            checkpoints.firstOrNull { it.jobId == explicit }
        } else {
            ResumePlanner.latestInterruptedBackup(checkpoints, profile.id)
        } ?: return null
        val destinationIds = destinationRepository.getAll().map { it.id }.toSet()
        val decision = ResumePlanner.decide(
            checkpoint = checkpoint,
            profile = profile,
            nowMillis = now,
            requireRebootFlag = false,
            destinationExists = { it in destinationIds },
        )
        return when (decision) {
            is ResumeDecision.Resume, is ResumeDecision.NeedsPassphrase -> decision
            else -> {
                DftbLog.i(TAG, "Not resuming ${checkpoint.jobId}: $decision")
                null
            }
        }
    }

    private suspend fun promoteToForeground(profile: BackupProfile, jobId: String) {
        try {
            setForeground(notifier.foregroundInfo(profile.id, profile.name, jobId, trigger))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (an IllegalStateException: background start,
            // or the dataSync time budget is exhausted on Android 15+) or a SecurityException. Run as
            // regular background work; if the system stops it, the checkpoint resumes it later.
            DftbLog.w(TAG, "Could not run in the foreground; continuing as background work", e)
        }
    }

    /** Expedited work on API < 31 runs as a foreground service and asks for this up front. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifier.foregroundInfo(profileId.orEmpty(), profileName.ifEmpty { profileId.orEmpty() }, plannedJobId, trigger)

    private fun batteryAllows(profile: BackupProfile): Boolean {
        val min = profile.schedule?.minBatteryPercent ?: return true
        if (min <= 0) return true
        val manager = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return true
        if (manager.isCharging) return true
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        // Integer.MIN_VALUE / 0 when the property is unsupported: do not block on unknown.
        return level <= 0 || level >= min
    }

    private suspend fun rearm(profile: BackupProfile) {
        if (!profile.isScheduled) return
        runCatchingLogged("re-arm") {
            // Fresh copy: markRun may have changed lastRunAt, which the next slot depends on.
            scheduler.schedule(profileRepository.get(profile.id) ?: profile)
        }
    }

    private fun failureText(outcome: BackupOutcome): String? = outcome.failure?.let { messages.failure(it) }

    private fun logStop(profileId: String) {
        val reason = stopReason
        val label = when (reason) {
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "foreground-service time limit (Android 15+ dataSync cap)"
            WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled by the app"
            WorkInfo.STOP_REASON_TIMEOUT -> "job timeout"
            WorkInfo.STOP_REASON_QUOTA -> "quota"
            WorkInfo.STOP_REASON_USER -> "stopped by the user"
            else -> "stop reason $reason"
        }
        // The engine has recorded the job INTERRUPTED (resumable); the next run continues it.
        DftbLog.w(TAG, "Backup of $profileId stopped: $label; it will resume from its checkpoint")
    }

    private suspend fun runCatchingLogged(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.e(TAG, "Post-backup step failed: $label", e)
        }
    }

    companion object {
        private const val TAG = "BackupWorker"

        const val KEY_PROFILE_ID = "profileId"
        const val KEY_TRIGGER = "trigger"
        const val KEY_RESUME_JOB_ID = "resumeJobId"

        const val TRIGGER_SCHEDULE = "schedule"
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_RESUME = "resume"

        fun inputData(profileId: String, trigger: String, resumeJobId: String?): Data = Data.Builder()
            .putString(KEY_PROFILE_ID, profileId)
            .putString(KEY_TRIGGER, trigger)
            .apply { resumeJobId?.let { putString(KEY_RESUME_JOB_ID, it) } }
            .build()
    }
}
