package ca.devdcs.backup.ui.destinations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.ConnectionTestResult
import ca.devdcs.backup.core.storage.ConnectionTester
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.repository.DestinationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Destinations manager list state. [destinations] is null until the first database emission. */
data class DestinationsUiState(
    val destinations: List<Destination>? = null,
    val testing: Set<String> = emptySet(),
    val results: Map<String, ConnectionTestResult> = emptyMap(),
    val deleting: Set<String> = emptySet(),
)

/** One-shot outcomes the list screen shows as a snackbar. */
sealed interface DestinationsEvent {
    data class Deleted(val name: String) : DestinationsEvent
    data object DeleteFailed : DestinationsEvent
}

/**
 * Backs the destinations manager: live list from Room, per-row connection test, and delete
 * (which also removes the vault secret and SAF grant via [DestinationHousekeeping]).
 */
@HiltViewModel
class DestinationsViewModel @Inject constructor(
    repository: DestinationRepository,
    private val tester: ConnectionTester,
    private val housekeeping: DestinationHousekeeping,
) : ViewModel() {

    private val transient = MutableStateFlow(DestinationsUiState())
    private val _events = Channel<DestinationsEvent>(Channel.BUFFERED)
    val events: Flow<DestinationsEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<DestinationsUiState> = combine(repository.observeAll(), transient) { list, state ->
        state.copy(destinations = list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DestinationsUiState())

    fun test(destination: Destination) {
        if (destination.id in transient.value.testing) return
        transient.update { it.copy(testing = it.testing + destination.id) }
        viewModelScope.launch {
            val result = tester.test(destination)
            transient.update { it.copy(testing = it.testing - destination.id, results = it.results + (destination.id to result)) }
        }
    }

    fun delete(destination: Destination) {
        if (destination.id in transient.value.deleting) return
        transient.update { it.copy(deleting = it.deleting + destination.id) }
        viewModelScope.launch {
            try {
                housekeeping.delete(destination)
                _events.trySend(DestinationsEvent.Deleted(destination.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Delete failed for ${destination.id}", e)
                _events.trySend(DestinationsEvent.DeleteFailed)
            } finally {
                transient.update { it.copy(deleting = it.deleting - destination.id, results = it.results - destination.id) }
            }
        }
    }

    private companion object {
        const val TAG = "DestinationsVM"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
