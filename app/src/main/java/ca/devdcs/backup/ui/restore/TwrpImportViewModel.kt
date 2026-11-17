package ca.devdcs.backup.ui.restore

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.layout.TwrpConventions
import ca.devdcs.backup.core.restore.twrp.TwrpBackup
import ca.devdcs.backup.core.restore.twrp.TwrpFileCheck
import ca.devdcs.backup.core.restore.twrp.TwrpImporter
import ca.devdcs.backup.core.restore.twrp.TwrpPartitionBackup
import ca.devdcs.backup.core.restore.twrp.TwrpPayloadFormat
import ca.devdcs.backup.core.restore.twrp.TwrpScanResult
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.ui.navigation.DftbDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/** Sidecar verification of the selected TWRP backup: progress and per-file results. */
data class TwrpVerifyUiState(
    val running: Boolean = false,
    val doneBytes: Long = 0L,
    val totalBytes: Long? = null,
    val checks: List<TwrpFileCheck>? = null,
    val failure: OpenFailure? = null,
) {
    val summary: TwrpVerifySummary? get() = checks?.let(TwrpImportRules::summarize)
}

/** Everything the TWRP import screen renders. */
data class TwrpImportUiState(
    val destinations: List<Destination>? = null,
    val destinationId: String? = null,
    val searchRoot: String = TwrpConventions.ROOT_FOLDER,
    val searching: Boolean = false,
    val scan: TwrpScanResult? = null,
    val scanFailure: OpenFailure? = null,
    val selected: TwrpBackup? = null,
    /** Sniffed payload format per partition backup name; a null value means it could not be read. */
    val formats: Map<String, TwrpPayloadFormat?> = emptyMap(),
    val verify: TwrpVerifyUiState = TwrpVerifyUiState(),
    val draft: TwrpActionDraft? = null,
    val task: TwrpTaskState? = null,
    val rootAvailable: Boolean = false,
) {
    val destination: Destination? get() = destinations?.firstOrNull { it.id == destinationId }

    fun partition(backupName: String): TwrpPartitionBackup? = selected?.partitions?.firstOrNull { it.backupName == backupName }

    val draftPartition: TwrpPartitionBackup? get() = draft?.let { partition(it.backupName) }

    val draftProblems: List<TwrpDraftProblem>
        get() {
            val d = draft ?: return emptyList()
            val p = partition(d.backupName) ?: return emptyList()
            return TwrpImportRules.problems(d, p, formats[d.backupName])
        }

    val taskActive: Boolean get() = task != null && !task.finished
}

/**
 * Backs the TWRP / OrangeFox import: find backup folders on a destination ([TwrpImporter.find]),
 * sniff each partition's format, verify the checksum sidecars, and configure per-partition actions
 * — write a raw image through the shared partition safety flow, or extract a filesystem archive
 * into a folder of a destination. Long actions run in [TwrpTaskRunner]; choices persist in
 * SavedStateHandle.
 */
@HiltViewModel
class TwrpImportViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: DestinationRepository,
    private val importer: TwrpImporter,
    private val runner: TwrpTaskRunner,
    private val router: CommandExecutorRouter,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<DftbDestination.TwrpImport>()
    private val _state = MutableStateFlow(
        TwrpImportUiState(
            destinationId = savedStateHandle.get<String>(KEY_DESTINATION) ?: route.destinationId,
            searchRoot = savedStateHandle.get<String>(KEY_ROOT) ?: TwrpConventions.ROOT_FOLDER,
            draft = TwrpActionDraft.decode(savedStateHandle.get<String>(KEY_DRAFT)),
        ),
    )
    val uiState: StateFlow<TwrpImportUiState> = combine(_state, runner.tasks) { state, tasks ->
        val folder = state.selected?.folderPath
        val id = state.destinationId
        val task = if (id != null && folder != null) {
            tasks.values.filter { it.destinationId == id && it.folderPath == folder }.maxByOrNull { it.startedAtMillis }
        } else {
            null
        }
        state.copy(task = task)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), _state.value)

    private var searchJob: Job? = null
    private var verifyJob: Job? = null
    private var sniffJob: Job? = null
    private var searchedOnce = false

    init {
        viewModelScope.launch {
            val root = try {
                router.current().let { it.tier == ElevationTier.ROOT && it.isAvailable() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _state.update { it.copy(rootAvailable = root) }
        }
        viewModelScope.launch {
            repository.observeAll().collect { all ->
                val sorted = all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                _state.update { state ->
                    val id = state.destinationId?.takeIf { wanted -> sorted.any { it.id == wanted } } ?: sorted.firstOrNull()?.id
                    state.copy(destinations = sorted, destinationId = id)
                }
                // First list (also after process death): search the chosen destination and reopen the folder.
                if (!searchedOnce && _state.value.destination != null) {
                    searchedOnce = true
                    search(reopen = savedStateHandle.get<String>(KEY_FOLDER))
                }
            }
        }
    }

    fun selectDestination(id: String) {
        if (id == _state.value.destinationId && _state.value.scan != null) return
        savedStateHandle[KEY_DESTINATION] = id
        savedStateHandle[KEY_FOLDER] = null
        setDraft(null)
        _state.update { it.copy(destinationId = id, scan = null, scanFailure = null, selected = null, formats = emptyMap(), verify = TwrpVerifyUiState()) }
        search()
    }

    fun setSearchRoot(text: String) {
        savedStateHandle[KEY_ROOT] = text
        _state.update { it.copy(searchRoot = text) }
    }

    fun search(reopen: String? = null) {
        val destination = _state.value.destination ?: return
        searchJob?.cancel()
        _state.update { it.copy(searching = true, scanFailure = null) }
        val root = _state.value.searchRoot.trim().ifEmpty { TwrpConventions.ROOT_FOLDER }
        searchJob = viewModelScope.launch {
            try {
                val result = importer.find(destination, root)
                _state.update { it.copy(searching = false, scan = result) }
                if (reopen != null) result.backups.firstOrNull { it.folderPath == reopen }?.let(::adoptBackup)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "TWRP search failed", e)
                _state.update { it.copy(searching = false, scanFailure = BackupUnlocker.failureOf(e)) }
            }
        }
    }

    fun openBackup(folderPath: String) {
        val backup = _state.value.scan?.backups?.firstOrNull { it.folderPath == folderPath } ?: return
        if (_state.value.selected?.folderPath != folderPath) setDraft(null)
        adoptBackup(backup)
    }

    fun closeBackup() {
        savedStateHandle[KEY_FOLDER] = null
        sniffJob?.cancel()
        verifyJob?.cancel()
        setDraft(null)
        _state.update { it.copy(selected = null, formats = emptyMap(), verify = TwrpVerifyUiState()) }
    }

    private fun adoptBackup(backup: TwrpBackup) {
        savedStateHandle[KEY_FOLDER] = backup.folderPath
        val draft = _state.value.draft?.takeIf { it.folderPath == backup.folderPath }
        if (draft == null) setDraft(null)
        _state.update { it.copy(selected = backup, formats = emptyMap(), verify = TwrpVerifyUiState()) }
        sniff(backup)
    }

    private fun sniff(backup: TwrpBackup) {
        val destination = _state.value.destination ?: return
        sniffJob?.cancel()
        sniffJob = viewModelScope.launch {
            for (partition in backup.partitions) {
                val format = try {
                    importer.sniff(destination, partition)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DftbLog.w(TAG, "Sniff of ${partition.backupName} failed: ${e.message}")
                    null
                }
                _state.update { it.copy(formats = it.formats + (partition.backupName to format)) }
            }
        }
    }

    fun verify() {
        val state = _state.value
        val destination = state.destination ?: return
        val backup = state.selected ?: return
        if (verifyJob?.isActive == true) return
        val total = backup.partitions.flatMap { it.parts }.map { it.sizeBytes }.takeIf { sizes -> sizes.all { it != null } }?.sumOf { it ?: 0L }
        _state.update { it.copy(verify = TwrpVerifyUiState(running = true, totalBytes = total)) }
        val done = AtomicLong(0L)
        verifyJob = viewModelScope.launch {
            val ticker = launch {
                while (true) {
                    delay(PROGRESS_TICK_MILLIS)
                    val now = done.get()
                    _state.update { s -> if (s.verify.running) s.copy(verify = s.verify.copy(doneBytes = now)) else s }
                }
            }
            try {
                val checks = importer.verify(destination, backup) { delta -> done.addAndGet(delta) }
                _state.update { it.copy(verify = it.verify.copy(running = false, doneBytes = done.get(), checks = checks)) }
            } catch (e: CancellationException) {
                _state.update { it.copy(verify = it.verify.copy(running = false)) }
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "TWRP verify failed", e)
                _state.update { it.copy(verify = it.verify.copy(running = false, failure = BackupUnlocker.failureOf(e))) }
            } finally {
                ticker.cancel()
            }
        }
    }

    fun cancelVerify() {
        verifyJob?.cancel()
    }

    fun startDraft(partition: TwrpPartitionBackup, action: TwrpAction) {
        val state = _state.value
        val backup = state.selected ?: return
        setDraft(
            TwrpActionDraft(
                folderPath = backup.folderPath,
                backupName = partition.backupName,
                action = action,
                extractDestinationId = state.destinationId,
                extractFolder = TwrpImportRules.defaultExtractFolder(backup.name, partition),
            ),
        )
    }

    fun editDraft(transform: (TwrpActionDraft) -> TwrpActionDraft) {
        _state.value.draft?.let { setDraft(transform(it)) }
    }

    fun cancelDraft() = setDraft(null)

    /** Runs the configured action; the screen asked for the final confirmation already. */
    fun runDraft() {
        val state = uiState.value
        val draft = state.draft ?: return
        val partition = state.draftPartition ?: return
        val backup = state.selected ?: return
        val source = state.destination ?: return
        if (state.draftProblems.isNotEmpty() || state.taskActive) return
        when (draft.action) {
            TwrpAction.RESTORE_IMAGE -> {
                val target = TwrpImportRules.targetPartition(draft, partition)
                runner.restoreImage(source, backup, partition, draft.overrides.toOptions(), target, title = "${backup.name} · $target")
            }
            TwrpAction.EXTRACT_FILES -> {
                val target = state.destinations?.firstOrNull { it.id == draft.extractDestinationId } ?: return
                val folder = TwrpImportRules.normalizeFolder(draft.extractFolder)
                runner.extract(source, backup, partition, target, folder, title = "${backup.name} · ${partition.backupName}")
            }
        }
        setDraft(null)
    }

    fun cancelTask() {
        uiState.value.task?.let { runner.cancel(it.id) }
    }

    fun dismissTask() {
        uiState.value.task?.let { runner.dismiss(it.id) }
    }

    private fun setDraft(draft: TwrpActionDraft?) {
        savedStateHandle[KEY_DRAFT] = draft?.let(TwrpActionDraft::encode)
        _state.update { it.copy(draft = draft) }
    }

    private companion object {
        const val TAG = "TwrpImportVM"
        const val KEY_DESTINATION = "twrpDestination"
        const val KEY_ROOT = "twrpRoot"
        const val KEY_FOLDER = "twrpFolder"
        const val KEY_DRAFT = "twrpDraft"
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val PROGRESS_TICK_MILLIS = 200L
    }
}
