package ca.devdcs.backup.ui.profiles

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.core.boot.ResumePlanner
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.engine.BackupMessages
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.core.retention.RetentionDeletion
import ca.devdcs.backup.core.retention.RetentionExecutor
import ca.devdcs.backup.core.retention.RetentionReport
import ca.devdcs.backup.core.schedule.BackupScheduler
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.repository.CheckpointRepository
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.domain.repository.HistoryRepository
import ca.devdcs.backup.domain.repository.ProfileRepository
import ca.devdcs.backup.core.storage.labelRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A retention dry run waiting for confirmation. [changed] = a re-check found a different plan. */
data class RetentionPreview(val profile: BackupProfile, val report: RetentionReport, val changed: Boolean = false)

/** Schedules overview state. [items] is null until the first database emission. */
data class SchedulesUiState(
    val items: List<ProfileListItem>? = null,
    val busy: Set<String> = emptySet(),
    val retentionBusy: Set<String> = emptySet(),
    val preview: RetentionPreview? = null,
    val passphraseFor: BackupProfile? = null,
)

/** One-shot outcomes of the schedules overview. */
sealed interface SchedulesEvent {
    data class Queued(val name: String) : SchedulesEvent
    /** A typed-passphrase run started in-process as job [jobId]. */
    data class Started(val name: String, val jobId: String) : SchedulesEvent
    data class AlreadyRunning(val name: String) : SchedulesEvent
    data class ScheduleChanged(val name: String, val enabled: Boolean) : SchedulesEvent
    data class RetentionApplied(val name: String, val deleted: Int, val failed: Int) : SchedulesEvent
    data class RunFinished(val name: String, val status: JobStatus, val failure: String?) : SchedulesEvent
    data object ActionFailed : SchedulesEvent

    /** An action failed against the destination for a known [reasonRes] (a string resource). */
    data class StorageFailed(@StringRes val reasonRes: Int) : SchedulesEvent
}

/**
 * Backs the schedules & retention overview: every profile's schedule, next slot and last result,
 * pause/resume, run now, and "apply retention now" as a two-step action — a
 * [RetentionExecutor.apply] dry run the user confirms, re-checked right before the real run so the
 * user never confirms one list and gets another.
 */
@HiltViewModel
class SchedulesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    destinationRepository: DestinationRepository,
    historyRepository: HistoryRepository,
    checkpointRepository: CheckpointRepository,
    private val scheduler: BackupScheduler,
    private val retentionExecutor: RetentionExecutor,
    private val passphraseRunner: PassphraseBackupRunner,
    private val messages: BackupMessages,
    private val clock: EngineClock,
) : ViewModel() {

    private val transient = MutableStateFlow(SchedulesUiState())
    private val _events = Channel<SchedulesEvent>(Channel.BUFFERED)
    val events: Flow<SchedulesEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<SchedulesUiState> =
        combine(
            ProfileItems.observe(profileRepository, destinationRepository, historyRepository, checkpointRepository, clock),
            transient,
        ) { items, state ->
            state.copy(items = ScheduleOrdering.sort(items))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SchedulesUiState())

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
                _events.trySend(SchedulesEvent.RunFinished(name, outcome.status, outcome.failure?.let { messages.failure(it) }))
            }
        }
    }

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
            _events.trySend(SchedulesEvent.ScheduleChanged(profile.name, enabled))
        }
    }

    fun runNow(profile: BackupProfile) {
        if (ResumePlanner.requiresTypedPassphrase(profile)) {
            transient.update { it.copy(passphraseFor = profile) }
            return
        }
        withBusy(profile.id) {
            scheduler.runNow(profile.id)
            _events.trySend(SchedulesEvent.Queued(profile.name))
        }
    }

    fun submitPassphrase(passphrase: CharArray) {
        val profile = transient.value.passphraseFor
        transient.update { it.copy(passphraseFor = null) }
        if (profile == null) {
            passphrase.fill('\u0000')
            return
        }
        when (val start = passphraseRunner.start(profile, passphrase)) {
            is PassphraseRunStart.Started -> _events.trySend(SchedulesEvent.Started(profile.name, start.jobId))
            PassphraseRunStart.AlreadyRunning -> _events.trySend(SchedulesEvent.AlreadyRunning(profile.name))
        }
    }

    fun dismissPassphrase() = transient.update { it.copy(passphraseFor = null) }

    /** Dry run of [profile]'s retention; the result opens the confirmation dialog. */
    fun previewRetention(profile: BackupProfile) {
        withRetentionBusy(profile.id) {
            val report = retentionExecutor.apply(profile, dryRun = true)
            transient.update { it.copy(preview = RetentionPreview(profile, report)) }
        }
    }

    fun dismissPreview() = transient.update { it.copy(preview = null) }

    /**
     * Applies the previewed retention. A fresh dry run first: when it would delete something else
     * than what the user saw (a backup finished in between), the dialog is shown again instead.
     */
    fun confirmRetention() {
        val preview = transient.value.preview ?: return
        transient.update { it.copy(preview = null) }
        if (preview.report.plan.isEmpty) return
        val profile = preview.profile
        withRetentionBusy(profile.id) {
            val recheck = retentionExecutor.apply(profile, dryRun = true)
            if (identities(recheck.plan.deletions) != identities(preview.report.plan.deletions)) {
                transient.update { it.copy(preview = RetentionPreview(profile, recheck, changed = true)) }
                return@withRetentionBusy
            }
            val report = retentionExecutor.apply(profile, dryRun = false)
            _events.trySend(SchedulesEvent.RetentionApplied(profile.name, report.deleted.size, report.failed.size))
        }
    }

    private fun identities(deletions: List<RetentionDeletion>): Set<Triple<String?, String?, List<String>>> =
        deletions.map { Triple(it.destinationId, it.jobDir, it.historyIds.sorted()) }.toSet()

    private fun withBusy(id: String, block: suspend () -> Unit) = launchGuarded(id, block) { busy, add ->
        transient.update { it.copy(busy = if (add) it.busy + busy else it.busy - busy) }
    }

    private fun withRetentionBusy(id: String, block: suspend () -> Unit) = launchGuarded(id, block) { busy, add ->
        transient.update { it.copy(retentionBusy = if (add) it.retentionBusy + busy else it.retentionBusy - busy) }
    }

    private fun launchGuarded(id: String, block: suspend () -> Unit, mark: (String, Boolean) -> Unit) {
        val state = transient.value
        if (id in state.busy || id in state.retentionBusy) return
        mark(id, true)
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                DftbLog.w(TAG, "Schedule action failed for $id (${e.reason})", e)
                _events.trySend(SchedulesEvent.StorageFailed(e.reason.labelRes()))
            } catch (e: Exception) {
                DftbLog.w(TAG, "Schedule action failed for $id", e)
                _events.trySend(SchedulesEvent.ActionFailed)
            } finally {
                mark(id, false)
            }
        }
    }

    private companion object {
        const val TAG = "SchedulesVM"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** Overview order: active schedules by next run, then paused, then manual-only profiles. */
internal object ScheduleOrdering {
    fun sort(items: List<ProfileListItem>): List<ProfileListItem> = items.sortedWith(
        compareBy<ProfileListItem> { groupOf(it) }.thenBy { it.nextRunAt ?: Long.MAX_VALUE },
    )

    fun groupOf(item: ProfileListItem): Int = when {
        item.profile.schedule == null -> GROUP_MANUAL
        item.profile.isScheduled -> GROUP_ACTIVE
        else -> GROUP_PAUSED
    }

    const val GROUP_ACTIVE = 0
    const val GROUP_PAUSED = 1
    const val GROUP_MANUAL = 2
}
