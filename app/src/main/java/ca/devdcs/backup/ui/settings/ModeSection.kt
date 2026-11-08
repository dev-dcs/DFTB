package ca.devdcs.backup.ui.settings

import android.content.ActivityNotFoundException
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Adb
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.environment.CheckStatus
import ca.devdcs.backup.core.environment.DiagnosticAction
import ca.devdcs.backup.core.environment.DiagnosticCheck
import ca.devdcs.backup.core.environment.DiagnosticReport
import ca.devdcs.backup.core.environment.Readiness
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.mapping.label
import ca.devdcs.backup.ui.onboarding.DiagnosticsState
import ca.devdcs.backup.ui.onboarding.rememberDiagnosticActionHandler

/**
 * Operating mode after onboarding: pick a candidate, read its live diagnostics, then switch behind
 * a confirmation that lists the consequences. Also the home of the companion-module builder,
 * the Shizuku permission request and the Discovery shortcut, since those are what a user changing
 * tiers needs next.
 */
@Composable
internal fun ModeSection(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    onOpenDiscovery: () -> Unit,
    viewModel: ModeSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CollectSettingsMessages(viewModel.messages, snackbarHostState)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }

    var confirmSwitch by rememberSaveable { mutableStateOf(false) }

    val modulePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP_MIME)) { uri ->
        if (uri != null) viewModel.exportModule(uri)
    }
    val launchModulePicker: () -> Unit = {
        try {
            modulePicker.launch(viewModel.suggestedModuleFileName())
        } catch (error: ActivityNotFoundException) {
            viewModel.showMessage(R.string.onboarding_no_app_for_action)
        }
    }
    val handleAction = rememberDiagnosticActionHandler(
        onGrantShizuku = viewModel::requestShizukuPermission,
        onSystemStateChanged = viewModel::rerunDiagnostics,
        onCompanionModuleInfo = launchModulePicker,
        onMessage = viewModel::showMessage,
    )

    SettingsList(contentPadding = contentPadding) {
        item(key = "mode_current") { CurrentModeCard(state.currentMode) }
        if (state.jobsActive) {
            item(key = "mode_jobs_running") {
                WarningCard(
                    title = stringResource(R.string.settings_mode_jobs_running_title),
                    body = stringResource(R.string.settings_mode_jobs_running_body),
                )
            }
        }
        item(key = "mode_pick_hint") {
            Text(
                text = stringResource(R.string.settings_mode_pick_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(OperatingMode.entries, key = { it.storageKey }) { mode ->
            ModeChoiceCard(
                mode = mode,
                selected = state.candidate == mode,
                isCurrent = state.currentMode == mode,
                onClick = { viewModel.selectCandidate(mode) },
            )
        }
        // The apply ("change mode") action sits directly under the mode-choice cards, so the save
        // is right where the user just made the selection rather than below the diagnostics list.
        item(key = "mode_apply_bar") {
            ApplyBar(
                state = state,
                onSwitch = { confirmSwitch = true },
            )
        }
        diagnosticsItems(
            diagnostics = state.diagnostics,
            onRerun = viewModel::rerunDiagnostics,
            onAction = handleAction,
        )
        if (state.candidate == OperatingMode.SHIZUKU || state.currentMode == OperatingMode.SHIZUKU) {
            item(key = "mode_shizuku") {
                SectionCard(
                    title = stringResource(R.string.settings_mode_shizuku_title),
                    subtitle = stringResource(R.string.settings_mode_shizuku_subtitle),
                    icon = Icons.Outlined.Adb,
                ) {
                    OutlinedButton(onClick = viewModel::requestShizukuPermission) {
                        Text(stringResource(R.string.diag_action_grant_shizuku_permission))
                    }
                }
            }
        }
        item(key = "mode_module_builder") {
            ModuleBuilderCard(
                export = state.moduleExport,
                onBuild = launchModulePicker,
                onDismiss = viewModel::dismissModuleResult,
            )
        }
        item(key = "mode_discovery") {
            SectionCard(
                title = stringResource(R.string.settings_mode_discovery_title),
                subtitle = stringResource(R.string.settings_mode_discovery_subtitle),
                icon = Icons.AutoMirrored.Outlined.ManageSearch,
            ) {
                OutlinedButton(onClick = onOpenDiscovery) {
                    Text(stringResource(R.string.settings_mode_discovery_open))
                }
            }
        }
    }

    val candidate = state.candidate
    if (confirmSwitch && candidate != null) {
        val effects = ModeChangeRules.effects(state.currentMode, candidate, state.report?.readiness)
        ConsequenceDialog(
            title = stringResource(R.string.settings_mode_confirm_title, stringResource(candidate.labelRes)),
            message = stringResource(
                R.string.settings_mode_confirm_message,
                state.currentMode?.let { stringResource(it.labelRes) } ?: stringResource(R.string.settings_mode_none),
                stringResource(candidate.labelRes),
            ),
            consequences = effects.map { stringResource(it.textRes) },
            confirmLabel = stringResource(R.string.settings_mode_confirm_action),
            onConfirm = {
                confirmSwitch = false
                viewModel.applyCandidate()
            },
            onDismiss = { confirmSwitch = false },
        )
    }
}

@Composable
private fun CurrentModeCard(current: OperatingMode?) {
    SectionCard(title = stringResource(R.string.settings_mode_current_title), icon = Icons.Outlined.Security) {
        if (current == null) {
            Text(stringResource(R.string.mode_not_selected), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(current.labelRes), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(current.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeChoiceCard(mode: OperatingMode, selected: Boolean, isCurrent: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    OutlinedCard(
        onClick = onClick,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) scheme.primaryContainer else scheme.surface,
            contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(mode.labelRes), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(mode.descriptionRes), style = MaterialTheme.typography.bodySmall)
                if (isCurrent) {
                    Text(
                        text = stringResource(R.string.settings_mode_current_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary,
                    )
                }
            }
            if (selected) {
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(R.string.settings_mode_selected_cd),
                    tint = scheme.primary,
                )
            }
        }
    }
}

private fun LazyListScope.diagnosticsItems(
    diagnostics: DiagnosticsState,
    onRerun: () -> Unit,
    onAction: (DiagnosticAction) -> Unit,
) {
    when (diagnostics) {
        DiagnosticsState.Idle -> Unit

        is DiagnosticsState.Running -> item(key = "diag_running") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.onboarding_diagnostics_running, stringResource(diagnostics.mode.labelRes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is DiagnosticsState.Failed -> item(key = "diag_failed") {
            WarningCard(title = stringResource(R.string.onboarding_diagnostics_failed)) {
                diagnostics.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                RerunButton(onRerun)
            }
        }

        is DiagnosticsState.Done -> {
            val report = diagnostics.report
            item(key = "diag_banner") { ReadinessBanner(report) }
            item(key = "diag_checks") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        report.checks.forEachIndexed { index, check ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            CheckRow(check = check, onAction = onAction)
                        }
                    }
                }
            }
            item(key = "diag_footer") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_diagnostics_duration, report.checks.size, report.durationMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    RerunButton(onRerun)
                }
            }
        }
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
                    text = stringResource(R.string.settings_mode_readiness, stringResource(report.mode.labelRes), report.readiness.label()),
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
            check.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
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
                OutlinedButton(onClick = { onAction(action) }) { Text(stringResource(action.labelRes)) }
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

/** The "Switch to X" button, or the reason it is not available yet. */
@Composable
private fun ApplyBar(state: ModeSettingsUiState, onSwitch: () -> Unit) {
    val candidate = state.candidate ?: return
    val reasonRes = when (state.verdict) {
        ModeApplyVerdict.READY -> null
        ModeApplyVerdict.SAME_MODE -> R.string.settings_mode_reason_same
        ModeApplyVerdict.NO_DIAGNOSTICS -> R.string.settings_mode_reason_no_diagnostics
        ModeApplyVerdict.UNAVAILABLE -> R.string.settings_mode_reason_unavailable
        ModeApplyVerdict.JOBS_RUNNING -> R.string.settings_mode_blocked_jobs
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = onSwitch,
            enabled = state.verdict == ModeApplyVerdict.READY && !state.applying,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_mode_switch_to, stringResource(candidate.labelRes)))
        }
        if (state.applying) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (reasonRes != null) {
            Text(
                text = stringResource(reasonRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Builds the companion module into a document the user picks and reports exactly what it holds. */
@Composable
private fun ModuleBuilderCard(export: ModuleExportState, onBuild: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    SectionCard(
        title = stringResource(R.string.companion_builder_title),
        subtitle = stringResource(R.string.companion_builder_subtitle),
        icon = Icons.Outlined.Extension,
    ) {
        Text(stringResource(R.string.companion_requires_root), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.companion_requires_magisk), style = MaterialTheme.typography.bodySmall)
        NoteCard(text = stringResource(R.string.settings_mode_module_tools_note), icon = Icons.Outlined.Info)
        when (export) {
            ModuleExportState.Idle -> Unit
            ModuleExportState.Building -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.companion_builder_building), style = MaterialTheme.typography.bodyMedium)
            }
            is ModuleExportState.Saved -> {
                val summary = export.summary
                val headline = if (summary.missingTools.isEmpty()) {
                    stringResource(R.string.companion_result_complete, export.savedAs)
                } else {
                    stringResource(R.string.companion_result_partial, summary.missingTools.size)
                }
                StatusLine(
                    icon = if (summary.missingTools.isEmpty()) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                    tint = if (summary.missingTools.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    text = headline,
                )
                if (summary.missingTools.isNotEmpty()) {
                    Text(stringResource(R.string.settings_mode_module_saved_as, export.savedAs), style = MaterialTheme.typography.bodySmall)
                }
                ModuleSummaryDetails(summary, Formatter.formatShortFileSize(context, summary.sizeBytes))
                Text(stringResource(R.string.companion_builder_flash_hint), style = MaterialTheme.typography.bodySmall)
            }
            is ModuleExportState.Failed -> {
                StatusLine(
                    icon = Icons.Outlined.Error,
                    tint = MaterialTheme.colorScheme.error,
                    text = stringResource(R.string.companion_result_failed),
                )
                Text(
                    stringResource(
                        when (export.reason) {
                            ModuleExportFailure.BUILD_FAILED -> R.string.settings_mode_module_build_failed
                            ModuleExportFailure.WRITE_FAILED -> R.string.settings_mode_module_write_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                export.summary?.let { ModuleSummaryDetails(it, sizeText = null) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onBuild, enabled = export !is ModuleExportState.Building) {
                Text(stringResource(R.string.companion_builder_build_action))
            }
            if (export is ModuleExportState.Saved || export is ModuleExportState.Failed) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_dismiss)) }
            }
        }
    }
}

@Composable
private fun ModuleSummaryDetails(summary: ModuleBuildSummary, sizeText: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (sizeText != null) Text(stringResource(R.string.companion_result_size, sizeText), style = MaterialTheme.typography.bodySmall)
        if (summary.includedTools.isEmpty()) {
            Text(stringResource(R.string.companion_result_no_tools), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                stringResource(R.string.companion_result_included_tools, summary.includedTools.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (summary.missingTools.isNotEmpty()) {
            Text(
                stringResource(R.string.companion_result_missing_tools, summary.missingTools.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (summary.warnings.isNotEmpty()) {
            Text(stringResource(R.string.settings_mode_module_details), style = MaterialTheme.typography.labelMedium)
            summary.warnings.forEach { warning ->
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val ZIP_MIME = "application/zip"
