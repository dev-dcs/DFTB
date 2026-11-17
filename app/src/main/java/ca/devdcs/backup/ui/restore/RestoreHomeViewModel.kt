package ca.devdcs.backup.ui.restore

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.restore.catalog.DefaultBackupCatalog
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.repository.DestinationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One backup row of the restore browser. */
data class BackupRow(
    val jobDir: String,
    val folderName: String,
    val profileName: String?,
    val createdAtMillis: Long?,
    val totalBytes: Long?,
    val encrypted: Boolean,
    /** True/false when the manifest was readable, null when it is encrypted (unknown). */
    val partial: Boolean?,
    val manifestReadable: Boolean,
)

/** Listing of one destination. */
data class BackupListing(
    val loading: Boolean = false,
    val rows: List<BackupRow>? = null,
    val failure: OpenFailure? = null,
)

/** Restore browser state; [destinations] is null until the database answered. */
data class RestoreHomeUiState(
    val destinations: List<Destination>? = null,
    val selectedId: String? = null,
    val listing: BackupListing = BackupListing(),
)

/** Pure ordering/mapping of catalog listings for the browser. */
object BackupRows {

    fun of(listed: List<DefaultBackupCatalog.ListedBackup>): List<BackupRow> = newestFirst(
        listed.map { item ->
            val s = item.summary
            BackupRow(
                jobDir = s.jobDir,
                folderName = s.jobDir.trimEnd('/').substringAfterLast('/'),
                profileName = s.profileName,
                createdAtMillis = s.createdAtMillis,
                totalBytes = s.totalBytes,
                encrypted = s.encrypted,
                partial = item.partial,
                manifestReadable = s.manifestReadable,
            )
        },
    )

    /** Newest first; undated backups last; ties broken by folder name (descending, i.e. newer stamp first). */
    fun newestFirst(rows: List<BackupRow>): List<BackupRow> =
        rows.sortedWith(
            compareByDescending<BackupRow> { it.createdAtMillis ?: Long.MIN_VALUE }
                .thenByDescending { it.folderName },
        )

    /** The destination to show: the saved one when it still exists, else the first by name. */
    fun pickDestination(destinations: List<Destination>, saved: String?): String? =
        destinations.firstOrNull { it.id == saved }?.id ?: destinations.firstOrNull()?.id
}

/**
 * Backs the restore browser: destinations from Room, the selected one remembered across process
 * death, and its backups listed through [DefaultBackupCatalog.listWithState] (newest first, with
 * the partial flag). Listings are cached per destination; refresh re-reads the destination.
 */
@HiltViewModel
class RestoreHomeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: DestinationRepository,
    private val catalog: DefaultBackupCatalog,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val selected = MutableStateFlow(savedStateHandle.get<String>(KEY_DESTINATION))
    private val listings = MutableStateFlow<Map<String, BackupListing>>(emptyMap())
    private val loadJobs = HashMap<String, Job>()
    private val destinations = repository.observeAll().map { all -> all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) }

    val uiState: StateFlow<RestoreHomeUiState> = combine(destinations, selected, listings) { sorted, saved, cache ->
        val id = BackupRows.pickDestination(sorted, saved)
        RestoreHomeUiState(destinations = sorted, selectedId = id, listing = id?.let { cache[it] } ?: BackupListing())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RestoreHomeUiState())

    init {
        viewModelScope.launch {
            combine(destinations, selected) { sorted, saved -> BackupRows.pickDestination(sorted, saved) }
                .distinctUntilChanged()
                .collect { id -> if (id != null && listings.value[id] == null) load(id) }
        }
    }

    fun select(destinationId: String) {
        savedStateHandle[KEY_DESTINATION] = destinationId
        selected.value = destinationId
    }

    fun refresh() {
        uiState.value.selectedId?.let { load(it, force = true) }
    }

    private fun load(destinationId: String, force: Boolean = false) {
        if (loadJobs[destinationId]?.isActive == true) {
            if (!force) return
            loadJobs[destinationId]?.cancel()
        }
        listings.update { it + (destinationId to (it[destinationId] ?: BackupListing()).copy(loading = true, failure = null)) }
        loadJobs[destinationId] = viewModelScope.launch {
            try {
                val destination = withContext(io) { repository.get(destinationId) }
                if (destination == null) {
                    listings.update { it + (destinationId to BackupListing(failure = OpenFailure.DestinationMissing)) }
                    return@launch
                }
                val rows = withContext(io) { BackupRows.of(catalog.listWithState(destination)) }
                listings.update { it + (destinationId to BackupListing(rows = rows)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Listing $destinationId failed: ${e.message}")
                listings.update { it + (destinationId to BackupListing(rows = it[destinationId]?.rows, failure = BackupUnlocker.failureOf(e))) }
            }
        }
    }

    private companion object {
        const val TAG = "RestoreHomeVM"
        const val KEY_DESTINATION = "destinationId"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
