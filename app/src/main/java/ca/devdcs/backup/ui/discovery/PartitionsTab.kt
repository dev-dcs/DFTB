package ca.devdcs.backup.ui.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.discovery.partition.PartitionGroup
import ca.devdcs.backup.core.discovery.partition.PartitionRisk
import ca.devdcs.backup.core.discovery.partition.PartitionScanResult
import ca.devdcs.backup.core.discovery.partition.PartitionScanUnavailable
import ca.devdcs.backup.core.discovery.partition.PartitionScanWarning
import ca.devdcs.backup.core.discovery.partition.PartitionSlot
import ca.devdcs.backup.core.discovery.partition.PartitionView
import ca.devdcs.backup.core.discovery.partition.labelRes
import ca.devdcs.backup.core.discovery.partition.messageRes
import ca.devdcs.backup.core.discovery.partition.summaryRes
import ca.devdcs.backup.core.discovery.partition.takesDetail
import ca.devdcs.backup.ui.components.KeyValueRow
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.ExpandableCard

/** Stable selection key of a partition group (by-name and mapper groups of one name stay distinct). */
fun partitionGroupKey(group: PartitionGroup): String = "${group.source.name}/${group.baseName}"

/**
 * Read-only partition browser. In [selectable] mode each group gets a checkbox; the caller owns the
 * selection ([selectedKeys], keyed by [partitionGroupKey]) and receives toggles via [onToggleGroup].
 * 's backup picker uses that mode; the Discovery hub leaves it off.
 */
@Composable
fun PartitionsTab(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    selectedKeys: Set<String> = emptySet(),
    onToggleGroup: (PartitionGroup) -> Unit = {},
    viewModel: PartitionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = currentLocale()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val outcome = state.outcome) {
            null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            is PartitionScanUnavailable -> item(key = "unavailable") {
                NoticeCard(
                    title = stringResource(R.string.discovery_partitions_unavailable_title),
                    body = stringResource(outcome.reason.messageRes()),
                    tone = Tone.ERROR,
                    icon = Icons.Outlined.ErrorOutline,
                    actions = {
                        val detail = outcome.detail
                        if (detail != null) {
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            RescanButton(scanning = state.scanning, onClick = viewModel::rescan)
                        }
                    },
                )
            }
            is PartitionScanResult -> {
                item(key = "header") {
                    HeaderCard(outcome, state.scannedAtMillis?.let { formatDateTime(it, locale) }, state.scanning, viewModel::rescan)
                }
                item(key = "view") { ViewToggle(state.view, viewModel::setView) }
                if (outcome.warnings.isNotEmpty()) {
                    item(key = "warnings") { WarningsCard(outcome.warnings) }
                }
                if (state.groups.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.discovery_partitions_empty_view),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.groups, key = { "g:" + partitionGroupKey(it) }) { group ->
                    val key = partitionGroupKey(group)
                    GroupCard(
                        group = group,
                        selectable = selectable,
                        selected = key in selectedKeys,
                        onToggle = { onToggleGroup(group) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RescanButton(scanning: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !scanning) {
        if (scanning) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(stringResource(R.string.partition_scan_rescan), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun HeaderCard(result: PartitionScanResult, scannedAt: String?, scanning: Boolean, onRescan: () -> Unit) {
    val yes = stringResource(R.string.discovery_yes)
    val no = stringResource(R.string.discovery_no)
    SectionCard(
        title = stringResource(R.string.discovery_partitions_header_title),
        subtitle = scannedAt?.let { stringResource(R.string.discovery_scanned_at, it) },
        icon = Icons.Outlined.DeveloperBoard,
    ) {
        if (scanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        KeyValueRow(stringResource(R.string.discovery_partitions_ab), if (result.abDevice) yes else no)
        val activeSlot = result.activeSlot
        KeyValueRow(
            stringResource(R.string.discovery_partitions_active_slot),
            when {
                !result.abDevice -> stringResource(R.string.discovery_not_applicable)
                activeSlot == null -> stringResource(R.string.discovery_unknown)
                else -> stringResource(activeSlot.labelRes())
            },
        )
        KeyValueRow(stringResource(R.string.discovery_partitions_dynamic), if (result.dynamicPartitions) yes else no)
        KeyValueRow(stringResource(R.string.discovery_partitions_virtual_ab), if (result.virtualAb) yes else no)
        KeyValueRow(stringResource(R.string.discovery_partitions_count), result.partitions.size.toString())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            RescanButton(scanning = scanning, onClick = onRescan)
        }
    }
}

@Composable
private fun ViewToggle(view: PartitionView, onSelect: (PartitionView) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            PartitionView.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = entry == view,
                    onClick = { onSelect(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = PartitionView.entries.size),
                    label = { Text(stringResource(entry.labelRes())) },
                )
            }
        }
        Text(
            stringResource(view.summaryRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WarningsCard(warnings: List<PartitionScanWarning>) {
    ExpandableCard(
        title = stringResource(R.string.discovery_warnings_title, warnings.size),
        subtitle = stringResource(R.string.discovery_partitions_warnings_subtitle),
    ) {
        warnings.forEach { warning ->
            val text = if (warning.kind.takesDetail) {
                stringResource(warning.kind.messageRes(), warning.detail.orEmpty())
            } else {
                stringResource(warning.kind.messageRes())
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
            val detail = warning.detail
            if (!warning.kind.takesDetail && detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun GroupCard(group: PartitionGroup, selectable: Boolean, selected: Boolean, onToggle: () -> Unit) {
    val critical = group.risk == PartitionRisk.CRITICAL_IDENTITY
    val colors = if (critical) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        CardDefaults.cardColors()
    }
    val rowModifier = if (selectable) {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
    } else {
        Modifier
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Row(
            modifier = rowModifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (selectable) Checkbox(checked = selected, onCheckedChange = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(group.baseName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        group.totalSizeBytes?.let { sizeText(it) } ?: stringResource(R.string.discovery_size_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(stringResource(group.category.labelRes()), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.members.filter { it.slot != PartitionSlot.NONE }.forEach { member ->
                        val slot = stringResource(member.slot.labelRes())
                        StatusBadge(
                            text = if (member.isActiveSlot) {
                                stringResource(R.string.discovery_partitions_slot_active, slot, stringResource(R.string.partition_slot_active_marker))
                            } else {
                                slot
                            },
                            tone = if (member.isActiveSlot) Tone.INFO else Tone.NEUTRAL,
                        )
                    }
                    if (group.isLogical) StatusBadge(stringResource(R.string.partition_logical_badge), Tone.NEUTRAL)
                    when (group.risk) {
                        PartitionRisk.SAFE_TO_IMAGE -> Unit
                        PartitionRisk.HUGE -> StatusBadge(stringResource(group.risk.labelRes()), Tone.WARNING)
                        PartitionRisk.CRITICAL_IDENTITY -> StatusBadge(stringResource(group.risk.labelRes()), Tone.ERROR)
                    }
                }
                group.risk.summaryRes()?.let { summary ->
                    Text(stringResource(summary), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    group.members.joinToString(", ") { it.blockDevice },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (critical) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
