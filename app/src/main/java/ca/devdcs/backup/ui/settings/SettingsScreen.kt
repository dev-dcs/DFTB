package ca.devdcs.backup.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R

/**
 * Navigation the settings screens need from outside their package. Built once by
 * [settingsGraph] from the NavController so section composables stay free of navigation types.
 */
@Immutable
class SettingsActions(
    val openSection: (SettingsSectionKey) -> Unit,
    val openDiscovery: () -> Unit,
    val openRcloneSetup: () -> Unit,
    val openRcloneConfig: () -> Unit,
    val openLicenses: () -> Unit,
    val openSystemInfo: () -> Unit,
)

/**
 * Settings root. A Material 3 list grouped by category on phones (each row opens its section
 * route); on expanded widths a list-detail layout shows the selected section beside the list so a
 * tablet never shows a stretched single column. Top-level: no up button, no bottom bar (the
 * shell's scaffold owns those).
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun SettingsScreen(
    actions: SettingsActions,
    viewModel: GeneralSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedKey by rememberSaveable { mutableStateOf(SettingsSectionKey.LANGUAGE.key) }
    val selected = SettingsSectionKey.fromKey(selectedKey) ?: SettingsSectionKey.LANGUAGE
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sizeClass = WindowSizeClass.calculateFromSize(DpSize(maxWidth, maxHeight))
        val twoPane = sizeClass.widthSizeClass == WindowWidthSizeClass.Expanded
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    scrollBehavior = scrollBehavior,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            if (twoPane) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    SettingsRootList(
                        state = state,
                        selected = selected,
                        onSelect = { selectedKey = it.key },
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                        modifier = Modifier
                            .width(ListPaneWidth)
                            .fillMaxHeight(),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Text(
                            text = stringResource(selected.titleRes),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
                        )
                        SettingsSectionContent(
                            section = selected,
                            actions = actions,
                            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                            snackbarHostState = snackbarHostState,
                        )
                    }
                }
            } else {
                SettingsRootList(
                    state = state,
                    selected = null,
                    onSelect = actions.openSection,
                    contentPadding = innerPadding.combinedWith(PaddingValues(top = 8.dp, bottom = 24.dp)),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * One settings section on its own screen (phones, deep links). Unknown keys render an
 * explanation instead of crashing, because keys arrive from other features' navigate() calls.
 */
@Composable
fun SettingsSectionScreen(
    sectionKey: String,
    actions: SettingsActions,
    onBack: () -> Unit,
) {
    val section = SettingsSectionKey.fromKey(sectionKey)
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(section?.titleRes ?: R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (section == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_unknown_section, sectionKey),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SettingsSectionContent(
                section = section,
                actions = actions,
                contentPadding = innerPadding.combinedWith(SectionContentPadding),
                snackbarHostState = snackbarHostState,
            )
        }
    }
}

/** Routes a section key to its content composable; shared by the single and two-pane layouts. */
@Composable
internal fun SettingsSectionContent(
    section: SettingsSectionKey,
    actions: SettingsActions,
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
) {
    when (section) {
        SettingsSectionKey.LANGUAGE -> LanguageSection(contentPadding, snackbarHostState)
        SettingsSectionKey.APPEARANCE -> AppearanceSection(contentPadding, snackbarHostState)
        SettingsSectionKey.MODE -> ModeSection(contentPadding, snackbarHostState, onOpenDiscovery = actions.openDiscovery)
        SettingsSectionKey.DEFAULTS -> DefaultsSection(contentPadding, snackbarHostState)
        SettingsSectionKey.SECURITY -> SecuritySection(
            contentPadding,
            snackbarHostState,
            onOpenRcloneSetup = actions.openRcloneSetup,
            onOpenRcloneConfig = actions.openRcloneConfig,
        )
        SettingsSectionKey.NOTIFICATIONS -> NotificationsSection(contentPadding, snackbarHostState)
        SettingsSectionKey.STORAGE -> StorageSection(contentPadding, snackbarHostState)
        SettingsSectionKey.ABOUT -> AboutSection(
            contentPadding,
            snackbarHostState,
            onOpenLicenses = actions.openLicenses,
            onOpenSystemInfo = actions.openSystemInfo,
        )
    }
}

/** Category headers and one row per section; [selected] highlights the open section (two-pane). */
@Composable
private fun SettingsRootList(
    state: GeneralSettingsUiState,
    selected: SettingsSectionKey?,
    onSelect: (SettingsSectionKey) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val groups = remember { SettingsSectionKey.grouped() }
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = SettingsContentMaxWidth)
                .fillMaxWidth()
                .then(if (selected != null) Modifier.selectableGroup() else Modifier),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            groups.forEach { (category, sections) ->
                item(key = "header_${category.name}") { SettingsCategoryHeader(stringResource(category.titleRes)) }
                items(sections, key = { it.key }) { section ->
                    SettingsRow(
                        section = section,
                        summary = section.summary(state),
                        selected = section == selected,
                        showChevron = selected == null,
                        onClick = { onSelect(section) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    section: SettingsSectionKey,
    summary: String,
    selected: Boolean,
    showChevron: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ListItem(
        headlineContent = { Text(stringResource(section.titleRes)) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(section.icon(), contentDescription = null) },
        trailingContent = if (showChevron) {
            { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) }
        } else {
            null
        },
        colors = if (selected) {
            ListItemDefaults.colors(
                containerColor = scheme.secondaryContainer,
                headlineColor = scheme.onSecondaryContainer,
                leadingIconColor = scheme.onSecondaryContainer,
                supportingColor = scheme.onSecondaryContainer,
            )
        } else {
            ListItemDefaults.colors()
        },
        // Phones: a navigation row. Two-pane: one tab of a single-selection group.
        modifier = if (showChevron) {
            Modifier.clickable(role = Role.Button, onClick = onClick)
        } else {
            Modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick)
        },
    )
}

/** Live summary for sections whose current value fits on one line; a static blurb otherwise. */
@Composable
private fun SettingsSectionKey.summary(state: GeneralSettingsUiState): String = when (this) {
    SettingsSectionKey.LANGUAGE -> state.language.nativeName
    SettingsSectionKey.APPEARANCE -> stringResource(state.themeMode.labelRes)
    SettingsSectionKey.MODE -> state.operatingMode?.let { stringResource(it.labelRes) } ?: stringResource(R.string.settings_mode_none)
    else -> stringResource(summaryRes)
}

/** Leading icon of a section row. */
internal fun SettingsSectionKey.icon(): ImageVector = when (this) {
    SettingsSectionKey.LANGUAGE -> Icons.Outlined.Language
    SettingsSectionKey.APPEARANCE -> Icons.Outlined.Palette
    SettingsSectionKey.MODE -> Icons.Outlined.Security
    SettingsSectionKey.DEFAULTS -> Icons.Outlined.Tune
    SettingsSectionKey.SECURITY -> Icons.Outlined.VpnKey
    SettingsSectionKey.NOTIFICATIONS -> Icons.Outlined.Notifications
    SettingsSectionKey.STORAGE -> Icons.Outlined.Storage
    SettingsSectionKey.ABOUT -> Icons.Outlined.Info
}

/** Scaffold inner padding plus a section's own padding, honouring the layout direction. */
@Composable
internal fun PaddingValues.combinedWith(extra: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction) + extra.calculateStartPadding(direction),
        end = calculateEndPadding(direction) + extra.calculateEndPadding(direction),
        top = calculateTopPadding() + extra.calculateTopPadding(),
        bottom = calculateBottomPadding() + extra.calculateBottomPadding(),
    )
}

private val ListPaneWidth = 360.dp
