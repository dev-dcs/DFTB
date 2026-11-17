package ca.devdcs.backup.ui.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.mapping.compressionLevelLabel
import ca.devdcs.backup.ui.mapping.label
import kotlin.math.roundToInt

/**
 * Step 6. Defaults for new profiles plus the two system permissions a background backup tool
 * depends on. Nothing here is persisted until Finish, so Back/Next can be used freely.
 */
@Composable
fun BaselineStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = StepContentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StepHeader(
                titleRes = R.string.onboarding_baseline_title,
                descriptionRes = R.string.onboarding_baseline_description,
                icon = Icons.Outlined.Tune,
            )
        }
        item { DestinationCard(state, viewModel) }
        item { FormatCard(state, viewModel) }
        if (state.archiveFormat.supportsCompressionLevel) {
            item { CompressionCard(state, viewModel) }
        }
        item { ChecksumCard(state, viewModel) }
        item { NotificationsCard(state, viewModel) }
        item { BatteryCard(state, viewModel) }
    }
}

@Composable
private fun DestinationCard(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.useSafFolder(uri)
    }
    SectionCard(title = stringResource(R.string.onboarding_destination_title), icon = Icons.Outlined.FolderOpen) {
        val destination = state.destination
        if (destination == null) {
            Text(stringResource(R.string.onboarding_destination_none), style = MaterialTheme.typography.bodyMedium)
        } else {
            // The built-in app-storage destination stores a stable marker as its name; translate it
            // at render time so a language switch is reflected rather than frozen at creation time.
            val displayName = if (destination.name == Destination.APP_STORAGE_NAME_MARKER) {
                stringResource(R.string.onboarding_destination_app_storage_name)
            } else {
                destination.name
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_destination_current, displayName, destination.type.label()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (state.destinationBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(
                enabled = !state.destinationBusy,
                onClick = {
                    try {
                        folderPicker.launch(null)
                    } catch (error: ActivityNotFoundException) {
                        viewModel.showMessage(R.string.onboarding_no_app_for_action)
                    }
                },
            ) {
                Text(stringResource(R.string.onboarding_destination_choose_folder))
            }
            OutlinedButton(enabled = !state.destinationBusy, onClick = viewModel::useAppStorage) {
                Text(stringResource(R.string.onboarding_destination_app_storage))
            }
            TextButton(
                enabled = !state.destinationBusy && state.destination != null,
                onClick = viewModel::decideDestinationLater,
            ) {
                Text(stringResource(R.string.onboarding_destination_decide_later))
            }
        }
        Text(
            text = stringResource(R.string.onboarding_destination_app_storage_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FormatCard(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    SectionCard(title = stringResource(R.string.onboarding_format_title), icon = Icons.Outlined.Archive) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ArchiveFormat.entries.filter { it.isArchive }.forEach { format ->
                val selected = state.archiveFormat == format
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.setArchiveFormat(format) },
                    label = { Text(format.label()) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                )
            }
        }
        Text(
            text = state.archiveFormat.fileExtension,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompressionCard(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val format = state.archiveFormat
    val max = format.maxCompressionLevel.coerceAtLeast(1)
    val level = state.compressionLevel.coerceIn(0, max)
    SectionCard(title = stringResource(R.string.onboarding_compression_title), icon = Icons.Outlined.Compress) {
        Text(
            text = stringResource(R.string.onboarding_compression_value, level, compressionLevelLabel(format, level)),
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = level.toFloat(),
            onValueChange = { viewModel.setCompressionLevel(it.roundToInt()) },
            valueRange = 0f..max.toFloat(),
            steps = (max - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChecksumCard(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    SectionCard(title = stringResource(R.string.onboarding_checksum_title), icon = Icons.Outlined.Verified) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChecksumAlgorithm.entries.forEach { algorithm ->
                val selected = state.checksumAlgorithm == algorithm
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.setChecksumAlgorithm(algorithm) },
                    label = { Text(algorithm.label()) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun NotificationsCard(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val localContext = LocalContext.current
    val activity = LocalActivity.current
    val launchContext = activity ?: localContext
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Once POST_NOTIFICATIONS is permanently denied the system shows no dialog and the request
        // returns denied immediately, so the button would be a silent no-op. shouldShowRationale
        // returns false in that state (after the request has been made), so fall back to the app's
        // notification settings page, where the grant is still reachable.
        if (!granted && activity != null &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) &&
            !DiagnosticIntents.openNotificationSettings(launchContext)
        ) {
            viewModel.showMessage(R.string.onboarding_no_app_for_action)
        }
        viewModel.refreshSystemState()
    }
    SectionCard(
        title = stringResource(R.string.onboarding_notifications_title),
        subtitle = stringResource(R.string.onboarding_notifications_desc),
        icon = Icons.Outlined.Notifications,
    ) {
        GrantRow(
            granted = state.notificationsGranted,
            grantedTextRes = R.string.onboarding_notifications_granted,
            buttonTextRes = R.string.onboarding_notifications_request,
            onRequest = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (!DiagnosticIntents.openNotificationSettings(launchContext)) {
                    viewModel.showMessage(R.string.onboarding_no_app_for_action)
                }
            },
        )
    }
}

@Composable
private fun BatteryCard(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val localContext = LocalContext.current
    val launchContext = LocalActivity.current ?: localContext
    SectionCard(
        title = stringResource(R.string.onboarding_battery_title),
        subtitle = stringResource(R.string.onboarding_battery_desc),
        icon = Icons.Outlined.BatterySaver,
    ) {
        GrantRow(
            granted = state.batteryExempt,
            grantedTextRes = R.string.onboarding_battery_granted,
            buttonTextRes = R.string.onboarding_battery_request,
            onRequest = {
                if (!DiagnosticIntents.openBatteryExemption(launchContext)) {
                    viewModel.showMessage(R.string.onboarding_no_app_for_action)
                }
            },
        )
    }
}

/** Either a green-tick confirmation line or the button that asks for the grant. */
@Composable
private fun GrantRow(granted: Boolean, grantedTextRes: Int, buttonTextRes: Int, onRequest: () -> Unit) {
    if (granted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(grantedTextRes), style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Column {
            OutlinedButton(onClick = onRequest) { Text(stringResource(buttonTextRes)) }
        }
    }
}
