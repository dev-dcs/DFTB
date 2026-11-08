package ca.devdcs.backup.ui.discovery

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.discovery.files.AccessRequirement
import ca.devdcs.backup.core.discovery.files.FileScanText
import ca.devdcs.backup.core.discovery.files.RootOutcome
import ca.devdcs.backup.core.discovery.files.ScanProgress
import ca.devdcs.backup.core.discovery.files.ScanResult
import ca.devdcs.backup.core.discovery.files.ScanSummary
import ca.devdcs.backup.core.discovery.files.ScannedFile
import ca.devdcs.backup.core.discovery.files.WalkerDecision
import ca.devdcs.backup.domain.model.FileCategory
import ca.devdcs.backup.ui.components.KeyValueRow
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.ExpandableCard
import ca.devdcs.backup.ui.destinations.FormTextField
import ca.devdcs.backup.ui.destinations.SwitchRow
import java.util.Locale

/** Which date bound a picker dialog edits. */
private enum class DateBound { AFTER, BEFORE }

/**
 * File-scan preview: choose a location (common folder, SAF tree, or categories), edit a filter
 * with live validation, run a cancellable preview with live counters, then read the summary and
 * the roots that could not be scanned together with the action that would unlock each one.
 */
@Composable
fun FilesTab(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = currentLocale()
    var pickerMissing by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onAccessChanged() }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onTreePicked(uri)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onAccessChanged()
    }
    val pickTree: () -> Unit = {
        try {
            treeLauncher.launch(null)
        } catch (_: ActivityNotFoundException) {
            pickerMissing = true
        }
    }
    val onRequirement: (AccessRequirement) -> Unit = { requirement ->
        when (requirement) {
            is AccessRequirement.AllFilesAccess -> openAllFilesAccess(context, viewModel.allFilesAccessIntent())
            is AccessRequirement.RuntimePermission -> permissionLauncher.launch(requirement.permissions.toTypedArray())
            is AccessRequirement.ElevatedTier -> Unit
            AccessRequirement.SafRegrant -> pickTree()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "location") {
            LocationCard(
                state = state,
                pickerMissing = pickerMissing,
                onMode = viewModel::setMode,
                onRoot = viewModel::selectRoot,
                onPickTree = pickTree,
                onToggleCategory = viewModel::toggleCategory,
                onRecursive = viewModel::setRecursive,
                onRequirement = onRequirement,
            )
        }
        item(key = "filter") {
            FilterEditor(
                form = state.form,
                parsed = state.parsed,
                locale = locale,
                onChange = viewModel::updateForm,
                onReset = viewModel::resetForm,
            )
        }
        item(key = "run") {
            RunCard(
                state = state,
                onRun = viewModel::run,
                onCancel = viewModel::cancel,
            )
        }
        if (state.rejectedProblems.isNotEmpty()) {
            item(key = "rejected") {
                NoticeCard(
                    title = stringResource(R.string.discovery_files_rejected),
                    body = state.rejectedProblems.joinToString("\n") { FileScanText.describe(context, it) },
                    tone = Tone.ERROR,
                    icon = Icons.Outlined.ErrorOutline,
                )
            }
        }
        if (state.failed) {
            item(key = "failed") {
                NoticeCard(
                    title = stringResource(R.string.discovery_files_failed),
                    tone = Tone.ERROR,
                    icon = Icons.Outlined.ErrorOutline,
                )
            }
        }
        state.result?.let { result ->
            item(key = "summary") { SummaryCard(result) }
            if (result.summary.matchedCount > 0) {
                item(key = "categories") { CategoriesCard(result.summary) }
                item(key = "extensions") { ExtensionsCard(result.summary) }
                item(key = "largest") { LargestCard(result.summary) }
                item(key = "dates") { OldestNewestCard(result.summary, locale) }
            }
            item(key = "roots") { RootsCard(result.roots, onRequirement) }
        }
    }
}

private fun openAllFilesAccess(context: Context, appIntent: Intent) {
    try {
        context.startActivity(appIntent)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            // No settings page for this permission on this build (e.g. API 29).
        }
    }
}

// ------------------------------------------------------------------ location

@Composable
private fun LocationCard(
    state: FilesUiState,
    pickerMissing: Boolean,
    onMode: (SourceMode) -> Unit,
    onRoot: (CommonRoot) -> Unit,
    onPickTree: () -> Unit,
    onToggleCategory: (FileCategory) -> Unit,
    onRecursive: (Boolean) -> Unit,
    onRequirement: (AccessRequirement) -> Unit,
) {
    SectionCard(title = stringResource(R.string.discovery_files_location_title), icon = Icons.Outlined.Folder) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SourceMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.mode == mode,
                    onClick = { onMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = SourceMode.entries.size),
                    label = {
                        Text(
                            stringResource(
                                when (mode) {
                                    SourceMode.FOLDER -> R.string.discovery_files_mode_folder
                                    SourceMode.CATEGORIES -> R.string.discovery_files_mode_categories
                                },
                            ),
                        )
                    },
                )
            }
        }
        when (state.mode) {
            SourceMode.FOLDER -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.commonRoots.forEach { root ->
                        FilterChip(
                            selected = state.folder?.location == root.path,
                            onClick = { onRoot(root) },
                            label = { Text(root.directory ?: stringResource(R.string.discovery_files_storage_root)) },
                        )
                    }
                }
                OutlinedButton(onClick = onPickTree) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.discovery_files_pick_folder), modifier = Modifier.padding(start = 8.dp))
                }
                if (pickerMissing) {
                    Text(
                        stringResource(R.string.destinations_error_no_picker),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val folder = state.folder
                if (folder == null) {
                    Text(
                        stringResource(R.string.discovery_files_no_location),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    KeyValueRow(stringResource(R.string.discovery_files_selected), folder.displayName)
                    PreflightRow(state.preflight, onRequirement)
                }
                SwitchRow(
                    label = stringResource(R.string.discovery_files_recursive),
                    checked = state.recursive,
                    onCheckedChange = onRecursive,
                )
            }
            SourceMode.CATEGORIES -> {
                Text(
                    stringResource(R.string.discovery_files_categories_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FileCategory.entries.forEach { category ->
                        FilterChip(
                            selected = category in state.categories,
                            onClick = { onToggleCategory(category) },
                            label = { Text(stringResource(category.labelRes())) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreflightRow(decision: WalkerDecision?, onRequirement: (AccessRequirement) -> Unit) {
    when (decision) {
        null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        is WalkerDecision.Use -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.discovery_files_will_use, stringResource(FileScanText.of(decision.walker))),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is WalkerDecision.Blocked -> BlockedBlock(
            reason = stringResource(FileScanText.of(decision.reason)),
            requirements = decision.requirements,
            onRequirement = onRequirement,
        )
    }
}

/** A blocked reason plus one action per requirement (ElevatedTier is a hint only: modes are chosen elsewhere). */
@Composable
private fun BlockedBlock(reason: String, requirements: List<AccessRequirement>, onRequirement: (AccessRequirement) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Text(reason, style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            requirements.forEach { requirement ->
                if (requirement is AccessRequirement.ElevatedTier) {
                    Text(
                        stringResource(R.string.discovery_files_tier_hint, stringResource(FileScanText.of(requirement))),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                } else {
                    TextButton(onClick = { onRequirement(requirement) }) { Text(stringResource(FileScanText.of(requirement))) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ filter editor

@Composable
private fun FilterEditor(
    form: FileFilterForm,
    parsed: ParsedFilterForm,
    locale: Locale,
    onChange: ((FileFilterForm) -> FileFilterForm) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    var picking by rememberSaveable { mutableStateOf<DateBound?>(null) }

    @Composable
    fun errorFor(field: FilterFormField): String? {
        val input = parsed.inputErrors[field]?.let { stringResource(it.messageRes()) }
        val engine = parsed.problemsFor(field).map { FileScanText.describe(context, it) }
        return (listOfNotNull(input) + engine).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    ExpandableCard(
        title = stringResource(R.string.discovery_files_filter_title),
        subtitle = if (parsed.filter.isEmpty) {
            stringResource(R.string.discovery_files_filter_none)
        } else {
            stringResource(R.string.discovery_files_filter_active)
        },
        initiallyExpanded = false,
    ) {
        FormTextField(
            value = form.includeExtensions,
            onValueChange = { v -> onChange { it.copy(includeExtensions = v) } },
            label = stringResource(R.string.discovery_files_include_ext),
            hint = stringResource(R.string.discovery_files_ext_hint),
            error = errorFor(FilterFormField.INCLUDE_EXTENSIONS),
        )
        FormTextField(
            value = form.excludeExtensions,
            onValueChange = { v -> onChange { it.copy(excludeExtensions = v) } },
            label = stringResource(R.string.discovery_files_exclude_ext),
            error = errorFor(FilterFormField.EXCLUDE_EXTENSIONS),
        )
        SizeField(
            label = stringResource(R.string.discovery_files_min_size),
            value = form.minSize,
            unit = form.minUnit,
            error = errorFor(FilterFormField.MIN_SIZE),
            onValue = { v -> onChange { it.copy(minSize = v) } },
            onUnit = { u -> onChange { it.copy(minUnit = u) } },
        )
        SizeField(
            label = stringResource(R.string.discovery_files_max_size),
            value = form.maxSize,
            unit = form.maxUnit,
            error = errorFor(FilterFormField.MAX_SIZE),
            onValue = { v -> onChange { it.copy(maxSize = v) } },
            onUnit = { u -> onChange { it.copy(maxUnit = u) } },
        )
        DateField(
            label = stringResource(R.string.discovery_files_modified_after),
            day = form.modifiedOnOrAfterDay,
            locale = locale,
            error = errorFor(FilterFormField.MODIFIED_AFTER),
            onPick = { picking = DateBound.AFTER },
            onClear = { onChange { it.copy(modifiedOnOrAfterDay = null) } },
        )
        DateField(
            label = stringResource(R.string.discovery_files_modified_before),
            day = form.modifiedOnOrBeforeDay,
            locale = locale,
            error = errorFor(FilterFormField.MODIFIED_BEFORE),
            onPick = { picking = DateBound.BEFORE },
            onClear = { onChange { it.copy(modifiedOnOrBeforeDay = null) } },
        )
        FormTextField(
            value = form.includeRegex,
            onValueChange = { v -> onChange { it.copy(includeRegex = v) } },
            label = stringResource(R.string.discovery_files_include_regex),
            hint = stringResource(R.string.discovery_files_regex_hint),
            error = errorFor(FilterFormField.INCLUDE_REGEX),
        )
        FormTextField(
            value = form.excludeRegex,
            onValueChange = { v -> onChange { it.copy(excludeRegex = v) } },
            label = stringResource(R.string.discovery_files_exclude_regex),
            error = errorFor(FilterFormField.EXCLUDE_REGEX),
        )
        SwitchRow(
            label = stringResource(R.string.discovery_files_default_exclusions),
            hint = stringResource(R.string.discovery_files_default_exclusions_hint),
            checked = form.useDefaultExclusions,
            onCheckedChange = { checked -> onChange { it.copy(useDefaultExclusions = checked) } },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onReset) { Text(stringResource(R.string.discovery_files_reset_filter)) }
        }
    }

    picking?.let { bound ->
        val initial = when (bound) {
            DateBound.AFTER -> form.modifiedOnOrAfterDay
            DateBound.BEFORE -> form.modifiedOnOrBeforeDay
        }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val day = pickerState.selectedDateMillis
                        onChange {
                            when (bound) {
                                DateBound.AFTER -> it.copy(modifiedOnOrAfterDay = day)
                                DateBound.BEFORE -> it.copy(modifiedOnOrBeforeDay = day)
                            }
                        }
                        picking = null
                    },
                ) { Text(stringResource(R.string.discovery_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.discovery_cancel)) } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun SizeField(
    label: String,
    value: String,
    unit: SizeUnit,
    error: String?,
    onValue: (String) -> Unit,
    onUnit: (SizeUnit) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = if (error != null) {
            { Text(error) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        trailingIcon = {
            Box {
                TextButton(onClick = { menuOpen = true }) { Text(stringResource(unit.labelRes())) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    SizeUnit.entries.forEach { entry ->
                        DropdownMenuItem(
                            text = { Text(stringResource(entry.labelRes())) },
                            onClick = {
                                onUnit(entry)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun DateField(
    label: String,
    day: Long?,
    locale: Locale,
    error: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    day?.let { formatPickerDay(it, locale) } ?: stringResource(R.string.discovery_files_any_date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPick) {
                Icon(Icons.Outlined.DateRange, contentDescription = stringResource(R.string.discovery_files_pick_date, label))
            }
            if (day != null) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.discovery_clear))
                }
            }
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

// ------------------------------------------------------------------ run / progress

@Composable
private fun RunCard(state: FilesUiState, onRun: () -> Unit, onCancel: () -> Unit) {
    SectionCard(title = stringResource(R.string.discovery_files_preview_title), icon = Icons.Outlined.Tune) {
        if (state.running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            ProgressBlock(state.progress)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.discovery_cancel)) }
            }
        } else {
            when {
                !state.hasSource -> Text(
                    stringResource(R.string.discovery_files_need_location),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                !state.parsed.isValid -> Text(
                    stringResource(R.string.discovery_files_fix_filter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.cancelled) {
                Text(stringResource(R.string.discovery_files_cancelled), style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onRun, enabled = state.canRun) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(if (state.result != null) R.string.discovery_files_run_again else R.string.discovery_files_run),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressBlock(progress: ScanProgress?) {
    val p = progress ?: ScanProgress(0, 0, 0, null)
    KeyValueRow(stringResource(R.string.discovery_files_dirs), p.dirsVisited.toString())
    KeyValueRow(stringResource(R.string.discovery_files_files), p.filesMatched.toString())
    KeyValueRow(stringResource(R.string.discovery_files_bytes), sizeText(p.bytesMatched))
    val path = p.currentPath
    if (path != null) {
        Text(
            path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ results

@Composable
private fun SummaryCard(result: ScanResult) {
    val summary = result.summary
    SectionCard(title = stringResource(R.string.discovery_files_summary_title), icon = Icons.Outlined.Info) {
        KeyValueRow(stringResource(R.string.discovery_files_files), summary.matchedCount.toString())
        KeyValueRow(stringResource(R.string.discovery_files_bytes), sizeText(summary.matchedBytes))
        KeyValueRow(stringResource(R.string.discovery_files_dirs), summary.dirsVisited.toString())
        KeyValueRow(stringResource(R.string.discovery_files_skipped), summary.skippedUnreadable.toString())
        if (summary.matchedCount == 0L) {
            Text(
                stringResource(R.string.discovery_files_nothing_matched),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CategoriesCard(summary: ScanSummary) {
    SectionCard(title = stringResource(R.string.discovery_files_by_category)) {
        FileCategory.entries.forEach { category ->
            val stat = summary.byCategory[category] ?: return@forEach
            if (stat.count == 0L) return@forEach
            StatRow(stringResource(category.labelRes()), stat.count, stat.bytes)
        }
        if (summary.unclassified.count > 0) {
            StatRow(stringResource(R.string.discovery_files_unclassified), summary.unclassified.count, summary.unclassified.bytes)
        }
    }
}

@Composable
private fun ExtensionsCard(summary: ScanSummary) {
    SectionCard(title = stringResource(R.string.discovery_files_top_extensions)) {
        summary.byExtension.forEach { stat ->
            StatRow(
                if (stat.extension.isEmpty()) stringResource(R.string.discovery_files_no_extension) else ".${stat.extension}",
                stat.count,
                stat.bytes,
            )
        }
    }
}

@Composable
private fun StatRow(label: String, count: Long, bytes: Long) {
    KeyValueRow(
        label,
        stringResource(
            R.string.discovery_files_count_size,
            pluralStringResource(R.plurals.discovery_files_count, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), count),
            sizeText(bytes),
        ),
    )
}

@Composable
private fun LargestCard(summary: ScanSummary) {
    SectionCard(title = stringResource(R.string.discovery_files_largest)) {
        summary.largestFiles.forEach { file -> KeyValueRow(file.relativePath, sizeText(file.sizeBytes)) }
    }
}

@Composable
private fun OldestNewestCard(summary: ScanSummary, locale: Locale) {
    SectionCard(title = stringResource(R.string.discovery_files_dates_title)) {
        FileWithDate(stringResource(R.string.discovery_files_oldest), summary.oldest, locale)
        HorizontalDivider()
        FileWithDate(stringResource(R.string.discovery_files_newest), summary.newest, locale)
    }
}

@Composable
private fun FileWithDate(label: String, file: ScannedFile?, locale: Locale) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (file == null) {
            Text(stringResource(R.string.discovery_unknown), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(file.relativePath, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                formatDateTime(file.modifiedAtMillis, locale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RootsCard(roots: List<RootOutcome>, onRequirement: (AccessRequirement) -> Unit) {
    val blocked = roots.count { it.blocked != null || it.rootProblem != null }
    SectionCard(
        title = stringResource(R.string.discovery_files_roots_title),
        subtitle = if (blocked > 0) pluralStringResource(R.plurals.discovery_files_blocked_roots, blocked, blocked) else null,
        icon = if (blocked > 0) Icons.Outlined.Warning else null,
    ) {
        roots.forEachIndexed { index, root ->
            if (index > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(root.location, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                root.walker?.let { walker ->
                    Text(
                        stringResource(R.string.discovery_files_walker_used, stringResource(FileScanText.of(walker))),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                root.rootProblem?.let { problem ->
                    BlockedBlock(stringResource(FileScanText.of(problem)), emptyList(), onRequirement)
                }
                root.blocked?.let { reason ->
                    BlockedBlock(stringResource(FileScanText.of(reason)), root.requirements, onRequirement)
                }
                if (root.blocked == null && root.uncoveredCategories.isNotEmpty() && root.requirements.isNotEmpty()) {
                    BlockedBlock(
                        stringResource(
                            R.string.discovery_files_uncovered,
                            root.uncoveredCategories.sortedBy { it.ordinal }.map { stringResource(it.labelRes()) }.joinToString(", "),
                        ),
                        root.requirements,
                        onRequirement,
                    )
                }
                root.warnings.forEach { warning ->
                    Text(
                        stringResource(FileScanText.of(warning)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}
