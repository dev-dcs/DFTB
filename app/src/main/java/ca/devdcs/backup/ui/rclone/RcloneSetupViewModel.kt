package ca.devdcs.backup.ui.rclone

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.labelRes
import ca.devdcs.backup.core.storage.rclone.ProviderGuide
import ca.devdcs.backup.core.storage.rclone.ProviderGuides
import ca.devdcs.backup.core.storage.rclone.RcloneCli
import ca.devdcs.backup.core.storage.rclone.RcloneConfigBuilder
import ca.devdcs.backup.core.storage.rclone.RcloneConfigStore
import ca.devdcs.backup.core.storage.rclone.RcloneRemote
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.ui.destinations.DestinationForm
import ca.devdcs.backup.ui.destinations.DestinationFormMapper
import ca.devdcs.backup.ui.destinations.DestinationFormValidator
import ca.devdcs.backup.ui.destinations.FormError
import ca.devdcs.backup.ui.destinations.FormField
import ca.devdcs.backup.ui.navigation.DftbDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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

/** Guided rclone setup state. [guide] null shows the provider list. */
data class RcloneSetupUiState(
    val toolChecked: Boolean = false,
    /** `rclone version` first line; null when rclone is unavailable (TOOL_MISSING). */
    val toolVersion: String? = null,
    /** Configured remotes (masked); null when rclone.conf could not be read. */
    val remotes: List<RcloneRemote>? = emptyList(),
    val guide: ProviderGuide? = null,
    val remoteName: String = "",
    val values: Map<String, String> = emptyMap(),
    val errors: List<RcloneConfigBuilder.FieldError> = emptyList(),
    val touched: Set<String> = emptySet(),
    val attempted: Boolean = false,
    val saving: Boolean = false,
    val warnings: List<RcloneConfigBuilder.Warning> = emptyList(),
    /** Remote just saved; the screen offers to create a destination for it. */
    val savedRemote: String? = null,
    val creatingDestination: Boolean = false,
) {
    val toolMissing: Boolean get() = toolChecked && toolVersion == null

    /** Errors to show: touched fields only, or all of them after a save attempt. */
    fun visibleErrors(key: String): List<RcloneConfigBuilder.FieldError> =
        errors.filter { it.key == key && (attempted || key in touched) }

    val remoteNameTaken: Boolean get() = remotes?.any { it.name == remoteName.trim() } == true
}

/** One-shot setup outcomes: a snackbar message, or navigation after a destination was created. */
sealed interface RcloneSetupEvent {
    data class Message(@StringRes val messageRes: Int) : RcloneSetupEvent
    data object DestinationCreated : RcloneSetupEvent
}

/**
 * Drives [ProviderGuide]-based setup: collects field values, validates them with
 * [RcloneConfigBuilder], obscures passwords through the rclone binary, saves the section with
 * [RcloneConfigStore.upsertRemote], and can create an RCLONE destination pointing at it.
 */
@HiltViewModel
class RcloneSetupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val configStore: RcloneConfigStore,
    private val cli: RcloneCli,
    private val repository: DestinationRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<DftbDestination.RcloneSetup>()
    private val _state = MutableStateFlow(RcloneSetupUiState())
    val uiState: StateFlow<RcloneSetupUiState> = _state.asStateFlow()

    private val _events = Channel<RcloneSetupEvent>(Channel.BUFFERED)
    val events: Flow<RcloneSetupEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val version = withContext(io) { cli.version() }
            _state.update { it.copy(toolChecked = true, toolVersion = version) }
        }
        viewModelScope.launch {
            refreshRemotes()
            route.guideId?.let(ProviderGuides::byId)?.let(::selectGuide)
        }
    }

    suspend fun refreshRemotes() {
        val remotes = try {
            configStore.remotes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageException) {
            DftbLog.w(TAG, "rclone.conf unreadable: ${e.reason}")
            null
        }
        _state.update { it.copy(remotes = remotes) }
    }

    fun reload() {
        viewModelScope.launch { refreshRemotes() }
    }

    fun selectGuide(guide: ProviderGuide) {
        val taken = _state.value.remotes.orEmpty().map { it.name }.toSet()
        val name = uniqueName(guide.suggestedRemoteName, taken)
        val defaults = guide.fields.mapNotNull { field -> field.defaultValue?.let { field.key to it } }.toMap()
        _state.update {
            it.copy(
                guide = guide,
                remoteName = name,
                values = defaults,
                touched = emptySet(),
                attempted = false,
                warnings = emptyList(),
                savedRemote = null,
            ).revalidated()
        }
    }

    /** Back to the provider list; false when already there. */
    fun closeGuide(): Boolean {
        if (_state.value.guide == null) return false
        _state.update { it.copy(guide = null, values = emptyMap(), errors = emptyList(), savedRemote = null) }
        return true
    }

    fun setRemoteName(name: String) {
        _state.update { it.copy(remoteName = name, touched = it.touched + RcloneConfigBuilder.REMOTE_NAME_KEY).revalidated() }
    }

    fun setValue(key: String, value: String) {
        _state.update { state ->
            val values = if (value.isEmpty()) state.values - key else state.values + (key to value)
            state.copy(values = values, touched = state.touched + key).revalidated()
        }
    }

    fun save() {
        val state = _state.value
        val guide = state.guide ?: return
        if (state.saving) return
        val checked = state.revalidated().copy(attempted = true)
        _state.value = checked
        if (checked.errors.isNotEmpty()) {
            _events.trySend(RcloneSetupEvent.Message(R.string.rclone_setup_fix_errors))
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val name = state.remoteName.trim()
                when (val result = RcloneConfigBuilder.build(guide, name, state.values) { plain -> cli.obscure(plain) }) {
                    is RcloneConfigBuilder.Result.Failure ->
                        _state.update { it.copy(saving = false, errors = result.errors, attempted = true) }
                    is RcloneConfigBuilder.Result.Success -> {
                        configStore.upsertRemote(result.section)
                        refreshRemotes()
                        _state.update { it.copy(saving = false, warnings = result.warnings, savedRemote = name) }
                        _events.trySend(RcloneSetupEvent.Message(R.string.rclone_setup_saved))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                DftbLog.w(TAG, "Saving rclone remote failed: ${e.reason}")
                _state.update { it.copy(saving = false) }
                val message = if (e.reason == StorageException.Reason.TOOL_MISSING) R.string.rclone_setup_error_obscure_tool else e.reason.labelRes()
                _events.trySend(RcloneSetupEvent.Message(message))
            }
        }
    }

    fun dismissSaved() {
        _state.update { it.copy(savedRemote = null) }
    }

    /** Pure check of the "create destination" dialog inputs; null when acceptable. */
    fun destinationErrors(remoteName: String, name: String, basePath: String): Map<FormField, FormError> {
        val known = _state.value.remotes?.map { it.name }?.toSet()
        return DestinationFormValidator.validate(rcloneForm(remoteName, name, basePath), known)
    }

    fun createDestination(remoteName: String, name: String, basePath: String) {
        if (_state.value.creatingDestination) return
        if (destinationErrors(remoteName, name, basePath).isNotEmpty()) return
        _state.update { it.copy(creatingDestination = true) }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val destination = Destination(
                    id = UUID.randomUUID().toString(),
                    name = name.trim(),
                    type = DestinationType.RCLONE,
                    config = DestinationFormMapper.toConfig(rcloneForm(remoteName, name, basePath)),
                    createdAt = now,
                    updatedAt = now,
                )
                withContext(io) { repository.save(destination) }
                _state.update { it.copy(creatingDestination = false, savedRemote = null) }
                _events.trySend(RcloneSetupEvent.DestinationCreated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Creating rclone destination failed", e)
                _state.update { it.copy(creatingDestination = false) }
                _events.trySend(RcloneSetupEvent.Message(R.string.destinations_error_save))
            }
        }
    }

    private fun rcloneForm(remoteName: String, name: String, basePath: String) =
        DestinationForm(type = DestinationType.RCLONE, name = name, remoteName = remoteName, basePath = basePath)

    private fun RcloneSetupUiState.revalidated(): RcloneSetupUiState {
        val current = guide ?: return copy(errors = emptyList())
        return copy(errors = RcloneConfigBuilder.validate(current, remoteName.trim(), values))
    }

    private companion object {
        const val TAG = "RcloneSetupVM"

        fun uniqueName(base: String, taken: Set<String>): String {
            if (base !in taken) return base
            var n = 2
            while ("$base$n" in taken) n++
            return "$base$n"
        }
    }
}
