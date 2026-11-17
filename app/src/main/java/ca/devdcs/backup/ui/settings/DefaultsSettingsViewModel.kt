package ca.devdcs.backup.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.repository.DestinationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** Defaults page state. [destinations] is null until Room has answered once. */
data class DefaultsSettingsUiState(
    val defaults: PipelineDefaults? = null,
    val destinations: List<Destination>? = null,
    val issues: Set<DefaultsIssue> = emptySet(),
)

/**
 * Edits the PipelineOptions defaults new backup profiles start from. Every write goes through
 * [PipelineDefaultsRules] so the stored combination is always valid, and writes are serialized so
 * a fast format-then-level sequence cannot interleave its two DataStore edits.
 */
@HiltViewModel
class DefaultsSettingsViewModel @Inject constructor(
    private val preferences: AppPreferencesRepository,
    destinationRepository: DestinationRepository,
) : ViewModel() {

    private val writeMutex = Mutex()
    private val _messages = Channel<SettingsMessage>(Channel.BUFFERED)
    val messages: Flow<SettingsMessage> = _messages.receiveAsFlow()

    private val destinationsFlow: Flow<List<Destination>?> = destinationRepository.observeAll()
        .map<List<Destination>, List<Destination>?> { list -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) }
        .onStart { emit(null) }
        .catch { error ->
            DftbLog.w(TAG, "Destinations unavailable", error)
            emit(emptyList())
        }

    val uiState: StateFlow<DefaultsSettingsUiState> = combine(
        preferences.preferences.map { PipelineDefaults.from(it) },
        destinationsFlow,
    ) { defaults, destinations ->
        DefaultsSettingsUiState(
            defaults = defaults,
            destinations = destinations,
            issues = PipelineDefaultsRules.issues(defaults, destinations?.map { it.id }?.toSet()),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DefaultsSettingsUiState())

    fun setArchiveFormat(format: ArchiveFormat) = edit { current ->
        if (!PipelineDefaultsRules.isSelectable(format)) return@edit
        val next = PipelineDefaultsRules.withFormat(current, format)
        // Level first: if the process dies between the two edits, the stored level is already valid
        // for the new format and still within range for the old one's wider scale.
        if (next.compressionLevel != current.compressionLevel) preferences.setDefaultCompressionLevel(next.compressionLevel)
        preferences.setDefaultArchiveFormat(next.archiveFormat)
    }

    fun setCompressionLevel(level: Int) = edit { current ->
        val next = PipelineDefaultsRules.withLevel(current, level)
        if (next.compressionLevel != current.compressionLevel) preferences.setDefaultCompressionLevel(next.compressionLevel)
    }

    fun setChecksumAlgorithm(algorithm: ChecksumAlgorithm) = edit { current ->
        if (current.checksumAlgorithm != algorithm) preferences.setDefaultChecksumAlgorithm(algorithm)
    }

    /** null = ask for a destination every time a profile is created. */
    fun setDefaultDestination(destinationId: String?) = edit { current ->
        if (current.destinationId != destinationId) preferences.setDefaultDestinationId(destinationId)
    }

    /** Rewrites the stored defaults to the nearest valid combination (only offered when issues exist). */
    fun repair() = edit { current ->
        val ids = uiState.value.destinations?.map { it.id }?.toSet()
        val fixed = PipelineDefaultsRules.normalize(current, ids)
        if (fixed.compressionLevel != current.compressionLevel) preferences.setDefaultCompressionLevel(fixed.compressionLevel)
        if (fixed.archiveFormat != current.archiveFormat) preferences.setDefaultArchiveFormat(fixed.archiveFormat)
        if (fixed.destinationId != current.destinationId) preferences.setDefaultDestinationId(fixed.destinationId)
        _messages.trySend(SettingsMessage(R.string.settings_defaults_repaired))
    }

    /** Runs [block] against the latest persisted defaults, one write sequence at a time. */
    private fun edit(block: suspend (PipelineDefaults) -> Unit) {
        viewModelScope.launch {
            writeMutex.withLock {
                try {
                    block(PipelineDefaults.from(preferences.preferences.first()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DftbLog.w(TAG, "Could not save defaults", e)
                    _messages.trySend(SettingsMessage(R.string.settings_save_failed))
                }
            }
        }
    }

    private companion object {
        const val TAG = "SettingsDefaults"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
