package ca.devdcs.backup.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.domain.security.VaultException
import ca.devdcs.backup.ui.components.SectionCard

/**
 * Encryption keys held in the vault, pinned SSH host keys, and shortcuts to the rclone remote
 * tools. Only metadata is ever shown: key material never leaves the vault on this page.
 */
@Composable
internal fun SecuritySection(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    onOpenRcloneSetup: () -> Unit,
    onOpenRcloneConfig: () -> Unit,
    viewModel: SecuritySettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CollectSettingsMessages(viewModel.messages, snackbarHostState)
    // Saved by id, not by object, so the dialog survives rotation and process death.
    var pendingKeyDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingHostForget by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsList(contentPadding = contentPadding) {
        item(key = "secu_keys") {
            KeysCard(
                keys = state.keys,
                busyRefs = state.busyRefs,
                onDelete = { pendingKeyDelete = it },
                onRetry = viewModel::refresh,
            )
        }
        item(key = "secu_known_hosts") {
            KnownHostsCard(
                hosts = state.knownHosts,
                busyIds = state.busyRefs,
                onForget = { pendingHostForget = it },
                onRetry = viewModel::refresh,
            )
        }
        item(key = "secu_rclone") {
            SectionCard(
                title = stringResource(R.string.settings_security_rclone_title),
                subtitle = stringResource(R.string.settings_security_rclone_subtitle),
                icon = Icons.Outlined.Cloud,
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = onOpenRcloneSetup) { Text(stringResource(R.string.rclone_setup_title)) }
                    OutlinedButton(onClick = onOpenRcloneConfig) { Text(stringResource(R.string.rclone_editor_title)) }
                }
            }
        }
    }

    val keyRef = pendingKeyDelete
    val keyRow = (state.keys as? ListLoad.Loaded)?.items?.firstOrNull { it.ref == keyRef }
    if (keyRef != null && keyRow != null) {
        if (keyRow.deletable) {
            ConsequenceDialog(
                title = stringResource(R.string.settings_security_key_delete_title),
                message = stringResource(R.string.settings_security_key_delete_message, shortRef(keyRow.ref)),
                consequences = listOf(
                    stringResource(R.string.settings_security_key_delete_consequence_unrecoverable),
                    stringResource(R.string.settings_security_key_delete_consequence_no_copy),
                    stringResource(R.string.settings_security_key_delete_consequence_restore),
                ),
                acknowledgement = stringResource(R.string.settings_security_key_delete_ack),
                confirmLabel = stringResource(R.string.settings_security_key_delete_confirm),
                destructive = true,
                onConfirm = {
                    pendingKeyDelete = null
                    viewModel.deleteKey(keyRef)
                },
                onDismiss = { pendingKeyDelete = null },
            )
        } else {
            // The list changed under the dialog (a profile now uses the key): explain instead.
            ConsequenceDialog(
                title = stringResource(R.string.settings_security_key_in_use_title),
                message = stringResource(R.string.settings_security_key_in_use_message),
                consequences = keyRow.usedBy.map { it.profileName },
                confirmLabel = stringResource(R.string.settings_ok),
                onConfirm = { pendingKeyDelete = null },
                onDismiss = { pendingKeyDelete = null },
            )
        }
    }

    val hostId = pendingHostForget
    val hostGroup = (state.knownHosts as? ListLoad.Loaded)?.items?.firstOrNull { it.hostId == hostId }
    if (hostId != null && hostGroup != null) {
        ConsequenceDialog(
            title = stringResource(R.string.settings_security_host_forget_title),
            message = stringResource(R.string.settings_security_host_forget_message, hostGroup.hostId),
            consequences = listOf(
                stringResource(R.string.settings_security_host_forget_consequence_repin),
                stringResource(R.string.settings_security_host_forget_consequence_verify),
            ),
            confirmLabel = stringResource(R.string.settings_security_host_forget_confirm),
            destructive = true,
            onConfirm = {
                pendingHostForget = null
                viewModel.forgetHost(hostGroup)
            },
            onDismiss = { pendingHostForget = null },
        )
    }
}

@Composable
private fun KeysCard(
    keys: ListLoad<EncryptionKeyRow>,
    busyRefs: Set<String>,
    onDelete: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    SectionCard(
        title = stringResource(R.string.settings_security_keys_title),
        subtitle = stringResource(R.string.settings_security_keys_subtitle),
        icon = Icons.Outlined.Key,
    ) {
        when (keys) {
            ListLoad.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            is ListLoad.Failed -> {
                Text(stringResource(keys.vaultReason.messageRes()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                RetryButton(onRetry)
            }
            is ListLoad.Loaded -> if (keys.items.isEmpty()) {
                Text(stringResource(R.string.settings_security_keys_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    pluralStringResource(R.plurals.settings_security_keys_count, keys.items.size, keys.items.size),
                    style = MaterialTheme.typography.labelLarge,
                )
                keys.items.forEachIndexed { index, key ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(shortRef(key.ref), style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                            Text(
                                stringResource(R.string.settings_security_key_created, formatDateTime(context, key.createdAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (key.usedBy.isEmpty()) {
                                    stringResource(R.string.settings_security_key_unused)
                                } else {
                                    stringResource(R.string.settings_security_key_used_by, key.usedBy.joinToString(", ") { it.profileName })
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (key.usedBy.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (key.ref in busyRefs) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            IconButton(onClick = { onDelete(key.ref) }, enabled = key.deletable) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(
                                        if (key.deletable) R.string.settings_security_key_delete_cd else R.string.settings_security_key_delete_blocked_cd,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KnownHostsCard(
    hosts: ListLoad<KnownHostGroup>,
    busyIds: Set<String>,
    onForget: (String) -> Unit,
    onRetry: () -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.settings_security_hosts_title),
        subtitle = stringResource(R.string.settings_security_hosts_subtitle),
        icon = Icons.Outlined.Fingerprint,
    ) {
        when (hosts) {
            ListLoad.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            is ListLoad.Failed -> {
                Text(stringResource(R.string.settings_security_hosts_unreadable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                RetryButton(onRetry)
            }
            is ListLoad.Loaded -> if (hosts.items.isEmpty()) {
                Text(stringResource(R.string.settings_security_hosts_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                hosts.items.forEachIndexed { index, group ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(group.hostId, style = MaterialTheme.typography.bodyLarge)
                            group.keys.forEach { key ->
                                Text(
                                    "${key.keyType} ${key.fingerprint}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (group.hostId in busyIds) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            TextButton(onClick = { onForget(group.hostId) }, enabled = group.endpoint != null) {
                                Text(stringResource(R.string.settings_security_host_forget))
                            }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.settings_security_hosts_tofu_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RetryButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.settings_retry))
    }
}

private fun VaultException.Reason?.messageRes(): Int = when (this) {
    VaultException.Reason.KEY_INVALIDATED,
    VaultException.Reason.KEY_UNRECOVERABLE,
    VaultException.Reason.KEY_LOST,
    -> R.string.settings_security_vault_key_gone
    VaultException.Reason.KEYSTORE_UNAVAILABLE -> R.string.settings_security_vault_keystore
    else -> R.string.settings_security_vault_unreadable
}

/** `enckey-1b2c3d4e…`: enough to tell keys apart without a wall of hex. */
internal fun shortRef(ref: String): String = if (ref.length <= SHORT_REF_LENGTH) ref else ref.take(SHORT_REF_LENGTH) + "…"

private const val SHORT_REF_LENGTH = 15
