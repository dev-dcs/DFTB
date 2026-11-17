package ca.devdcs.backup.core.boot

import ca.devdcs.backup.core.pipeline.engine.BackupJob
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.CheckpointState
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType
import ca.devdcs.backup.domain.model.PipelineOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePlannerTest {

    private val now = 10_000_000_000L

    private fun profile(pipeline: PipelineOptions = PipelineOptions.DEFAULT, enabled: Boolean = true) = BackupProfile(
        id = "p1",
        name = "Phone",
        destinationId = "current-dest",
        pipeline = pipeline,
        createdAt = 0L,
        updatedAt = 0L,
        enabled = enabled,
    )

    private fun checkpoint(
        status: JobStatus = JobStatus.INTERRUPTED,
        type: JobType = JobType.BACKUP,
        flag: Boolean = true,
        updatedAt: Long = now - 60_000L,
        extras: Map<String, String> = mapOf(BackupJob.EXTRA_JOB_DIR to "Phone_2026-10-19_0200", BackupJob.EXTRA_DESTINATION_ID to "job-dest"),
        completed: List<String> = listOf("partition:boot", "app:com.example"),
    ) = JobCheckpoint(
        jobId = "job-1",
        profileId = "p1",
        type = type,
        status = status,
        attempt = 2,
        state = CheckpointState(completedItems = completed, extras = extras),
        createdAt = now - 3_600_000L,
        updatedAt = updatedAt,
        resumeAfterReboot = flag,
    )

    private val allDestinations: (String) -> Boolean = { true }

    @Test
    fun resumeBuildsOptionsFromCheckpoint() {
        val decision = ResumePlanner.decide(checkpoint(), profile(), now, requireRebootFlag = true, destinationExists = allDestinations)
        decision as ResumeDecision.Resume
        assertEquals("job-1", decision.jobId)
        assertEquals("job-dest", decision.profile.destinationId)
        val options = decision.runOptions()
        assertEquals(setOf("partition:boot", "app:com.example"), options.skipSourceIds)
        assertEquals("Phone_2026-10-19_0200", options.jobDirName)
        assertNull(options.passphrase)
    }

    @Test
    fun missingExtrasFallBackToProfileDestinationAndNewFolder() {
        val decision = ResumePlanner.decide(checkpoint(extras = emptyMap(), completed = emptyList()), profile(), now, true, allDestinations)
        decision as ResumeDecision.Resume
        assertEquals("current-dest", decision.profile.destinationId)
        assertNull(decision.jobDirName)
        assertTrue(decision.skipSourceIds.isEmpty())
    }

    @Test
    fun onlyInterruptedRowsAreResumed() {
        val running = ResumePlanner.decide(checkpoint(status = JobStatus.RUNNING), profile(), now, true, allDestinations)
        assertEquals(ResumeDecision.Skip("job-1", ResumeSkipReason.NOT_INTERRUPTED), running)
        val done = ResumePlanner.decide(checkpoint(status = JobStatus.COMPLETED), profile(), now, true, allDestinations)
        assertEquals(ResumeSkipReason.NOT_INTERRUPTED, (done as ResumeDecision.Skip).reason)
    }

    @Test
    fun rebootFlagHonouredOnlyOnBootPath() {
        val unflagged = checkpoint(flag = false)
        assertEquals(ResumeSkipReason.NOT_FLAGGED, (ResumePlanner.decide(unflagged, profile(), now, true, allDestinations) as ResumeDecision.Skip).reason)
        assertTrue(ResumePlanner.decide(unflagged, profile(), now, false, allDestinations) is ResumeDecision.Resume)
    }

    @Test
    fun restoresAreNeverResumed() {
        val decision = ResumePlanner.decide(checkpoint(type = JobType.RESTORE), profile(), now, true, allDestinations)
        assertEquals(ResumeDecision.NotifyOnly("job-1", JobType.RESTORE), decision)
    }

    @Test
    fun passphraseProfilesNeedTheUser() {
        val typed = profile(PipelineOptions(encryptionMode = EncryptionMode.AES_256_GCM, encryptionKeyRef = null))
        assertTrue(ResumePlanner.decide(checkpoint(), typed, now, true, allDestinations) is ResumeDecision.NeedsPassphrase)
        val vault = profile(PipelineOptions(encryptionMode = EncryptionMode.AES_256_GCM, encryptionKeyRef = "enckey-1"))
        assertTrue(ResumePlanner.decide(checkpoint(), vault, now, true, allDestinations) is ResumeDecision.Resume)
    }

    @Test
    fun staleMissingOrDisabledAreSkipped() {
        val old = checkpoint(updatedAt = now - ResumePlanner.MAX_AUTO_RESUME_AGE_MILLIS - 1)
        assertEquals(ResumeSkipReason.TOO_OLD, (ResumePlanner.decide(old, profile(), now, true, allDestinations) as ResumeDecision.Skip).reason)
        assertEquals(ResumeSkipReason.PROFILE_MISSING, (ResumePlanner.decide(checkpoint(), null, now, true, allDestinations) as ResumeDecision.Skip).reason)
        assertEquals(
            ResumeSkipReason.PROFILE_DISABLED,
            (ResumePlanner.decide(checkpoint(), profile(enabled = false), now, true, allDestinations) as ResumeDecision.Skip).reason,
        )
        assertEquals(
            ResumeSkipReason.DESTINATION_MISSING,
            (ResumePlanner.decide(checkpoint(), profile(), now, true, destinationExists = { false }) as ResumeDecision.Skip).reason,
        )
    }

    @Test
    fun latestInterruptedBackupPicksNewestOfProfile() {
        val a = checkpoint().copy(jobId = "a", updatedAt = 1L)
        val b = checkpoint().copy(jobId = "b", updatedAt = 5L)
        val other = checkpoint().copy(jobId = "c", profileId = "p2", updatedAt = 9L)
        val restore = checkpoint(type = JobType.RESTORE).copy(jobId = "d", updatedAt = 8L)
        assertEquals("b", ResumePlanner.latestInterruptedBackup(listOf(a, b, other, restore), "p1")?.jobId)
        assertNull(ResumePlanner.latestInterruptedBackup(listOf(other), "p1"))
    }
}
