package ca.devdcs.backup.ui.destinations

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.storage.ConnectionTestResult
import ca.devdcs.backup.core.storage.labelRes
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.ui.mapping.label

/**
 * Destinations manager: every configured destination with its type, last verification and cached
 * capacity, plus test / edit / delete, and entry points to add one or set up rclone remotes.
 *
 * @param onBack up navigation, or null when the screen is a top-level section (no up arrow).
 */
@Composable
fun DestinationsScreen(
    onBack: (() -> Unit)?,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenRcloneSetup: () -> Unit,
    onOpenRcloneConfig: () -> Unit,
    viewModel: DestinationsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<Destination?>(null) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val appStorageName = stringResource(R.string.onboarding_destination_app_storage_name)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is DestinationsEvent.Deleted -> context.getString(
                    R.string.destinations_deleted,
                    if (event.name == Destination.APP_STORAGE_NAME_MARKER) appStorageName else event.name,
                )
                DestinationsEvent.DeleteFailed -> context.getString(R.string.destinations_delete_failed)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.destinations_title)) },
                navigationIcon = {
                    // Null when shown as a top-level tab: the shell's bar/rail is the way out.
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.destinations_back))
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.destinations_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rclone_setup_title)) },
                                onClick = {
                                    menuOpen = false
                                    onOpenRcloneSetup()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rclone_editor_title)) },
                                onClick = {
                                    menuOpen = false
                                    onOpenRcloneConfig()
                                },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.destinations_add)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val destinations = state.destinations
        when {
            destinations == null -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            destinations.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.destinations_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                    end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(destinations, key = { it.id }) { destination ->
                    DestinationRow(
                        destination = destination,
                        testing = destination.id in state.testing,
                        deleting = destination.id in state.deleting,
                        result = state.results[destination.id],
                        onTest = { viewModel.test(destination) },
                        onEdit = { onEdit(destination.id) },
                        onDelete = { pendingDelete = destination },
                    )
                }
            }
        }
    }

    pendingDelete?.let { destination ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.destinations_delete_title)) },
            text = { Text(stringResource(R.string.destinations_delete_message, destination.displayName())) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(destination)
                }) { Text(stringResource(R.string.destinations_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.destinations_cancel)) }
            },
        )
    }
}

@Composable
private fun DestinationRow(
    destination: Destination,
    testing: Boolean,
    deleting: Boolean,
    result: ConnectionTestResult?,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(destination.type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(destination.displayName(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        destination.type.label(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val verifiedAt = destination.lastVerifiedAt
            Text(
                if (verifiedAt == null) {
                    stringResource(R.string.destinations_never_verified)
                } else {
                    stringResource(
                        R.string.destinations_last_verified,
                        DateUtils.getRelativeTimeSpanString(verifiedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val used = destination.usedFraction
            val free = destination.lastKnownFreeBytes
            val total = destination.lastKnownTotalBytes
            if (used != null && free != null && total != null) {
                LinearProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(
                        R.string.destinations_space,
                        Formatter.formatShortFileSize(context, free),
                        Formatter.formatShortFileSize(context, total),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (free != null) {
                Text(
                    stringResource(R.string.destinations_space_free_only, Formatter.formatShortFileSize(context, free)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (result != null) TestResultLine(result)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (testing || deleting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                TextButton(onClick = onTest, enabled = !testing && !deleting) {
                    Text(stringResource(R.string.destinations_test))
                }
                TextButton(onClick = onEdit, enabled = !deleting) {
                    Text(stringResource(R.string.destinations_edit))
                }
                TextButton(onClick = onDelete, enabled = !deleting) {
                    Text(stringResource(R.string.destinations_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Outcome of a connection test: latency and capacity, or the localized failure reason. */
@Composable
fun TestResultLine(result: ConnectionTestResult, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (result.ok) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.storage_test_ok, result.latencyMillis.toInt()), style = MaterialTheme.typography.bodyMedium)
                val free = result.space?.freeBytes
                if (free != null) {
                    Text(
                        stringResource(R.string.destinations_space_free_only, Formatter.formatShortFileSize(context, free)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.storage_test_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                result.failure?.let { reason ->
                    Text(stringResource(reason.labelRes()), style = MaterialTheme.typography.bodySmall)
                }
                result.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
