package ca.devdcs.backup.ui.restore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.ui.discovery.NoticeCard
import ca.devdcs.backup.ui.discovery.StatusBadge
import ca.devdcs.backup.ui.discovery.Tone
import ca.devdcs.backup.ui.discovery.currentLocale
import ca.devdcs.backup.ui.discovery.formatDateTime
import ca.devdcs.backup.ui.discovery.sizeText

/**
 * Restore browser (top-level, inside the shell's scaffold, so no up button): pick a destination,
 * then a backup on it (newest first, with profile, date, size and encrypted / partial badges).
 * Pull to refresh re-reads the destination. Links to the TWRP import and to adding a destination.
 */
@Composable
fun RestoreHomeScreen(
    onOpenBackup: (destinationId: String, jobDir: String) -> Unit,
    onOpenTwrpImport: (destinationId: String?) -> Unit,
    onAddDestination: () -> Unit,
    viewModel: RestoreHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val columns = when (restoreWidthClass()) {
        WindowWidthSizeClass.Expanded -> 3
        WindowWidthSizeClass.Medium -> 2
        else -> 1
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.restore_ui_home_title)) },
                actions = {
                    IconButton(onClick = { onOpenTwrpImport(state.selectedId) }) {
                        Icon(Icons.Outlined.Unarchive, contentDescription = stringResource(R.string.restore_ui_twrp_open))
                    }
                    IconButton(onClick = viewModel::refresh, enabled = state.selectedId != null && !state.listing.loading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.restore_ui_refresh))
                    }
                },
            )
        },
    ) { innerPadding ->
        val destinations = state.destinations
        when {
            destinations == null -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            destinations.isEmpty() -> Box(Modifier.fillMaxSize().padding(innerPadding).padding(24.dp), contentAlignment = Alignment.Center) {
                NoticeCard(
                    title = stringResource(R.string.restore_ui_no_destinations_title),
                    body = stringResource(R.string.restore_ui_no_destinations_body),
                    tone = Tone.INFO,
                    icon = Icons.Outlined.SettingsBackupRestore,
                    actions = { Button(onClick = onAddDestination) { Text(stringResource(R.string.restore_ui_add_destination)) } },
                )
            }
            else -> PullToRefreshBox(
                isRefreshing = state.listing.loading && state.listing.rows != null,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            ) {
                BackupGrid(
                    state = state,
                    columns = columns,
                    onSelectDestination = viewModel::select,
                    onRetry = viewModel::refresh,
                    onOpenBackup = { row -> state.selectedId?.let { onOpenBackup(it, row.jobDir) } },
                    onOpenTwrpImport = { onOpenTwrpImport(state.selectedId) },
                )
            }
        }
    }
}

@Composable
private fun BackupGrid(
    state: RestoreHomeUiState,
    columns: Int,
    onSelectDestination: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenBackup: (BackupRow) -> Unit,
    onOpenTwrpImport: () -> Unit,
) {
    val listing = state.listing
    val rows = listing.rows
    val full = GridItemSpan(columns)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "destinations", span = { full }) {
            DestinationChips(destinations = state.destinations.orEmpty(), selectedId = state.selectedId, onSelect = onSelectDestination)
        }
        listing.failure?.let { failure ->
            item(key = "failure", span = { full }) {
                NoticeCard(
                    title = stringResource(R.string.restore_ui_list_failed),
                    body = failure.text(),
                    tone = Tone.ERROR,
                    icon = Icons.Outlined.ErrorOutline,
                    actions = { TextButton(onClick = onRetry) { Text(stringResource(R.string.restore_ui_retry)) } },
                )
            }
        }
        when {
            rows == null && listing.loading -> item(key = "loading", span = { full }) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            rows != null && rows.isEmpty() -> item(key = "empty", span = { full }) {
                NoticeCard(
                    title = stringResource(R.string.restore_ui_no_backups_title),
                    body = stringResource(R.string.restore_ui_no_backups_body),
                    tone = Tone.NEUTRAL,
                    icon = Icons.Outlined.Inventory2,
                )
            }
            rows != null -> items(rows, key = { it.jobDir }) { row -> BackupRowCard(row, onClick = { onOpenBackup(row) }) }
        }
        item(key = "twrp", span = { full }) {
            OutlinedButton(onClick = onOpenTwrpImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Unarchive, contentDescription = null)
                Text(stringResource(R.string.restore_ui_twrp_link), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun BackupRowCard(row: BackupRow, onClick: () -> Unit) {
    val locale = currentLocale()
    val encryptedLabel = stringResource(R.string.restore_ui_badge_encrypted)
    val lockedLabel = stringResource(R.string.restore_ui_badge_locked)
    val partialLabel = stringResource(R.string.restore_ui_badge_partial)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (row.manifestReadable) Icons.Outlined.Inventory2 else Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.profileName ?: row.folderName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        row.createdAtMillis?.let { formatDateTime(it, locale) } ?: stringResource(R.string.restore_ui_date_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                row.totalBytes?.let { Text(sizeText(it), style = MaterialTheme.typography.labelLarge) }
            }
            if (row.profileName != null) {
                Text(row.folderName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            val badges = buildList {
                if (row.encrypted) add(encryptedLabel to Tone.INFO)
                if (!row.manifestReadable) add(lockedLabel to Tone.NEUTRAL)
                if (row.partial == true) add(partialLabel to Tone.WARNING)
            }
            if (badges.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    badges.forEach { (text, tone) -> StatusBadge(text, tone) }
                }
            }
        }
    }
}
