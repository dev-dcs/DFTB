package ca.devdcs.backup.ui.shell

import ca.devdcs.backup.core.boot.ResumeSkipReason
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.progress.JobKind
import ca.devdcs.backup.core.progress.JobPhase
import ca.devdcs.backup.core.progress.JobProgress
import ca.devdcs.backup.core.schedule.NetworkRequirement
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.ScheduleFrequency
import ca.devdcs.backup.domain.model.SchedulePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class DashboardAggregatorTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_800_000_000_000L
    private val zone = ZoneOffset.UTC

    private val nas = Destination(
        id = "d1",
        name = "NAS",
        type = DestinationType.SMB,
        createdAt = 0,
        updatedAt = 0,
        lastVerifiedAt = now - hour,
        lastKnownFreeBytes = 50,
        lastKnownTotalBytes = 1_000,
    )
    private val sd = Destination(id = "d2", name = "card", type = DestinationType.LOCAL_SAF, createdAt = 0, updatedAt = 0)

    private fun profile(
        id: String,
        destinationId: String? = "d1",
        schedule: SchedulePolicy? = null,
        encrypted: Boolean = false,
        keyRef: String? = null,
        enabled: Boolean = true,
    ) = BackupProfile(
        id = id,
        name = "Profile $id",
        destinationId = destinationId,
        schedule = schedule,
        pipeline = if (encrypted) PipelineOptions(encryptionMode = EncryptionMode.AES_256_GCM, encryptionKeyRef = keyRef) else PipelineOptions.DEFAULT,
        createdAt = 0,
        updatedAt = 0,
        enabled = enabled,
    )

    private fun record(id: String, profileId: String, status: JobStatus, finishedAt: Long? = now - hour, error: String? = null) = HistoryRecord(
        id = id,
        profileId = profileId,
        destinationId = "d1",
        type = JobType.BACKUP,
        status = status,
        startedAt = now - 2 * hour,
        finishedAt = finishedAt,
        errorMessage = error,
    )

    private fun checkpoint(jobId: String, profileId: String?, type: JobType = JobType.BACKUP, updatedAt: Long = now - hour) = JobCheckpoint(
        jobId = jobId,
        profileId = profileId,
        type = type,
        status = JobStatus.INTERRUPTED,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    private fun progress(jobId: String, title: String = "Profile p1") = JobProgress(
        jobId = jobId,
        kind = JobKind.BACKUP,
        title = title,
        phase = JobPhase.RUNNING,
        startedAtMillis = now - 60_000,
        updatedAtMillis = now,
    )

    private fun work(
        profileId: String,
        periodic: Boolean,
        state: ScheduledWorkState = ScheduledWorkState.ENQUEUED,
        next: Long? = null,
        attempts: Int = 0,
    ) = ScheduledWork(profileId, periodic, state, next, attempts, NetworkRequirement.UNMETERED, requiresCharging = true, requiresDeviceIdle = false)

    private fun inputs(
        profiles: List<BackupProfile> = listOf(profile("p1")),
        lastBackups: Map<String, HistoryRecord> = emptyMap(),
        recent: List<HistoryRecord> = lastBackups.values.toList(),
        checkpoints: List<JobCheckpoint> = emptyList(),
        destinations: List<Destination> = listOf(nas),
        active: List<JobProgress> = emptyList(),
        cancels: Set<String> = emptySet(),
        work: List<ScheduledWork> = emptyList(),
        mode: OperatingMode? = OperatingMode.STANDARD,
        elevation: ElevationStatus = ElevationStatus.NOT_REQUIRED,
        notifications: Boolean = true,
    ) = DashboardInputs(
        profiles = profiles,
        lastBackups = lastBackups,
        recentHistory = recent,
        checkpoints = checkpoints,
        destinations = destinations,
        activeJobs = active,
        cancelRequests = cancels,
        scheduledWork = work,
        operatingMode = mode,
        elevation = elevation,
        notificationsEnabled = notifications,
        nowMillis = now,
        zone = zone,
    )

    @Test
    fun emptyInputsGiveEmptyModel() {
        val model = DashboardAggregator.aggregate(inputs(profiles = emptyList(), destinations = emptyList()))
        assertTrue(model.warnings.isEmpty())
        assertTrue(model.profiles.isEmpty())
        assertTrue(model.upcoming.isEmpty())
        assertTrue(model.destinations.isEmpty())
        assertTrue(model.activeJobs.isEmpty())
    }

    @Test
    fun lastStatusMapsHistoryAndDetectsDeadRunningRows() {
        val live = setOf("live")
        assertEquals(LastRunStatus.NEVER, DashboardAggregator.lastStatus(null, live))
        assertEquals(LastRunStatus.RUNNING, DashboardAggregator.lastStatus(record("live", "p1", JobStatus.RUNNING, null), live))
        assertEquals(LastRunStatus.INTERRUPTED, DashboardAggregator.lastStatus(record("dead", "p1", JobStatus.RUNNING, null), live))
        assertEquals(LastRunStatus.SUCCESS, DashboardAggregator.lastStatus(record("a", "p1", JobStatus.COMPLETED), live))
        assertEquals(LastRunStatus.WARNINGS, DashboardAggregator.lastStatus(record("a", "p1", JobStatus.COMPLETED_WITH_WARNINGS), live))
        assertEquals(LastRunStatus.FAILED, DashboardAggregator.lastStatus(record("a", "p1", JobStatus.FAILED), live))
        assertEquals(LastRunStatus.CANCELLED, DashboardAggregator.lastStatus(record("a", "p1", JobStatus.CANCELLED), live))
    }

    @Test
    fun runNowAvailabilityReflectsDestinationPassphraseAndRunningJobs() {
        val profiles = listOf(
            profile("ok"),
            profile("nodest", destinationId = null),
            profile("gone", destinationId = "deleted"),
            profile("pass", encrypted = true, keyRef = null),
            profile("stored", encrypted = true, keyRef = "vault:key"),
            profile("busy"),
        )
        val running = record("job-busy", "busy", JobStatus.RUNNING, null)
        val model = DashboardAggregator.aggregate(
            inputs(profiles = profiles, recent = listOf(running), active = listOf(progress("job-busy", "Profile busy"))),
        )
        val byId = model.profiles.associateBy { it.profile.id }
        assertEquals(RunNowAvailability.AVAILABLE, byId.getValue("ok").runNow)
        assertEquals(RunNowAvailability.NO_DESTINATION, byId.getValue("nodest").runNow)
        assertEquals(RunNowAvailability.NO_DESTINATION, byId.getValue("gone").runNow)
        assertTrue(byId.getValue("gone").destinationMissing)
        assertFalse(byId.getValue("nodest").destinationMissing)
        assertEquals(RunNowAvailability.NEEDS_PASSPHRASE, byId.getValue("pass").runNow)
        assertEquals(RunNowAvailability.AVAILABLE, byId.getValue("stored").runNow)
        assertEquals(RunNowAvailability.RUNNING, byId.getValue("busy").runNow)
        assertEquals("job-busy", byId.getValue("busy").runningJobId)
        assertEquals("busy", model.activeJobs.single().profileId)
    }

    @Test
    fun runningWorkWithoutBusJobStillCountsAsRunning() {
        val model = DashboardAggregator.aggregate(inputs(work = listOf(work("p1", periodic = false, state = ScheduledWorkState.RUNNING))))
        assertEquals(RunNowAvailability.RUNNING, model.profiles.single().runNow)
        assertTrue("running work is not an upcoming run", model.upcoming.isEmpty())
    }

    @Test
    fun interruptedBackupsAreClassifiedByTheResumePlanner() {
        val profiles = listOf(
            profile("resumable"),
            profile("pass", encrypted = true),
            profile("old"),
            profile("nodest", destinationId = "deleted"),
        )
        val checkpoints = listOf(
            checkpoint("j-res", "resumable"),
            checkpoint("j-pass", "pass"),
            checkpoint("j-old", "old", updatedAt = now - 3 * day),
            checkpoint("j-nodest", "nodest"),
            checkpoint("j-orphan", "deleted-profile"),
        )
        val warnings = DashboardAggregator.aggregate(inputs(profiles = profiles, checkpoints = checkpoints)).warnings
        assertTrue(DashboardWarning.PassphraseRequired("pass", "Profile pass", "j-pass") in warnings)
        assertTrue(DashboardWarning.InterruptedBackup("resumable", "Profile resumable", "j-res", resumable = true, reason = null) in warnings)
        assertTrue(DashboardWarning.InterruptedBackup("old", "Profile old", "j-old", resumable = false, reason = ResumeSkipReason.TOO_OLD) in warnings)
        assertTrue(
            DashboardWarning.InterruptedBackup("nodest", "Profile nodest", "j-nodest", resumable = false, reason = ResumeSkipReason.DESTINATION_MISSING) in warnings,
        )
        assertTrue("orphaned checkpoints are not shown", warnings.none { it.key.endsWith("j-orphan") })
    }

    @Test
    fun onlyTheNewestInterruptedBackupPerProfileAndNotWhileItRuns() {
        val checkpoints = listOf(
            checkpoint("older", "p1", updatedAt = now - 2 * hour),
            checkpoint("newer", "p1", updatedAt = now - hour),
        )
        val warnings = DashboardAggregator.aggregate(inputs(checkpoints = checkpoints)).warnings
        assertEquals(listOf("int:newer"), warnings.map { it.key })

        val resuming = DashboardAggregator.aggregate(
            inputs(checkpoints = listOf(checkpoint("newer", "p1")), active = listOf(progress("newer"))),
        ).warnings
        assertTrue(resuming.isEmpty())
    }

    @Test
    fun interruptedRestoreIsReportedWithoutAProfile() {
        val warnings = DashboardAggregator.aggregate(inputs(checkpoints = listOf(checkpoint("r1", null, JobType.RESTORE)))).warnings
        assertEquals(listOf(DashboardWarning.InterruptedJob("r1", JobType.RESTORE)), warnings)
    }

    @Test
    fun failedLastRunWarnsUnlessAnInterruptionAlreadyCoversTheProfile() {
        val failed = record("f1", "p1", JobStatus.FAILED, error = "Host unreachable")
        val warnings = DashboardAggregator.aggregate(inputs(lastBackups = mapOf("p1" to failed))).warnings
        assertEquals(listOf(DashboardWarning.LastRunFailed("p1", "Profile p1", "f1", "Host unreachable", now - hour)), warnings)

        val covered = DashboardAggregator.aggregate(
            inputs(lastBackups = mapOf("p1" to failed), checkpoints = listOf(checkpoint("f1", "p1"))),
        ).warnings
        assertEquals(listOf("int:f1"), covered.map { it.key })

        val succeeded = DashboardAggregator.aggregate(inputs(lastBackups = mapOf("p1" to record("s1", "p1", JobStatus.COMPLETED)))).warnings
        assertTrue(succeeded.isEmpty())
    }

    @Test
    fun environmentWarningsComeFirst() {
        val warnings = DashboardAggregator.aggregate(
            inputs(
                mode = OperatingMode.ROOT,
                elevation = ElevationStatus.UNAVAILABLE,
                notifications = false,
                lastBackups = mapOf("p1" to record("f1", "p1", JobStatus.FAILED)),
            ),
        ).warnings
        assertEquals(DashboardWarning.ElevationUnavailable(OperatingMode.ROOT), warnings[0])
        assertEquals(DashboardWarning.NotificationsDisabled, warnings[1])
        assertEquals("fail:f1", warnings[2].key)
    }

    @Test
    fun elevationWarningOnlyWhenTheModeNeedsItAndTheProbeFailed() {
        assertTrue(DashboardAggregator.aggregate(inputs(mode = OperatingMode.SHIZUKU, elevation = ElevationStatus.CHECKING)).warnings.isEmpty())
        assertTrue(DashboardAggregator.aggregate(inputs(mode = OperatingMode.SHIZUKU, elevation = ElevationStatus.AVAILABLE)).warnings.isEmpty())
        assertTrue(DashboardAggregator.aggregate(inputs(mode = OperatingMode.STANDARD, elevation = ElevationStatus.UNAVAILABLE)).warnings.isEmpty())
        assertEquals(
            listOf(DashboardWarning.ElevationUnavailable(OperatingMode.SHIZUKU)),
            DashboardAggregator.aggregate(inputs(mode = OperatingMode.SHIZUKU, elevation = ElevationStatus.UNAVAILABLE)).warnings,
        )
    }

    @Test
    fun upcomingRunsComeFromWorkManagerWithKinds() {
        val profiles = listOf(profile("a"), profile("b"), profile("c"))
        val items = listOf(
            work("a", periodic = true, next = now + 5 * hour),
            work("b", periodic = true, next = now + 2 * hour, attempts = 2),
            work("c", periodic = false, state = ScheduledWorkState.ENQUEUED, next = null),
        )
        val upcoming = DashboardAggregator.aggregate(inputs(profiles = profiles, work = items)).upcoming
        assertEquals(listOf("c", "b", "a"), upcoming.map { it.profileId })
        assertEquals(listOf(UpcomingKind.QUEUED, UpcomingKind.RETRY, UpcomingKind.SCHEDULED), upcoming.map { it.kind })
        assertNull(upcoming[0].atMillis)
        assertEquals(now + 2 * hour, upcoming[1].atMillis)
        assertEquals(2, upcoming[1].attempt)
        assertTrue(upcoming.all { it.requiresCharging && it.network == NetworkRequirement.UNMETERED })
    }

    @Test
    fun scheduledProfileWithoutQueuedWorkGetsAnExpectedSlot() {
        val policy = SchedulePolicy(frequency = ScheduleFrequency.DAILY, hour = 2, minute = 0, requireUnmetered = true)
        val local = profile("loc", destinationId = "d2", schedule = policy)
        val net = profile("net", destinationId = "d1", schedule = policy)
        val disabled = profile("off", schedule = policy, enabled = false)
        val model = DashboardAggregator.aggregate(inputs(profiles = listOf(local, net, disabled), destinations = listOf(nas, sd)))
        val byId = model.upcoming.associateBy { it.profileId }
        assertEquals(setOf("loc", "net"), byId.keys)
        val expected = byId.getValue("loc")
        assertEquals(UpcomingKind.EXPECTED, expected.kind)
        val slot = expected.atMillis!!
        assertTrue(slot > now && slot - now <= day)
        assertEquals(0L, (slot / hour) % 24 - 2)
        // Network need follows the destination type, as the scheduler does.
        assertEquals(NetworkRequirement.NONE, expected.network)
        assertEquals(NetworkRequirement.UNMETERED, byId.getValue("net").network)
        assertEquals(UpcomingKind.EXPECTED, model.profiles.first { it.profile.id == "loc" }.nextRun?.kind)
    }

    @Test
    fun queuedPeriodicWorkSuppressesTheExpectedSlot() {
        val policy = SchedulePolicy(frequency = ScheduleFrequency.DAILY, hour = 2)
        val model = DashboardAggregator.aggregate(
            inputs(profiles = listOf(profile("p1", schedule = policy)), work = listOf(work("p1", periodic = true, next = now + hour))),
        )
        assertEquals(listOf(UpcomingKind.SCHEDULED), model.upcoming.map { it.kind })
    }

    @Test
    fun destinationHealthFlagsStaleChecksAndLowSpace() {
        val stale = nas.copy(id = "d3", name = "Old", lastVerifiedAt = now - 8 * day, lastKnownFreeBytes = 500, lastKnownTotalBytes = 1_000)
        val model = DashboardAggregator.aggregate(inputs(destinations = listOf(stale, sd, nas)))
        assertEquals(listOf("card", "NAS", "Old"), model.destinations.map { it.destination.name })
        val byId = model.destinations.associateBy { it.destination.id }
        assertEquals(VerificationState.NEVER, byId.getValue("d2").verification)
        assertNull(byId.getValue("d2").usedFraction)
        assertFalse(byId.getValue("d2").lowSpace)
        assertEquals(VerificationState.RECENT, byId.getValue("d1").verification)
        assertTrue(byId.getValue("d1").lowSpace)
        assertEquals(0.95f, byId.getValue("d1").usedFraction!!, 0.0001f)
        assertEquals(VerificationState.STALE, byId.getValue("d3").verification)
        assertFalse(byId.getValue("d3").lowSpace)
    }

    @Test
    fun activeJobsCarryCancelRequestsAndProfileLinksFromTheJournal() {
        val model = DashboardAggregator.aggregate(
            inputs(
                checkpoints = listOf(checkpoint("j1", "p1").copy(status = JobStatus.RUNNING)),
                active = listOf(progress("j1"), progress("j2", "Restore")),
                cancels = setOf("j1"),
            ),
        )
        assertEquals(listOf("j1", "j2"), model.activeJobs.map { it.progress.jobId })
        assertTrue(model.activeJobs[0].cancelRequested)
        assertEquals("p1", model.activeJobs[0].profileId)
        assertNull(model.activeJobs[1].profileId)
        assertFalse(model.activeJobs[1].cancelRequested)
    }
}
