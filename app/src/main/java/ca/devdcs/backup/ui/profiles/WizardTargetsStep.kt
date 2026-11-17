package ca.devdcs.backup.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.discovery.apps.ComponentAvailability
import ca.devdcs.backup.domain.model.AppComponent
import ca.devdcs.backup.domain.model.AppTarget
import ca.devdcs.backup.domain.model.SlotPolicy
import ca.devdcs.backup.ui.destinations.ExpandableCard
import ca.devdcs.backup.ui.discovery.AppsTab
import ca.devdcs.backup.ui.discovery.AppsViewModel
import ca.devdcs.backup.ui.discovery.PartitionsTab
import ca.devdcs.backup.ui.discovery.labelRes
import ca.devdcs.backup.ui.discovery.partitionGroupKey

/** Sub-pages of the targets step. */
private enum class TargetsTab { PARTITIONS, APPS, FILES }

/** Partitions kept in this order; [SlotPolicy.NONE] is implied for un-slotted partitions only. */
private val SLOT_CHOICES = listOf(SlotPolicy.ACTIVE, SlotPolicy.INACTIVE, SlotPolicy.BOTH)

/**
 * (b) What to back up: partitions (Discovery's partition browser in selectable mode plus the slot
 * policy), apps (the app browser in selectable mode plus per-app / default components with the
 * reasons the current mode cannot capture some), and files (folders, categories, filters).
 */
@Composable
internal fun TargetsStep(state: WizardUiState, viewModel: ProfileWizardViewModel, modifier: Modifier) {
    val draft = state.draft ?: return
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = TargetsTab.entries[tabIndex.coerceIn(0, TargetsTab.entries.lastIndex)]
    val problems = state.visibleProblemsFor(WizardStep.TARGETS)
    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            TargetsTab.entries.forEach { entry ->
                val count = when (entry) {
                    TargetsTab.PARTITIONS -> draft.targets.partitions.size
                    TargetsTab.APPS -> draft.targets.apps.size
                    TargetsTab.FILES -> draft.targets.paths.size + draft.targets.categories.size
                }
                Tab(
                    selected = entry == tab,
                    onClick = { tabIndex = entry.ordinal },
                    text = {
                        Text(
                            stringResource(
                                R.string.profiles_targets_tab_count,
                                stringResource(
                                    when (entry) {
                                        TargetsTab.PARTITIONS -> R.string.profiles_targets_tab_partitions
                                        TargetsTab.APPS -> R.string.profiles_targets_tab_apps
                                        TargetsTab.FILES -> R.string.profiles_targets_tab_files
                                    },
                                ),
                                count,
                            ),
                            maxLines = 1,
                        )
                    },
                )
            }
        }
        if (problems.isNotEmpty()) {
            ProblemLines(problems, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
        val paneModifier = Modifier.weight(1f).fillMaxWidth()
        when (tab) {
            TargetsTab.PARTITIONS -> PartitionsPane(draft, viewModel, paneModifier)
            TargetsTab.APPS -> AppsPane(state, draft, viewModel, paneModifier)
            TargetsTab.FILES -> FilesPane(state, viewModel, paneModifier)
        }
    }
}

@Composable
private fun PartitionsPane(draft: ProfileDraft, viewModel: ProfileWizardViewModel, modifier: Modifier) {
    Column(modifier) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.profiles_slot_title), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SLOT_CHOICES.forEachIndexed { index, policy ->
                    SegmentedButton(
                        selected = draft.slotPolicy == policy,
                        onClick = { viewModel.setSlotPolicy(policy) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = SLOT_CHOICES.size),
                        label = { Text(stringResource(policy.labelRes()), maxLines = 1) },
                    )
                }
            }
            Text(
                stringResource(draft.slotPolicy.descriptionRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PartitionsTab(
            contentPadding = PagePadding,
            modifier = Modifier.weight(1f),
            selectable = true,
            selectedKeys = draft.selectedPartitionKeys,
            onToggleGroup = { group -> viewModel.togglePartition(group, partitionGroupKey(group)) },
        )
    }
}

@Composable
private fun AppsPane(state: WizardUiState, draft: ProfileDraft, viewModel: ProfileWizardViewModel, modifier: Modifier) {
    val appsViewModel: AppsViewModel = hiltViewModel()
    val appsState by appsViewModel.uiState.collectAsStateWithLifecycle()
    var perAppOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        ExpandableCard(
            title = stringResource(R.string.profiles_components_title),
            subtitle = stringResource(
                R.string.profiles_components_subtitle,
                draft.defaultComponents.sortedBy { it.ordinal }.map { stringResource(it.labelRes()) }.joinToString(", ")
                    .ifEmpty { stringResource(R.string.profiles_components_none) },
            ),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        ) {
            ComponentChips(
                selected = draft.defaultComponents,
                availability = state.availability,
                onToggle = viewModel::toggleDefaultComponent,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = viewModel::applyDefaultsToAll, enabled = draft.targets.apps.isNotEmpty()) {
                    Text(stringResource(R.string.profiles_components_apply_all))
                }
                TextButton(onClick = { perAppOpen = true }, enabled = draft.targets.apps.isNotEmpty()) {
                    Text(stringResource(R.string.profiles_components_per_app, draft.targets.apps.size))
                }
            }
        }
        AppsTab(
            contentPadding = PagePadding,
            modifier = Modifier.weight(1f),
            selectable = true,
            selectedPackages = draft.selectedPackages,
            onToggleApp = viewModel::toggleApp,
            viewModel = appsViewModel,
        )
    }
    if (perAppOpen) {
        val labels = appsState.apps.associate { it.packageName to it.label }
        PerAppComponentsDialog(
            apps = draft.targets.apps,
            labels = labels,
            availability = state.availability,
            onToggle = viewModel::toggleAppComponent,
            onRemove = viewModel::removeApp,
            onDismiss = { perAppOpen = false },
        )
    }
}

/**
 * One chip per [AppComponent]. Components the current mode cannot capture are disabled (unless
 * already selected, so they can be removed) and their reason is listed underneath.
 */
@Composable
private fun ComponentChips(
    selected: Set<AppComponent>,
    availability: Map<AppComponent, ComponentAvailability>,
    onToggle: (AppComponent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppComponent.entries.forEach { component ->
                val available = availability[component]?.available != false
                val isSelected = component in selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggle(component) },
                    enabled = available || isSelected,
                    label = { Text(stringResource(component.labelRes())) },
                    leadingIcon = if (!available) {
                        {
                            Icon(
                                Icons.Outlined.Block,
                                contentDescription = stringResource(R.string.profiles_cd_unavailable),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        // Reasons for every component this mode cannot capture; a warning when it is selected anyway.
        AppComponent.entries.forEach { component ->
            val entry = availability[component] ?: return@forEach
            if (!entry.available) {
                HintLine(
                    stringResource(R.string.profiles_component_reason, stringResource(component.labelRes()), stringResource(entry.reason.messageRes)),
                    warning = component in selected,
                )
            }
        }
    }
}

@Composable
private fun PerAppComponentsDialog(
    apps: List<AppTarget>,
    labels: Map<String, String>,
    availability: Map<AppComponent, ComponentAvailability>,
    onToggle: (String, AppComponent) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_components_per_app_title)) },
        text = {
            if (apps.isEmpty()) {
                Text(stringResource(R.string.profiles_components_per_app_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = PER_APP_MAX_HEIGHT),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        labels[app.packageName] ?: app.packageName,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { onRemove(app.packageName) }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = stringResource(R.string.profiles_remove_app, labels[app.packageName] ?: app.packageName),
                                    )
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AppComponent.entries.forEach { component ->
                                    val available = availability[component]?.available != false
                                    val isSelected = component in app.components
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { onToggle(app.packageName, component) },
                                        enabled = available || isSelected,
                                        label = { Text(stringResource(component.labelRes())) },
                                    )
                                }
                            }
                            if (app.components.isEmpty()) {
                                Text(
                                    stringResource(R.string.profiles_problem_app_no_components),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.profiles_done)) } },
    )
}

private val PER_APP_MAX_HEIGHT = 420.dp
