package ca.devdcs.backup.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.progress.JobProgressBus
import ca.devdcs.backup.core.restore.apps.AppRestoreEvent
import ca.devdcs.backup.core.restore.apps.AppRestoreEvents
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.repository.HistoryRepository
import ca.devdcs.backup.domain.repository.ProfileRepository
import ca.devdcs.backup.ui.navigation.DftbDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Pending install prompts plus the queue's bookkeeping, as the shell effect consumes them. */
data class InstallPromptsUi(
    val pending: List<AppRestoreEvent.InstallConfirmationRequired> = emptyList(),
    val state: InstallPromptState = InstallPromptState(),
) {
    val pendingIds: List<Int> get() = pending.map { it.sessionId }

    fun event(sessionId: Int): AppRestoreEvent.InstallConfirmationRequired? = pending.firstOrNull { it.sessionId == sessionId }
}

/**
 * App-level event handling for the shell: notification deep links (resolved to a route once the
 * graph can navigate) and the one-at-a-time launch of system install prompts the app restorer
 * waits on. Activity-scoped so the queue survives configuration changes.
 */
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val inbox: DeepLinkInbox,
    restoreEvents: AppRestoreEvents,
    private val bus: JobProgressBus,
    private val historyRepository: HistoryRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    val pendingDeepLink: StateFlow<ShellDeepLink?> = inbox.pending

    private val promptState = MutableStateFlow(InstallPromptState())

    val installPrompts: StateFlow<InstallPromptsUi> =
        combine(restoreEvents.pendingConfirmations, promptState) { pending, state -> InstallPromptsUi(pending, state) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, InstallPromptsUi())

    init {
        viewModelScope.launch {
            restoreEvents.pendingConfirmations.collect { pending ->
                val ids = pending.map { it.sessionId }
                promptState.update { InstallPromptQueue.onPendingChanged(it, ids) }
            }
        }
    }

    // --- Deep links ----------------------------------------------------------------------------

    /**
     * Where [link] should land right now: a profile alert opens the job monitor while that
     * profile's backup is running (the worker's foreground notification uses the profile action),
     * otherwise the profile; a job link opens the monitor while anything runs, else the dashboard.
     */
    suspend fun resolve(link: ShellDeepLink): DftbDestination = when (link) {
        is ShellDeepLink.OpenProfile -> runningJobOf(link.profileId)?.let { DftbDestination.JobMonitor(it) }
            ?: if (profileExists(link.profileId)) DftbDestination.ProfileWizard(profileId = link.profileId) else DftbDestination.Dashboard
        is ShellDeepLink.OpenJob -> resolveJob(link.jobId)
        ShellDeepLink.OpenDashboard -> DftbDestination.Dashboard
    }

    fun consume(link: ShellDeepLink) = inbox.consume(link)

    private fun resolveJob(jobId: String?): DftbDestination {
        val active = bus.activeJobs.value
        return when {
            jobId != null -> DftbDestination.JobMonitor(jobId)
            active.size == 1 -> DftbDestination.JobMonitor(active.keys.first())
            active.isNotEmpty() -> DftbDestination.JobMonitor(null)
            else -> DftbDestination.Dashboard
        }
    }

    private suspend fun runningJobOf(profileId: String): String? {
        val active = bus.activeJobs.value
        if (active.isEmpty()) return null
        return try {
            historyRepository.observeActive().first()
                .firstOrNull { it.profileId == profileId && it.status == JobStatus.RUNNING && it.id in active }
                ?.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "Could not read active jobs", e)
            null
        }
    }

    private suspend fun profileExists(profileId: String): Boolean = try {
        profileRepository.get(profileId) != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DftbLog.w(TAG, "Could not read profile $profileId", e)
        false
    }

    // --- Install prompts -----------------------------------------------------------------------

    fun onPromptLaunched(sessionId: Int) = promptState.update { InstallPromptQueue.onLaunched(it, sessionId) }

    fun onPromptLaunchFailed(sessionId: Int) = promptState.update { InstallPromptQueue.onLaunchFailed(it, sessionId) }

    fun onPromptTimedOut(sessionId: Int) = promptState.update { InstallPromptQueue.onLaunchTimedOut(it, sessionId) }

    fun onPaused() = promptState.update(InstallPromptQueue::onPaused)

    fun onResumed() = promptState.update(InstallPromptQueue::onResumed)

    fun relaunchPrompt(sessionId: Int) = promptState.update { InstallPromptQueue.requestRelaunch(it, sessionId) }

    private companion object {
        const val TAG = "ShellVM"
    }
}
