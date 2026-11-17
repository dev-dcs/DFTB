package ca.devdcs.backup.ui.rclone

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.R
import ca.devdcs.backup.core.storage.rclone.GuideField
import ca.devdcs.backup.core.storage.rclone.GuideFieldKind
import ca.devdcs.backup.core.storage.rclone.OAuthTokenValidator
import ca.devdcs.backup.core.storage.rclone.ProviderGuide
import ca.devdcs.backup.core.storage.rclone.ProviderGuides
import ca.devdcs.backup.core.storage.rclone.RcloneConfigBuilder
import ca.devdcs.backup.core.storage.rclone.RcloneRemote
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.destinations.ChoiceChips
import ca.devdcs.backup.ui.destinations.FormError
import ca.devdcs.backup.ui.destinations.FormField
import ca.devdcs.backup.ui.destinations.FormTextField
import ca.devdcs.backup.ui.destinations.SecureWindowEffect
import ca.devdcs.backup.ui.destinations.messageRes
import kotlinx.coroutines.launch

/**
 * Guided rclone remote setup: pick a provider, follow its numbered steps, fill the fields it
 * needs (secrets masked), paste an OAuth token where required, and save the remote into
 * DFTB's rclone.conf; then optionally create a destination that uses it.
 */
@Composable
fun RcloneSetupScreen(
    onBack: () -> Unit,
    onOpenConfigEditor: () -> Unit,
    onDestinationCreated: () -> Unit,
    viewModel: RcloneSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var destinationFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is RcloneSetupEvent.Message -> snackbarHostState.showSnackbar(context.getString(event.messageRes))
                RcloneSetupEvent.DestinationCreated -> onDestinationCreated()
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reload() }
    BackHandler(enabled = state.guide != null) { viewModel.closeGuide() }

    // The guide form can show pasted tokens and revealed passwords.
    if (state.guide != null) SecureWindowEffect()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.guide?.let { stringResource(it.titleRes) } ?: stringResource(R.string.rclone_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.closeGuide()) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.destinations_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val padding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
            end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
            top = innerPadding.calculateTopPadding() + 8.dp,
            bottom = innerPadding.calculateBottomPadding() + 24.dp,
        )
        val guide = state.guide
        if (guide == null) {
            GuideList(
                state = state,
                padding = padding,
                onSelect = viewModel::selectGuide,
                onOpenConfigEditor = onOpenConfigEditor,
                onUseRemote = { destinationFor = it },
            )
        } else {
            GuideForm(state = state, guide = guide, padding = padding, viewModel = viewModel)
        }
    }

    state.savedRemote?.let { remote ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSaved,
            title = { Text(stringResource(R.string.rclone_setup_saved)) },
            text = { Text(stringResource(R.string.rclone_setup_offer_destination, remote)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissSaved()
                    destinationFor = remote
                }) { Text(stringResource(R.string.rclone_setup_create_destination)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissSaved) { Text(stringResource(R.string.rclone_setup_not_now)) }
            },
        )
    }

    destinationFor?.let { remote ->
        CreateDestinationDialog(
            remoteName = remote,
            busy = state.creatingDestination,
            validate = { name, path -> viewModel.destinationErrors(remote, name, path) },
            onDismiss = { destinationFor = null },
            onCreate = { name, path ->
                destinationFor = null
                viewModel.createDestination(remote, name, path)
            },
        )
    }
}

@Composable
private fun ToolStatusCard(state: RcloneSetupUiState) {
    if (!state.toolChecked) return
    val missing = state.toolMissing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (missing) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(if (missing) Icons.Outlined.CloudOff else Icons.Outlined.Cloud, contentDescription = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (missing) {
                    Text(stringResource(R.string.storage_reason_tool_missing), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.rclone_setup_tool_missing), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.rclone_setup_tool_ok, state.toolVersion.orEmpty()), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun GuideList(
    state: RcloneSetupUiState,
    padding: PaddingValues,
    onSelect: (ProviderGuide) -> Unit,
    onOpenConfigEditor: () -> Unit,
    onUseRemote: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ToolStatusCard(state) }
        item {
            Text(
                stringResource(R.string.rclone_setup_pick_provider),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(ProviderGuides.all, key = { it.id }) { guide ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onSelect(guide) }) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(guide.titleRes), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(guide.summaryRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SectionCard(
                title = stringResource(R.string.rclone_setup_existing_title),
                subtitle = stringResource(R.string.rclone_setup_existing_hint),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                val remotes = state.remotes
                when {
                    remotes == null -> Text(stringResource(R.string.destinations_rclone_unreadable), style = MaterialTheme.typography.bodyMedium)
                    remotes.isEmpty() -> Text(stringResource(R.string.destinations_rclone_no_remotes), style = MaterialTheme.typography.bodyMedium)
                    else -> remotes.forEach { remote -> RemoteRow(remote, onUse = { onUseRemote(remote.name) }) }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOpenConfigEditor) { Text(stringResource(R.string.rclone_editor_title)) }
                }
            }
        }
    }
}

@Composable
private fun RemoteRow(remote: RcloneRemote, onUse: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(remote.name, style = MaterialTheme.typography.bodyLarge)
            Text(remote.type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onUse) { Text(stringResource(R.string.rclone_setup_use_remote)) }
    }
}

@Composable
private fun GuideForm(
    state: RcloneSetupUiState,
    guide: ProviderGuide,
    padding: PaddingValues,
    viewModel: RcloneSetupViewModel,
) {
    @Composable
    fun errorFor(key: String): String? = state.visibleErrors(key).firstOrNull()?.let { stringResource(it.messageRes()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ToolStatusCard(state) }
        item {
            SectionCard(title = stringResource(R.string.rclone_setup_steps_title), subtitle = stringResource(guide.summaryRes)) {
                guide.stepsRes.forEachIndexed { index, res ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.rclone_setup_step_number, index + 1),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(stringResource(res, guide.rcloneType), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
                guide.authorizeCommand?.let { command -> AuthorizeCommand(command) }
            }
        }
        item {
            SectionCard(title = stringResource(R.string.rclone_setup_fields_title)) {
                FormTextField(
                    value = state.remoteName,
                    onValueChange = viewModel::setRemoteName,
                    label = stringResource(R.string.destinations_field_remote_name),
                    error = errorFor(RcloneConfigBuilder.REMOTE_NAME_KEY),
                    hint = if (state.remoteNameTaken) stringResource(R.string.rclone_setup_name_taken) else stringResource(R.string.rclone_setup_name_hint),
                )
                guide.fields.forEach { field ->
                    GuideFieldInput(
                        field = field,
                        value = state.values[field.key].orEmpty(),
                        error = errorFor(field.key),
                        onValueChange = { viewModel.setValue(field.key, it) },
                    )
                }
            }
        }
        if (state.warnings.isNotEmpty()) {
            item {
                SectionCard(title = stringResource(R.string.rclone_setup_warnings_title), icon = Icons.Outlined.Warning) {
                    state.warnings.forEach { Text(stringResource(it.messageRes()), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.saving) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Button(onClick = viewModel::save, enabled = !state.saving) { Text(stringResource(R.string.rclone_setup_save)) }
            }
        }
    }
}

@Composable
private fun AuthorizeCommand(command: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.rclone_setup_authorize_label)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(clipLabel, style = MaterialTheme.typography.labelLarge)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(command, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                }
                IconButton(onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, command))) }
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.rclone_setup_copy))
                }
            }
        }
    }
}

@Composable
private fun GuideFieldInput(field: GuideField, value: String, error: String?, onValueChange: (String) -> Unit) {
    val label = stringResource(field.labelRes).let { if (field.required) it else stringResource(R.string.rclone_setup_optional, it) }
    val hint = field.hintRes?.let { stringResource(it) }
    when (field.kind) {
        GuideFieldKind.CHOICE -> ChoiceChips(
            label = label,
            options = field.choices.map { it to it },
            selected = value.ifEmpty { field.defaultValue.orEmpty() },
            onSelect = onValueChange,
            error = error,
        )
        GuideFieldKind.OAUTH_TOKEN -> {
            FormTextField(
                value = value,
                onValueChange = onValueChange,
                label = label,
                error = error,
                hint = hint,
                singleLine = false,
                minLines = 3,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            if (value.isNotBlank()) TokenStatus(value)
        }
        GuideFieldKind.MULTILINE_SECRET -> FormTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            error = error,
            hint = hint,
            secret = true,
            singleLine = false,
            minLines = 4,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        else -> FormTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            error = error,
            hint = hint,
            placeholder = field.defaultValue,
            secret = field.secret || field.kind == GuideFieldKind.PASSWORD,
            keyboardType = when (field.kind) {
                GuideFieldKind.NUMBER -> KeyboardType.Number
                GuideFieldKind.URL -> KeyboardType.Uri
                GuideFieldKind.EMAIL -> KeyboardType.Email
                else -> KeyboardType.Text
            },
        )
    }
}

/** Live verdict on a pasted `rclone authorize` token. */
@Composable
private fun TokenStatus(text: String) {
    val result = remember(text) { OAuthTokenValidator.validate(text) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        when (result) {
            is OAuthTokenValidator.Result.Valid -> {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(stringResource(R.string.rclone_token_valid), style = MaterialTheme.typography.bodyMedium)
                    result.expiry?.let { Text(stringResource(R.string.rclone_token_expiry, it), style = MaterialTheme.typography.bodySmall) }
                    if (!result.hasRefreshToken) {
                        Text(
                            stringResource(R.string.rclone_setup_warning_no_refresh),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            is OAuthTokenValidator.Result.Invalid -> {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(stringResource(result.problem.messageRes()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CreateDestinationDialog(
    remoteName: String,
    busy: Boolean,
    validate: (String, String) -> Map<FormField, FormError>,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(remoteName) }
    var path by rememberSaveable { mutableStateOf("") }
    val errors = validate(name, path)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rclone_setup_create_destination)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rclone_setup_destination_for, remoteName), style = MaterialTheme.typography.bodyMedium)
                FormTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.destinations_field_name),
                    error = errors[FormField.NAME]?.let { stringResource(it.messageRes()) },
                )
                FormTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = stringResource(R.string.destinations_field_base_path),
                    error = errors[FormField.BASE_PATH]?.let { stringResource(it.messageRes()) }
                        ?: errors[FormField.REMOTE_NAME]?.let { stringResource(it.messageRes()) },
                    hint = stringResource(R.string.destinations_hint_base_path),
                    keyboardType = KeyboardType.Uri,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, path) }, enabled = errors.isEmpty() && !busy) {
                Text(stringResource(R.string.rclone_setup_create))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.destinations_cancel)) }
        },
    )
}
