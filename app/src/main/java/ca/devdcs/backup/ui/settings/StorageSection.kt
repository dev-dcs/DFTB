package ca.devdcs.backup.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.ui.components.KeyValueRow
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.licenses.ExternalLinks
import kotlinx.coroutines.launch

/**
 * App cache usage (archive staging and other temporary files) with clear actions, and export of
 * the in-app diagnostic log by share sheet or to a document of the user's choice.
 */
@Composable
internal fun StorageSection(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    viewModel: StorageSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val localContext = LocalContext.current
    val launchContext = LocalActivity.current ?: localContext
    val scope = rememberCoroutineScope()
    var pendingClear by rememberSaveable { mutableStateOf<String?>(null) }

    val shareTitle = stringResource(R.string.settings_storage_log_share_title)
    LaunchedEffect(viewModel, localContext) {
        viewModel.events.collect { event ->
            when (event) {
                is StorageEvent.Message -> scope.launch { snackbarHostState.showSnackbar(event.message.resolve(localContext)) }
                is StorageEvent.ShareLog -> {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType(LOG_MIME)
                        .putExtra(Intent.EXTRA_STREAM, event.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // ClipData carries the read grant through the chooser to the picked target.
                    send.clipData = ClipData.newRawUri(null, event.uri)
                    if (!ExternalLinks.launch(launchContext, Intent.createChooser(send, shareTitle))) {
                        scope.launch { snackbarHostState.showSnackbar(localContext.getString(R.string.onboarding_no_app_for_action)) }
                    }
                }
            }
        }
    }

    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LOG_MIME)) { uri ->
        if (uri != null) viewModel.saveLog(uri)
    }

    SettingsList(contentPadding = contentPadding) {
        item(key = "stor_cache") {
            SectionCard(
                title = stringResource(R.string.settings_storage_cache_title),
                subtitle = stringResource(R.string.settings_storage_cache_subtitle),
                icon = Icons.Outlined.Storage,
            ) {
                val usage = state.usage
                if (state.measureFailed && !state.clearing) {
                    Text(
                        text = stringResource(R.string.settings_storage_measure_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = viewModel::measure) {
                        Text(stringResource(R.string.settings_storage_measure_retry))
                    }
                } else if (usage == null || state.clearing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    KeyValueRow(
                        label = stringResource(R.string.settings_storage_staging),
                        value = Formatter.formatShortFileSize(localContext, usage.stagingBytes),
                    )
                    KeyValueRow(
                        label = stringResource(R.string.settings_storage_other_cache),
                        value = Formatter.formatShortFileSize(localContext, usage.otherCacheBytes),
                    )
                    KeyValueRow(
                        label = stringResource(R.string.settings_storage_total_cache),
                        value = Formatter.formatShortFileSize(localContext, usage.totalBytes),
                    )
                }
                Text(
                    text = stringResource(R.string.settings_storage_staging_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.jobsActive) {
                    Text(
                        text = stringResource(R.string.settings_storage_clear_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val enabled = !state.jobsActive && !state.clearing && usage != null
                    OutlinedButton(onClick = { pendingClear = CLEAR_STAGING }, enabled = enabled) {
                        Text(stringResource(R.string.settings_storage_clear_staging))
                    }
                    OutlinedButton(onClick = { pendingClear = CLEAR_ALL }, enabled = enabled) {
                        Icon(Icons.Outlined.CleaningServices, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text(stringResource(R.string.settings_storage_clear_all), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                    TextButton(onClick = viewModel::measure, enabled = !state.clearing) {
                        Text(stringResource(R.string.settings_storage_recalculate))
                    }
                }
            }
        }
        item(key = "stor_log") {
            SectionCard(
                title = stringResource(R.string.settings_storage_log_title),
                subtitle = stringResource(R.string.settings_storage_log_subtitle),
                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
            ) {
                Text(
                    pluralStringResource(R.plurals.settings_storage_log_lines, state.logLineCount, state.logLineCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.settings_storage_log_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.exporting) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = viewModel::shareLog, enabled = !state.exporting) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text(stringResource(R.string.settings_storage_log_share), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                    OutlinedButton(
                        onClick = {
                            try {
                                savePicker.launch(viewModel.suggestedLogFileName())
                            } catch (error: ActivityNotFoundException) {
                                scope.launch { snackbarHostState.showSnackbar(localContext.getString(R.string.onboarding_no_app_for_action)) }
                            }
                        },
                        enabled = !state.exporting,
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text(stringResource(R.string.settings_storage_log_save), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                }
            }
        }
    }

    val clearKind = pendingClear
    if (clearKind != null) {
        val all = clearKind == CLEAR_ALL
        ConsequenceDialog(
            title = stringResource(if (all) R.string.settings_storage_clear_all_title else R.string.settings_storage_clear_staging_title),
            consequences = listOf(
                stringResource(if (all) R.string.settings_storage_clear_all_consequence else R.string.settings_storage_clear_staging_consequence),
                stringResource(R.string.settings_storage_clear_backups_safe),
            ),
            confirmLabel = stringResource(R.string.settings_storage_clear_confirm),
            destructive = true,
            onConfirm = {
                pendingClear = null
                if (all) viewModel.clearAllCache() else viewModel.clearStaging()
            },
            onDismiss = { pendingClear = null },
        )
    }
}

private const val LOG_MIME = "text/plain"
private const val CLEAR_STAGING = "staging"
private const val CLEAR_ALL = "all"
