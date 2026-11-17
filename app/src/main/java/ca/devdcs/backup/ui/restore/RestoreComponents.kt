package ca.devdcs.backup.ui.restore

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import ca.devdcs.backup.R
import ca.devdcs.backup.core.progress.JobProgress
import ca.devdcs.backup.core.restore.partition.SlotChoice
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.ui.destinations.SecureWindowEffect
import ca.devdcs.backup.ui.destinations.SwitchRow
import ca.devdcs.backup.ui.destinations.displayName
import ca.devdcs.backup.ui.discovery.NoticeCard
import ca.devdcs.backup.ui.discovery.Tone
import ca.devdcs.backup.ui.discovery.formatSize

/*
 * Building blocks shared by the restore, backup-detail and TWRP import screens. Colors come only
 * from MaterialTheme; layouts use start/end so they mirror under RTL.
 */

/** Width class of the window (phone = Compact); Compact when no Activity hosts the composition. */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun restoreWidthClass(): WindowWidthSizeClass {
    val activity = LocalActivity.current ?: return WindowWidthSizeClass.Compact
    return calculateWindowSizeClass(activity).widthSizeClass
}

/** True when the window is wide enough for side-by-side panes. */
@Composable
fun useTwoPanes(): Boolean = restoreWidthClass() == WindowWidthSizeClass.Expanded

/** Destination picker as wrapping filter chips (works for a handful of destinations without a menu). */
@Composable
fun DestinationChips(
    destinations: List<Destination>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.restore_ui_destination_label),
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            destinations.forEach { destination ->
                FilterChip(
                    selected = destination.id == selectedId,
                    onClick = { onSelect(destination.id) },
                    label = { Text(destination.displayName()) },
                )
            }
        }
    }
}

/**
 * Explicit consequence dialog for a destructive or risky choice. With [typedWord], the confirm
 * button stays disabled until the word is typed (see [ConfirmationWord]).
 */
@Composable
fun ConsequenceDialog(
    title: String,
    consequence: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    typedWord: String? = null,
    details: List<String> = emptyList(),
) {
    var typed by rememberSaveable { mutableStateOf("") }
    val accepted = typedWord == null || ConfirmationWord.accepted(typed, typedWord)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(consequence, style = MaterialTheme.typography.bodyMedium)
                details.forEach { line -> Text(stringResource(R.string.restore_ui_bullet, line), style = MaterialTheme.typography.bodySmall) }
                if (typedWord != null) {
                    Text(
                        stringResource(R.string.restore_ui_confirm_type_word, typedWord),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.restore_ui_confirm_word_label)) },
                        isError = typed.isNotBlank() && !accepted,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = accepted) {
                Text(confirmLabel, color = if (accepted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.restore_ui_cancel)) }
        },
    )
}

/** Consequence dialog for one [RiskyOption]; [extra] lists what the option affects (partitions). */
@Composable
fun RiskyOptionDialog(option: RiskyOption, onConfirm: () -> Unit, onDismiss: () -> Unit, extra: List<String> = emptyList()) {
    val word = option.confirmWordRes()?.let { stringResource(it) }
    ConsequenceDialog(
        title = stringResource(option.titleRes()),
        consequence = stringResource(option.consequenceRes()),
        confirmLabel = stringResource(R.string.restore_ui_risky_enable),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        typedWord = word,
        details = extra,
    )
}

/**
 * A switch for a [RiskyOption]: turning it on asks for confirmation first, turning it off is
 * immediate. [onChange] receives the confirmed value.
 */
@Composable
fun RiskySwitchRow(
    option: RiskyOption,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    extra: List<String> = emptyList(),
    enabled: Boolean = true,
) {
    var asking by rememberSaveable(option) { mutableStateOf(false) }
    SwitchRow(
        label = stringResource(option.titleRes()),
        hint = stringResource(option.hintRes()),
        checked = checked,
        onCheckedChange = { wanted ->
            if (!enabled) return@SwitchRow
            if (wanted) asking = true else onChange(false)
        },
    )
    if (asking) {
        RiskyOptionDialog(
            option = option,
            extra = extra,
            onConfirm = {
                asking = false
                onChange(true)
            },
            onDismiss = { asking = false },
        )
    }
}

/** Slot choice for slotted (A/B) partition images. */
@Composable
fun SlotChoiceSelector(selected: SlotChoice, onSelect: (SlotChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.restore_ui_slot_title), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlotChoice.entries.forEach { choice ->
                FilterChip(selected = choice == selected, onClick = { onSelect(choice) }, label = { Text(stringResource(choice.labelRes())) })
            }
        }
        Text(stringResource(selected.descriptionRes()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Slot choice plus every partition override, each behind its own consequence dialog.
 * [criticalNames] are the identity partitions concerned (listed in their dialog).
 */
@Composable
fun PartitionOverridesEditor(
    overrides: PartitionOverrides,
    onChange: (PartitionOverrides) -> Unit,
    criticalNames: List<String>,
    showBuildOverride: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SlotChoiceSelector(overrides.slotChoice) { onChange(overrides.copy(slotChoice = it)) }
        Text(stringResource(R.string.restore_ui_overrides_title), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.restore_ui_overrides_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val options = RiskyOption.entries.filter { it.partition && (showBuildOverride || it != RiskyOption.OTHER_BUILD) }
        options.forEach { option ->
            if (option == RiskyOption.CRITICAL_IDENTITY && criticalNames.isEmpty() && !overrides.allowCriticalIdentity) return@forEach
            RiskySwitchRow(
                option = option,
                checked = overrides.isOn(option),
                onChange = { onChange(overrides.with(option, it)) },
                extra = if (option == RiskyOption.CRITICAL_IDENTITY) criticalNames else emptyList(),
            )
        }
    }
}

/** Shown while bytes are being written to a block device, and after a partition write failed. */
@Composable
fun DoNotRebootBanner(writingNow: Boolean, modifier: Modifier = Modifier) {
    NoticeCard(
        title = stringResource(if (writingNow) R.string.restore_ui_writing_partition_title else R.string.restore_ui_do_not_reboot_title),
        body = stringResource(if (writingNow) R.string.restore_ui_writing_partition_body else R.string.restore_ui_do_not_reboot_body),
        tone = if (writingNow) Tone.WARNING else Tone.ERROR,
        icon = Icons.Outlined.PowerSettingsNew,
        modifier = modifier,
    )
}

/** Live progress of a restore job: bar, bytes, speed, ETA, current item and message. */
@Composable
fun JobProgressBlock(progress: JobProgress?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (progress == null || progress.indeterminate) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
        }
        if (progress == null) return@Column
        Text(stringResource(progress.phase.labelRes), style = MaterialTheme.typography.labelLarge)
        if (progress.totalBytes > 0) {
            Text(
                stringResource(
                    R.string.restore_ui_progress_bytes,
                    formatSize(context, progress.processedBytes),
                    formatSize(context, progress.totalBytes),
                    progress.percent,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (progress.processedBytes > 0) {
            Text(stringResource(R.string.restore_ui_progress_bytes_only, formatSize(context, progress.processedBytes)), style = MaterialTheme.typography.bodyMedium)
        }
        if (progress.bytesPerSecond > 0) {
            val eta = progress.etaSeconds
            Text(
                if (eta != null) {
                    stringResource(R.string.restore_ui_progress_speed_eta, formatSize(context, progress.bytesPerSecond), formatDuration(eta))
                } else {
                    stringResource(R.string.restore_ui_progress_speed, formatSize(context, progress.bytesPerSecond))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        progress.currentItem?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        progress.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Determinate bar for a byte count when [total] is known, else indeterminate. */
@Composable
fun BytesProgress(done: Long, total: Long?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (total != null && total > 0) {
            val fraction = (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.restore_ui_progress_bytes, formatSize(context, done), formatSize(context, total), (fraction * 100).toInt()),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.restore_ui_progress_bytes_only, formatSize(context, done)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** h:mm:ss / m:ss, locale-neutral digits grouping. */
fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.ROOT, "%d:%02d", m, sec)
}

/**
 * Passphrase prompt for an encrypted backup. FLAG_SECURE is set while it shows; the text field
 * state is never saved to the instance state, and its content is copied into a CharArray and
 * cleared on submit so the caller can wipe the only copy after use.
 */
@Composable
fun PassphraseDialog(
    busy: Boolean,
    error: String?,
    note: String?,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    SecureWindowEffect()
    val state = remember { TextFieldState() }
    var revealed by remember { mutableStateOf(false) }
    DisposableEffect(state) {
        onDispose { state.clearText() }
    }
    fun submit() {
        val text = state.text
        if (text.isEmpty() || busy) return
        val chars = CharArray(text.length) { text[it] }
        state.clearText()
        onSubmit(chars)
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
        title = { Text(stringResource(R.string.restore_ui_passphrase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.restore_ui_passphrase_body), style = MaterialTheme.typography.bodyMedium)
                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedSecureTextField(
                    state = state,
                    enabled = !busy,
                    label = { Text(stringResource(R.string.restore_ui_passphrase_label)) },
                    isError = error != null,
                    supportingText = if (error != null) {
                        { Text(error) }
                    } else {
                        null
                    },
                    textObfuscationMode = if (revealed) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
                    trailingIcon = {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(if (revealed) R.string.restore_ui_passphrase_hide else R.string.restore_ui_passphrase_show),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !busy) { Text(stringResource(R.string.restore_ui_passphrase_unlock)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.restore_ui_cancel)) }
        },
    )
}
