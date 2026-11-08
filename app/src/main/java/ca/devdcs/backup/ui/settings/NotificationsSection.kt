package ca.devdcs.backup.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.licenses.ExternalLinks
import ca.devdcs.backup.ui.onboarding.DiagnosticIntents
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Notification permission and channel status with direct links into the system pages that change
 * them. Nothing here is stored by DFTB: the system owns every switch, so the page only reads it
 * (refreshing on resume) and sends the user to the right place.
 */
@Composable
internal fun NotificationsSection(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val localContext = LocalContext.current
    val activity = LocalActivity.current
    val launchContext = activity ?: localContext
    // Local one-shot messages: this page has no ViewModel-side failures to report.
    val messages = remember { Channel<SettingsMessage>(Channel.BUFFERED) }
    val messageFlow = remember(messages) { messages.receiveAsFlow() }
    CollectSettingsMessages(messageFlow, snackbarHostState)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val openAppSettings: () -> Unit = {
        if (!DiagnosticIntents.openNotificationSettings(launchContext)) {
            messages.trySend(SettingsMessage(R.string.onboarding_no_app_for_action))
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // A permanent denial resolves instantly without a dialog; the settings page is the only remedy.
        if (!granted && activity != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            openAppSettings()
        }
        viewModel.refresh()
    }

    SettingsList(contentPadding = contentPadding) {
        item(key = "noti_status") {
            SectionCard(
                title = stringResource(R.string.settings_notifications_status_title),
                subtitle = stringResource(R.string.onboarding_notifications_desc),
                icon = Icons.Outlined.Notifications,
            ) {
                val scheme = MaterialTheme.colorScheme
                when (state.permission) {
                    NotificationPermissionStatus.NOT_REQUIRED -> StatusLine(
                        icon = Icons.Outlined.Info,
                        tint = scheme.onSurfaceVariant,
                        text = stringResource(R.string.settings_notifications_permission_not_required),
                    )
                    NotificationPermissionStatus.GRANTED -> StatusLine(
                        icon = Icons.Outlined.CheckCircle,
                        tint = scheme.primary,
                        text = stringResource(R.string.settings_notifications_permission_granted),
                    )
                    NotificationPermissionStatus.DENIED -> StatusLine(
                        icon = Icons.Outlined.NotificationsOff,
                        tint = scheme.error,
                        text = stringResource(R.string.settings_notifications_permission_denied),
                    )
                }
                StatusLine(
                    icon = if (state.appNotificationsEnabled) Icons.Outlined.CheckCircle else Icons.Outlined.NotificationsOff,
                    tint = if (state.appNotificationsEnabled) scheme.primary else scheme.error,
                    text = stringResource(
                        if (state.appNotificationsEnabled) R.string.settings_notifications_app_enabled else R.string.settings_notifications_app_disabled,
                    ),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.permission == NotificationPermissionStatus.DENIED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text(stringResource(R.string.onboarding_notifications_request))
                        }
                    }
                    OutlinedButton(onClick = openAppSettings) {
                        Text(stringResource(R.string.settings_notifications_open_settings))
                    }
                }
            }
        }
        if (state.isLoaded) {
            item(key = "noti_channels") {
                SectionCard(
                    title = stringResource(R.string.settings_notifications_channels_title),
                    subtitle = stringResource(R.string.settings_notifications_channels_subtitle),
                ) {
                    state.channels.forEachIndexed { index, channel ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(channel.nameRes), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(channel.descriptionRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(
                                        when (channel.blocked) {
                                            null -> R.string.settings_notifications_channel_missing
                                            true -> R.string.settings_notifications_channel_blocked
                                            false -> R.string.settings_notifications_channel_on
                                        },
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (channel.blocked == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                            }
                            TextButton(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, launchContext.packageName)
                                        .putExtra(Settings.EXTRA_CHANNEL_ID, channel.id)
                                    if (!ExternalLinks.launch(launchContext, intent)) openAppSettings()
                                },
                                enabled = channel.blocked != null,
                            ) { Text(stringResource(R.string.settings_notifications_channel_open)) }
                        }
                    }
                }
            }
        }
    }
}
