package ca.devdcs.backup.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.notification.NotificationChannels
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** POST_NOTIFICATIONS as the user sees it. */
enum class NotificationPermissionStatus {
    /** Below Android 13 there is no runtime permission; only the app-level switch matters. */
    NOT_REQUIRED,
    GRANTED,
    DENIED,
}

/** One of DFTB's channels and whether the user silenced it in system settings. */
data class ChannelStatus(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
    /** null when the channel does not exist yet (created on first app start). */
    val blocked: Boolean?,
)

data class NotificationSettingsUiState(
    val permission: NotificationPermissionStatus = NotificationPermissionStatus.NOT_REQUIRED,
    val appNotificationsEnabled: Boolean = true,
    val channels: List<ChannelStatus> = emptyList(),
    val isLoaded: Boolean = false,
)

/**
 * Reads the notification facts the system owns (runtime permission, app switch, per-channel
 * block) so the page can show why job progress or failure alerts might not appear. Refreshed on
 * every resume because all three change in system Settings, outside the app.
 */
@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationManager: NotificationManagerCompat,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationSettingsUiState())
    val uiState: StateFlow<NotificationSettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val next = try {
                withContext(ioDispatcher) { read() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // NotificationManager binder calls can fail transiently (DeadSystemException etc.).
                DftbLog.w(TAG, "Could not read notification state", e)
                return@launch
            }
            _uiState.value = next
        }
    }

    private fun read(): NotificationSettingsUiState {
        val permission = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> NotificationPermissionStatus.NOT_REQUIRED
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ->
                NotificationPermissionStatus.GRANTED
            else -> NotificationPermissionStatus.DENIED
        }
        val channels = CHANNELS.map { (id, names) ->
            val channel = notificationManager.getNotificationChannelCompat(id)
            ChannelStatus(
                id = id,
                nameRes = names.first,
                descriptionRes = names.second,
                blocked = channel?.let { it.importance == NotificationManagerCompat.IMPORTANCE_NONE },
            )
        }
        return NotificationSettingsUiState(
            permission = permission,
            appNotificationsEnabled = notificationManager.areNotificationsEnabled(),
            channels = channels,
            isLoaded = true,
        )
    }

    private companion object {
        const val TAG = "SettingsNotify"

        val CHANNELS: List<Pair<String, Pair<Int, Int>>> = listOf(
            NotificationChannels.JOBS to (R.string.notification_channel_jobs_name to R.string.notification_channel_jobs_desc),
            NotificationChannels.ALERTS to (R.string.notification_channel_alerts_name to R.string.notification_channel_alerts_desc),
            NotificationChannels.SCHEDULER to (R.string.notification_channel_scheduler_name to R.string.notification_channel_scheduler_desc),
        )
    }
}
