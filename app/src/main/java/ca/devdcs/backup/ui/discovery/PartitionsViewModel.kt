package ca.devdcs.backup.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.core.discovery.partition.PartitionGroup
import ca.devdcs.backup.core.discovery.partition.PartitionScanOutcome
import ca.devdcs.backup.core.discovery.partition.PartitionScanResult
import ca.devdcs.backup.core.discovery.partition.PartitionScanner
import ca.devdcs.backup.core.discovery.partition.PartitionView
import ca.devdcs.backup.core.discovery.partition.PartitionViews
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Partitions tab state. [outcome] is null only before the first scan returns. */
data class PartitionsUiState(
    val scanning: Boolean = false,
    val outcome: PartitionScanOutcome? = null,
    val scannedAtMillis: Long? = null,
    val view: PartitionView = PartitionView.STANDARD,
    /** Groups of [outcome] for [view]; empty when the scan is unavailable. */
    val groups: List<PartitionGroup> = emptyList(),
)

/**
 * Backs the read-only partition browser: runs (or reuses the cached) [PartitionScanner] scan and
 * derives the STANDARD / ADVANCED grouping via [PartitionViews]. Selection state is deliberately
 * not held here; a picker owns it and passes it to the composable.
 */
@HiltViewModel
class PartitionsViewModel @Inject constructor(
    private val scanner: PartitionScanner,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PartitionsUiState())
    val uiState: StateFlow<PartitionsUiState> = _uiState.asStateFlow()
    private var scanJob: Job? = null

    init {
        load(force = false)
    }

    fun rescan() = load(force = true)

    fun setView(view: PartitionView) {
        _uiState.update { it.copy(view = view, groups = groupsOf(it.outcome, view)) }
    }

    private fun load(force: Boolean) {
        if (scanJob?.isActive == true) return
        _uiState.update { it.copy(scanning = true) }
        scanJob = viewModelScope.launch {
            try {
                val outcome = if (force) scanner.rescan() else scanner.scan()
                val at = scanner.lastScan?.scannedAtMillis
                _uiState.update { it.copy(outcome = outcome, scannedAtMillis = at, groups = groupsOf(outcome, it.view)) }
            } finally {
                _uiState.update { it.copy(scanning = false) }
            }
        }
    }

    private fun groupsOf(outcome: PartitionScanOutcome?, view: PartitionView): List<PartitionGroup> =
        if (outcome is PartitionScanResult) PartitionViews.groups(outcome, view) else emptyList()
}
