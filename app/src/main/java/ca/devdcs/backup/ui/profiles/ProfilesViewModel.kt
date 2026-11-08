package ca.devdcs.backup.ui.profiles

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.core.boot.ResumeDecision
import ca.devdcs.backup.core.boot.ResumePlanner
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.engine.BackupJob
import ca.devdcs.backup.core.pipeline.engine.BackupMessages
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.core.schedule.BackupScheduler
import ca.devdcs.backup.core.schedule.ScheduleMath
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.JobType
import ca.devdcs.backup.domain.repository.CheckpointRepository
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.domain.repository.HistoryRepository
import ca.devdcs.backup.domain.repository.ProfileRepository
import ca.devdcs.backup.core.storage.labelRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.UUID
import javax.inject.Inject

/** One profile as the list and the schedules overview show it. */
data class ProfileListItem(
    val profile: BackupProfile,
    val destination: Destination?,
    /** Newest BACKUP history record of the profile (any status), or null when it never ran. */
    val lastBackup: HistoryRecord?,
    /** Next scheduled slot, or null when the profile is manual-only, paused or invalid. */
    val nextRunAt: Long?,
    /** The interrupted backup that can be continued, or null. */
    val resume: ResumeDecision.Resume? = null,
) {
    val running: Boolean get() = lastBackup?.status == JobStatus.RUNNING

    /** Runs of this profile need the user to type the passphrase (nothing unattended can run it). */
    val needsPassphrase: Boolean get() = ResumePlanner.requiresTypedPassphrase(profile)
}

/**
 * How the UI may continue a profile's interrupted backup. Exists because [ResumePlanner.decide]
 * stops at "needs passphrase" for typed-key profiles (the unattended path cannot continue them),
 * while the user, passphrase in hand, can: the same job id, folder, destination and completed
 * sources are then taken from the checkpoint exactly as the planner would.
 */
internal object ResumeOptions {

    fun resumeFor(
        profile: BackupProfile,
        checkpoints: List<JobCheckpoint>,
        nowMillis: Long,
        destinationIds: Set<String>,
    ): ResumeDecision.Resume? {
        val checkpoint = ResumePlanner.latestInterruptedBackup(checkpoints, profile.id) ?: return null
        val decision = ResumePlanner.decide(
            checkpoint = checkpoint,
            profile = profile,
            nowMillis = nowMillis,
            requireRebootFlag = false,
            destinationExists = { it in destinationIds },
        )
        return when (decision) {
            is ResumeDecision.Resume -> decision
            is ResumeDecision.NeedsPassphrase -> {
                val extras = checkpoint.state.extras
                ResumeDecision.Resume(
                    jobId = checkpoint.jobId,
                    profile = profile.copy(
                        destinationId = extras[BackupJob.EXTRA_DESTINATION_ID]?.takeIf { it.isNotBlank() } ?: profile.destinationId,
                    ),
                    skipSourceIds = checkpoint.state.completedItems.toSet(),
                    jobDirName = extras[BackupJob.EXTRA_JOB_DIR]?.takeIf { it.isNotBlank() },
                )
            }
            else -> null
        }
    }
}

/** Builds [ProfileListItem]s; shared by the profiles list and the schedules overview. */
internal object ProfileItems {

    /** Live items, re-derived when profiles, destinations, histories or checkpoints change and once a minute. */
    fun observe(
        profiles: ProfileRepository,
        destinations: DestinationRepository,
        history: HistoryRepository,
        checkpoints: CheckpointRepository,
        clock: EngineClock,
    ): Flow<List<ProfileListItem>> {
        val lastRuns: Flow<Pair<List<BackupProfile>, Map<String, HistoryRecord?>>> = profiles.observeAll().flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(list to emptyMap())
            } else {
                combine(list.map { profile -> history.observeForProfile(profile.id).map { records -> profile.id to latestBackup(records) } }) { pairs ->
                    list to pairs.toMap()
                }
            }
        }
        val ticker = flow {
            while (true) {
                emit(clock.nowMillis())
                delay(TICK_MILLIS)
            }
        }
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return combine(lastRuns, destinations.observeAll(), checkpoints.observeAll(), ticker) { (list, runs), dests, journal, now ->
            val byId = dests.associateBy { it.id }
            list.map { profile ->
                ProfileListItem(
                    profile = profile,
                    destination = profile.destinationId?.let(byId::get),
                    lastBackup = runs[profile.id],
                    nextRunAt = nextRun(profile, now, clock),
                    resume = ResumeOptions.resumeFor(profile, journal, now, byId.keys),
                )
            }.sortedWith { a, b -> collator.compare(a.profile.name, b.profile.name) }
        }.catch { e ->
            // One unreadable row (corrupt JSON, unknown enum) must degrade the screen, not crash it.
            DftbLog.e("ProfileItems", "Could not read the profiles", e)
            emit(emptyList())
        }
    }

    fun latestBackup(records: List<HistoryRecord>): HistoryRecord? = records.firstOrNull { it.type == JobType.BACKUP }

    fun nextRun(profile: BackupProfile, now: Long, clock: EngineClock): Long? {
        val schedule = profile.schedule ?: return null
        if (!profile.isScheduled || !schedule.isValid) return null
        return ScheduleMath.nextSlot(schedule, now, clock.zone())
    }

    private const val TICK_MILLIS = 60_000L
}

/** A run waiting for the user to type the passphrase; [resume] continues an interrupted job. */
data class PassphraseRequest(val profile: BackupProfile, val resume: ResumeDecision.Resume? = null)

/** Profiles list state. [items] is null until the first database emission. */
data class ProfilesUiState(
    val items: List<ProfileListItem>? = null,
    val busy: Set<String> = emptySet(),
    /** "Back up now" / "Resume" of a typed-passphrase profile, waiting for the passphrase. */
    val passphraseRequest: PassphraseRequest? = null,
    val pendingDelete: BackupProfile? = null,
)

/** One-shot outcomes the list shows as snackbars (or, for [Started], the job monitor). */
sealed interface ProfilesEvent {
    data class Queued(val name: String) : ProfilesEvent
    data class ResumeQueued(val name: String) : ProfilesEvent

    /** A typed-passphrase run started in-process as job [jobId]. */
    data class Started(val name: String, val jobId: String) : ProfilesEvent
    data class AlreadyRunning(val name: String) : ProfilesEvent
    data class Duplicated(val name: String) : ProfilesEvent
    data class Deleted(val name: String) : ProfilesEvent
    data class ScheduleChanged(val name: String, val enabled: Boolean) : ProfilesEvent
    data class RunFinished(val name: String, val status: JobStatus, val failure: String?) : ProfilesEvent
    data object ActionFailed : ProfilesEvent

    /** An action failed against the destination for a known [reasonRes] (a string resource). */
    data class StorageFailed(@StringRes val reasonRes: Int) : ProfilesEvent
}

/**
 * Backs the profiles list: live rows (profile, destination, last run from History, next slot from
 * [ScheduleMath], resumable interrupted job from the checkpoint journal), "back up now" and
 * "resume" through [BackupScheduler] (or [PassphraseBackupRunner] after a prompt for profiles whose
 * passphrase is typed per run), duplicate, delete and the schedule switch.
 */
@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    destinationRepository: DestinationRepository,
    historyRepository: HistoryRepository,
    private val checkpointRepository: CheckpointRepository,
    private val scheduler: BackupScheduler,
    private val passphraseRunner: PassphraseBackupRunner,
    private val messages: BackupMessages,
    private val clock: EngineClock,
) : ViewModel() {

    private val transient = MutableStateFlow(ProfilesUiState())
    private val _events = Channel<ProfilesEvent>(Channel.BUFFERED)
    val events: Flow<ProfilesEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<ProfilesUiState> =
        combine(
            ProfileItems.observe(profileRepository, destinationRepository, historyRepository, checkpointRepository, clock),
            transient,
        ) { items, state ->
            state.copy(items = items)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProfilesUiState())

    init {
        viewModelScope.launch {
            passphraseRunner.outcomes.collect { outcome ->
                val name = try {
                    profileRepository.get(outcome.profileId)?.name
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DftbLog.w(TAG, "Could not resolve the finished profile ${outcome.profileId}", e)
                    null
                } ?: return@collect
                _events.trySend(ProfilesEvent.RunFinished(name, outcome.status, outcome.failure?.let { messages.failure(it) }))
            }
        }
    }

    /** "Back up now": queued through WorkManager, or a passphrase prompt for typed-key profiles. */
    fun runNow(profile: BackupProfile) {
        if (ResumePlanner.requiresTypedPassphrase(profile)) {
            transient.update { it.copy(passphraseRequest = PassphraseRequest(profile)) }
            return
        }
        withBusy(profile.id) {
            scheduler.runNow(profile.id)
            _events.trySend(ProfilesEvent.Queued(profile.name))
        }
    }

    /**
     * Continues the interrupted backup of [item]: through WorkManager when the key is stored, or
     * after a passphrase prompt (run in-process, the passphrase never touches disk) when it is typed.
     */
    fun resume(item: ProfileListItem) {
        val resume = item.resume ?: return
        val profile = item.profile
        if (ResumePlanner.requiresTypedPassphrase(profile)) {
            transient.update { it.copy(passphraseRequest = PassphraseRequest(profile, resume)) }
            return
        }
        withBusy(profile.id) {
            scheduler.enqueueResume(profile.id, resume.jobId)
            _events.trySend(ProfilesEvent.ResumeQueued(profile.name))
        }
    }

    fun submitPassphrase(passphrase: CharArray) {
        val request = transient.value.passphraseRequest
        transient.update { it.copy(passphraseRequest = null) }
        if (request == null) {
            passphrase.fill('\u0000')
            return
        }
        val profile = request.profile
        when (val start = passphraseRunner.start(profile, passphrase, request.resume)) {
            is PassphraseRunStart.Started -> _events.trySend(ProfilesEvent.Started(profile.name, start.jobId))
            PassphraseRunStart.AlreadyRunning -> _events.trySend(ProfilesEvent.AlreadyRunning(profile.name))
        }
    }

    fun dismissPassphrase() = transient.update { it.copy(passphraseRequest = null) }

    /**
     * Copies [profile] under [copyName] with a new id. The copy's schedule starts paused so two
     * profiles never start backing up the same data on the same slot without the user choosing to.
     */
    fun duplicate(profile: BackupProfile, copyName: String) {
        withBusy(profile.id) {
            val now = clock.nowMillis()
            val copy = profile.copy(
                id = UUID.randomUUID().toString(),
                name = copyName,
                createdAt = now,
                updatedAt = now,
                lastRunAt = null,
                enabled = profile.schedule == null,
            )
            profileRepository.save(copy)
            _events.trySend(ProfilesEvent.Duplicated(copy.name))
        }
    }

    fun requestDelete(profile: BackupProfile) = transient.update { it.copy(pendingDelete = profile) }

    fun cancelDelete() = transient.update { it.copy(pendingDelete = null) }

    /**
     * Deletes the profile, its schedule and its resume journal. History rows and the backups stored
     * on destinations stay (the engines expose no "delete every backup of a profile" operation), and
     * so does any vault key: those backups must remain restorable.
     */
    fun confirmDelete() {
        val profile = transient.value.pendingDelete ?: return
        transient.update { it.copy(pendingDelete = null) }
        withBusy(profile.id) {
            // Row first: a failure leaves the profile listed with its schedule intact. The cleanup
            // after it is idempotent and must not turn a completed delete into a failure.
            profileRepository.delete(profile.id)
            try {
                scheduler.cancel(profile.id)
                checkpointRepository.deleteForProfile(profile.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Cleanup after deleting ${profile.id} failed", e)
            }
            _events.trySend(ProfilesEvent.Deleted(profile.name))
        }
    }

    /** Pauses or resumes the schedule of [profile] (no effect on manual-only profiles). */
    fun setScheduleEnabled(profile: BackupProfile, enabled: Boolean) {
        if (profile.schedule == null) return
        withBusy(profile.id) {
            val now = clock.nowMillis()
            profileRepository.setEnabled(profile.id, enabled, now)
            try {
                scheduler.schedule(profile.copy(enabled = enabled, updatedAt = now))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the stored switch honest: nothing was queued, so do not claim it changed.
                withContext(NonCancellable) {
                    try {
                        profileRepository.setEnabled(profile.id, profile.enabled, clock.nowMillis())
                    } catch (revert: Exception) {
                        DftbLog.w(TAG, "Could not revert the schedule switch of ${profile.id}", revert)
                    }
                }
                throw e
            }
            _events.trySend(ProfilesEvent.ScheduleChanged(profile.name, enabled))
        }
    }

    private fun withBusy(id: String, block: suspend () -> Unit) {
        if (id in transient.value.busy) return
        transient.update { it.copy(busy = it.busy + id) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                DftbLog.w(TAG, "Profile action failed for $id (${e.reason})", e)
                _events.trySend(ProfilesEvent.StorageFailed(e.reason.labelRes()))
            } catch (e: Exception) {
                DftbLog.w(TAG, "Profile action failed for $id", e)
                _events.trySend(ProfilesEvent.ActionFailed)
            } finally {
                transient.update { it.copy(busy = it.busy - id) }
            }
        }
    }

    private companion object {
        const val TAG = "ProfilesVM"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
