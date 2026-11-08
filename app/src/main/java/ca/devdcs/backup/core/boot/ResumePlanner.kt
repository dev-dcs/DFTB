package ca.devdcs.backup.core.boot

import ca.devdcs.backup.core.pipeline.engine.BackupJob
import ca.devdcs.backup.core.pipeline.engine.BackupRunOptions
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType

/** Why an interrupted job is not continued automatically. */
enum class ResumeSkipReason {
    /** RUNNING (live in this process) or already finished. */
    NOT_INTERRUPTED,
    /** The user did not allow continuing after a reboot. */
    NOT_FLAGGED,
    NO_PROFILE_LINK,
    PROFILE_MISSING,
    PROFILE_DISABLED,
    /** The job's destination no longer exists. */
    DESTINATION_MISSING,
    /** Last progress is too old; a fresh backup is more useful than finishing a stale one. */
    TOO_OLD,
}

/** What to do with one non-terminal checkpoint. */
sealed interface ResumeDecision {
    val jobId: String

    /**
     * Re-run the engine on the same job: same [jobId] (history row and journal continue), same job
     * folder, same destination, skipping sources that already completed.
     */
    data class Resume(
        override val jobId: String,
        /** The profile with its destination pinned to the one the job was writing to. */
        val profile: BackupProfile,
        val skipSourceIds: Set<String>,
        val jobDirName: String?,
    ) : ResumeDecision {
        /** Fresh options per run: [BackupRunOptions] owns a wipeable passphrase, so it is never shared. */
        fun runOptions(): BackupRunOptions = BackupRunOptions(skipSourceIds = skipSourceIds, jobDirName = jobDirName)
    }

    /** The profile encrypts with a typed passphrase that is not stored; only the user can continue. */
    data class NeedsPassphrase(override val jobId: String, val profile: BackupProfile) : ResumeDecision

    /** Restores (and verify/sync jobs) are never re-run unattended; the user is told instead. */
    data class NotifyOnly(override val jobId: String, val type: JobType) : ResumeDecision

    data class Skip(override val jobId: String, val reason: ResumeSkipReason) : ResumeDecision
}

/**
 * Turns a checkpoint plus the current profile into a [ResumeDecision]. Exists as a pure function
 * so the resume rules (only INTERRUPTED rows, flag honoured, destination pinned from the
 * checkpoint extras, completed sources skipped, passphrase profiles never unattended) are tested
 * without WorkManager or Room.
 */
object ResumePlanner {

    /** Interrupted jobs whose last progress is older than this start over instead. */
    const val MAX_AUTO_RESUME_AGE_MILLIS = 48L * 60 * 60 * 1000

    /** True when unattended runs are impossible: encryption with no stored key reference. */
    fun requiresTypedPassphrase(profile: BackupProfile): Boolean =
        profile.pipeline.isEncrypted && profile.pipeline.encryptionKeyRef.isNullOrBlank()

    /**
     * @param requireRebootFlag true on the boot path (honour [JobCheckpoint.resumeAfterReboot]);
     * false when the profile's own scheduled run picks its interrupted job back up.
     * @param destinationExists whether a destination id is still configured.
     */
    fun decide(
        checkpoint: JobCheckpoint,
        profile: BackupProfile?,
        nowMillis: Long,
        requireRebootFlag: Boolean,
        destinationExists: (String) -> Boolean,
        maxAgeMillis: Long = MAX_AUTO_RESUME_AGE_MILLIS,
    ): ResumeDecision {
        val jobId = checkpoint.jobId
        if (checkpoint.status != JobStatus.INTERRUPTED) return ResumeDecision.Skip(jobId, ResumeSkipReason.NOT_INTERRUPTED)
        if (checkpoint.type != JobType.BACKUP) return ResumeDecision.NotifyOnly(jobId, checkpoint.type)
        if (requireRebootFlag && !checkpoint.resumeAfterReboot) return ResumeDecision.Skip(jobId, ResumeSkipReason.NOT_FLAGGED)
        if (checkpoint.profileId == null) return ResumeDecision.Skip(jobId, ResumeSkipReason.NO_PROFILE_LINK)
        if (profile == null || profile.id != checkpoint.profileId) return ResumeDecision.Skip(jobId, ResumeSkipReason.PROFILE_MISSING)
        if (!profile.enabled) return ResumeDecision.Skip(jobId, ResumeSkipReason.PROFILE_DISABLED)
        if (nowMillis - checkpoint.updatedAt > maxAgeMillis) return ResumeDecision.Skip(jobId, ResumeSkipReason.TOO_OLD)

        val extras = checkpoint.state.extras
        val destinationId = extras[BackupJob.EXTRA_DESTINATION_ID]?.takeIf { it.isNotBlank() } ?: profile.destinationId
        if (destinationId == null || !destinationExists(destinationId)) return ResumeDecision.Skip(jobId, ResumeSkipReason.DESTINATION_MISSING)
        if (requiresTypedPassphrase(profile)) return ResumeDecision.NeedsPassphrase(jobId, profile)

        return ResumeDecision.Resume(
            jobId = jobId,
            profile = profile.copy(destinationId = destinationId),
            skipSourceIds = checkpoint.state.completedItems.toSet(),
            jobDirName = extras[BackupJob.EXTRA_JOB_DIR]?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The interrupted backup a profile's own run should continue: the most recently updated
     * INTERRUPTED BACKUP checkpoint of [profileId], or null.
     */
    fun latestInterruptedBackup(checkpoints: List<JobCheckpoint>, profileId: String): JobCheckpoint? =
        checkpoints.filter { it.profileId == profileId && it.type == JobType.BACKUP && it.status == JobStatus.INTERRUPTED }
            .maxByOrNull { it.updatedAt }
}
