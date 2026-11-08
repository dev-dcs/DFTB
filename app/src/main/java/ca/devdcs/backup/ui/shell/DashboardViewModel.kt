package ca.devdcs.backup.ui.shell

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.notification.NotificationChannels
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import ca.devdcs.backup.core.progress.JobProgress
import ca.devdcs.backup.core.progress.JobProgressBus
import ca.devdcs.backup.core.schedule.BackupScheduler
import ca.devdcs.backup.di.DefaultDispatcher
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.model.JobType
import ca.devdcs.backup.domain.repository.CheckpointRepository
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.domain.repository.HistoryRepository
import ca.devdcs.backup.domain.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Dashboard screen state. [model] is null until the first database emission. */
data class DashboardUiState(
    val model: DashboardModel? = null,
    /** Profiles whose "Back up now" / resume request is being handed to WorkManager. */
    val queueing: Set<String> = emptySet(),
    val nowMillis: Long = 0L,
)

/** One-shot results shown as a snackbar. */
sealed interface DashboardEvent {
    data class BackupQueued(val profileName: String) : DashboardEvent
    data class ResumeQueued(val profileName: String) : DashboardEvent
    data class QueueFailed(val profileName: String) : DashboardEvent
}

/**
 * Backs the dashboard: combines profiles, history, the resume journal, destinations, live jobs
 * and WorkManager state through [DashboardAggregator], probes whether the chosen mode's elevated
 * access is usable, and starts "Back up now" / resume through [BackupScheduler].
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    profileRepository: ProfileRepository,
    private val historyRepository: HistoryRepository,
    checkpointRepository: CheckpointRepository,
    destinationRepository: DestinationRepository,
    bus: JobProgressBus,
    scheduledWorkSource: ScheduledWorkSource,
    private val preferences: AppPreferencesRepository,
    private val router: CommandExecutorRouter,
    private val channels: NotificationChannels,
    private val scheduler: BackupScheduler,
    private val promptStore: ShellPromptStore,
    private val clock: EngineClock,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private data class StoredState(
        val profiles: List<BackupProfile>,
        val recent: List<HistoryRecord>,
        val checkpoints: List<JobCheckpoint>,
        val destinations: List<Destination>,
        val lastBackups: Map<String, HistoryRecord> = emptyMap(),
    )

    private data class Environment(
        val elevation: ElevationStatus = ElevationStatus.CHECKING,
        val notificationsEnabled: Boolean = true,
        val notificationPromptShown: Boolean = true,
    )

    private data class LiveState(
        val activeJobs: Map<String, JobProgress>,
        val cancelRequests: Set<String>,
        val work: List<ScheduledWork>,
        val mode: OperatingMode?,
        val environment: Environment,
    )

    private val environment = MutableStateFlow(Environment())
    private val queueing = MutableStateFlow<Set<String>>(emptySet())
    private val _events = Channel<DashboardEvent>(Channel.BUFFERED)
    val events: Flow<DashboardEvent> = _events.receiveAsFlow()
    private var environmentJob: Job? = null

    private val mode: Flow<OperatingMode?> = preferences.preferences.map { it.operatingMode }.distinctUntilChanged()

    private val stored: Flow<StoredState> = combine(
        profileRepository.observeAll(),
        historyRepository.observeRecent(RECENT_LIMIT),
        checkpointRepository.observeAll(),
        destinationRepository.observeAll(),
    ) { profiles, recent, checkpoints, destinations -> StoredState(profiles, recent, checkpoints, destinations) }
        .mapLatest { state -> state.copy(lastBackups = lastBackups(state.profiles, state.recent)) }

    private val live: Flow<LiveState> = combine(
        bus.activeJobs,
        bus.cancelRequests,
        scheduledWorkSource.observe(),
        mode,
        environment,
    ) { active, cancels, work, mode, env -> LiveState(active, cancels, work, mode, env) }

    /** Re-evaluates relative times ("5 minutes ago") and expected slots without new data. */
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(TICK_MILLIS)
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(stored, live, ticker, queueing) { s, l, now, q ->
        val model = DashboardAggregator.aggregate(
            DashboardInputs(
                profiles = s.profiles,
                lastBackups = s.lastBackups,
                recentHistory = s.recent,
                checkpoints = s.checkpoints,
                destinations = s.destinations,
                activeJobs = l.activeJobs.values.toList(),
                cancelRequests = l.cancelRequests,
                scheduledWork = l.work,
                operatingMode = l.mode,
                elevation = l.environment.elevation,
                notificationsEnabled = l.environment.notificationsEnabled,
                nowMillis = now,
                zone = clock.zone(),
            ),
        )
        DashboardUiState(model = model, queueing = q, nowMillis = now)
    }
        .flowOn(defaultDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DashboardUiState())

    /** True once: API 33+, notifications off and the shell never asked before. */
    val autoRequestNotifications: StateFlow<Boolean> = environment
        .map { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !it.notificationsEnabled && !it.notificationPromptShown }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    init {
        viewModelScope.launch {
            val shown = try {
                promptStore.notificationPromptShown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Prompt flags unreadable", e)
                true
            }
            environment.update { it.copy(notificationPromptShown = shown) }
        }
        // Re-probe whenever the operating mode changes (Settings may switch it while we are alive).
        viewModelScope.launch { mode.collect { refreshEnvironment() } }
    }

    /** Re-checks elevated access and notification state; called on resume and after permission dialogs. */
    fun refreshEnvironment() {
        environmentJob?.cancel()
        environmentJob = viewModelScope.launch {
            val enabled = withContext(ioDispatcher) { channels.areNotificationsEnabled() }
            environment.update { it.copy(notificationsEnabled = enabled) }
            val currentMode = try {
                preferences.preferences.first().operatingMode
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Preferences unreadable", e)
                null
            }
            if (currentMode == null || currentMode == OperatingMode.STANDARD) {
                environment.update { it.copy(elevation = ElevationStatus.NOT_REQUIRED) }
                return@launch
            }
            environment.update { if (it.elevation == ElevationStatus.NOT_REQUIRED) it.copy(elevation = ElevationStatus.CHECKING) else it }
            val available = try {
                withContext(ioDispatcher) { router.executorFor(ElevationTier.forMode(currentMode))?.isAvailable() == true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Elevation probe failed for $currentMode", e)
                false
            }
            environment.update { it.copy(elevation = if (available) ElevationStatus.AVAILABLE else ElevationStatus.UNAVAILABLE) }
        }
    }

    /** The automatic permission request is about to show; never show it again. */
    fun onNotificationPromptLaunched() {
        environment.update { it.copy(notificationPromptShown = true) }
        viewModelScope.launch {
            try {
                promptStore.markNotificationPromptShown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Could not persist the prompt flag", e)
            }
        }
    }

    fun backUpNow(profileId: String, profileName: String) = enqueue(profileId, profileName, resumeJobId = null)

    /** Continues an interrupted backup from its checkpoint (same job id and job folder). */
    fun resume(profileId: String, profileName: String, jobId: String) = enqueue(profileId, profileName, resumeJobId = jobId)

    private fun enqueue(profileId: String, profileName: String, resumeJobId: String?) {
        if (profileId in queueing.value) return
        queueing.update { it + profileId }
        viewModelScope.launch {
            try {
                if (resumeJobId == null) scheduler.runNow(profileId) else scheduler.enqueueResume(profileId, resumeJobId)
                _events.trySend(if (resumeJobId == null) DashboardEvent.BackupQueued(profileName) else DashboardEvent.ResumeQueued(profileName))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Could not queue a run of $profileId", e)
                _events.trySend(DashboardEvent.QueueFailed(profileName))
            } finally {
                queueing.update { it - profileId }
            }
        }
    }

    /**
     * Newest backup record per profile: from the recent window, falling back to one indexed query
     * for profiles whose last run is older than that window.
     */
    private suspend fun lastBackups(profiles: List<BackupProfile>, recent: List<HistoryRecord>): Map<String, HistoryRecord> {
        val result = HashMap<String, HistoryRecord>()
        recent.forEach { record ->
            val profileId = record.profileId ?: return@forEach
            if (record.type == JobType.BACKUP && profileId !in result) result[profileId] = record
        }
        val missing = profiles.map { it.id }.filter { it !in result }
        if (missing.isEmpty() || recent.size < RECENT_LIMIT) return result
        withContext(ioDispatcher) {
            missing.forEach { id ->
                try {
                    historyRepository.latestForProfile(id)?.takeIf { it.type == JobType.BACKUP }?.let { result[id] = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DftbLog.w(TAG, "History lookup failed for $id", e)
                }
            }
        }
        return result
    }

    private companion object {
        const val TAG = "DashboardVM"
        const val RECENT_LIMIT = 200
        const val TICK_MILLIS = 30_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
