package ca.devdcs.backup.ui.destinations

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.ftp.FtpBackend
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.ui.components.SectionCard
import ca.devdcs.backup.ui.mapping.label
import kotlinx.coroutines.launch

/**
 * Add / edit one destination. The add flow starts with a type picker; the form then shows only
 * the inputs that type uses, with protocol extras in an expandable "Advanced" card.
 */
@Composable
fun DestinationEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onOpenRcloneSetup: () -> Unit,
    onOpenRcloneConfig: () -> Unit,
    viewModel: DestinationEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noPickerMessage = stringResource(R.string.destinations_error_no_picker)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                DestinationEditorEvent.Saved -> onSaved()
                is DestinationEditorEvent.Message -> snackbarHostState.showSnackbar(context.getString(event.messageRes))
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshEnvironment() }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onTreePicked(uri)
    }
    val volumeTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) viewModel.onTreePicked(uri)
    }
    val pickTree: (String?) -> Unit = { volumeId ->
        val intent = volumeId?.takeIf { it.isNotBlank() }?.let { RemovableVolumes.openTreeIntent(context, it) }
        try {
            if (intent != null) volumeTreeLauncher.launch(intent) else treeLauncher.launch(null)
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbarHostState.showSnackbar(noPickerMessage) }
        }
    }

    val goBack: () -> Unit = { if (!viewModel.backToTypePicker()) onBack() }
    BackHandler(enabled = state.isNew && state.form != null) { viewModel.backToTypePicker() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNew) R.string.destinations_add_title else R.string.destinations_edit_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = goBack) {
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
        val form = state.form
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.notFound -> Box(Modifier.fillMaxSize().padding(innerPadding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.destinations_not_found), style = MaterialTheme.typography.bodyLarge)
            }
            form == null -> TypePicker(padding, onPick = viewModel::pickType, onOpenRcloneSetup = onOpenRcloneSetup)
            else -> EditorForm(
                state = state,
                form = form,
                padding = padding,
                viewModel = viewModel,
                onPickTree = pickTree,
                onOpenRcloneSetup = onOpenRcloneSetup,
                onOpenRcloneConfig = onOpenRcloneConfig,
            )
        }
    }
}

@Composable
private fun TypePicker(padding: PaddingValues, onPick: (DestinationType) -> Unit, onOpenRcloneSetup: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.destinations_pick_type),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(DestinationTypeSpec.selectableTypes, key = { it.storageKey }) { type ->
            Card(modifier = Modifier.fillMaxWidth().clickable { onPick(type) }) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(type.label(), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(type.descriptionRes()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            SectionCard(
                title = stringResource(R.string.destinations_more_providers_title),
                subtitle = stringResource(R.string.destinations_more_providers_text),
                icon = Icons.Outlined.Cloud,
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOpenRcloneSetup) { Text(stringResource(R.string.rclone_setup_title)) }
                }
            }
        }
    }
}

@Composable
private fun EditorForm(
    state: DestinationEditorUiState,
    form: DestinationForm,
    padding: PaddingValues,
    viewModel: DestinationEditorViewModel,
    onPickTree: (String?) -> Unit,
    onOpenRcloneSetup: () -> Unit,
    onOpenRcloneConfig: () -> Unit,
) {
    val spec = form.spec
    val type = form.type
    val errors = state.visibleErrors
    var confirmForget by rememberSaveable { mutableStateOf(false) }

    @Composable
    fun err(field: FormField): String? = errors[field]?.let { stringResource(it.messageRes()) }

    @Composable
    fun label(field: FormField): String = stringResource(field.labelRes(type))

    @Composable
    fun hint(field: FormField): String? = field.hintRes(type)?.let { stringResource(it) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = type.label(), subtitle = stringResource(type.descriptionRes()), icon = type.icon()) {
                FormTextField(
                    value = form.name,
                    onValueChange = { v -> viewModel.edit(FormField.NAME) { it.copy(name = v) } },
                    label = label(FormField.NAME),
                    error = err(FormField.NAME),
                )
            }
        }

        if (spec.usesTree) {
            item {
                SectionCard(title = stringResource(R.string.destinations_section_folder), icon = Icons.Outlined.Folder) {
                    if (spec.usesUsbVolume) {
                        UsbVolumePicker(state, form, viewModel, error = err(FormField.USB_VOLUME))
                    }
                    val treeText = if (form.treeUri.isBlank()) {
                        stringResource(R.string.destinations_no_folder)
                    } else {
                        stringResource(R.string.destinations_folder_selected, RemovableVolumes.displayPath(form.treeUri))
                    }
                    Text(treeText, style = MaterialTheme.typography.bodyMedium)
                    err(FormField.TREE_URI)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = { onPickTree(if (spec.usesUsbVolume) form.usbVolumeId else null) }) {
                        Text(stringResource(R.string.destinations_choose_folder))
                    }
                }
            }
        }

        if (spec.usesRemoteName) {
            item {
                SectionCard(title = stringResource(R.string.destinations_section_rclone), icon = Icons.Outlined.Cloud) {
                    val remotes = state.rcloneRemotes
                    if (remotes.isNullOrEmpty()) {
                        Text(
                            stringResource(if (remotes == null) R.string.destinations_rclone_unreadable else R.string.destinations_rclone_no_remotes),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FormTextField(
                            value = form.remoteName,
                            onValueChange = { v -> viewModel.edit(FormField.REMOTE_NAME) { it.copy(remoteName = v) } },
                            label = label(FormField.REMOTE_NAME),
                            error = err(FormField.REMOTE_NAME),
                        )
                    } else {
                        ChoiceChips(
                            label = label(FormField.REMOTE_NAME),
                            options = remotes.map { it to it },
                            selected = form.remoteName,
                            onSelect = { v -> viewModel.edit(FormField.REMOTE_NAME) { it.copy(remoteName = v) } },
                            error = err(FormField.REMOTE_NAME),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenRcloneSetup) { Text(stringResource(R.string.destinations_rclone_new_remote)) }
                        TextButton(onClick = onOpenRcloneConfig) { Text(stringResource(R.string.rclone_editor_title)) }
                    }
                }
            }
        }

        if (spec.showsHost || spec.showsPort || spec.showsBasePath || spec.showsUsername) {
            item {
                SectionCard(title = stringResource(R.string.destinations_section_location)) {
                    if (spec.showsHost) {
                        FormTextField(
                            value = form.host,
                            onValueChange = { v -> viewModel.edit(FormField.HOST) { it.copy(host = v) } },
                            label = label(FormField.HOST),
                            error = err(FormField.HOST),
                            hint = hint(FormField.HOST),
                            keyboardType = KeyboardType.Uri,
                        )
                    }
                    if (spec.showsPort) {
                        val defaultPort = if (form.option(FormField.FTPS_MODE) == DestinationOptions.FTPS_MODE_IMPLICIT) {
                            FtpBackend.DEFAULT_IMPLICIT_PORT
                        } else {
                            spec.defaultPort
                        }
                        FormTextField(
                            value = form.port,
                            onValueChange = { v -> viewModel.edit(FormField.PORT) { it.copy(port = v.filter(Char::isDigit).take(5)) } },
                            label = label(FormField.PORT),
                            error = err(FormField.PORT),
                            placeholder = defaultPort?.toString(),
                            hint = defaultPort?.let { stringResource(R.string.destinations_hint_port_default, it) },
                            keyboardType = KeyboardType.Number,
                        )
                    }
                    if (spec.showsUsername) {
                        FormTextField(
                            value = form.username,
                            onValueChange = { v -> viewModel.edit(FormField.USERNAME) { it.copy(username = v) } },
                            label = label(FormField.USERNAME),
                            error = err(FormField.USERNAME),
                            hint = if (spec.usernameInSecret && form.canKeepStoredSecret) stringResource(R.string.destinations_secret_stored_hint) else null,
                            keyboardType = if (type == DestinationType.MEGA) KeyboardType.Email else KeyboardType.Text,
                        )
                    }
                    if (spec.showsBasePath) {
                        FormTextField(
                            value = form.basePath,
                            onValueChange = { v -> viewModel.edit(FormField.BASE_PATH) { it.copy(basePath = v) } },
                            label = label(FormField.BASE_PATH),
                            error = err(FormField.BASE_PATH),
                            hint = hint(FormField.BASE_PATH),
                            keyboardType = KeyboardType.Uri,
                        )
                    }
                }
            }
        }

        if (spec.authMethods != listOf(AuthMethod.NONE)) {
            item { CredentialsCard(form, viewModel, errors = { err(it) }, label = { label(it) }, hint = { hint(it) }) }
        }

        if (spec.advanced.isNotEmpty()) {
            item {
                ExpandableCard(
                    title = stringResource(R.string.destinations_section_advanced),
                    subtitle = stringResource(R.string.destinations_section_advanced_hint),
                    initiallyExpanded = spec.advanced.any { errors.containsKey(it) },
                ) {
                    spec.advanced.forEach { field -> AdvancedField(field, form, viewModel, err(field), label(field), hint(field)) }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.testResult?.let { result ->
                        TestResultLine(result)
                        val sshType = type == DestinationType.SFTP || type == DestinationType.SSH
                        if (!result.ok && result.failure == StorageException.Reason.HOST_KEY && sshType) {
                            Text(stringResource(R.string.destinations_host_key_explain), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { confirmForget = true }) {
                                Icon(Icons.Outlined.Key, contentDescription = null)
                                Text(stringResource(R.string.destinations_host_key_forget), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.busy) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        OutlinedButton(onClick = viewModel::test, enabled = !state.busy) {
                            Text(stringResource(R.string.destinations_test_connection))
                        }
                        Button(onClick = viewModel::save, enabled = !state.busy) {
                            Text(stringResource(R.string.destinations_save))
                        }
                    }
                }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.destinations_host_key_forget)) },
            text = { Text(stringResource(R.string.destinations_host_key_confirm, form.host.trim())) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    viewModel.forgetHostKey()
                }) { Text(stringResource(R.string.destinations_host_key_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.destinations_cancel)) }
            },
        )
    }
}

@Composable
private fun UsbVolumePicker(
    state: DestinationEditorUiState,
    form: DestinationForm,
    viewModel: DestinationEditorViewModel,
    error: String?,
) {
    val volumes = state.removableVolumes
    val options = volumes.map { volume ->
        val text = if (volume.writable) volume.description else stringResource(R.string.destinations_usb_read_only, volume.description)
        volume.id to text
    }.toMutableList()
    // A saved volume that is not plugged in right now stays visible (and selected).
    if (form.usbVolumeId.isNotBlank() && volumes.none { it.id.equals(form.usbVolumeId, ignoreCase = true) }) {
        options += form.usbVolumeId to stringResource(R.string.destinations_usb_not_connected, form.usbVolumeId)
    }
    if (options.isEmpty()) {
        Text(stringResource(R.string.destinations_usb_none), style = MaterialTheme.typography.bodyMedium)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    } else {
        ChoiceChips(
            label = stringResource(R.string.destinations_field_usb_volume),
            options = options,
            selected = form.usbVolumeId,
            onSelect = { v -> viewModel.edit(FormField.USB_VOLUME) { it.copy(usbVolumeId = v) } },
            error = error,
        )
    }
    TextButton(onClick = viewModel::refreshEnvironment) { Text(stringResource(R.string.destinations_usb_refresh)) }
}

@Composable
private fun CredentialsCard(
    form: DestinationForm,
    viewModel: DestinationEditorViewModel,
    errors: @Composable (FormField) -> String?,
    label: @Composable (FormField) -> String,
    hint: @Composable (FormField) -> String?,
) {
    val spec = form.spec
    val storedHint = if (form.canKeepStoredSecret) stringResource(R.string.destinations_secret_stored_hint) else null
    SectionCard(title = stringResource(R.string.destinations_section_credentials), icon = Icons.Outlined.Key) {
        if (spec.authMethods.size > 1) {
            ChoiceChips(
                label = stringResource(R.string.destinations_field_auth),
                options = spec.authMethods.map { it.name to stringResource(it.labelRes()) },
                selected = form.authMethod.name,
                onSelect = { v -> viewModel.edit(FormField.PASSWORD) { it.copy(authMethod = AuthMethod.valueOf(v)) } },
            )
        }
        when (form.authMethod) {
            AuthMethod.NONE -> Text(
                stringResource(R.string.destinations_auth_none_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AuthMethod.PASSWORD -> FormTextField(
                value = form.password,
                onValueChange = { v -> viewModel.edit(FormField.PASSWORD) { it.copy(password = v) } },
                label = label(FormField.PASSWORD),
                error = errors(FormField.PASSWORD),
                hint = storedHint,
                secret = true,
            )
            AuthMethod.PRIVATE_KEY -> {
                FormTextField(
                    value = form.privateKey,
                    onValueChange = { v -> viewModel.edit(FormField.PRIVATE_KEY) { it.copy(privateKey = v) } },
                    label = label(FormField.PRIVATE_KEY),
                    error = errors(FormField.PRIVATE_KEY),
                    hint = storedHint ?: hint(FormField.PRIVATE_KEY),
                    secret = true,
                    singleLine = false,
                    minLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                FormTextField(
                    value = form.passphrase,
                    onValueChange = { v -> viewModel.edit(FormField.PASSPHRASE) { it.copy(passphrase = v) } },
                    label = label(FormField.PASSPHRASE),
                    error = errors(FormField.PASSPHRASE),
                    secret = true,
                )
            }
        }
    }
}

@Composable
private fun AdvancedField(
    field: FormField,
    form: DestinationForm,
    viewModel: DestinationEditorViewModel,
    error: String?,
    label: String,
    hint: String?,
) {
    when (field) {
        FormField.FTPS_MODE -> ChoiceChips(
            label = label,
            options = listOf(
                DestinationOptions.FTPS_MODE_EXPLICIT to stringResource(R.string.destinations_ftps_explicit),
                DestinationOptions.FTPS_MODE_IMPLICIT to stringResource(R.string.destinations_ftps_implicit),
            ),
            selected = form.option(field).ifEmpty { DestinationOptions.FTPS_MODE_EXPLICIT },
            onSelect = { v -> viewModel.edit(field) { it.withOption(field, v) } },
            error = error,
        )
        FormField.WEBDAV_SCHEME -> ChoiceChips(
            label = label,
            options = listOf(
                "https" to stringResource(R.string.destinations_scheme_https),
                "http" to stringResource(R.string.destinations_scheme_http),
            ),
            selected = form.option(field).ifEmpty { "https" },
            onSelect = { v -> viewModel.edit(field) { it.withOption(field, v) } },
            error = error,
        )
        FormField.SMB_ENCRYPT, FormField.SMB_GUEST -> SwitchRow(
            label = label,
            hint = hint,
            checked = form.switch(field),
            onCheckedChange = { on -> viewModel.edit(field) { it.withSwitch(field, on) } },
        )
        FormField.FTP_CONNECT_TIMEOUT, FormField.FTP_DATA_TIMEOUT,
        FormField.WEBDAV_CONNECT_TIMEOUT, FormField.WEBDAV_IO_TIMEOUT -> FormTextField(
            value = form.option(field),
            onValueChange = { v -> viewModel.edit(field) { it.withOption(field, v.filter(Char::isDigit).take(5)) } },
            label = label,
            error = error,
            hint = hint,
            keyboardType = KeyboardType.Number,
        )
        else -> FormTextField(
            value = form.option(field),
            onValueChange = { v -> viewModel.edit(field) { it.withOption(field, v) } },
            label = label,
            error = error,
            hint = hint,
            keyboardType = if (field == FormField.S3_ENDPOINT) KeyboardType.Uri else KeyboardType.Text,
            textStyle = if (field == FormField.TLS_PIN) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else null,
        )
    }
}
