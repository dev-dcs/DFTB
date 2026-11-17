package ca.devdcs.backup.ui.discovery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.core.discovery.files.FileScanner
import ca.devdcs.backup.core.discovery.files.FilterProblem
import ca.devdcs.backup.core.discovery.files.ScanEvent
import ca.devdcs.backup.core.discovery.files.ScanOptions
import ca.devdcs.backup.core.discovery.files.ScanProgress
import ca.devdcs.backup.core.discovery.files.ScanResult
import ca.devdcs.backup.core.discovery.files.StorageAccessInspector
import ca.devdcs.backup.core.discovery.files.WalkerDecision
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.domain.model.FileCategory
import ca.devdcs.backup.domain.model.PathTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

/** What the preview scans: one folder (path or SAF tree) or the standard roots of some categories. */
enum class SourceMode { FOLDER, CATEGORIES }

/** A chosen folder. [displayName] is what the UI shows; [location] is what the scanner opens. */
data class FolderChoice(val location: String, val displayName: String, val isSaf: Boolean)

/** A shortcut chip for a well-known shared-storage folder; [directory] is null for the storage root. */
data class CommonRoot(val directory: String?, val path: String)

/** Files tab state. [parsed] is always [FileFilterFormParser.parse] of [form]. */
data class FilesUiState(
    val mode: SourceMode = SourceMode.FOLDER,
    val folder: FolderChoice? = null,
    val categories: Set<FileCategory> = emptySet(),
    val recursive: Boolean = true,
    val commonRoots: List<CommonRoot> = emptyList(),
    val form: FileFilterForm = FileFilterForm(),
    val parsed: ParsedFilterForm = FileFilterFormParser.parse(FileFilterForm(), ZoneId.systemDefault()),
    /** Which walker [folder] would use right now, or what blocks it. Null while unknown. */
    val preflight: WalkerDecision? = null,
    val running: Boolean = false,
    val progress: ScanProgress? = null,
    val result: ScanResult? = null,
    /** The engine rejected the filter (should not happen when [parsed] is valid, but never hidden). */
    val rejectedProblems: List<FilterProblem> = emptyList(),
    val cancelled: Boolean = false,
    val failed: Boolean = false,
) {
    val hasSource: Boolean
        get() = when (mode) {
            SourceMode.FOLDER -> folder != null
            SourceMode.CATEGORIES -> categories.isNotEmpty()
        }

    val canRun: Boolean get() = hasSource && parsed.isValid && !running
}

/**
 * Backs the file-scan preview: location choice (common roots, SAF tree, categories), the filter
 * editor (validated live through [FileFilterFormParser]), and a cancellable [FileScanner] run whose
 * progress and bounded summary are published as they arrive. Nothing is collected per file
 * (ScanOptions.collectFiles stays false), so a preview of a huge tree stays small in memory.
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: FileScanner,
    private val inspector: StorageAccessInspector,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState(commonRoots = commonRoots()))
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()
    private var scanJob: Job? = null
    private var preflightJob: Job? = null

    fun setMode(mode: SourceMode) = _uiState.update { it.copy(mode = mode) }

    fun selectRoot(root: CommonRoot) {
        val name = root.directory ?: root.path
        selectFolder(FolderChoice(root.path, name, isSaf = false))
    }

    /** Result of the system folder picker. Persists the read grant the SAF walker requires. */
    fun onTreePicked(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            DftbLog.w(TAG, "Could not persist read grant for picked tree", e)
        }
        val label = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: IllegalArgumentException) {
            uri.toString()
        }
        selectFolder(FolderChoice(uri.toString(), label, isSaf = true))
    }

    fun toggleCategory(category: FileCategory) = _uiState.update {
        it.copy(categories = if (category in it.categories) it.categories - category else it.categories + category)
    }

    fun setRecursive(recursive: Boolean) = _uiState.update { it.copy(recursive = recursive) }

    fun updateForm(transform: (FileFilterForm) -> FileFilterForm) = _uiState.update { state ->
        val form = transform(state.form)
        state.copy(form = form, parsed = FileFilterFormParser.parse(form, ZoneId.systemDefault()))
    }

    fun resetForm() = updateForm { FileFilterForm() }

    /** Re-evaluates access after the user returns from a permission dialog or Settings. */
    fun onAccessChanged() {
        _uiState.value.folder?.let { refreshPreflight(it.location) }
    }

    /** Intent for this app's "All files access" page (API 30+). */
    fun allFilesAccessIntent(): Intent = inspector.allFilesAccessIntent()

    fun run() {
        val state = _uiState.value
        if (!state.canRun) return
        val filter = state.parsed.filter.takeUnless { it.isEmpty }
        val options = ScanOptions(useDefaultExclusions = state.form.useDefaultExclusions)
        val flow = when (state.mode) {
            SourceMode.FOLDER -> {
                val folder = state.folder ?: return
                scanner.scan(PathTarget(folder.location, state.recursive, filter), options)
            }
            SourceMode.CATEGORIES -> scanner.scanCategories(state.categories, filter, options)
        }
        _uiState.update {
            it.copy(running = true, progress = null, result = null, rejectedProblems = emptyList(), cancelled = false, failed = false)
        }
        scanJob = viewModelScope.launch {
            try {
                flow.collect { event ->
                    when (event) {
                        is ScanEvent.Progress -> _uiState.update { it.copy(progress = event.progress) }
                        is ScanEvent.InvalidFilter -> _uiState.update { it.copy(rejectedProblems = event.problems) }
                        is ScanEvent.Completed -> _uiState.update { it.copy(result = event.result) }
                    }
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(cancelled = true) }
                throw e
            } catch (e: Exception) {
                DftbLog.e(TAG, "File scan preview failed", e)
                _uiState.update { it.copy(failed = true) }
            } finally {
                _uiState.update { it.copy(running = false) }
            }
        }
    }

    fun cancel() {
        scanJob?.cancel()
    }

    private fun selectFolder(choice: FolderChoice) {
        _uiState.update { it.copy(folder = choice, mode = SourceMode.FOLDER, preflight = null) }
        refreshPreflight(choice.location)
    }

    private fun refreshPreflight(location: String) {
        preflightJob?.cancel()
        preflightJob = viewModelScope.launch {
            try {
                val decision = scanner.checkAccess(location)
                _uiState.update { if (it.folder?.location == location) it.copy(preflight = decision) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Access pre-check failed", e)
            }
        }
    }

    private fun commonRoots(): List<CommonRoot> {
        val base = inspector.primaryStoragePath.trimEnd('/')
        val dirs = listOf(
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
        )
        return listOf(CommonRoot(null, base)) + dirs.map { CommonRoot(it, "$base/$it") }
    }

    private companion object {
        const val TAG = "FilesVM"
    }
}
