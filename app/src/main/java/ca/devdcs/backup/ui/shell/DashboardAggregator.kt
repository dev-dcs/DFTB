package ca.devdcs.backup.ui.shell

import ca.devdcs.backup.core.boot.ResumeDecision
import ca.devdcs.backup.core.boot.ResumePlanner
import ca.devdcs.backup.core.boot.ResumeSkipReason
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.progress.JobKind
import ca.devdcs.backup.core.progress.JobProgress
import ca.devdcs.backup.core.schedule.NetworkRequirement
import ca.devdcs.backup.core.schedule.ScheduleMath
import ca.devdcs.backup.core.schedule.ScheduleSpecs
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType
import java.time.ZoneId

/** WorkManager state of one backup work item, stripped of WorkManager types (see [ScheduledWorkSource]). */
enum class ScheduledWorkState { ENQUEUED, BLOCKED, RUNNING }

/** One unfinished backup work item of a profile: its periodic schedule or a one-off run. */
data class ScheduledWork(
    val profileId: String,
    val periodic: Boolean,
    val state: ScheduledWorkState,
    /** WorkManager's next planned start; null when it does not know (running, or blocked). */
    val nextRunAtMillis: Long?,
    val runAttemptCount: Int,
    val network: NetworkRequirement,
    val requiresCharging: Boolean,
    val requiresDeviceIdle: Boolean,
)

/** Whether the elevated access the chosen mode depends on is usable right now. */
enum class ElevationStatus { NOT_REQUIRED, CHECKING, AVAILABLE, UNAVAILABLE }

/** Everything the dashboard is computed from; a plain value so the aggregation is unit-tested. */
data class DashboardInputs(
    val profiles: List<BackupProfile>,
    /** profileId → newest BACKUP history record of that profile. */
    val lastBackups: Map<String, HistoryRecord>,
    /** Recent history, newest first (job → profile links of running jobs). */
    val recentHistory: List<HistoryRecord>,
    val checkpoints: List<JobCheckpoint>,
    val destinations: List<Destination>,
    val activeJobs: List<JobProgress>,
    val cancelRequests: Set<String>,
    val scheduledWork: List<ScheduledWork>,
    val operatingMode: OperatingMode?,
    val elevation: ElevationStatus,
    val notificationsEnabled: Boolean,
    val nowMillis: Long,
    val zone: ZoneId,
)

/** Outcome of a profile's newest backup as the dashboard labels it. */
enum class LastRunStatus { NEVER, RUNNING, SUCCESS, WARNINGS, FAILED, CANCELLED, INTERRUPTED }

/** Whether "Back up now" can be offered for a profile, and why not. */
enum class RunNowAvailability { AVAILABLE, RUNNING, NEEDS_PASSPHRASE, NO_DESTINATION }

/** How an upcoming run is known: from WorkManager, or computed because nothing is queued yet. */
enum class UpcomingKind { SCHEDULED, EXPECTED, RETRY, QUEUED, RUNNING }

data class UpcomingRun(
    val profileId: String,
    val profileName: String,
    val kind: UpcomingKind,
    /** Null for [UpcomingKind.QUEUED] / [UpcomingKind.RUNNING] (waiting on conditions, not a time). */
    val atMillis: Long?,
    val attempt: Int,
    val network: NetworkRequirement,
    val requiresCharging: Boolean,
    val requiresDeviceIdle: Boolean,
)

data class ActiveJobCard(
    val progress: JobProgress,
    val profileId: String?,
    val cancelRequested: Boolean,
)

data class ProfileCard(
    val profile: BackupProfile,
    val lastBackup: HistoryRecord?,
    val lastStatus: LastRunStatus,
    val destination: Destination?,
    /** The profile names a destination id that no longer exists. */
    val destinationMissing: Boolean,
    val runningJobId: String?,
    val runNow: RunNowAvailability,
    val nextRun: UpcomingRun?,
)

/** Freshness of a destination's cached capacity / reachability check. */
enum class VerificationState { NEVER, RECENT, STALE }

data class DestinationHealth(
    val destination: Destination,
    val verification: VerificationState,
    val usedFraction: Float?,
    val lowSpace: Boolean,
)

/** Something on the dashboard the user should act on. [key] is stable for list diffing. */
sealed interface DashboardWarning {
    val key: String

    data class ElevationUnavailable(val mode: OperatingMode) : DashboardWarning {
        override val key: String get() = "elevation"
    }

    data object NotificationsDisabled : DashboardWarning {
        override val key: String get() = "notifications"
    }

    /** An interrupted backup of a typed-passphrase profile: only the user can continue it. */
    data class PassphraseRequired(val profileId: String, val profileName: String, val jobId: String) : DashboardWarning {
        override val key: String get() = "pass:$jobId"
    }

    /** An interrupted backup; [resumable] = continuing it would pick up where it stopped. */
    data class InterruptedBackup(
        val profileId: String,
        val profileName: String,
        val jobId: String,
        val resumable: Boolean,
        val reason: ResumeSkipReason?,
    ) : DashboardWarning {
        override val key: String get() = "int:$jobId"
    }

    /** An interrupted restore / verify / sync; these are never continued automatically. */
    data class InterruptedJob(val jobId: String, val type: JobType) : DashboardWarning {
        override val key: String get() = "job:$jobId"
    }

    data class LastRunFailed(
        val profileId: String,
        val profileName: String,
        val recordId: String,
        val message: String?,
        val finishedAtMillis: Long?,
    ) : DashboardWarning {
        override val key: String get() = "fail:$recordId"
    }
}

data class DashboardModel(
    val warnings: List<DashboardWarning>,
    val activeJobs: List<ActiveJobCard>,
    val profiles: List<ProfileCard>,
    val upcoming: List<UpcomingRun>,
    val destinations: List<DestinationHealth>,
)

/**
 * Pure aggregation of repositories, the progress bus and WorkManager state into the dashboard.
 * Exists so the rules (which interruption is resumable, what counts as a failed last run, which
 * time is the next run, when space is low) are decided once and tested, not in composables.
 */
object DashboardAggregator {

    /** A destination check older than this is flagged as stale. */
    const val STALE_VERIFICATION_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    /** Free space below this fraction of capacity is flagged as low. */
    const val LOW_SPACE_FRACTION: Double = 0.10

    fun aggregate(input: DashboardInputs): DashboardModel {
        val now = input.nowMillis
        val activeIds = input.activeJobs.map { it.jobId }.toSet()
        val destinationsById = input.destinations.associateBy { it.id }
        val profilesById = input.profiles.associateBy { it.id }
        val jobProfiles = jobProfileLinks(input.checkpoints, input.recentHistory)

        val activeCards = input.activeJobs.map { job ->
            ActiveJobCard(progress = job, profileId = jobProfiles[job.jobId], cancelRequested = job.jobId in input.cancelRequests)
        }
        val runningByProfile: Map<String, String> = input.activeJobs
            .filter { it.kind == JobKind.BACKUP }
            .mapNotNull { job -> jobProfiles[job.jobId]?.let { it to job.jobId } }
            .toMap()

        val upcomingByProfile: Map<String, List<UpcomingRun>> = input.profiles.associate { profile ->
            val type = profile.destinationId?.let { destinationsById[it] }?.type
            profile.id to upcomingFor(profile, input.scheduledWork.filter { it.profileId == profile.id }, type, now, input.zone)
        }

        val profileCards = input.profiles.map { profile ->
            val last = input.lastBackups[profile.id]
            val destination = profile.destinationId?.let { destinationsById[it] }
            val destinationMissing = profile.destinationId != null && destination == null
            val workRunning = upcomingByProfile[profile.id].orEmpty().any { it.kind == UpcomingKind.RUNNING }
            val runningJobId = runningByProfile[profile.id]
            val running = runningJobId != null || workRunning
            ProfileCard(
                profile = profile,
                lastBackup = last,
                lastStatus = lastStatus(last, activeIds),
                destination = destination,
                destinationMissing = destinationMissing,
                runningJobId = runningJobId,
                runNow = when {
                    running -> RunNowAvailability.RUNNING
                    destination == null -> RunNowAvailability.NO_DESTINATION
                    ResumePlanner.requiresTypedPassphrase(profile) -> RunNowAvailability.NEEDS_PASSPHRASE
                    else -> RunNowAvailability.AVAILABLE
                },
                nextRun = upcomingByProfile[profile.id].orEmpty().minWithOrNull(upcomingOrder),
            )
        }

        val upcoming = upcomingByProfile.values.flatten()
            .filter { it.kind != UpcomingKind.RUNNING }
            .sortedWith(upcomingOrder)

        val health = input.destinations
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { destinationHealth(it, now) }

        return DashboardModel(
            warnings = warnings(input, profilesById, destinationsById, profileCards, activeIds),
            activeJobs = activeCards,
            profiles = profileCards,
            upcoming = upcoming,
            destinations = health,
        )
    }

    /** Running/queued first (no time), then soonest first. */
    val upcomingOrder: Comparator<UpcomingRun> = compareBy<UpcomingRun> { kindRank(it.kind) }
        .thenBy { it.atMillis ?: Long.MIN_VALUE }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.profileName }

    private fun kindRank(kind: UpcomingKind): Int = when (kind) {
        UpcomingKind.RUNNING -> 0
        UpcomingKind.QUEUED -> 1
        UpcomingKind.RETRY, UpcomingKind.SCHEDULED, UpcomingKind.EXPECTED -> 2
    }

    /** jobId → profileId from the journal and history (both keyed by job id). */
    fun jobProfileLinks(checkpoints: List<JobCheckpoint>, history: List<HistoryRecord>): Map<String, String> {
        val links = HashMap<String, String>()
        history.forEach { record -> record.profileId?.let { links[record.id] = it } }
        checkpoints.forEach { cp -> cp.profileId?.let { links[cp.jobId] = it } }
        return links
    }

    fun lastStatus(record: HistoryRecord?, activeJobIds: Set<String>): LastRunStatus = when (record?.status) {
        null -> LastRunStatus.NEVER
        // A RUNNING row whose job is not live is a process that died; repair will mark it interrupted.
        JobStatus.RUNNING -> if (record.id in activeJobIds) LastRunStatus.RUNNING else LastRunStatus.INTERRUPTED
        JobStatus.INTERRUPTED -> LastRunStatus.INTERRUPTED
        JobStatus.COMPLETED -> LastRunStatus.SUCCESS
        JobStatus.COMPLETED_WITH_WARNINGS -> LastRunStatus.WARNINGS
        JobStatus.FAILED -> LastRunStatus.FAILED
        JobStatus.CANCELLED -> LastRunStatus.CANCELLED
    }

    /**
     * Upcoming runs of one profile from its WorkManager items. A scheduled profile with no
     * periodic item yet (the scheduler reconciles asynchronously) gets its computed next slot,
     * marked [UpcomingKind.EXPECTED] so the UI never presents it as queued.
     */
    fun upcomingFor(
        profile: BackupProfile,
        work: List<ScheduledWork>,
        destinationType: DestinationType?,
        nowMillis: Long,
        zone: ZoneId,
    ): List<UpcomingRun> {
        val runs = work.map { item ->
            val kind = when {
                item.state == ScheduledWorkState.RUNNING -> UpcomingKind.RUNNING
                item.state == ScheduledWorkState.BLOCKED -> UpcomingKind.QUEUED
                item.runAttemptCount > 0 && item.nextRunAtMillis != null -> UpcomingKind.RETRY
                item.periodic && item.nextRunAtMillis != null -> UpcomingKind.SCHEDULED
                // A one-off "Back up now" / resume waiting for its constraints.
                else -> UpcomingKind.QUEUED
            }
            UpcomingRun(
                profileId = profile.id,
                profileName = profile.name,
                kind = kind,
                atMillis = if (kind == UpcomingKind.SCHEDULED || kind == UpcomingKind.RETRY) item.nextRunAtMillis else null,
                attempt = item.runAttemptCount,
                network = item.network,
                requiresCharging = item.requiresCharging,
                requiresDeviceIdle = item.requiresDeviceIdle,
            )
        }
        val policy = profile.schedule
        val hasPeriodic = work.any { it.periodic }
        if (hasPeriodic || !profile.isScheduled || policy == null || !policy.isValid) return runs
        val expected = UpcomingRun(
            profileId = profile.id,
            profileName = profile.name,
            kind = UpcomingKind.EXPECTED,
            atMillis = ScheduleMath.nextSlot(policy, nowMillis, zone),
            attempt = 0,
            network = ScheduleSpecs.networkFor(destinationType, policy.requireUnmetered),
            requiresCharging = policy.requireCharging,
            requiresDeviceIdle = policy.requireDeviceIdle,
        )
        return runs + expected
    }

    fun destinationHealth(destination: Destination, nowMillis: Long): DestinationHealth {
        val verifiedAt = destination.lastVerifiedAt
        val verification = when {
            verifiedAt == null -> VerificationState.NEVER
            nowMillis - verifiedAt > STALE_VERIFICATION_MILLIS -> VerificationState.STALE
            else -> VerificationState.RECENT
        }
        val free = destination.lastKnownFreeBytes
        val total = destination.lastKnownTotalBytes
        val low = free != null && total != null && total > 0 && free.toDouble() / total < LOW_SPACE_FRACTION
        return DestinationHealth(destination, verification, destination.usedFraction, low)
    }

    private fun warnings(
        input: DashboardInputs,
        profilesById: Map<String, BackupProfile>,
        destinationsById: Map<String, Destination>,
        cards: List<ProfileCard>,
        activeIds: Set<String>,
    ): List<DashboardWarning> {
        val out = ArrayList<DashboardWarning>()
        val mode = input.operatingMode
        if (mode != null && mode != OperatingMode.STANDARD && input.elevation == ElevationStatus.UNAVAILABLE) {
            out += DashboardWarning.ElevationUnavailable(mode)
        }
        if (!input.notificationsEnabled) out += DashboardWarning.NotificationsDisabled

        val passphrase = ArrayList<DashboardWarning>()
        val interrupted = ArrayList<DashboardWarning>()
        val interruptedProfiles = HashSet<String>()
        // Newest first; one warning per profile (its latest interrupted backup is the one a run continues).
        input.checkpoints
            .filter { it.status == JobStatus.INTERRUPTED && it.jobId !in activeIds }
            .sortedByDescending { it.updatedAt }
            .forEach { cp ->
                if (cp.type != JobType.BACKUP) {
                    interrupted += DashboardWarning.InterruptedJob(cp.jobId, cp.type)
                    return@forEach
                }
                val profileId = cp.profileId ?: return@forEach
                val profile = profilesById[profileId] ?: return@forEach
                if (!interruptedProfiles.add(profileId)) return@forEach
                when (
                    val decision = ResumePlanner.decide(
                        checkpoint = cp,
                        profile = profile,
                        nowMillis = input.nowMillis,
                        requireRebootFlag = false,
                        destinationExists = { it in destinationsById },
                    )
                ) {
                    is ResumeDecision.NeedsPassphrase -> passphrase += DashboardWarning.PassphraseRequired(profileId, profile.name, cp.jobId)
                    is ResumeDecision.Resume -> interrupted += DashboardWarning.InterruptedBackup(profileId, profile.name, cp.jobId, resumable = true, reason = null)
                    is ResumeDecision.Skip -> interrupted += DashboardWarning.InterruptedBackup(profileId, profile.name, cp.jobId, resumable = false, reason = decision.reason)
                    is ResumeDecision.NotifyOnly -> interrupted += DashboardWarning.InterruptedJob(cp.jobId, decision.type)
                }
            }

        val failed = cards
            .filter { it.lastStatus == LastRunStatus.FAILED && it.runningJobId == null && it.profile.id !in interruptedProfiles }
            .mapNotNull { card ->
                val record = card.lastBackup ?: return@mapNotNull null
                DashboardWarning.LastRunFailed(card.profile.id, card.profile.name, record.id, record.errorMessage, record.finishedAt)
            }

        out += passphrase
        out += failed
        out += interrupted
        return out
    }
}
