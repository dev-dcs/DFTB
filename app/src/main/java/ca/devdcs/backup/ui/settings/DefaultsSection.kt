package ca.devdcs.backup.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.displayName
import ca.devdcs.backup.ui.destinations.icon
import ca.devdcs.backup.ui.mapping.compressionLevelLabel
import ca.devdcs.backup.ui.mapping.label
import kotlin.math.roundToInt

/**
 * Defaults new backup profiles inherit: container format, compression level, checksum and
 * destination. Writes apply immediately (no save button) and only valid combinations can be
 * stored; a combination that went stale (a deleted destination) is flagged with a one-tap repair.
 */
@Composable
internal fun DefaultsSection(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    viewModel: DefaultsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CollectSettingsMessages(viewModel.messages, snackbarHostState)
    val defaults = state.defaults
    if (defaults == null) {
        Box(Modifier.fillMaxWidth().padding(contentPadding), contentAlignment = Alignment.TopCenter) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
        }
        return
    }
    SettingsList(contentPadding = contentPadding) {
        item(key = "defa_intro") {
            Text(
                text = stringResource(R.string.settings_defaults_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.issues.isNotEmpty()) {
            item(key = "defa_issues") {
                WarningCard(title = stringResource(R.string.settings_defaults_issues_title)) {
                    state.issues.sortedBy { it.ordinal }.forEach { issue ->
                        Text(stringResource(issue.messageRes()), style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(onClick = viewModel::repair) { Text(stringResource(R.string.settings_defaults_repair)) }
                }
            }
        }
        item(key = "defa_format") {
            SectionCard(title = stringResource(R.string.onboarding_format_title), icon = Icons.Outlined.Archive) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PipelineDefaultsRules.selectableFormats.forEach { format ->
                        val selected = defaults.archiveFormat == format
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setArchiveFormat(format) },
                            label = { Text(format.label()) },
                            leadingIcon = checkIconIf(selected),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_defaults_extension, defaults.archiveFormat.fileExtension),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (PipelineDefaultsRules.needsFullLocalStaging(defaults.archiveFormat)) {
                    Text(
                        text = stringResource(R.string.settings_defaults_7z_staging),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "defa_compression") {
            CompressionCard(defaults = defaults, onLevelCommitted = viewModel::setCompressionLevel)
        }
        item(key = "defa_checksum") {
            SectionCard(title = stringResource(R.string.onboarding_checksum_title), icon = Icons.Outlined.Verified) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ChecksumAlgorithm.entries.forEach { algorithm ->
                        val selected = defaults.checksumAlgorithm == algorithm
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setChecksumAlgorithm(algorithm) },
                            label = { Text(algorithm.label()) },
                            leadingIcon = checkIconIf(selected),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_defaults_checksum_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "defa_destination") {
            DestinationCard(
                selectedId = defaults.destinationId,
                destinations = state.destinations,
                missing = DefaultsIssue.DESTINATION_MISSING in state.issues,
                onSelect = viewModel::setDefaultDestination,
            )
        }
    }
}

private fun checkIconIf(selected: Boolean): (@Composable () -> Unit)? =
    if (selected) {
        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
    } else {
        null
    }

@Composable
private fun CompressionCard(defaults: PipelineDefaults, onLevelCommitted: (Int) -> Unit) {
    val format = defaults.archiveFormat
    SectionCard(title = stringResource(R.string.onboarding_compression_title), icon = Icons.Outlined.Compress) {
        if (PipelineDefaultsRules.levelApplies(format)) {
            LevelSlider(defaults = defaults, onLevelCommitted = onLevelCommitted)
        } else {
            Text(
                text = stringResource(R.string.settings_defaults_level_not_applicable, format.label()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Emits its rows into the caller's column (the compression card body). */
@Composable
private fun LevelSlider(defaults: PipelineDefaults, onLevelCommitted: (Int) -> Unit) {
    val format = defaults.archiveFormat
    val range = PipelineDefaultsRules.levelRange(format)
    // Local while dragging, committed once on release so DataStore sees one write per gesture.
    var sliderValue by remember(format, defaults.compressionLevel) {
        mutableFloatStateOf(defaults.compressionLevel.coerceIn(range).toFloat())
    }
    val level = sliderValue.roundToInt().coerceIn(range)
    val levelDescription = stringResource(R.string.onboarding_compression_value, level, compressionLevelLabel(format, level))
    Text(
        text = levelDescription,
        style = MaterialTheme.typography.bodyLarge,
    )
    Slider(
        value = sliderValue,
        onValueChange = { sliderValue = it },
        onValueChangeFinished = { onLevelCommitted(sliderValue.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = levelDescription },
    )
    Text(
        text = stringResource(R.string.settings_defaults_level_range, range.first, range.last),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DestinationCard(
    selectedId: String?,
    destinations: List<Destination>?,
    missing: Boolean,
    onSelect: (String?) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.settings_defaults_destination_title),
        subtitle = stringResource(R.string.settings_defaults_destination_subtitle),
        icon = Icons.Outlined.FolderOpen,
    ) {
        if (destinations == null) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            DestinationOptions(selectedId = selectedId, destinations = destinations, missing = missing, onSelect = onSelect)
        }
    }
}

@Composable
private fun DestinationOptions(
    selectedId: String?,
    destinations: List<Destination>,
    missing: Boolean,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.selectableGroup()) {
            DestinationOption(
                title = stringResource(R.string.settings_defaults_destination_ask),
                supporting = stringResource(R.string.settings_defaults_destination_ask_hint),
                selected = selectedId == null || missing,
                icon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null) },
                onClick = { onSelect(null) },
            )
            destinations.forEach { destination ->
                DestinationOption(
                    title = destination.displayName(),
                    supporting = destination.type.label(),
                    selected = destination.id == selectedId,
                    icon = { Icon(destination.type.icon(), contentDescription = null) },
                    onClick = { onSelect(destination.id) },
                )
            }
        }
        if (destinations.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_defaults_destination_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DestinationOption(
    title: String,
    supporting: String,
    selected: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        leadingContent = icon,
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = CardDefaults.cardColors().containerColor),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}

private fun DefaultsIssue.messageRes(): Int = when (this) {
    DefaultsIssue.FORMAT_NOT_SELECTABLE -> R.string.settings_defaults_issue_format
    DefaultsIssue.LEVEL_OUT_OF_RANGE -> R.string.settings_defaults_issue_level
    DefaultsIssue.DESTINATION_MISSING -> R.string.settings_defaults_issue_destination
}
