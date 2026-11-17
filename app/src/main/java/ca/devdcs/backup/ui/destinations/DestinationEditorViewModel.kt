package ca.devdcs.backup.ui.destinations

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import ca.devdcs.backup.R
import ca.devdcs.backup.core.locale.LocaleManager
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.ConnectionTestResult
import ca.devdcs.backup.core.storage.ConnectionTester
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.rclone.RcloneConfigStore
import ca.devdcs.backup.core.storage.ssh.SshKnownHosts
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationConfig
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.domain.security.VaultException
import ca.devdcs.backup.ui.navigation.DftbDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * Editor state. [form] null means the "pick a type" step of the add flow. [visibleErrors] holds
 * only errors for fields the user touched (all of them after a save/test attempt), so a fresh
 * form is not covered in red.
 */
data class DestinationEditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val notFound: Boolean = false,
    val form: DestinationForm? = null,
    val errors: Map<FormField, FormError> = emptyMap(),
    val touched: Set<FormField> = emptySet(),
    val showAllErrors: Boolean = false,
    val removableVolumes: List<RemovableVolumeOption> = emptyList(),
    /** Remote names in rclone.conf; null when the config could not be read. */
    val rcloneRemotes: List<String>? = null,
    val testing: Boolean = false,
    val testResult: ConnectionTestResult? = null,
    val saving: Boolean = false,
) {
    val visibleErrors: Map<FormField, FormError>
        get() = if (showAllErrors) errors else errors.filterKeys { it in touched }

    val busy: Boolean get() = testing || saving
}

/** One-shot editor outcomes. */
sealed interface DestinationEditorEvent {
    data object Saved : DestinationEditorEvent
    data class Message(@StringRes val messageRes: Int) : DestinationEditorEvent
}

/**
 * Add/edit flow for one destination: type pick, per-type form, SAF/USB folder grant, connection
 * test and save. Secrets go to the vault through [DestinationHousekeeping]; the [Destination] row
 * only ever carries the ref. A connection test of unsaved credentials uses a throw-away vault ref
 * that is deleted right after, so cancelling the editor leaves no secret behind.
 */
@HiltViewModel
class DestinationEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repository: DestinationRepository,
    private val housekeeping: DestinationHousekeeping,
    private val tester: ConnectionTester,
    private val knownHosts: SshKnownHosts,
    private val rcloneConfig: RcloneConfigStore,
    private val localeManager: LocaleManager,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<DftbDestination.DestinationEditor>()
    private val _state = MutableStateFlow(DestinationEditorUiState(isNew = route.destinationId == null))
    val uiState: StateFlow<DestinationEditorUiState> = _state.asStateFlow()

    private val _events = Channel<DestinationEditorEvent>(Channel.BUFFERED)
    val events: Flow<DestinationEditorEvent> = _events.receiveAsFlow()

    private var original: Destination? = null
    private val newId: String = UUID.randomUUID().toString()
    private val id: String get() = original?.id ?: newId

    /** Trees granted in this editor session that are not (yet) saved; released on exit. */
    private val pendingGrants = LinkedHashSet<String>()

    /** Last successful test and the exact config it probed, so a save can keep its capacity. */
    private var lastVerified: Pair<DestinationConfig, ConnectionTestResult>? = null
    private var lastVerifiedAt: Long = 0L

    init {
        viewModelScope.launch {
            val existingId = route.destinationId
            if (existingId == null) {
                _state.update { it.copy(loading = false) }
            } else {
                val destination = withContext(io) { repository.get(existingId) }
                if (destination == null) {
                    _state.update { it.copy(loading = false, notFound = true) }
                } else {
                    original = destination
                    val method = safely { housekeeping.storedMethod(destination.credentialRef) }
                    val treeVolume = destination.config.treeUri?.let(RemovableVolumes::volumeIdOfTree)
                    val form = DestinationForm.from(destination, method, treeVolume)
                    setForm(form.copy(name = shownName(destination.name)), loading = false)
                }
            }
            refreshEnvironment()
        }
    }

    /** Re-read removable volumes and rclone remotes (both change while the app is in background). */
    fun refreshEnvironment() {
        viewModelScope.launch {
            val volumes = withContext(io) { RemovableVolumes.list(context) }
            val remotes = try {
                rcloneConfig.listRemotes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                DftbLog.w(TAG, "rclone remotes unavailable: ${e.reason}")
                null
            }
            _state.update { state ->
                state.copy(removableVolumes = volumes, rcloneRemotes = remotes).revalidated()
            }
        }
    }

    fun pickType(type: DestinationType) {
        if (!_state.value.isNew) return
        setForm(DestinationForm(type = type))
    }

    /** Back from the form to the type picker (add flow only); false when there is no such step. */
    fun backToTypePicker(): Boolean {
        val state = _state.value
        if (!state.isNew || state.form == null) return false
        _state.update { it.copy(form = null, errors = emptyMap(), touched = emptySet(), showAllErrors = false, testResult = null) }
        return true
    }

    /** Apply an edit to [field]; marks it touched so its error becomes visible. */
    fun edit(field: FormField, transform: (DestinationForm) -> DestinationForm) {
        _state.update { state ->
            val form = state.form ?: return@update state
            state.copy(form = transform(form), touched = state.touched + field, testResult = null).revalidated()
        }
    }

    /** A folder came back from the document tree picker. */
    fun onTreePicked(uri: Uri) {
        viewModelScope.launch {
            try {
                housekeeping.takeTree(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                DftbLog.w(TAG, "Could not persist folder grant", e)
                _events.trySend(DestinationEditorEvent.Message(R.string.destinations_error_tree_grant))
                return@launch
            }
            val text = uri.toString()
            pendingGrants += text
            val treeVolume = RemovableVolumes.volumeIdOfTree(text)
            edit(FormField.TREE_URI) { form ->
                var next = form.copy(treeUri = text, treeVolumeId = treeVolume)
                if (form.spec.usesUsbVolume && form.usbVolumeId.isBlank() && treeVolume != null &&
                    _state.value.removableVolumes.any { it.id.equals(treeVolume, ignoreCase = true) }
                ) {
                    next = next.copy(usbVolumeId = treeVolume)
                }
                if (form.name.isBlank()) next = next.copy(name = RemovableVolumes.displayPath(text).substringAfterLast('/'))
                next
            }
        }
    }

    fun test() {
        val state = _state.value
        val form = state.form ?: return
        if (state.busy) return
        if (!requireValid()) return
        _state.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val config = DestinationFormMapper.toConfig(form)
            var tempRef: String? = null
            try {
                tempRef = housekeeping.newTestRef()
                val ref = housekeeping.applySecretPlan(DestinationFormMapper.secretPlan(form), original?.credentialRef, tempRef)
                // Probe under the real id only when the saved row matches what is tested, so the
                // recorded capacity never describes a config the user then discards.
                val testId = if (original != null && original?.config == config && ref == original?.credentialRef) id else "$id-test"
                val now = System.currentTimeMillis()
                val probe = Destination(
                    id = testId,
                    name = form.name.trim(),
                    type = form.type,
                    config = config,
                    credentialRef = ref,
                    createdAt = original?.createdAt ?: now,
                    updatedAt = now,
                )
                val result = tester.test(probe)
                if (result.ok) {
                    lastVerified = config to result
                    lastVerifiedAt = System.currentTimeMillis()
                }
                _state.update { it.copy(testing = false, testResult = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultException) {
                DftbLog.w(TAG, "Vault failure during test: ${e.reason}")
                _state.update { it.copy(testing = false) }
                _events.trySend(DestinationEditorEvent.Message(R.string.destinations_error_vault))
            } catch (e: Exception) {
                DftbLog.w(TAG, "Connection test could not run", e)
                _state.update { it.copy(testing = false) }
                _events.trySend(DestinationEditorEvent.Message(R.string.destinations_error_test))
            } finally {
                tempRef?.let { ref -> appScope.launch(io) { runCatching { housekeeping.discardRef(ref) } } }
            }
        }
    }

    /** Drop the pinned SSH host key after the user confirmed the server was re-keyed. */
    fun forgetHostKey() {
        val form = _state.value.form ?: return
        val host = form.host.trim()
        if (host.isEmpty()) return
        val port = form.port.trim().toIntOrNull() ?: DestinationTypeSpec.SSH_DEFAULT_PORT
        viewModelScope.launch {
            val removed = withContext(io) { knownHosts.forget(host, port) }
            _state.update { it.copy(testResult = null) }
            _events.trySend(
                DestinationEditorEvent.Message(
                    if (removed) R.string.destinations_host_key_forgotten else R.string.destinations_host_key_none,
                ),
            )
        }
    }

    fun save() {
        val state = _state.value
        val form = state.form ?: return
        if (state.busy) return
        if (!requireValid()) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val previous = original
            var createdRef: String? = null
            try {
                val config = DestinationFormMapper.toConfig(form)
                val plan = DestinationFormMapper.secretPlan(form)
                // A removal is applied only after the row no longer references the secret.
                val ref = if (plan is SecretPlan.Remove) null else housekeeping.applySecretPlan(plan, previous?.credentialRef)
                if (ref != null && ref != previous?.credentialRef) createdRef = ref
                val now = System.currentTimeMillis()
                val verified = lastVerified?.takeIf { it.first == config }?.second
                val configUnchanged = previous != null && previous.config == config
                val destination = Destination(
                    id = id,
                    name = storedName(form.name.trim(), previous),
                    type = form.type,
                    config = config,
                    credentialRef = ref,
                    createdAt = previous?.createdAt ?: now,
                    updatedAt = now,
                    lastVerifiedAt = if (verified != null) lastVerifiedAt else previous?.lastVerifiedAt?.takeIf { configUnchanged },
                    lastKnownFreeBytes = if (verified != null) verified.space?.freeBytes else previous?.lastKnownFreeBytes?.takeIf { configUnchanged },
                    lastKnownTotalBytes = if (verified != null) verified.space?.totalBytes else previous?.lastKnownTotalBytes?.takeIf { configUnchanged },
                )
                withContext(io) { repository.save(destination) }
                createdRef = null
                original = destination
                if (plan is SecretPlan.Remove) housekeeping.applySecretPlan(plan, previous?.credentialRef)
                val keep = config.treeUri
                val toRelease = (pendingGrants + listOfNotNull(previous?.config?.treeUri)).filter { it != keep }
                pendingGrants.clear()
                toRelease.forEach { housekeeping.releaseTreeIfUnused(it) }
                _state.update { it.copy(saving = false) }
                _events.trySend(DestinationEditorEvent.Saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultException) {
                DftbLog.w(TAG, "Vault failure during save: ${e.reason}")
                _state.update { it.copy(saving = false) }
                _events.trySend(DestinationEditorEvent.Message(R.string.destinations_error_vault))
            } catch (e: Exception) {
                DftbLog.w(TAG, "Saving destination failed", e)
                _state.update { it.copy(saving = false) }
                _events.trySend(DestinationEditorEvent.Message(R.string.destinations_error_save))
            } finally {
                // A secret minted for a row that was never saved must not linger in the vault.
                createdRef?.let { ref -> appScope.launch(io) { runCatching { housekeeping.discardRef(ref) } } }
            }
        }
    }

    override fun onCleared() {
        val unsaved = pendingGrants.toList()
        pendingGrants.clear()
        if (unsaved.isNotEmpty()) {
            appScope.launch { unsaved.forEach { runCatching { housekeeping.releaseTreeIfUnused(it) } } }
        }
    }

    /** The built-in destination's marker name is edited as its translation, and kept if unchanged. */
    private fun shownName(stored: String): String =
        if (stored == Destination.APP_STORAGE_NAME_MARKER) appStorageLabel() else stored

    private fun storedName(typed: String, previous: Destination?): String =
        if (previous?.name == Destination.APP_STORAGE_NAME_MARKER && typed == appStorageLabel()) {
            Destination.APP_STORAGE_NAME_MARKER
        } else {
            typed
        }

    private fun appStorageLabel(): String =
        localeManager.localizedContext().getString(R.string.onboarding_destination_app_storage_name)

    private fun requireValid(): Boolean {
        val state = _state.value.revalidated()
        _state.value = state.copy(showAllErrors = true)
        if (state.errors.isEmpty()) return true
        _events.trySend(DestinationEditorEvent.Message(R.string.destinations_fix_errors))
        return false
    }

    private fun setForm(form: DestinationForm, loading: Boolean = _state.value.loading) {
        _state.update {
            it.copy(loading = loading, form = form, touched = emptySet(), showAllErrors = false, testResult = null).revalidated()
        }
    }

    private fun DestinationEditorUiState.revalidated(): DestinationEditorUiState {
        val current = form ?: return copy(errors = emptyMap())
        return copy(errors = DestinationFormValidator.validate(current, rcloneRemotes?.toSet()))
    }

    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DftbLog.w(TAG, "Could not read vault metadata", e)
        null
    }

    private companion object {
        const val TAG = "DestEditorVM"
    }
}
