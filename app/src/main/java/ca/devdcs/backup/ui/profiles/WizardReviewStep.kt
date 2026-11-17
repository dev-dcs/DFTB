package ca.devdcs.backup.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.domain.model.SlotPolicy
import ca.devdcs.backup.ui.components.KeyValueRow
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.displayName
import ca.devdcs.backup.ui.discovery.NoticeCard
import ca.devdcs.backup.ui.discovery.Tone
import ca.devdcs.backup.ui.discovery.currentLocale
import ca.devdcs.backup.ui.discovery.labelRes
import ca.devdcs.backup.ui.mapping.label

/**
 * (f) Everything the profile will do, each section with a way back to its page, every open
 * problem, and a pre-flight (estimated size via the engine's planner, free space on the destination
 * and for local staging). Saving happens from the bottom bar (Save / Save & run now).
 */
@Composable
internal fun ReviewStep(state: WizardUiState, viewModel: ProfileWizardViewModel, modifier: Modifier) {
    val draft = state.draft ?: return
    val wording = rememberScheduleWording()
    val locale = currentLocale()
    val pipeline = draft.effectivePipeline(draft.existingKeyRef)
    val shape = TargetShape.of(draft.targets)
    val yes = stringResource(R.string.profiles_yes)
    val no = stringResource(R.string.profiles_no)

    LazyColumn(modifier = modifier, contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val errors = state.problems.filter { it.isError }
        val warnings = state.problems.filterNot { it.isError }
        if (errors.isNotEmpty()) {
            item(key = "errors") {
                NoticeCard(
                    title = stringResource(R.string.profiles_review_errors),
                    tone = Tone.ERROR,
                    icon = Icons.Outlined.ErrorOutline,
                    actions = { ProblemsByStep(errors, viewModel::goTo) },
                )
            }
        }
        if (warnings.isNotEmpty()) {
            item(key = "warnings") {
                SectionCard(title = stringResource(R.string.profiles_review_warnings)) { ProblemsByStep(warnings, viewModel::goTo) }
            }
        }

        item(key = "name") {
            ReviewSection(WizardStep.NAME, viewModel::goTo) {
                KeyValueRow(stringResource(R.string.profiles_field_name), draft.name.trim().ifEmpty { "—" })
                if (draft.description.isNotBlank()) {
                    Text(draft.description.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item(key = "targets") {
            ReviewSection(WizardStep.TARGETS, viewModel::goTo) {
                Text(targetsSummary(draft.targets), style = MaterialTheme.typography.bodyMedium)
                if (draft.targets.partitions.isNotEmpty()) {
                    KeyValueRow(
                        stringResource(R.string.profiles_review_partitions),
                        draft.targets.partitions.joinToString(", ") { it.name },
                    )
                    if (draft.targets.partitions.any { it.slotPolicy != SlotPolicy.NONE }) {
                        KeyValueRow(stringResource(R.string.profiles_slot_title), stringResource(draft.slotPolicy.labelRes()))
                    }
                }
                if (draft.targets.apps.isNotEmpty()) {
                    val components = draft.targets.apps.flatMap { it.components }.toSet().sortedBy { it.ordinal }
                    KeyValueRow(
                        stringResource(R.string.profiles_review_components),
                        components.map { stringResource(it.labelRes()) }.joinToString(", "),
                    )
                }
                draft.targets.paths.forEach { path ->
                    Text(
                        locationLabel(path.location),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item(key = "destination") {
            ReviewSection(WizardStep.DESTINATION, viewModel::goTo) {
                val destination = state.selectedDestination
                Text(
                    destination?.let { "${it.displayName()}$SEPARATOR${it.type.label()}" } ?: stringResource(R.string.profiles_no_destination),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (destination == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        item(key = "options") {
            ReviewSection(WizardStep.OPTIONS, viewModel::goTo) {
                if (PipelineRules.showsArchiveFormat(shape)) {
                    KeyValueRow(stringResource(R.string.profiles_opt_tree_format), pipeline.archiveFormat.label())
                }
                if (PipelineRules.showsCodec(shape, pipeline)) {
                    KeyValueRow(stringResource(R.string.profiles_review_codec), pipeline.compressionCodec.label())
                }
                PipelineRules.levelRange(pipeline, shape)?.let { range ->
                    KeyValueRow(
                        stringResource(R.string.profiles_opt_level),
                        stringResource(
                            R.string.profiles_opt_level_value,
                            pipeline.compressionLevel,
                            stringResource(PipelineRules.levelTier(pipeline.compressionLevel, range.last).labelRes()),
                        ),
                    )
                }
                KeyValueRow(stringResource(R.string.profiles_opt_encryption_title), stringResource(draft.keyChoice.labelRes()))
                KeyValueRow(stringResource(R.string.profiles_opt_mask), if (pipeline.maskFilenames) yes else no)
                KeyValueRow(stringResource(R.string.profiles_opt_checksum), pipeline.checksumAlgorithm.label())
                KeyValueRow(stringResource(R.string.profiles_opt_verify), if (pipeline.verifyAfterBackup) yes else no)
                KeyValueRow(stringResource(R.string.profiles_opt_structure), pipeline.structureMode.label())
                KeyValueRow(stringResource(R.string.profiles_opt_transfer), pipeline.transferMode.label())
                KeyValueRow(stringResource(R.string.profiles_opt_twrp), if (pipeline.twrpCompatible) yes else no)
            }
        }
        item(key = "schedule") {
            ReviewSection(WizardStep.SCHEDULE, viewModel::goTo) {
                Text(
                    ScheduleSummary.describe(
                        schedule = draft.effectiveSchedule(),
                        enabled = draft.enabled,
                        networkDestination = state.selectedDestination?.type?.isNetwork == true,
                        wording = wording,
                        firstDayOfWeek = firstDayOfWeek(locale),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(ScheduleSummary.retention(draft.effectiveRetention(), wording), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item(key = "preflight") { PreflightCard(state, viewModel, compact = false) }
    }
}

@Composable
private fun ReviewSection(step: WizardStep, onEdit: (WizardStep) -> Unit, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(step.titleRes()), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onEdit(step) }) {
                    Text(stringResource(R.string.profiles_review_edit, stringResource(step.titleRes())))
                }
            }
            content()
        }
    }
}

@Composable
private fun ProblemsByStep(problems: List<DraftProblem>, onGoTo: (WizardStep) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        problems.groupBy { it.step }.toSortedMap(compareBy<WizardStep> { it.ordinal }).forEach { (step, list) ->
            Row(verticalAlignment = Alignment.Top) {
                ProblemLines(list, modifier = Modifier.weight(1f))
                TextButton(onClick = { onGoTo(step) }) { Text(stringResource(R.string.profiles_review_fix, stringResource(step.titleRes()))) }
            }
        }
    }
}
