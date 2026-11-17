package ca.devdcs.backup.ui.profiles

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.core.storage.PreflightResult
import ca.devdcs.backup.core.storage.labelRes
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.ui.components.KeyValueRow
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.displayName
import ca.devdcs.backup.ui.destinations.icon
import ca.devdcs.backup.ui.mapping.label

/**
 * (c) Where the backup goes: pick one configured destination or add a new one (the destination
 * editor; the new destination is selected on return), then preview the estimated size against the
 * destination's free space.
 */
@Composable
internal fun DestinationStep(
    state: WizardUiState,
    viewModel: ProfileWizardViewModel,
    onAddDestination: () -> Unit,
    modifier: Modifier,
) {
    val draft = state.draft ?: return
    val destinations = state.destinations
    val problems = state.visibleProblemsFor(WizardStep.DESTINATION)
    LazyColumn(modifier = modifier, contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (problems.isNotEmpty()) item(key = "problems") { ProblemLines(problems) }
        when {
            destinations == null -> item(key = "loading") { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            destinations.isEmpty() -> item(key = "empty") {
                SectionCard(title = stringResource(R.string.profiles_dest_empty_title), icon = Icons.Outlined.Info) {
                    Text(stringResource(R.string.profiles_dest_empty_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            else -> items(destinations, key = { it.id }) { destination ->
                DestinationChoice(
                    destination = destination,
                    selected = destination.id == draft.destinationId,
                    onSelect = { viewModel.selectDestination(destination.id) },
                )
            }
        }
        item(key = "add") {
            OutlinedButton(onClick = onAddDestination) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.profiles_dest_add), modifier = Modifier.padding(start = 8.dp))
            }
        }
        item(key = "space") { PreflightCard(state, viewModel, compact = true) }
    }
}

@Composable
private fun DestinationChoice(destination: Destination, selected: Boolean, onSelect: () -> Unit) {
    val context = LocalContext.current
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Icon(destination.type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(destination.displayName(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    destination.type.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val free = destination.lastKnownFreeBytes
                val total = destination.lastKnownTotalBytes
                Text(
                    when {
                        free != null && total != null -> stringResource(
                            R.string.profiles_dest_space,
                            Formatter.formatShortFileSize(context, free),
                            Formatter.formatShortFileSize(context, total),
                        )
                        free != null -> stringResource(R.string.profiles_dest_space_free, Formatter.formatShortFileSize(context, free))
                        else -> stringResource(R.string.profiles_dest_space_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Size estimate and free-space preview, shared by the destination and review pages. [compact]
 * omits the per-source list and the source issues (shown on the review page).
 */
@Composable
internal fun PreflightCard(state: WizardUiState, viewModel: ProfileWizardViewModel, compact: Boolean) {
    val context = LocalContext.current
    val draft = state.draft ?: return
    SectionCard(
        title = stringResource(R.string.profiles_preflight_title),
        subtitle = stringResource(R.string.profiles_preflight_subtitle),
        icon = Icons.Outlined.Storage,
    ) {
        val estimateState = state.estimate
        val estimate = state.currentEstimate
        when {
            draft.targets.isEmpty -> Text(
                stringResource(R.string.profiles_preflight_need_targets),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            estimateState is EstimateState.Running -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.profiles_preflight_running), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = viewModel::cancelEstimate) { Text(stringResource(R.string.profiles_cancel)) }
                }
            }
            estimate != null -> {
                EstimateSummary(estimate, compact)
                SpaceLines(state, estimate, onCheckSpace = viewModel::recheckSpace)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = viewModel::estimate) { Text(stringResource(R.string.profiles_preflight_again)) }
                }
            }
            else -> {
                val failed = estimateState as? EstimateState.Failed
                if (failed != null && failed.key == state.estimateKey) {
                    HintLine(
                        stringResource(R.string.profiles_preflight_failed, failed.detail ?: context.getString(R.string.profiles_unknown_error)),
                        icon = Icons.Outlined.ErrorOutline,
                        warning = true,
                    )
                } else if (estimateState is EstimateState.Done) {
                    HintLine(stringResource(R.string.profiles_preflight_stale))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = viewModel::estimate) { Text(stringResource(R.string.profiles_preflight_run)) }
                }
            }
        }
    }
}

@Composable
private fun EstimateSummary(estimate: PreflightEstimate, compact: Boolean) {
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, estimate.totalBytes)
    KeyValueRow(
        stringResource(R.string.profiles_preflight_size),
        if (estimate.exact) size else stringResource(R.string.profiles_preflight_size_approx, size),
    )
    val items = estimate.totalItems.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    KeyValueRow(stringResource(R.string.profiles_preflight_items), pluralStringResource(R.plurals.profiles_items, items, estimate.totalItems))
    estimate.plan?.let { plan ->
        KeyValueRow(
            stringResource(R.string.profiles_preflight_artifacts),
            pluralStringResource(R.plurals.profiles_artifacts, plan.artifacts.size, plan.artifacts.size),
        )
    }
    if (estimate.transferUnsupported) {
        HintLine(stringResource(R.string.profiles_problem_transfer_unsupported), icon = Icons.Outlined.ErrorOutline, warning = true)
    }
    val failedLines = estimate.lines.filter { it.failed }
    if (failedLines.isNotEmpty()) {
        HintLine(
            stringResource(R.string.profiles_preflight_unmeasured, failedLines.joinToString(", ") { it.displayName }),
            icon = Icons.Outlined.Warning,
            warning = true,
        )
    }
    if (compact) return
    estimate.plan?.artifacts?.take(MAX_ARTIFACT_LINES)?.forEach { artifact ->
        KeyValueRow(
            artifact.logicalName,
            stringResource(
                R.string.profiles_preflight_artifact_line,
                artifact.spec.format.label(),
                Formatter.formatShortFileSize(context, artifact.estimate.totalBytes),
            ),
        )
    }
    val more = (estimate.plan?.artifacts?.size ?: 0) - MAX_ARTIFACT_LINES
    if (more > 0) {
        Text(
            pluralStringResource(R.plurals.profiles_preflight_more, more, more),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    estimate.issues.forEach { issue ->
        HintLine(
            stringResource(R.string.profiles_preflight_issue, issue.subject, issue.message),
            icon = if (issue.blocking) Icons.Outlined.ErrorOutline else Icons.Outlined.Warning,
            warning = true,
        )
    }
}

private const val MAX_ARTIFACT_LINES = 12

@Composable
private fun SpaceLines(state: WizardUiState, estimate: PreflightEstimate, onCheckSpace: () -> Unit) {
    val context = LocalContext.current
    val destination = state.selectedDestination
    when {
        destination == null -> HintLine(stringResource(R.string.profiles_preflight_no_destination))
        state.spaceCheck is SpaceCheckState.Running -> {
            Text(stringResource(R.string.profiles_preflight_checking_space, destination.displayName()), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        else -> {
            val check = state.currentSpaceCheck
            if (check == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCheckSpace) { Text(stringResource(R.string.profiles_preflight_check_space)) }
                }
            } else {
                PreflightLine(
                    label = stringResource(R.string.profiles_preflight_destination, destination.displayName()),
                    result = check.result,
                )
            }
        }
    }
    estimate.stagingCheck?.let { staging ->
        PreflightLine(
            label = stringResource(
                R.string.profiles_preflight_staging,
                Formatter.formatShortFileSize(context, estimate.stagingRequiredBytes),
            ),
            result = staging,
        )
    }
}

@Composable
private fun PreflightLine(label: String, result: PreflightResult) {
    val context = LocalContext.current
    val (icon, text, warning) = when (result) {
        is PreflightResult.Sufficient -> Triple(
            Icons.Outlined.CheckCircle,
            result.space.freeBytes?.let {
                stringResource(R.string.profiles_preflight_enough, Formatter.formatShortFileSize(context, it))
            } ?: stringResource(R.string.profiles_preflight_enough_unknown_free),
            false,
        )
        is PreflightResult.Insufficient -> Triple(
            Icons.Outlined.ErrorOutline,
            stringResource(R.string.profiles_preflight_short, Formatter.formatShortFileSize(context, result.shortfallBytes)),
            true,
        )
        is PreflightResult.Unknown -> Triple(
            Icons.Outlined.Info,
            result.failure?.let { stringResource(R.string.profiles_preflight_unknown_reason, stringResource(it.labelRes())) }
                ?: stringResource(R.string.profiles_preflight_unknown),
            true,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = stringResource(
                when (result) {
                    is PreflightResult.Sufficient -> R.string.profiles_cd_ok
                    is PreflightResult.Insufficient -> R.string.profiles_cd_error
                    is PreflightResult.Unknown -> R.string.profiles_cd_warning
                },
            ),
            tint = when {
                result is PreflightResult.Insufficient -> MaterialTheme.colorScheme.error
                warning -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(18.dp),
        )
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
