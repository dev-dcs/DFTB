package ca.devdcs.backup.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.core.environment.CheckStatus
import ca.devdcs.backup.core.environment.DiagnosticAction
import ca.devdcs.backup.core.environment.DiagnosticCheck
import ca.devdcs.backup.core.environment.DiagnosticReport
import ca.devdcs.backup.core.environment.Readiness
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.ui.mapping.label

/**
 * Step 5. Four mode cards; selecting one runs the environment diagnostics and renders the report
 * underneath with a fix button per actionable check. Next is gated by the readiness verdict.
 */
@Composable
fun ModeStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    var showModuleDialog by rememberSaveable { mutableStateOf(false) }
    val handleAction = rememberDiagnosticActionHandler(
        onGrantShizuku = viewModel::requestShizukuPermission,
        onSystemStateChanged = viewModel::onResumed,
        onCompanionModuleInfo = { showModuleDialog = true },
        onMessage = viewModel::showMessage,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = StepContentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StepHeader(
                titleRes = R.string.onboarding_mode_title,
                descriptionRes = R.string.onboarding_mode_description,
                icon = Icons.Outlined.Security,
            )
        }
        items(OperatingMode.entries, key = { it.storageKey }) { mode ->
            ModeCard(
                mode = mode,
                selected = state.selectedMode == mode,
                onClick = { viewModel.selectMode(mode) },
            )
        }
        when (val diagnostics = state.diagnostics) {
            DiagnosticsState.Idle -> item {
                Text(
                    text = stringResource(R.string.onboarding_mode_select_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            is DiagnosticsState.Running -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.onboarding_diagnostics_running, stringResource(diagnostics.mode.labelRes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is DiagnosticsState.Failed -> item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.onboarding_diagnostics_failed), style = MaterialTheme.typography.titleMedium)
                        if (!diagnostics.detail.isNullOrBlank()) {
                            Text(diagnostics.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        RerunButton(onClick = viewModel::rerunDiagnostics)
                    }
                }
            }

            is DiagnosticsState.Done -> {
                val report = diagnostics.report
                item { ReadinessBanner(report) }
                items(report.checks, key = { it.id }) { check ->
                    CheckRow(check = check, onAction = handleAction)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_diagnostics_duration, report.checks.size, report.durationMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        RerunButton(onClick = viewModel::rerunDiagnostics)
                    }
                }
            }
        }
    }

    if (showModuleDialog) {
        AlertDialog(
            onDismissRequest = { showModuleDialog = false },
            title = { Text(stringResource(R.string.onboarding_module_dialog_title)) },
            text = { Text(stringResource(R.string.onboarding_module_dialog_text)) },
            confirmButton = {
                TextButton(onClick = { showModuleDialog = false }) {
                    Text(stringResource(R.string.onboarding_module_dialog_ok))
                }
            },
        )
    }
}

@Composable
private fun RerunButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.onboarding_diagnostics_rerun))
    }
}

@Composable
private fun ModeCard(mode: OperatingMode, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    OutlinedCard(
        onClick = onClick,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(mode.labelRes), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(mode.descriptionRes), style = MaterialTheme.typography.bodySmall)
            }
            if (selected) {
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(R.string.onboarding_mode_selected),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ReadinessBanner(report: DiagnosticReport) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (report.readiness) {
        Readiness.READY -> scheme.primaryContainer to scheme.onPrimaryContainer
        Readiness.DEGRADED -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        Readiness.UNAVAILABLE -> scheme.errorContainer to scheme.onErrorContainer
    }
    val descriptionRes = when (report.readiness) {
        Readiness.READY -> R.string.onboarding_readiness_ready_desc
        Readiness.DEGRADED -> R.string.onboarding_readiness_degraded_desc
        Readiness.UNAVAILABLE -> R.string.onboarding_readiness_unavailable_desc
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(report.readiness.icon(), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${stringResource(report.mode.labelRes)}: ${report.readiness.label()}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(stringResource(descriptionRes), style = MaterialTheme.typography.bodyMedium)
            if (report.readiness == Readiness.UNAVAILABLE && report.mode.requiresRoot) {
                Text(stringResource(R.string.diag_mode_requires_root), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun Readiness.icon(): ImageVector = when (this) {
    Readiness.READY -> Icons.Outlined.CheckCircle
    Readiness.DEGRADED -> Icons.Outlined.Warning
    Readiness.UNAVAILABLE -> Icons.Outlined.Error
}

@Composable
private fun CheckRow(check: DiagnosticCheck, onAction: (DiagnosticAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = check.status.icon(),
            contentDescription = check.status.label(),
            tint = check.status.tint(),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(check.titleRes), style = MaterialTheme.typography.bodyLarge)
            if (!check.detail.isNullOrBlank()) {
                Text(
                    text = check.detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val hintRes = check.hintRes
            if (hintRes != null) {
                Text(
                    text = stringResource(hintRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val action = check.action
            if (action != null && check.status != CheckStatus.PASS) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { onAction(action) }) {
                    Text(stringResource(action.labelRes))
                }
            }
        }
    }
}

private fun CheckStatus.icon(): ImageVector = when (this) {
    CheckStatus.PASS -> Icons.Outlined.CheckCircle
    CheckStatus.WARN -> Icons.Outlined.Warning
    CheckStatus.FAIL -> Icons.Outlined.Error
    CheckStatus.INFO -> Icons.Outlined.Info
}

@Composable
private fun CheckStatus.tint(): Color = when (this) {
    CheckStatus.PASS -> MaterialTheme.colorScheme.primary
    CheckStatus.WARN -> MaterialTheme.colorScheme.tertiary
    CheckStatus.FAIL -> MaterialTheme.colorScheme.error
    CheckStatus.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}
