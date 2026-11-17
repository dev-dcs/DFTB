package ca.devdcs.backup.ui.profiles

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.devdcs.backup.R
import ca.devdcs.backup.core.discovery.files.AccessRequirement
import ca.devdcs.backup.core.discovery.files.FileScanText
import ca.devdcs.backup.core.discovery.files.WalkerDecision
import ca.devdcs.backup.domain.model.FileCategory
import ca.devdcs.backup.domain.model.FileFilter
import ca.devdcs.backup.domain.model.PathTarget
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.FormTextField
import ca.devdcs.backup.ui.destinations.SwitchRow
import ca.devdcs.backup.ui.discovery.FileFilterForm
import ca.devdcs.backup.ui.discovery.FilterFormField
import ca.devdcs.backup.ui.discovery.SizeUnit
import ca.devdcs.backup.ui.discovery.currentLocale
import ca.devdcs.backup.ui.discovery.formatPickerDay
import ca.devdcs.backup.ui.discovery.labelRes
import ca.devdcs.backup.ui.discovery.messageRes
import ca.devdcs.backup.ui.discovery.sizeText
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Files part of the targets step: folder targets (common roots, the system folder picker, or a
 * typed absolute path) with per-folder recursion, filter and access status, plus file categories
 * collected from the standard media folders.
 */
@Composable
internal fun FilesPane(state: WizardUiState, viewModel: ProfileWizardViewModel, modifier: Modifier) {
    val draft = state.draft ?: return
    val context = LocalContext.current
    var manualPath by rememberSaveable { mutableStateOf("") }
    var manualRejected by rememberSaveable { mutableStateOf(false) }
    var pickerMissing by remember { mutableStateOf(false) }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onTreePicked(uri)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshEnvironment()
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
    val present = draft.targets.paths.map { TargetEdits.normalizeLocation(it.location) }.toSet()

    LazyColumn(modifier = modifier, contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "add") {
            SectionCard(title = stringResource(R.string.profiles_files_add_title), icon = Icons.Outlined.Folder) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.commonRoots.forEach { root ->
                        val selected = TargetEdits.normalizeLocation(root.path) in present
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (selected) {
                                    draft.targets.paths.firstOrNull { TargetEdits.normalizeLocation(it.location) == TargetEdits.normalizeLocation(root.path) }
                                        ?.let { viewModel.removePath(it.location) }
                                } else {
                                    viewModel.addPath(root.path)
                                }
                            },
                            label = { Text(root.directory ?: stringResource(R.string.profiles_files_storage_root)) },
                        )
                    }
                }
                OutlinedButton(onClick = pickTree) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.profiles_files_pick_folder), modifier = Modifier.padding(start = 8.dp))
                }
                if (pickerMissing) {
                    Text(
                        stringResource(R.string.profiles_files_no_picker),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormTextField(
                        value = manualPath,
                        onValueChange = {
                            manualPath = it
                            manualRejected = false
                        },
                        label = stringResource(R.string.profiles_files_path_field),
                        placeholder = stringResource(R.string.profiles_files_path_placeholder),
                        error = if (manualRejected) stringResource(R.string.profiles_files_path_rejected) else null,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            if (viewModel.addPath(manualPath)) manualPath = "" else manualRejected = true
                        },
                        enabled = manualPath.isNotBlank(),
                    ) { Text(stringResource(R.string.profiles_files_add)) }
                }
            }
        }
        if (draft.targets.paths.isEmpty()) {
            item(key = "no-folders") {
                Text(
                    stringResource(R.string.profiles_files_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(draft.targets.paths, key = { "p:" + it.location }) { path ->
            PathCard(
                path = path,
                access = state.pathAccess[path.location],
                onRecursive = { viewModel.setRecursive(path.location, it) },
                onEditFilter = { viewModel.openFilterEditor(path.location) },
                onRemove = { viewModel.removePath(path.location) },
                onRequirement = onRequirement,
            )
        }
        item(key = "categories") {
            SectionCard(
                title = stringResource(R.string.profiles_files_categories_title),
                subtitle = stringResource(R.string.profiles_files_categories_sub),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FileCategory.entries.forEach { category ->
                        FilterChip(
                            selected = category in draft.targets.categories,
                            onClick = { viewModel.toggleCategory(category) },
                            label = { Text(stringResource(category.labelRes())) },
                        )
                    }
                }
            }
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

/** What the UI shows for a folder target: the SAF tree's document id, or the path itself. */
fun locationLabel(location: String): String {
    if (!location.startsWith("content://")) return location
    return try {
        DocumentsContract.getTreeDocumentId(Uri.parse(location))
    } catch (e: IllegalArgumentException) {
        location
    }
}

@Composable
private fun PathCard(
    path: PathTarget,
    access: WalkerDecision?,
    onRecursive: (Boolean) -> Unit,
    onEditFilter: () -> Unit,
    onRemove: () -> Unit,
    onRequirement: (AccessRequirement) -> Unit,
) {
    val label = locationLabel(path.location)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (path.isSafUri) {
                        Text(
                            stringResource(R.string.profiles_files_saf_folder),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.profiles_files_remove, label))
                }
            }
            AccessLine(access, onRequirement)
            SwitchRow(
                label = stringResource(R.string.profiles_files_recursive),
                checked = path.recursive,
                onCheckedChange = onRecursive,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    filterSummary(path.filter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEditFilter) {
                    Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.profiles_files_edit_filter), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun AccessLine(decision: WalkerDecision?, onRequirement: (AccessRequirement) -> Unit) {
    when (decision) {
        null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        is WalkerDecision.Use -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.profiles_files_readable, stringResource(FileScanText.of(decision.walker))),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is WalkerDecision.Blocked -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Text(stringResource(FileScanText.of(decision.reason)), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                decision.requirements.forEach { requirement ->
                    if (requirement is AccessRequirement.ElevatedTier) {
                        Text(
                            stringResource(R.string.profiles_files_tier_hint, stringResource(FileScanText.of(requirement))),
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
}

/** One-line description of a folder filter ("No filter" or its active criteria). */
@Composable
private fun filterSummary(filter: FileFilter?): String {
    if (filter == null || filter.isEmpty) return stringResource(R.string.profiles_filter_none)
    val locale = currentLocale()
    val parts = buildList {
        if (filter.includeExtensions.isNotEmpty()) {
            add(stringResource(R.string.profiles_filter_only, filter.includeExtensions.joinToString(", ") { ".$it" }))
        }
        if (filter.excludeExtensions.isNotEmpty()) {
            add(stringResource(R.string.profiles_filter_except, filter.excludeExtensions.joinToString(", ") { ".$it" }))
        }
        filter.minSizeBytes?.let { add(stringResource(R.string.profiles_filter_min, sizeText(it))) }
        filter.maxSizeBytes?.let { add(stringResource(R.string.profiles_filter_max, sizeText(it))) }
        // Bounds are exclusive: "after" is one millisecond before its first day, "before" the start of its day.
        filter.modifiedAfterEpochMillis?.let { add(stringResource(R.string.profiles_filter_after, formatDay(it + 1, locale))) }
        filter.modifiedBeforeEpochMillis?.let { add(stringResource(R.string.profiles_filter_before, formatDay(it, locale))) }
        if (!filter.includeRegex.isNullOrEmpty() || !filter.excludeRegex.isNullOrEmpty()) add(stringResource(R.string.profiles_filter_regex))
    }
    return parts.joinToString(SEPARATOR)
}

/** Localized medium date in the device zone. */
private fun formatDay(millis: Long, locale: Locale): String = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(millis))

/** Which date bound a picker dialog edits. */
private enum class DateBound { AFTER, BEFORE }

/**
 * Edits one folder's filter with the Discovery editor's fields and live validation (the parser is
 * shared); Save stays disabled while any field has an error.
 */
@Composable
internal fun FilterEditorDialog(
    editor: FilterEditorState,
    onChange: ((FileFilterForm) -> FileFilterForm) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val locale = currentLocale()
    val form = editor.form
    val parsed = editor.parsed
    var picking by rememberSaveable { mutableStateOf<DateBound?>(null) }

    @Composable
    fun errorFor(field: FilterFormField): String? {
        val input = parsed.inputErrors[field]?.let { stringResource(it.messageRes()) }
        val engine = parsed.problemsFor(field).map { FileScanText.describe(context, it) }
        return (listOfNotNull(input) + engine).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_filter_title, locationLabel(editor.location))) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                SizeInput(
                    label = stringResource(R.string.discovery_files_min_size),
                    value = form.minSize,
                    unit = form.minUnit,
                    error = errorFor(FilterFormField.MIN_SIZE),
                    onValue = { v -> onChange { it.copy(minSize = v) } },
                    onUnit = { u -> onChange { it.copy(minUnit = u) } },
                )
                SizeInput(
                    label = stringResource(R.string.discovery_files_max_size),
                    value = form.maxSize,
                    unit = form.maxUnit,
                    error = errorFor(FilterFormField.MAX_SIZE),
                    onValue = { v -> onChange { it.copy(maxSize = v) } },
                    onUnit = { u -> onChange { it.copy(maxUnit = u) } },
                )
                DateInput(
                    label = stringResource(R.string.discovery_files_modified_after),
                    day = form.modifiedOnOrAfterDay,
                    locale = locale,
                    error = errorFor(FilterFormField.MODIFIED_AFTER),
                    onPick = { picking = DateBound.AFTER },
                    onClear = { onChange { it.copy(modifiedOnOrAfterDay = null) } },
                )
                DateInput(
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onReset) { Text(stringResource(R.string.discovery_files_reset_filter)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = parsed.isValid) { Text(stringResource(R.string.profiles_filter_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.profiles_cancel)) } },
    )

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
                ) { Text(stringResource(R.string.profiles_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.profiles_cancel)) } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun SizeInput(
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
private fun DateInput(label: String, day: Long?, locale: Locale, error: String?, onPick: () -> Unit, onClear: () -> Unit) {
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
        if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
