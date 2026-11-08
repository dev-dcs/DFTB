package ca.devdcs.backup.ui.discovery

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.discovery.apps.AppFlagBadge
import ca.devdcs.backup.core.discovery.apps.AppScanWarning
import ca.devdcs.backup.core.discovery.apps.AppScanWarningKind
import ca.devdcs.backup.core.discovery.apps.AppSizeInfo
import ca.devdcs.backup.core.discovery.apps.ComponentAvailability
import ca.devdcs.backup.core.discovery.apps.InstalledApp
import ca.devdcs.backup.core.discovery.apps.SizeSource
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.domain.model.AppComponent
import ca.devdcs.backup.ui.components.KeyValueRow
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.ExpandableCard

private val ICON_SIZE = 40.dp

/** Warning kinds that have their own dedicated card, so the generic list skips them. */
private val DEDICATED_WARNINGS = setOf(AppScanWarningKind.LIMITED_PACKAGE_VISIBILITY, AppScanWarningKind.USAGE_ACCESS_NOT_GRANTED)

/**
 * Installed-app browser: search / filter / sort, lazy icons, on-demand size measurement with a
 * per-app breakdown, what the current tier can capture, and the usage-access / visibility caveats.
 * In [selectable] mode each row gets a checkbox; the caller owns [selectedPackages] and receives
 * toggles via [onToggleApp] (used by the picker; off in the Discovery hub).
 */
@Composable
fun AppsTab(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    selectedPackages: Set<String> = emptySet(),
    onToggleApp: (InstalledApp) -> Unit = {},
    viewModel: AppsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = currentLocale()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshAccess() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val scan = state.scan
        if (scan == null) {
            item(key = "loading") {
                if (state.failed) {
                    NoticeCard(
                        title = stringResource(R.string.apps_warn_list_failed),
                        tone = Tone.ERROR,
                        icon = Icons.Outlined.ErrorOutline,
                        actions = { TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.discovery_retry)) } },
                    )
                } else {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
            return@LazyColumn
        }

        if (scan.limitedVisibility) {
            item(key = "visibility") {
                NoticeCard(
                    title = stringResource(R.string.discovery_apps_limited_title),
                    body = stringResource(R.string.apps_warn_limited_visibility),
                    tone = Tone.INFO,
                    icon = Icons.Outlined.Info,
                )
            }
        }
        // Usage access only matters when sizes come from StorageStatsManager: Standard mode, or a
        // shell tier that is currently down.
        val statsPath = scan.tier == ElevationTier.NONE || state.warnings.any { it.kind == AppScanWarningKind.SHELL_UNAVAILABLE }
        if (!state.usageAccessGranted && statsPath) {
            item(key = "usage") {
                NoticeCard(
                    title = stringResource(R.string.discovery_apps_usage_title),
                    body = stringResource(R.string.discovery_apps_usage_body),
                    tone = Tone.WARNING,
                    icon = Icons.Outlined.Timer,
                    actions = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = { openUsageAccess(context, scan.usageAccessSettingsAction) }) {
                                Text(stringResource(R.string.discovery_apps_usage_open))
                            }
                        }
                    },
                )
            }
        }
        item(key = "availability") { AvailabilityCard(scan.tier, state.availability) }
        item(key = "measure") {
            MeasureCard(
                progress = state.measuring,
                measuredCount = state.sizes.size,
                onMeasure = viewModel::measureSizes,
                onCancel = viewModel::cancelMeasure,
            )
        }
        val generic = state.warnings.filter { it.kind !in DEDICATED_WARNINGS }
        if (generic.isNotEmpty()) item(key = "warnings") { AppWarningsCard(generic) }
        item(key = "controls") {
            ListControls(
                query = state.query,
                onSearch = viewModel::setSearch,
                onToggleFilter = viewModel::toggleFilter,
                onSort = viewModel::setSort,
                refreshing = state.loading,
                onRefresh = viewModel::refresh,
            )
        }
        item(key = "count") {
            Text(
                pluralStringResource(R.plurals.discovery_apps_showing, state.apps.size, state.visibleApps.size, state.apps.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.visibleApps, key = { "a:" + it.packageName }) { app ->
            AppRow(
                app = app,
                size = state.sizes[app.packageName],
                selectable = selectable,
                selected = app.packageName in selectedPackages,
                onToggle = { onToggleApp(app) },
                viewModel = viewModel,
                locale = locale,
            )
        }
    }
}

private fun openUsageAccess(context: Context, action: String) {
    try {
        context.startActivity(Intent(action))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        } catch (_: ActivityNotFoundException) {
            // Neither settings page exists on this build; nothing else can grant usage access.
        }
    }
}

@Composable
private fun AvailabilityCard(tier: ElevationTier, availability: Map<AppComponent, ComponentAvailability>) {
    SectionCard(
        title = stringResource(R.string.discovery_apps_availability_title),
        subtitle = stringResource(R.string.discovery_apps_availability_subtitle, stringResource(tier.labelRes())),
        icon = Icons.Outlined.Storage,
    ) {
        AppComponent.entries.forEach { component ->
            val entry = availability[component] ?: return@forEach
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    if (entry.available) Icons.Outlined.CheckCircle else Icons.Outlined.Block,
                    contentDescription = stringResource(
                        if (entry.available) R.string.apps_avail_available else R.string.discovery_unavailable,
                    ),
                    tint = if (entry.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(component.labelRes()), style = MaterialTheme.typography.bodyLarge)
                    if (!entry.available) {
                        Text(
                            stringResource(entry.reason.messageRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeasureCard(progress: SizeMeasureProgress?, measuredCount: Int, onMeasure: () -> Unit, onCancel: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.discovery_apps_measure_title),
        subtitle = stringResource(R.string.discovery_apps_measure_subtitle),
    ) {
        if (progress != null) {
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.discovery_apps_measure_progress, progress.done, progress.total),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.discovery_cancel)) }
            }
        } else {
            if (measuredCount > 0) {
                Text(
                    pluralStringResource(R.plurals.discovery_apps_measured, measuredCount, measuredCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onMeasure) {
                    Text(stringResource(if (measuredCount > 0) R.string.discovery_apps_measure_again else R.string.discovery_apps_measure))
                }
            }
        }
    }
}

@Composable
private fun AppWarningsCard(warnings: List<AppScanWarning>) {
    ExpandableCard(title = stringResource(R.string.discovery_warnings_title, warnings.size)) {
        warnings.groupBy { it.kind }.forEach { (kind, entries) ->
            val packages = entries.mapNotNull { it.packageName }.distinct()
            val subject = when {
                packages.isEmpty() -> ""
                packages.size <= MAX_NAMED_PACKAGES -> packages.joinToString(", ")
                else -> stringResource(
                    R.string.discovery_apps_and_more,
                    packages.take(MAX_NAMED_PACKAGES).joinToString(", "),
                    packages.size - MAX_NAMED_PACKAGES,
                )
            }
            // Messages without a %1$s placeholder ignore the extra argument.
            Text(stringResource(kind.messageRes, subject), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val MAX_NAMED_PACKAGES = 3

@Composable
private fun ListControls(
    query: AppListQuery,
    onSearch: (String) -> Unit,
    onToggleFilter: (AppListFilter) -> Unit,
    onSort: (AppSort) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query.search,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.discovery_apps_search)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (query.search.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearch("") }) {
                        Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.discovery_clear))
                    }
                }
            } else {
                null
            },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppListFilter.entries.forEach { filter ->
                FilterChip(
                    selected = filter in query.filters,
                    onClick = { onToggleFilter(filter) },
                    label = { Text(stringResource(filter.labelRes())) },
                )
            }
        }
        Text(stringResource(R.string.discovery_apps_sort), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AppSort.entries.forEachIndexed { index, sort ->
                SegmentedButton(
                    selected = query.sort == sort,
                    onClick = { onSort(sort) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = AppSort.entries.size),
                    label = { Text(stringResource(sort.labelRes())) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onRefresh, enabled = !refreshing) { Text(stringResource(R.string.discovery_apps_refresh)) }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    size: AppSizeInfo?,
    selectable: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    viewModel: AppsViewModel,
    locale: java.util.Locale,
) {
    var expanded by rememberSaveable(app.packageName) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectable) Checkbox(checked = selected, onCheckedChange = { onToggle() })
                AppIcon(app.packageName, viewModel)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val badges = app.flags.badges()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusBadge(stringResource(app.kind.labelRes), Tone.NEUTRAL)
                        if (!app.enabled) StatusBadge(stringResource(R.string.discovery_apps_disabled), Tone.NEUTRAL)
                        badges.forEach { badge -> StatusBadge(stringResource(badge.labelRes), badgeTone(badge)) }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        size?.let { sizeText(it.totalKnownBytes) } ?: stringResource(R.string.discovery_size_not_measured),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = stringResource(if (expanded) R.string.destinations_collapse else R.string.destinations_expand),
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HorizontalDivider()
                    AppDetails(app, locale)
                    HorizontalDivider()
                    SizeBreakdown(app, size)
                }
            }
        }
    }
}

private fun badgeTone(badge: AppFlagBadge): Tone = when (badge) {
    AppFlagBadge.ROOT_MANAGER, AppFlagBadge.CRITICAL_SYSTEM -> Tone.ERROR
    AppFlagBadge.SELF, AppFlagBadge.NO_ALLOW_BACKUP -> Tone.WARNING
    AppFlagBadge.APEX, AppFlagBadge.SPLIT, AppFlagBadge.OBB, AppFlagBadge.DEBUGGABLE -> Tone.INFO
}

@Composable
private fun AppIcon(packageName: String, viewModel: AppsViewModel) {
    val sizePx = with(LocalDensity.current) { ICON_SIZE.roundToPx() }
    val bitmap by produceState(initialValue = viewModel.cachedIcon(packageName), packageName, sizePx) {
        if (value == null) value = viewModel.loadIcon(packageName, sizePx)
    }
    Box(Modifier.size(ICON_SIZE), contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) {
            val image = remember(current) { current.asImageBitmap() }
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Outlined.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppDetails(app: InstalledApp, locale: java.util.Locale) {
    KeyValueRow(
        stringResource(R.string.discovery_apps_version),
        stringResource(R.string.discovery_apps_version_value, app.versionName ?: "—", app.versionCode),
    )
    KeyValueRow(stringResource(R.string.discovery_apps_target_sdk), app.targetSdk.toString())
    KeyValueRow(stringResource(R.string.discovery_apps_installed), formatDateTime(app.firstInstallTimeMillis, locale))
    KeyValueRow(stringResource(R.string.discovery_apps_updated), formatDateTime(app.lastUpdateTimeMillis, locale))
    KeyValueRow(
        stringResource(R.string.discovery_apps_installer),
        app.installSource.installerPackageName ?: stringResource(R.string.discovery_unknown),
    )
    if (app.isSplitBundle) {
        KeyValueRow(stringResource(R.string.discovery_apps_splits), app.splitNames.joinToString(", "))
    }
}

@Composable
private fun SizeBreakdown(app: InstalledApp, size: AppSizeInfo?) {
    Text(stringResource(R.string.discovery_apps_breakdown), style = MaterialTheme.typography.labelLarge)
    if (size == null) {
        Text(
            stringResource(R.string.discovery_apps_breakdown_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val notMeasurable = stringResource(R.string.discovery_size_not_measurable)
    @Composable
    fun row(label: Int, bytes: Long?) = KeyValueRow(stringResource(label), bytes?.let { sizeText(it) } ?: notMeasurable)

    row(R.string.discovery_apps_size_apk, size.apkBytes)
    if (app.isSplitBundle) row(R.string.discovery_apps_size_splits, size.splitBytes)
    when (size.source) {
        SizeSource.STATS_MANAGER -> {
            row(R.string.discovery_apps_size_data_aggregate, size.aggregateDataBytes)
            row(R.string.discovery_apps_size_cache_included, size.cacheBytes)
        }
        SizeSource.SHELL_DU -> {
            row(R.string.discovery_location_ce_data, size.internalDataBytes)
            row(R.string.discovery_apps_size_cache_included, size.cacheBytes)
            row(R.string.discovery_location_de_data, size.deviceProtectedBytes)
            row(R.string.discovery_location_external_data, size.externalDataBytes)
            row(R.string.discovery_location_media, size.mediaBytes)
            row(R.string.discovery_location_obb, size.obbBytes)
        }
        SizeSource.APK_ONLY -> Unit
    }
    KeyValueRow(stringResource(R.string.discovery_apps_size_total), sizeText(size.totalKnownBytes))
    Text(
        stringResource(size.source.labelRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (size.unreadable.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Text(
                stringResource(
                    R.string.discovery_apps_unreadable,
                    size.unreadable.sortedBy { it.ordinal }.map { stringResource(it.labelRes()) }.joinToString(", "),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
