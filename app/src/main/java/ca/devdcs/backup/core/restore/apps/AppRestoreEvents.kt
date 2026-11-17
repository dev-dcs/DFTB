package ca.devdcs.backup.core.restore.apps

import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Something only the device owner can do mid-restore; the UI starts [intent] from an Activity. */
sealed interface AppRestoreEvent {
    val packageName: String

    /**
     * PackageInstaller returned STATUS_PENDING_USER_ACTION (Standard mode): start [intent] with
     * `startActivity` to show the system install prompt. The restore waits for the decision.
     */
    data class InstallConfirmationRequired(
        override val packageName: String,
        val label: String,
        val sessionId: Int,
        val intent: Intent,
    ) : AppRestoreEvent

    /** DFTB may not install apps yet; [intent] opens the "Install unknown apps" setting. The unit fails. */
    data class InstallPermissionRequired(override val packageName: String, val intent: Intent) : AppRestoreEvent

    /** The prompt of [sessionId] was answered, timed out or abandoned; drop it from the UI. */
    data class InstallConfirmationResolved(override val packageName: String, val sessionId: Int) : AppRestoreEvent
}

/**
 * Event channel between the app restorer (which may run in a worker with no UI) and whatever UI
 * is showing. Exists because Android only lets a foreground Activity launch the install prompt:
 * the restorer publishes the prompt intent here and suspends; [pendingConfirmations] keeps every
 * unanswered prompt so a UI that appears later still sees it.
 */
@Singleton
class AppRestoreEvents @Inject constructor() {

    private val _events = MutableSharedFlow<AppRestoreEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<AppRestoreEvent> = _events.asSharedFlow()

    private val _pending = MutableStateFlow<List<AppRestoreEvent.InstallConfirmationRequired>>(emptyList())
    val pendingConfirmations: StateFlow<List<AppRestoreEvent.InstallConfirmationRequired>> = _pending.asStateFlow()

    internal fun publish(event: AppRestoreEvent) {
        when (event) {
            is AppRestoreEvent.InstallConfirmationRequired -> _pending.update { list -> list.filterNot { it.sessionId == event.sessionId } + event }
            is AppRestoreEvent.InstallConfirmationResolved -> _pending.update { list -> list.filterNot { it.sessionId == event.sessionId } }
            is AppRestoreEvent.InstallPermissionRequired -> Unit
        }
        _events.tryEmit(event)
    }
}
