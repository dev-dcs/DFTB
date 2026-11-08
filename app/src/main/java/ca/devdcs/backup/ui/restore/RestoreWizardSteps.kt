package ca.devdcs.backup.ui.restore

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.restore.ConflictPolicy
import ca.devdcs.backup.core.restore.apps.AppVersionPolicy
import ca.devdcs.backup.core.restore.engine.RestoreUnitKind
import ca.devdcs.backup.core.restore.files.TwrpRestoreMode
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.ChoiceChips
import ca.devdcs.backup.ui.destinations.ExpandableCard
import ca.devdcs.backup.ui.destinations.FormTextField
import ca.devdcs.backup.ui.destinations.RemovableVolumes
import ca.devdcs.backup.ui.destinations.SwitchRow
import ca.devdcs.backup.ui.discovery.NoticeCard
import ca.devdcs.backup.ui.discovery.StatusBadge
import ca.devdcs.backup.ui.discovery.Tone
import ca.devdcs.backup.ui.discovery.sizeText

/*
 * The restore wizard's steps: choose units, set options, review the dry-run preflight.
 */

/** Step header: "Step 2 of 3 · Options". */
@Composable
fun WizardHeader(step: RestoreStep) {
    val (index, title) = when (step) {
        RestoreStep.SELECT -> 1 to R.string.restore_ui_step_select
        RestoreStep.OPTIONS -> 2 to R.string.restore_ui_step_options
        RestoreStep.PREFLIGHT -> 3 to R.string.restore_ui_step_preflight
        RestoreStep.DETAILS -> return
    }
    Text(
        stringResource(R.string.restore_ui_step_header, index, WIZARD_STEPS, stringResource(title)),
        style = MaterialTheme.typography.titleMedium,
    )
}

private const val WIZARD_STEPS = 3

/** Back / Next row at the bottom of a step. */
@Composable
fun WizardNav(onBack: () -> Unit, onNext: (() -> Unit)?, nextEnabled: Boolean, nextLabel: String = stringResource(R.string.restore_ui_next)) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.restore_ui_back_step)) }
        if (onNext != null) Button(onClick = onNext, enabled = nextEnabled) { Text(nextLabel) }
    }
}

// --- step 1: selection -------------------------------------------------------------------------

@Composable
fun SelectStep(
    state: RestoreBackupUiState,
    onToggleUnit: (String) -> Unit,
    onToggleKind: (RestoreUnitKind) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val selected = remember(state.input, state.units) { state.selectedUnits }
    val bytes = remember(state.input, state.units) { RestoreUnitGrouping.restoreBytes(state.units, selected.map { it.id }.toSet()) }
    val chosen = remember(state.input, state.units) { RestoreWizardRules.effectiveSelection(state.input, state.units) }
    val kindStates = remember(state.input, state.units) {
        listOf(RestoreUnitKind.PARTITION, RestoreUnitKind.APP, RestoreUnitKind.FILE_TREE).associateWith { RestoreWizardRules.kindState(state.input, state.units, it) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WizardHeader(RestoreStep.SELECT)
        Text(
            pluralStringResource(R.plurals.restore_ui_selected_summary, selected.size, selected.size, state.units.size, sizeText(bytes)),
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onSelectAll(true) }) { Text(stringResource(R.string.restore_ui_select_all)) }
            TextButton(onClick = { onSelectAll(false) }) { Text(stringResource(R.string.restore_ui_select_none)) }
        }
        for (kind in listOf(RestoreUnitKind.PARTITION, RestoreUnitKind.APP, RestoreUnitKind.FILE_TREE)) {
            val units = state.groups.of(kind)
            if (units.isEmpty()) continue
            SectionCard(title = stringResource(kind.groupTitleRes()), icon = kind.icon()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val groupState = kindStates.getValue(kind)
                    val groupBytes = remember(units) { RestoreUnitGrouping.saturatingSum(units.map { it.restoreBytes }) }
                    Row(
                        modifier = Modifier.fillMaxWidth().triStateToggleable(state = groupState, role = Role.Checkbox) { onToggleKind(kind) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(state = groupState, onClick = null)
                        Text(
                            stringResource(R.string.restore_ui_group_summary, units.size, sizeText(groupBytes)),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    HorizontalDivider()
                    units.forEach { unit ->
                        UnitLine(
                            unit = unit,
                            modifier = Modifier.toggleable(value = unit.id in chosen, role = Role.Checkbox, onValueChange = { onToggleUnit(unit.id) }).padding(vertical = 2.dp),
                            leading = { Checkbox(checked = unit.id in chosen, onCheckedChange = null) },
                        )
                    }
                }
            }
        }
        if (selected.isEmpty()) {
            Text(stringResource(R.string.restore_ui_problem_nothing_selected), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        WizardNav(onBack = onBack, onNext = onNext, nextEnabled = selected.isNotEmpty())
    }
}

// --- step 2: options ---------------------------------------------------------------------------

/** Callbacks of the options step, grouped so the composable signature stays readable. */
class OptionsActions(
    val edit: ((RestoreWizardInput) -> RestoreWizardInput) -> Unit,
    val setFileTarget: (FileTargetKind) -> Unit,
    val setRisky: (RiskyOption, Boolean) -> Unit,
    val pickTree: () -> Unit,
    val back: () -> Unit,
    val next: () -> Unit,
)

@Composable
fun OptionsStep(state: RestoreBackupUiState, actions: OptionsActions) {
    val input = state.input
    val kinds = state.selectedUnits.map { it.kind }.toSet()
    val problems = state.problems
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WizardHeader(RestoreStep.OPTIONS)
        SectionCard(title = stringResource(R.string.restore_ui_options_general), icon = Icons.Outlined.Tune) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(
                    label = stringResource(R.string.restore_ui_conflict_title),
                    options = ConflictPolicy.entries.map { it.name to stringResource(it.labelRes()) },
                    selected = input.conflictPolicy.name,
                    onSelect = { name -> actions.edit { it.copy(conflictPolicy = ConflictPolicy.valueOf(name)) } },
                )
                Hint(stringResource(input.conflictPolicy.descriptionRes()))
                SwitchRow(
                    label = stringResource(R.string.restore_ui_verify_first),
                    hint = stringResource(R.string.restore_ui_verify_first_hint),
                    checked = input.verifyBeforeRestore,
                    onCheckedChange = { on -> actions.edit { it.copy(verifyBeforeRestore = on) } },
                )
                SwitchRow(
                    label = stringResource(R.string.restore_ui_metadata),
                    hint = stringResource(R.string.restore_ui_metadata_hint),
                    checked = input.restoreMetadata,
                    onCheckedChange = { on -> actions.edit { it.copy(restoreMetadata = on) } },
                )
                RiskySwitchRow(RiskyOption.IGNORE_SPACE, input.ignoreInsufficientSpace, onChange = { actions.setRisky(RiskyOption.IGNORE_SPACE, it) })
            }
        }
        if (RestoreUnitKind.FILE_TREE in kinds) FileOptions(state, actions, problems)
        if (RestoreUnitKind.APP in kinds) AppOptions(state, actions)
        if (RestoreUnitKind.PARTITION in kinds) PartitionOptions(state, actions)
        val general = problems.filter { it != WizardProblem.NOTHING_SELECTED && it !in FILE_PROBLEMS }
        general.forEach { problem -> ProblemLine(problem) }
        WizardNav(onBack = actions.back, onNext = actions.next, nextEnabled = RestoreWizardRules.blocking(problems).isEmpty())
    }
}

private val FILE_PROBLEMS = setOf(
    WizardProblem.FOLDER_REQUIRED,
    WizardProblem.FOLDER_NOT_ABSOLUTE,
    WizardProblem.FOLDER_TRAVERSAL,
    WizardProblem.FOLDER_PROTECTED,
    WizardProblem.FOLDER_IN_APP_DATA,
    WizardProblem.TREE_REQUIRED,
    WizardProblem.TWRP_EXTRACT_NEEDS_FOLDER,
)

@Composable
private fun FileOptions(state: RestoreBackupUiState, actions: OptionsActions, problems: List<WizardProblem>) {
    val input = state.input
    val nandroid = state.selectedUnits.any { it.nandroid }
    SectionCard(title = stringResource(R.string.restore_ui_options_files), icon = Icons.Outlined.Folder) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChips(
                label = stringResource(R.string.restore_ui_target_title),
                options = FileTargetKind.entries.map { it.name to stringResource(it.labelRes()) },
                selected = input.fileTarget.name,
                onSelect = { actions.setFileTarget(FileTargetKind.valueOf(it)) },
            )
            when (input.fileTarget) {
                FileTargetKind.ORIGINAL -> Hint(stringResource(R.string.restore_ui_target_original_hint))
                FileTargetKind.FOLDER -> {
                    val folderError = problems.firstOrNull { it.blocking && it in FILE_PROBLEMS && it != WizardProblem.TREE_REQUIRED && it != WizardProblem.TWRP_EXTRACT_NEEDS_FOLDER }
                    FormTextField(
                        value = input.folderPath,
                        onValueChange = { value -> actions.edit { it.copy(folderPath = value) } },
                        label = stringResource(R.string.restore_ui_folder_label),
                        error = folderError?.let { stringResource(it.messageRes()) },
                        hint = stringResource(R.string.restore_ui_folder_hint),
                    )
                    if (WizardProblem.FOLDER_IN_APP_DATA in problems) ProblemLine(WizardProblem.FOLDER_IN_APP_DATA)
                }
                FileTargetKind.SAF_TREE -> {
                    val tree = input.treeUri
                    Text(
                        if (tree != null) RemovableVolumes.displayPath(tree) else stringResource(R.string.restore_ui_tree_none),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = actions.pickTree) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Text(stringResource(R.string.restore_ui_tree_pick), modifier = Modifier.padding(start = 8.dp))
                    }
                    if (WizardProblem.TREE_REQUIRED in problems) ProblemLine(WizardProblem.TREE_REQUIRED)
                }
            }
            if (nandroid) {
                ChoiceChips(
                    label = stringResource(R.string.restore_ui_twrp_mode_title),
                    options = TwrpRestoreMode.entries.map { it.name to stringResource(it.labelRes()) },
                    selected = input.twrpMode.name,
                    onSelect = { name -> actions.edit { it.copy(twrpMode = TwrpRestoreMode.valueOf(name)) } },
                )
                Hint(stringResource(input.twrpMode.descriptionRes()))
                if (WizardProblem.TWRP_EXTRACT_NEEDS_FOLDER in problems) ProblemLine(WizardProblem.TWRP_EXTRACT_NEEDS_FOLDER)
            }
        }
    }
}

@Composable
private fun AppOptions(state: RestoreBackupUiState, actions: OptionsActions) {
    val input = state.input
    SectionCard(title = stringResource(R.string.restore_ui_options_apps), icon = Icons.Outlined.Apps) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.tier == ElevationTier.NONE) Hint(stringResource(R.string.restore_ui_apps_standard_hint))
            SwitchRow(
                label = stringResource(R.string.restore_ui_apps_apks),
                checked = input.restoreApks,
                onCheckedChange = { on -> actions.edit { it.copy(restoreApks = on) } },
            )
            SwitchRow(
                label = stringResource(R.string.restore_ui_apps_data),
                hint = stringResource(R.string.restore_ui_apps_data_hint),
                checked = input.restoreAppData,
                onCheckedChange = { on -> actions.edit { it.copy(restoreAppData = on) } },
            )
            ChoiceChips(
                label = stringResource(R.string.restore_ui_version_title),
                options = AppVersionPolicy.entries.map { it.name to stringResource(it.labelRes()) },
                selected = input.versionPolicy.name,
                onSelect = { name -> actions.edit { it.copy(versionPolicy = AppVersionPolicy.valueOf(name)) } },
            )
            Hint(stringResource(input.versionPolicy.descriptionRes()))
            RiskySwitchRow(RiskyOption.ALLOW_DOWNGRADE, input.allowDowngrade, onChange = { actions.setRisky(RiskyOption.ALLOW_DOWNGRADE, it) })
            if (input.restoreAppData) {
                SwitchRow(
                    label = stringResource(R.string.restore_ui_apps_external),
                    checked = input.restoreExternalData,
                    onCheckedChange = { on -> actions.edit { it.copy(restoreExternalData = on) } },
                )
                SwitchRow(
                    label = stringResource(R.string.restore_ui_apps_obb),
                    checked = input.restoreObb,
                    onCheckedChange = { on -> actions.edit { it.copy(restoreObb = on) } },
                )
            }
            SwitchRow(
                label = stringResource(R.string.restore_ui_apps_permissions),
                hint = stringResource(R.string.restore_ui_apps_permissions_hint),
                checked = input.restorePermissions,
                onCheckedChange = { on -> actions.edit { it.copy(restorePermissions = on) } },
            )
            if (state.users.size > 1 || (input.targetUserId != null && input.targetUserId != state.callerUserId)) {
                val current = input.targetUserId ?: state.callerUserId
                ChoiceChips(
                    label = stringResource(R.string.restore_ui_apps_user),
                    options = state.users.map { id ->
                        id.toString() to if (id == state.callerUserId) stringResource(R.string.restore_ui_apps_user_self, id) else stringResource(R.string.restore_ui_apps_user_other, id)
                    },
                    selected = current.toString(),
                    onSelect = { value ->
                        val id = value.toIntOrNull() ?: return@ChoiceChips
                        actions.edit { it.copy(targetUserId = if (id == state.callerUserId) null else id) }
                    },
                )
            }
            ExpandableCard(title = stringResource(R.string.restore_ui_apps_advanced)) {
                SwitchRow(
                    label = stringResource(R.string.restore_ui_apps_cache),
                    checked = input.restoreCache,
                    onCheckedChange = { on -> actions.edit { it.copy(restoreCache = on) } },
                )
                SwitchRow(
                    label = stringResource(R.string.restore_ui_apps_installer),
                    hint = stringResource(R.string.restore_ui_apps_installer_hint),
                    checked = input.restoreInstaller,
                    onCheckedChange = { on -> actions.edit { it.copy(restoreInstaller = on) } },
                )
            }
            if (WizardProblem.APP_NOTHING_TO_RESTORE in state.problems) ProblemLine(WizardProblem.APP_NOTHING_TO_RESTORE)
        }
    }
}

@Composable
private fun PartitionOptions(state: RestoreBackupUiState, actions: OptionsActions) {
    val input = state.input
    SectionCard(title = stringResource(R.string.restore_ui_options_partitions), icon = Icons.Outlined.Memory) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.tier != ElevationTier.ROOT) {
                NoticeCard(
                    title = stringResource(R.string.restore_ui_partitions_need_root),
                    body = stringResource(R.string.restore_ui_partitions_need_root_body),
                    tone = Tone.WARNING,
                    icon = Icons.Outlined.Shield,
                )
            }
            Hint(stringResource(R.string.restore_ui_partitions_hint))
            val critical = state.criticalSelected
            if (critical.isNotEmpty()) {
                NoticeCard(
                    title = stringResource(R.string.restore_ui_critical_selected_title),
                    body = stringResource(R.string.restore_ui_critical_selected_body, critical.joinToString(", ")),
                    tone = Tone.ERROR,
                    icon = Icons.Outlined.WarningAmber,
                )
            }
            PartitionOverridesEditor(
                overrides = input.partition,
                onChange = { next ->
                    // Each switch already went through its own dialog; apply them one by one so the
                    // ViewModel sees exactly which gate changed.
                    RiskyOption.entries.filter { it.partition && next.isOn(it) != input.partition.isOn(it) }
                        .forEach { option -> actions.setRisky(option, next.isOn(option)) }
                    if (next.slotChoice != input.partition.slotChoice) actions.edit { it.copy(partition = it.partition.copy(slotChoice = next.slotChoice)) }
                },
                criticalNames = critical,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ProblemLine(problem: WizardProblem) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (problem.blocking) Icons.Outlined.ErrorOutline else Icons.Outlined.WarningAmber,
            contentDescription = stringResource(if (problem.blocking) R.string.restore_ui_cd_error else R.string.restore_ui_cd_warning),
            tint = if (problem.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(problem.messageRes()),
            style = MaterialTheme.typography.bodySmall,
            color = if (problem.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- step 3: preflight -------------------------------------------------------------------------

@Composable
fun PreflightStep(state: RestoreBackupUiState, onRetry: () -> Unit, onBack: () -> Unit, onStart: () -> Unit) {
    val preflight = state.preflight
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WizardHeader(RestoreStep.PREFLIGHT)
        Text(stringResource(R.string.restore_ui_preflight_intro), style = MaterialTheme.typography.bodyMedium)
        val report = preflight.report
        when {
            preflight.running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.restore_ui_preflight_running), style = MaterialTheme.typography.bodyMedium)
            }
            preflight.failure != null -> NoticeCard(
                title = stringResource(R.string.restore_ui_preflight_failed),
                body = preflight.failure.text(),
                tone = Tone.ERROR,
                icon = Icons.Outlined.ErrorOutline,
                actions = { TextButton(onClick = onRetry) { Text(stringResource(R.string.restore_ui_retry)) } },
            )
            report == null -> TextButton(onClick = onRetry) { Text(stringResource(R.string.restore_ui_preflight_run)) }
            else -> PreflightReportView(report, state)
        }
        val risky = RestoreWizardRules.activeRiskyOptions(state.input, state.units)
        if (risky.isNotEmpty()) {
            NoticeCard(
                title = stringResource(R.string.restore_ui_risky_active_title),
                body = risky.map { stringResource(it.titleRes()) }.joinToString("\n"),
                tone = Tone.WARNING,
                icon = Icons.Outlined.WarningAmber,
            )
        }
        WizardNav(
            onBack = onBack,
            onNext = { confirming = true },
            nextEnabled = state.canStart,
            nextLabel = stringResource(R.string.restore_ui_start),
        )
    }
    if (confirming) {
        StartRestoreDialog(
            state = state,
            onConfirm = {
                confirming = false
                onStart()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun PreflightReportView(report: PreflightReport, state: RestoreBackupUiState) {
    report.crossDevice?.let { (from, here) ->
        NoticeCard(
            title = stringResource(R.string.restore_ui_cross_device_title),
            body = stringResource(R.string.restore_ui_cross_device_body, deviceLabel(from), deviceLabel(here)),
            tone = Tone.WARNING,
            icon = Icons.Outlined.WarningAmber,
        )
    }
    val available = PreflightRules.availableCount(report.checks)
    Text(
        pluralStringResource(R.plurals.restore_ui_preflight_summary, report.checks.size, available, report.checks.size),
        style = MaterialTheme.typography.titleSmall,
    )
    report.checks.forEach { check -> UnitCheckRow(check) }
    PreflightRules.blocker(report.checks, state.input.ignoreInsufficientSpace)?.let { blocker ->
        Text(stringResource(blocker.messageRes()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun UnitCheckRow(check: UnitCheck) {
    val p = check.preflight
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.padding(top = 2.dp)) {
            if (p.available) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = stringResource(R.string.restore_ui_preflight_available_cd), tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Outlined.Block, contentDescription = stringResource(R.string.restore_ui_preflight_blocked_cd), tint = MaterialTheme.colorScheme.error)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(check.unit.kind.icon(), contentDescription = stringResource(check.unit.kind.groupTitleRes()), modifier = Modifier.size(16.dp))
                Text(check.unit.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusBadge(stringResource(p.requiredTier.requiredLabelRes()), if (p.requiredTier == ElevationTier.NONE) Tone.NEUTRAL else Tone.INFO)
                if (p.available) {
                    val tone = when (check.space) {
                        SpaceVerdict.INSUFFICIENT -> Tone.ERROR
                        SpaceVerdict.UNKNOWN -> Tone.WARNING
                        else -> Tone.NEUTRAL
                    }
                    StatusBadge(stringResource(check.space.labelRes()), tone)
                }
            }
            if (p.available) {
                val required = p.requiredBytes
                val free = p.freeBytes
                if (required != null && required > 0) {
                    Text(
                        if (free != null) {
                            stringResource(R.string.restore_ui_preflight_space, sizeText(required), sizeText(free))
                        } else {
                            stringResource(R.string.restore_ui_preflight_space_needed, sizeText(required))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                check.shortfallBytes?.let {
                    Text(stringResource(R.string.restore_ui_preflight_shortfall, sizeText(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            (check.failure?.takeIf { it !is OpenFailure.Other }?.text() ?: p.reason)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            p.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Final confirmation with what the restore will change on this device. */
@Composable
private fun StartRestoreDialog(state: RestoreBackupUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val report = state.preflight.report
    val runnable = report?.checks?.filter { it.preflight.available }?.map { it.unit } ?: state.selectedUnits
    val input = state.input
    val lines = ArrayList<String>()
    val files = runnable.count { it.kind == RestoreUnitKind.FILE_TREE }
    val apps = runnable.count { it.kind == RestoreUnitKind.APP }
    val partitions = runnable.filter { it.kind == RestoreUnitKind.PARTITION }
    if (files > 0) {
        val target = when (input.fileTarget) {
            FileTargetKind.ORIGINAL -> stringResource(R.string.restore_ui_target_original)
            FileTargetKind.FOLDER -> RestoreWizardRules.normalizeFolder(input.folderPath)
            FileTargetKind.SAF_TREE -> input.treeUri?.let(RemovableVolumes::displayPath) ?: stringResource(R.string.restore_ui_tree_none)
        }
        lines += pluralStringResource(R.plurals.restore_ui_confirm_files, files, files, target, stringResource(input.conflictPolicy.descriptionRes()))
    }
    if (apps > 0) lines += pluralStringResource(R.plurals.restore_ui_confirm_apps, apps, apps)
    if (partitions.isNotEmpty()) {
        lines += stringResource(R.string.restore_ui_confirm_partitions, partitions.joinToString(", ") { it.displayName })
    }
    ConsequenceDialog(
        title = stringResource(R.string.restore_ui_confirm_title),
        consequence = stringResource(
            R.string.restore_ui_confirm_body,
            state.details?.manifest?.profile?.name ?: state.jobDir,
        ),
        confirmLabel = stringResource(R.string.restore_ui_start),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        details = lines,
    )
}
