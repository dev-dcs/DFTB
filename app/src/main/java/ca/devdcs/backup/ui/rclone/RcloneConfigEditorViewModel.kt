package ca.devdcs.backup.ui.rclone

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.labelRes
import ca.devdcs.backup.core.storage.rclone.RcloneConfigStore
import ca.devdcs.backup.core.storage.rclone.RcloneIniIssue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Raw editor state. [issues] is null until the text is validated (it is reset by every edit, so a
 * stale "valid" verdict is never shown for changed text).
 */
data class RcloneConfigEditorUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val original: String = "",
    val text: String = "",
    val issues: List<RcloneIniIssue>? = null,
    val saving: Boolean = false,
    val restoring: Boolean = false,
) {
    val dirty: Boolean get() = text != original
    val busy: Boolean get() = loading || saving || restoring
}

/** One-shot editor outcomes shown as snackbars. */
sealed interface RcloneConfigEditorEvent {
    data class Message(@StringRes val messageRes: Int) : RcloneConfigEditorEvent
}

/**
 * Backs the raw rclone.conf editor: load from the vault-backed [RcloneConfigStore], validate
 * with line-numbered issues, save, and swap back to the previous version.
 */
@HiltViewModel
class RcloneConfigEditorViewModel @Inject constructor(
    private val configStore: RcloneConfigStore,
) : ViewModel() {

    private val _state = MutableStateFlow(RcloneConfigEditorUiState())
    val uiState: StateFlow<RcloneConfigEditorUiState> = _state.asStateFlow()

    private val _events = Channel<RcloneConfigEditorEvent>(Channel.BUFFERED)
    val events: Flow<RcloneConfigEditorEvent> = _events.receiveAsFlow()

    init {
        load()
    }

    private fun load() {
        _state.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            try {
                val text = configStore.read()
                _state.update { it.copy(loading = false, original = text, text = text, issues = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                DftbLog.w(TAG, "rclone.conf could not be loaded: ${e.reason}")
                _state.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun retryLoad() = load()

    fun setText(text: String) {
        _state.update { it.copy(text = text, issues = null) }
    }

    fun validate() {
        val document = configStore.validate(_state.value.text)
        _state.update { it.copy(issues = document.issues) }
        _events.trySend(
            RcloneConfigEditorEvent.Message(
                if (document.isValid) R.string.rclone_editor_valid else R.string.rclone_editor_invalid,
            ),
        )
    }

    fun save() {
        val state = _state.value
        if (state.busy || state.loadFailed) return
        val document = configStore.validate(state.text)
        if (!document.isValid) {
            _state.update { it.copy(issues = document.issues) }
            _events.trySend(RcloneConfigEditorEvent.Message(R.string.rclone_editor_invalid))
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val saved = configStore.write(state.text)
                val normalized = configStore.read()
                _state.update {
                    // Keep anything typed while the save ran; it is still unsaved.
                    val text = if (it.text == state.text) normalized else it.text
                    it.copy(saving = false, original = normalized, text = text, issues = if (text == normalized) saved.issues else null)
                }
                _events.trySend(RcloneConfigEditorEvent.Message(R.string.rclone_editor_saved))
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                DftbLog.w(TAG, "rclone.conf save failed: ${e.reason}")
                _state.update { it.copy(saving = false, issues = configStore.validate(state.text).issues) }
                _events.trySend(RcloneConfigEditorEvent.Message(e.reason.labelRes()))
            }
        }
    }

    /** Swap in the version replaced by the last save (discarding unsaved edits). */
    fun restorePrevious() {
        if (_state.value.busy || _state.value.loadFailed) return
        _state.update { it.copy(restoring = true) }
        viewModelScope.launch {
            try {
                val restored = configStore.restorePrevious()
                val text = configStore.read()
                _state.update { it.copy(restoring = false, original = text, text = text, issues = null) }
                _events.trySend(
                    RcloneConfigEditorEvent.Message(
                        if (restored) R.string.rclone_editor_restored else R.string.rclone_editor_no_previous,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                DftbLog.w(TAG, "rclone.conf restore failed: ${e.reason}")
                _state.update { it.copy(restoring = false) }
                _events.trySend(RcloneConfigEditorEvent.Message(e.reason.labelRes()))
            }
        }
    }

    private companion object {
        const val TAG = "RcloneConfEditorVM"
    }
}
