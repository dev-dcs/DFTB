package ca.devdcs.backup.ui.restore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.core.pipeline.engine.ManifestDevice
import ca.devdcs.backup.core.restore.engine.RestoreUnitKind
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.ui.components.KeyValueRow
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.ExpandableCard
import ca.devdcs.backup.ui.discovery.NoticeCard
import ca.devdcs.backup.ui.discovery.StatusBadge
import ca.devdcs.backup.ui.discovery.Tone
import ca.devdcs.backup.ui.discovery.currentLocale
import ca.devdcs.backup.ui.discovery.formatDateTime
import ca.devdcs.backup.ui.discovery.sizeText
import ca.devdcs.backup.ui.mapping.label

/*
 * Read-only sections of one backup's screen: lock state, details, units and verification.
 */

/** Opening / locked / failed states, with the action that moves on from each. */
@Composable
fun LockSection(state: RestoreBackupUiState, onEnterPassphrase: () -> Unit, onRetry: () -> Unit) {
    when (state.lock) {
        LockState.OPENING -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.restore_ui_opening), style = MaterialTheme.typography.bodyMedium)
        }
        LockState.NEEDS_PASSPHRASE, LockState.UNLOCKING -> NoticeCard(
            title = stringResource(R.string.restore_ui_locked_title),
            body = state.vaultProblem?.let { stringResource(R.string.restore_ui_locked_vault_problem, it.text()) }
                ?: stringResource(R.string.restore_ui_locked_body),
            tone = Tone.INFO,
            icon = Icons.Outlined.Lock,
            actions = {
                Button(onClick = onEnterPassphrase, enabled = state.lock != LockState.UNLOCKING) {
                    Text(stringResource(R.string.restore_ui_enter_passphrase))
                }
            },
        )
        LockState.FAILED -> NoticeCard(
            title = stringResource(R.string.restore_ui_open_failed),
            body = state.openFailure?.text(),
            tone = Tone.ERROR,
            icon = Icons.Outlined.ErrorOutline,
            actions = { TextButton(onClick = onRetry) { Text(stringResource(R.string.restore_ui_retry)) } },
        )
        LockState.OPEN -> Unit
    }
}

/** Device, dates, profile snapshot and partial / cross-device warnings. */
@Composable
fun DetailsSection(details: BackupDetails, thisDevice: ManifestDevice?) {
    val locale = currentLocale()
    val manifest = details.manifest
    val profile = manifest.profile
    val pipeline = profile.pipeline
    val device = manifest.device
    if (manifest.partial) {
        NoticeCard(
            title = stringResource(R.string.restore_ui_partial_title),
            body = stringResource(R.string.restore_ui_partial_body),
            tone = Tone.WARNING,
            icon = Icons.Outlined.WarningAmber,
        )
    }
    if (thisDevice != null && (thisDevice.model != device.model || thisDevice.device != device.device)) {
        NoticeCard(
            title = stringResource(R.string.restore_ui_cross_device_title),
            body = stringResource(R.string.restore_ui_cross_device_body, deviceLabel(device), deviceLabel(thisDevice)),
            tone = Tone.WARNING,
            icon = Icons.Outlined.PhoneAndroid,
        )
    }
    SectionCard(title = stringResource(R.string.restore_ui_details_title), icon = Icons.Outlined.Info, subtitle = profile.name) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (profile.description.isNotBlank()) Text(profile.description, style = MaterialTheme.typography.bodyMedium)
            KeyValueRow(stringResource(R.string.restore_ui_detail_created), formatDateTime(manifest.createdAtMillis, locale))
            KeyValueRow(stringResource(R.string.restore_ui_detail_finished), formatDateTime(manifest.finishedAtMillis, locale))
            details.summary.totalBytes?.let { KeyValueRow(stringResource(R.string.restore_ui_detail_size), sizeText(it)) }
            KeyValueRow(stringResource(R.string.restore_ui_detail_device), deviceLabel(device))
            KeyValueRow(stringResource(R.string.restore_ui_detail_android), stringResource(R.string.restore_ui_detail_android_value, device.sdkInt))
            device.slotSuffix?.let { KeyValueRow(stringResource(R.string.restore_ui_detail_slot), it) }
            if (device.fingerprint.isNotBlank()) KeyValueRow(stringResource(R.string.restore_ui_detail_build), device.fingerprint)
            KeyValueRow(stringResource(R.string.restore_ui_detail_app), stringResource(R.string.restore_ui_detail_app_value, manifest.app.versionName))
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            KeyValueRow(stringResource(R.string.restore_ui_detail_format), stringResource(R.string.restore_ui_pair, pipeline.archiveFormat.label(), pipeline.compressionCodec.label()))
            KeyValueRow(stringResource(R.string.restore_ui_detail_encryption), encryptionText(pipeline.encryptionMode, details))
            KeyValueRow(stringResource(R.string.restore_ui_detail_checksum), manifest.checksumAlgorithm.label())
            KeyValueRow(stringResource(R.string.restore_ui_detail_structure), pipeline.structureMode.label())
            if (pipeline.twrpCompatible) KeyValueRow(stringResource(R.string.restore_ui_detail_twrp), stringResource(R.string.restore_ui_yes))
            val targets = profile.targets
            val counts = listOfNotNull(
                targets.partitions.size.takeIf { it > 0 }?.let { pluralCount(R.plurals.restore_ui_count_partitions, it) },
                targets.apps.size.takeIf { it > 0 }?.let { pluralCount(R.plurals.restore_ui_count_apps, it) },
                targets.paths.size.takeIf { it > 0 }?.let { pluralCount(R.plurals.restore_ui_count_folders, it) },
                targets.categories.size.takeIf { it > 0 }?.let { pluralCount(R.plurals.restore_ui_count_categories, it) },
            )
            if (counts.isNotEmpty()) KeyValueRow(stringResource(R.string.restore_ui_detail_selection), counts.joinToString(", "))
            if (details.runCount > 1) KeyValueRow(stringResource(R.string.restore_ui_detail_runs), details.runCount.toString())
        }
    }
    if (manifest.warnings.isNotEmpty()) {
        ExpandableCard(
            title = stringResource(R.string.restore_ui_backup_warnings),
            subtitle = pluralStringResource(R.plurals.restore_ui_warning_count, manifest.warnings.size, manifest.warnings.size),
        ) {
            manifest.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun pluralCount(id: Int, count: Int): String = pluralStringResource(id, count, count)

@Composable
private fun encryptionText(mode: EncryptionMode, details: BackupDetails): String {
    if (mode == EncryptionMode.NONE && !details.summary.encrypted) return mode.label()
    val how = when {
        details.unlockedWithVaultKey -> stringResource(R.string.restore_ui_unlocked_vault)
        details.unlockedWithPassphrase -> stringResource(R.string.restore_ui_unlocked_passphrase)
        else -> null
    }
    return if (how != null) stringResource(R.string.restore_ui_pair, mode.label(), how) else mode.label()
}

fun deviceLabel(device: ManifestDevice): String =
    listOf(device.manufacturer, device.model).filter { it.isNotBlank() }.joinToString(" ") +
        if (device.device.isNotBlank()) " (${device.device})" else ""

/** Every unit of the backup grouped by kind, with sizes and risk badges. */
@Composable
fun UnitsSection(groups: RestoreUnitGroups) {
    SectionCard(title = stringResource(R.string.restore_ui_units_title), icon = Icons.Outlined.Restore) {
        if (groups.isEmpty) {
            Text(stringResource(R.string.restore_ui_units_empty), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (kind in listOf(RestoreUnitKind.PARTITION, RestoreUnitKind.APP, RestoreUnitKind.FILE_TREE)) {
                val units = groups.of(kind)
                if (units.isEmpty()) continue
                UnitGroupHeader(kind, units)
                units.forEach { unit -> UnitLine(unit) }
            }
        }
    }
}

@Composable
fun UnitGroupHeader(kind: RestoreUnitKind, units: List<RestoreUnitInfo>, modifier: Modifier = Modifier) {
    val total = RestoreUnitGrouping.saturatingSum(units.map { it.restoreBytes })
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(kind.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(kind.groupTitleRes()), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.restore_ui_group_summary, units.size, sizeText(total)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One unit: name, size and badges; [trailing] lets the wizard add a checkbox. */
@Composable
fun UnitLine(unit: RestoreUnitInfo, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (leading != null) leading()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(unit.displayName, style = MaterialTheme.typography.bodyMedium)
            UnitBadges(unit)
        }
        Text(sizeText(unit.restoreBytes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun UnitBadges(unit: RestoreUnitInfo) {
    val critical = stringResource(R.string.restore_ui_badge_critical)
    val logical = stringResource(R.string.restore_ui_badge_logical)
    val twrp = stringResource(R.string.restore_ui_badge_twrp)
    val encrypted = stringResource(R.string.restore_ui_badge_encrypted)
    val badges = buildList {
        if (unit.criticalIdentity) add(critical to Tone.ERROR)
        if (unit.logicalPartition) add(logical to Tone.NEUTRAL)
        if (unit.nandroid) add(twrp to Tone.NEUTRAL)
        if (unit.encrypted) add(encrypted to Tone.INFO)
    }
    if (badges.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        badges.forEach { (text, tone) -> StatusBadge(text, tone) }
    }
}

/** Quick / deep verification with live progress and per-artifact results. */
@Composable
fun VerifySection(verify: VerifyUiState, enabled: Boolean, onVerify: (deep: Boolean) -> Unit, onCancel: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.restore_ui_verify_title),
        icon = Icons.Outlined.VerifiedUser,
        subtitle = stringResource(R.string.restore_ui_verify_subtitle),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (verify.running) {
                Text(
                    stringResource(if (verify.deep) R.string.restore_ui_verify_running_deep else R.string.restore_ui_verify_running_quick),
                    style = MaterialTheme.typography.bodyMedium,
                )
                BytesProgress(done = verify.doneBytes, total = verify.totalBytes.takeIf { it > 0 })
                TextButton(onClick = onCancel) { Text(stringResource(R.string.restore_ui_cancel)) }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onVerify(false) }, enabled = enabled) { Text(stringResource(R.string.restore_ui_verify_quick)) }
                    OutlinedButton(onClick = { onVerify(true) }, enabled = enabled) { Text(stringResource(R.string.restore_ui_verify_deep)) }
                }
                Text(stringResource(R.string.restore_ui_verify_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            verify.failure?.let { failure ->
                Text(failure.text(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            val results = verify.results
            val summary = verify.summary
            if (results != null && summary != null) {
                Text(
                    if (summary.allOk) {
                        stringResource(R.string.restore_ui_verify_all_ok, summary.total)
                    } else {
                        pluralStringResource(R.plurals.restore_ui_verify_failed, summary.failed, summary.failed, summary.total)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (summary.allOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                results.forEach { result -> VerifiedRow(result) }
            }
        }
    }
}

@Composable
private fun VerifiedRow(result: VerifiedArtifact) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.padding(top = 2.dp)) {
            if (result.ok) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = stringResource(R.string.restore_ui_verify_ok_cd), tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = stringResource(R.string.restore_ui_verify_bad_cd), tint = MaterialTheme.colorScheme.error)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(result.name, style = MaterialTheme.typography.bodyMedium)
            result.problem?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        result.storedBytes?.let { Text(sizeText(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
