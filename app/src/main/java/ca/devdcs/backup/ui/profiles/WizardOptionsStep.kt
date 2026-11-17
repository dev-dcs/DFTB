package ca.devdcs.backup.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.CompressionCodec
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.StructureMode
import ca.devdcs.backup.domain.model.TransferMode
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.ExpandableCard
import ca.devdcs.backup.ui.destinations.SwitchRow
import ca.devdcs.backup.ui.mapping.label
import kotlin.math.roundToInt

/**
 * (d) How the backup is produced: TWRP compatibility (which forces plain output), format and
 * compression (only combinations [PipelineRules] allows), encryption key source with its trade-off
 * spelled out, filename masking, integrity (checksum, verify), layout and transfer mode.
 */
@Composable
internal fun OptionsStep(state: WizardUiState, viewModel: ProfileWizardViewModel, modifier: Modifier) {
    val draft = state.draft ?: return
    val options = draft.pipeline
    val shape = TargetShape.of(draft.targets)
    val destinationType = state.selectedDestination?.type
    val problems = state.visibleProblemsFor(WizardStep.OPTIONS)
    LazyColumn(modifier = modifier, contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (problems.isNotEmpty()) item(key = "problems") { ProblemLines(problems) }

        item(key = "twrp") {
            SectionCard(title = stringResource(R.string.profiles_opt_twrp_title), icon = Icons.Outlined.Restore) {
                SwitchRow(
                    label = stringResource(R.string.profiles_opt_twrp),
                    hint = stringResource(R.string.profiles_opt_twrp_hint),
                    checked = options.twrpCompatible,
                    onCheckedChange = { checked -> viewModel.updatePipeline { it.copy(twrpCompatible = checked) } },
                )
                if (options.twrpCompatible) HintLine(stringResource(R.string.profiles_opt_twrp_forced), warning = true)
            }
        }

        item(key = "format") {
            SectionCard(title = stringResource(R.string.profiles_opt_format_title), icon = Icons.Outlined.Archive) {
                val block = PipelineRules.compressionBlock(options)
                if (block != null) HintLine(stringResource(block.messageRes()))
                if (PipelineRules.showsArchiveFormat(shape)) {
                    OptionChips(
                        label = stringResource(R.string.profiles_opt_tree_format),
                        values = PipelineRules.TREE_FORMATS,
                        selected = options.archiveFormat,
                        enabled = block == null,
                        text = { it.label() },
                        onSelect = { format -> viewModel.updatePipeline { it.copy(archiveFormat = format) } },
                    )
                    if (options.archiveFormat == ArchiveFormat.SEVEN_ZIP) HintLine(stringResource(R.string.profiles_opt_7z_hint))
                    if (options.archiveFormat == ArchiveFormat.RAW_COPY) HintLine(stringResource(R.string.profiles_opt_raw_hint))
                } else {
                    HintLine(stringResource(R.string.profiles_opt_images_only))
                }
                if (PipelineRules.showsCodec(shape, options)) {
                    OptionChips(
                        label = stringResource(
                            if (shape.hasPartitions) R.string.profiles_opt_codec_images else R.string.profiles_opt_codec_files,
                        ),
                        values = CompressionCodec.entries,
                        selected = options.compressionCodec,
                        enabled = block == null,
                        text = { it.label() },
                        onSelect = { codec -> viewModel.updatePipeline { it.copy(compressionCodec = codec) } },
                    )
                }
                LevelSlider(options = options, shape = shape, enabled = block == null, onLevel = { level ->
                    viewModel.updatePipeline { it.copy(compressionLevel = level) }
                })
            }
        }

        item(key = "encryption") {
            EncryptionCard(state, draft, viewModel)
        }

        item(key = "integrity") {
            SectionCard(title = stringResource(R.string.profiles_opt_integrity_title), icon = Icons.Outlined.Fingerprint) {
                OptionChips(
                    label = stringResource(R.string.profiles_opt_checksum),
                    values = ChecksumAlgorithm.entries,
                    selected = options.checksumAlgorithm,
                    enabled = true,
                    text = { it.label() },
                    onSelect = { algorithm -> viewModel.updatePipeline { it.copy(checksumAlgorithm = algorithm) } },
                )
                if (options.checksumAlgorithm == ChecksumAlgorithm.MD5 || options.checksumAlgorithm == ChecksumAlgorithm.SHA1) {
                    HintLine(stringResource(R.string.profiles_opt_checksum_weak))
                }
                SwitchRow(
                    label = stringResource(R.string.profiles_opt_verify),
                    hint = stringResource(R.string.profiles_opt_verify_hint),
                    checked = options.verifyAfterBackup,
                    onCheckedChange = { checked -> viewModel.updatePipeline { it.copy(verifyAfterBackup = checked) } },
                )
            }
        }

        item(key = "layout") {
            SectionCard(title = stringResource(R.string.profiles_opt_layout_title), icon = Icons.Outlined.Sync) {
                Text(stringResource(R.string.profiles_opt_structure), style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    StructureMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = options.structureMode == mode,
                            onClick = { viewModel.updatePipeline { it.copy(structureMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = StructureMode.entries.size),
                            label = { Text(mode.label(), maxLines = 1) },
                        )
                    }
                }
                Text(
                    stringResource(options.structureMode.descriptionRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.profiles_opt_transfer), style = MaterialTheme.typography.labelLarge)
                TransferMode.entries.forEach { mode ->
                    val block = PipelineRules.transferBlock(mode, destinationType)
                    ChoiceRow(
                        title = mode.label(),
                        description = stringResource(mode.descriptionRes()),
                        selected = options.transferMode == mode,
                        enabled = block == null || options.transferMode == mode,
                        blockedReason = block?.let { stringResource(it.messageRes()) },
                        onSelect = { viewModel.updatePipeline { it.copy(transferMode = mode) } },
                    )
                }
                if (options.transferMode == TransferMode.LOCAL_STAGING && PipelineRules.stagingIsDirect(destinationType)) {
                    HintLine(stringResource(R.string.profiles_opt_staging_local))
                }
            }
        }

        item(key = "advanced") {
            ExpandableCard(title = stringResource(R.string.profiles_opt_advanced)) {
                SwitchRow(
                    label = stringResource(R.string.profiles_opt_posix),
                    hint = stringResource(R.string.profiles_opt_posix_hint),
                    checked = options.preservePosixMetadata,
                    onCheckedChange = { checked -> viewModel.updatePipeline { it.copy(preservePosixMetadata = checked) } },
                )
                SwitchRow(
                    label = stringResource(R.string.profiles_opt_selinux),
                    hint = stringResource(R.string.profiles_opt_selinux_hint),
                    checked = options.preserveSelinuxContext,
                    onCheckedChange = { checked -> viewModel.updatePipeline { it.copy(preserveSelinuxContext = checked) } },
                )
            }
        }
    }
}

@Composable
private fun EncryptionCard(state: WizardUiState, draft: ProfileDraft, viewModel: ProfileWizardViewModel) {
    val options = draft.pipeline
    SectionCard(title = stringResource(R.string.profiles_opt_encryption_title), icon = Icons.Outlined.Lock) {
        draft.unsupportedEncryption?.let { mode ->
            HintLine(stringResource(R.string.profiles_opt_encryption_legacy, mode), icon = Icons.Outlined.Warning, warning = true)
        }
        val hasStored = draft.existingKeyRef != null && KeyChoice.ofRef(draft.existingKeyRef) == KeyChoice.STORED_SECRET
        PipelineRules.keyChoices(hasStored).forEach { choice ->
            val block = PipelineRules.keyChoiceBlock(choice, options)
            ChoiceRow(
                title = stringResource(choice.labelRes()),
                description = stringResource(choice.descriptionRes()),
                selected = draft.keyChoice == choice && draft.unsupportedEncryption == null,
                enabled = block == null,
                blockedReason = block?.let { stringResource(it.messageRes()) },
                onSelect = { viewModel.setKeyChoice(choice) },
            )
        }
        when (draft.keyChoice) {
            KeyChoice.PASSPHRASE -> HintLine(stringResource(R.string.profiles_opt_passphrase_warning), icon = Icons.Outlined.Warning, warning = true)
            KeyChoice.VAULT_KEY -> {
                HintLine(stringResource(R.string.profiles_opt_vault_warning), icon = Icons.Outlined.Warning, warning = true)
                if (draft.existingKeyRef != null && KeyChoice.ofRef(draft.existingKeyRef) == KeyChoice.VAULT_KEY) {
                    HintLine(stringResource(R.string.profiles_opt_vault_reused))
                }
            }
            KeyChoice.STORED_SECRET -> HintLine(stringResource(R.string.profiles_opt_stored_hint))
            KeyChoice.NONE -> Unit
        }
        val maskBlock = PipelineRules.maskBlock(draft.keyChoice, options)
        ToggleRow(
            label = stringResource(R.string.profiles_opt_mask),
            hint = maskBlock?.let { stringResource(it.messageRes()) } ?: stringResource(R.string.profiles_opt_mask_hint),
            checked = options.maskFilenames,
            enabled = maskBlock == null,
            onCheckedChange = { checked -> viewModel.updatePipeline { it.copy(maskFilenames = checked) } },
        )
    }
}

/** A radio row with a title, a description, and (when disabled) the reason it cannot be chosen. */
@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    blockedReason: String?,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (blockedReason != null) {
                Text(blockedReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

/** Single-choice chips over enum [values]. */
@Composable
private fun <T> OptionChips(
    label: String,
    values: List<T>,
    selected: T,
    enabled: Boolean,
    text: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    enabled = enabled,
                    label = { Text(text(value)) },
                )
            }
        }
    }
}

/**
 * One level for everything compressible in the profile. The engine clamps per format, so when a
 * part uses a lower level than the slider (e.g. gzip images at 9 under a zstd level of 19) it is said.
 */
@Composable
private fun LevelSlider(
    options: PipelineOptions,
    shape: TargetShape,
    enabled: Boolean,
    onLevel: (Int) -> Unit,
) {
    val range = PipelineRules.levelRange(options, shape)
    if (range == null) {
        if (!options.twrpCompatible) HintLine(stringResource(R.string.profiles_opt_level_none))
        return
    }
    val level = options.compressionLevel.coerceIn(range)
    val tier = PipelineRules.levelTier(level, range.last)
    val description = stringResource(R.string.profiles_opt_level_value, level, stringResource(tier.labelRes()))
    Text(stringResource(R.string.profiles_opt_level), style = MaterialTheme.typography.labelLarge)
    Text(description, style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = level.toFloat(),
        onValueChange = { onLevel(it.roundToInt().coerceIn(range)) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
    )
    if (PipelineRules.showsArchiveFormat(shape)) {
        val treeLevel = PipelineRules.effectiveTreeLevel(options, level)
        if (treeLevel != level) HintLine(stringResource(R.string.profiles_opt_level_tree_clamped, treeLevel))
    }
    if (shape.hasPartitions) {
        val imageLevel = PipelineRules.effectiveImageLevel(options, level)
        if (imageLevel != level) HintLine(stringResource(R.string.profiles_opt_level_image_clamped, imageLevel))
    }
}
