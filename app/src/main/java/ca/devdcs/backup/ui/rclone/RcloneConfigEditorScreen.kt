package ca.devdcs.backup.ui.rclone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.storage.rclone.RcloneIniIssue
import ca.devdcs.backup.ui.destinations.SecureWindowEffect

/**
 * Plain-text editor for DFTB's whole rclone.conf, for providers and options the guides do not
 * cover. Secrets are shown unmasked (it is a raw file), so the window is FLAG_SECURE while this
 * screen is composed, and leaving with unsaved changes asks first.
 */
@Composable
fun RcloneConfigEditorScreen(
    onBack: () -> Unit,
    viewModel: RcloneConfigEditorViewModel = hiltViewModel(),
) {
    SecureWindowEffect()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is RcloneConfigEditorEvent.Message -> snackbarHostState.showSnackbar(context.getString(event.messageRes))
            }
        }
    }

    val requestBack: () -> Unit = { if (state.dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rclone_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.destinations_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.loadFailed -> Box(Modifier.fillMaxSize().padding(innerPadding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.rclone_editor_load_failed), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = viewModel::retryLoad) { Text(stringResource(R.string.rclone_editor_retry)) }
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                    end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { SecretsWarning() }
                item {
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = viewModel::setText,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.rclone_editor_label)) },
                        placeholder = { Text(stringResource(R.string.rclone_editor_placeholder)) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 12,
                        enabled = !state.saving && !state.restoring,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                        ),
                        supportingText = if (state.dirty) {
                            { Text(stringResource(R.string.rclone_editor_unsaved)) }
                        } else {
                            null
                        },
                    )
                }
                item {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (state.saving || state.restoring) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp).align(Alignment.CenterVertically),
                                strokeWidth = 2.dp,
                            )
                        }
                        TextButton(onClick = { confirmRestore = true }, enabled = !state.busy) {
                            Text(stringResource(R.string.rclone_editor_restore))
                        }
                        OutlinedButton(onClick = viewModel::validate, enabled = !state.busy) {
                            Text(stringResource(R.string.rclone_editor_validate))
                        }
                        Button(onClick = viewModel::save, enabled = !state.busy && state.dirty) {
                            Text(stringResource(R.string.rclone_editor_save))
                        }
                    }
                }
                state.issues?.let { issues -> item { IssuesCard(issues) } }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.rclone_editor_discard_title)) },
            text = { Text(stringResource(R.string.rclone_editor_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text(stringResource(R.string.rclone_editor_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.rclone_editor_keep_editing)) }
            },
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.rclone_editor_restore)) },
            text = { Text(stringResource(R.string.rclone_editor_restore_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    viewModel.restorePrevious()
                }) { Text(stringResource(R.string.rclone_editor_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.destinations_cancel)) }
            },
        )
    }
}

@Composable
private fun SecretsWarning() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Warning, contentDescription = null)
            Text(stringResource(R.string.rclone_editor_secrets_warning), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun IssuesCard(issues: List<RcloneIniIssue>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (issues.isEmpty()) {
                Text(stringResource(R.string.rclone_editor_no_issues), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val errors = issues.count { it.severity == RcloneIniIssue.Severity.ERROR }
            val warnings = issues.size - errors
            Text(
                stringResource(R.string.rclone_editor_issue_summary, errors, warnings),
                style = MaterialTheme.typography.titleSmall,
            )
            issues.sortedWith(compareBy<RcloneIniIssue>({ it.line ?: Int.MAX_VALUE }, { it.severity })).forEach { issue ->
                val isError = issue.severity == RcloneIniIssue.Severity.ERROR
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.Warning,
                        contentDescription = stringResource(if (isError) R.string.rclone_editor_error else R.string.rclone_editor_warning),
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    )
                    val message = stringResource(issue.code.messageRes())
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            issue.line?.let { stringResource(R.string.rclone_editor_issue_line, it, message) } ?: message,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            issue.message,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
