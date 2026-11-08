package ca.devdcs.backup.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.BuildConfig
import ca.devdcs.backup.R
import ca.devdcs.backup.companion.CompanionModuleBuilder
import ca.devdcs.backup.companion.ModuleBuildResult
import ca.devdcs.backup.companion.ModuleManifest
import ca.devdcs.backup.core.environment.DiagnosticReport
import ca.devdcs.backup.core.environment.EnvironmentProbe
import ca.devdcs.backup.core.environment.ShizukuProbe
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import ca.devdcs.backup.core.progress.JobProgressBus
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.ui.onboarding.DiagnosticsState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject

/** Lifecycle of building the companion module into a user-picked document. */
sealed interface ModuleExportState {
    data object Idle : ModuleExportState
    data object Building : ModuleExportState

    /** The zip was written to [savedAs]; [summary] lists what made it in and what did not. */
    data class Saved(val summary: ModuleBuildSummary, val savedAs: String) : ModuleExportState

    /** Nothing usable was produced or it could not be written; [summary] is null when the build never ran. */
    data class Failed(val reason: ModuleExportFailure, val summary: ModuleBuildSummary?) : ModuleExportState
}

enum class ModuleExportFailure {
    /** The builder produced no zip (see the warnings). */
    BUILD_FAILED,
    /** The zip was built but the chosen document could not be written. */
    WRITE_FAILED,
}

/** The parts of a [ModuleBuildResult] worth keeping once the temporary zip is gone. */
data class ModuleBuildSummary(
    val sizeBytes: Long,
    val includedTools: List<String>,
    val missingTools: List<String>,
    val warnings: List<String>,
) {
    companion object {
        fun of(result: ModuleBuildResult) = ModuleBuildSummary(
            sizeBytes = result.sizeBytes,
            includedTools = result.includedTools,
            missingTools = result.missingTools,
            warnings = result.warnings,
        )
    }
}

/** Everything the operating-mode page renders. */
data class ModeSettingsUiState(
    val currentMode: OperatingMode? = null,
    /** The mode whose diagnostics are shown and that "Switch" would commit; survives process death. */
    val candidate: OperatingMode? = null,
    val diagnostics: DiagnosticsState = DiagnosticsState.Idle,
    val jobsActive: Boolean = false,
    val applying: Boolean = false,
    val moduleExport: ModuleExportState = ModuleExportState.Idle,
    val isLoaded: Boolean = false,
) {
    /** The finished report for exactly [candidate], or null while none is available. */
    val report: DiagnosticReport? get() = (diagnostics as? DiagnosticsState.Done)?.report?.takeIf { it.mode == candidate }

    val verdict: ModeApplyVerdict get() = ModeChangeRules.verdict(currentMode, candidate, report, jobsActive)
}

/**
 * Changes the operating mode after onboarding. The user picks a candidate, the same
 * [EnvironmentProbe] diagnostics as onboarding run for it, and only a candidate whose report is not
 * UNAVAILABLE can be committed (after a confirmation listing its effects) — and never while a job
 * is running, because the executor router switches tiers on the very next command. Also hosts the
 * companion-module builder, which a ROOT_MODULE switch needs before its diagnostics can pass.
 */
@HiltViewModel
class ModeSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val preferences: AppPreferencesRepository,
    private val environmentProbe: EnvironmentProbe,
    private val shizukuProbe: ShizukuProbe,
    private val moduleBuilder: CompanionModuleBuilder,
    private val jobProgressBus: JobProgressBus,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val local = MutableStateFlow(
        LocalState(candidate = OperatingMode.fromStorageKey(savedStateHandle.get<String>(KEY_CANDIDATE))),
    )

    private val _messages = Channel<SettingsMessage>(Channel.BUFFERED)
    val messages: Flow<SettingsMessage> = _messages.receiveAsFlow()

    val uiState: StateFlow<ModeSettingsUiState> = combine(
        preferences.preferences.map { it.operatingMode },
        jobProgressBus.activeJobs.map { jobs -> jobs.values.any { !it.isTerminal } },
        local,
    ) { current, jobsActive, state ->
        ModeSettingsUiState(
            currentMode = current,
            candidate = state.candidate ?: current,
            diagnostics = state.diagnostics,
            jobsActive = jobsActive,
            applying = state.applying,
            moduleExport = state.moduleExport,
            isLoaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ModeSettingsUiState())

    private var diagnosticsJob: Job? = null

    init {
        viewModelScope.launch {
            // First visit: the candidate starts as the persisted mode and its report is shown at once.
            val initial = local.value.candidate ?: preferences.preferences.first().operatingMode
            if (initial != null) {
                setCandidate(initial)
                runDiagnostics(initial)
            }
        }
        viewModelScope.launch {
            shizukuProbe.binderAlive
                .drop(1)
                .catch { error -> DftbLog.w(TAG, "Shizuku binder flow failed", error) }
                .collect { alive ->
                    if (alive && local.value.candidate == OperatingMode.SHIZUKU) rerunDiagnostics()
                }
        }
    }

    fun selectCandidate(mode: OperatingMode) {
        val state = local.value
        if (state.candidate == mode && state.diagnostics is DiagnosticsState.Running) return
        setCandidate(mode)
        runDiagnostics(mode)
    }

    fun rerunDiagnostics() {
        val mode = local.value.candidate ?: return
        runDiagnostics(mode)
    }

    /** ON_RESUME: the user may be back from granting root, Shizuku or All-files access. */
    fun onResumed() {
        if (local.value.diagnostics !is DiagnosticsState.Running && local.value.candidate != null) rerunDiagnostics()
    }

    fun requestShizukuPermission() {
        viewModelScope.launch {
            val granted = try {
                shizukuProbe.requestPermission()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Shizuku permission request failed", e)
                false
            }
            _messages.trySend(
                SettingsMessage(if (granted) R.string.settings_mode_shizuku_granted else R.string.onboarding_shizuku_denied),
            )
            if (local.value.candidate == OperatingMode.SHIZUKU) rerunDiagnostics()
        }
    }

    fun showMessage(messageRes: Int) {
        _messages.trySend(SettingsMessage(messageRes))
    }

    /**
     * Commits the candidate. The verdict is re-evaluated here from the live sources (persisted
     * mode, job bus, latest report) rather than from the rendered state, so a job that started
     * while the confirm dialog was open still blocks the switch.
     */
    fun applyCandidate() {
        val state = local.value
        val candidate = state.candidate ?: return
        if (state.applying) return
        local.update { it.copy(applying = true) }
        viewModelScope.launch {
            try {
                val current = preferences.preferences.first().operatingMode
                val jobsActive = jobProgressBus.activeJobs.value.values.any { !it.isTerminal }
                val report = (local.value.diagnostics as? DiagnosticsState.Done)?.report?.takeIf { it.mode == candidate }
                when (ModeChangeRules.verdict(current, candidate, report, jobsActive)) {
                    ModeApplyVerdict.READY -> {
                        preferences.setOperatingMode(candidate)
                        DftbLog.i(TAG, "Operating mode changed: $current -> $candidate")
                        _messages.trySend(SettingsMessage(R.string.settings_mode_changed, listOf(MessageArg.Res(candidate.labelRes))))
                    }
                    ModeApplyVerdict.JOBS_RUNNING -> _messages.trySend(SettingsMessage(R.string.settings_mode_blocked_jobs))
                    ModeApplyVerdict.UNAVAILABLE -> _messages.trySend(SettingsMessage(R.string.settings_mode_reason_unavailable))
                    ModeApplyVerdict.NO_DIAGNOSTICS -> _messages.trySend(SettingsMessage(R.string.settings_mode_reason_no_diagnostics))
                    ModeApplyVerdict.SAME_MODE -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.e(TAG, "Could not persist operating mode", e)
                _messages.trySend(SettingsMessage(R.string.settings_save_failed))
            } finally {
                local.update { it.copy(applying = false) }
            }
        }
    }

    /** Suggested file name for the ACTION_CREATE_DOCUMENT picker. */
    fun suggestedModuleFileName(): String = "${ModuleManifest.MODULE_ID}-${BuildConfig.VERSION_NAME}.zip"

    /**
     * Builds the module into a private temp file, then copies it into [target] (the document the
     * user just created). A failed build or copy deletes the empty document so no broken zip is
     * left behind for someone to flash.
     */
    fun exportModule(target: Uri) {
        if (local.value.moduleExport is ModuleExportState.Building) return
        local.update { it.copy(moduleExport = ModuleExportState.Building) }
        viewModelScope.launch {
            val outcome = try {
                buildAndCopy(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.e(TAG, "Companion module export failed", e)
                ModuleExportState.Failed(ModuleExportFailure.WRITE_FAILED, null)
            }
            if (outcome is ModuleExportState.Failed) discardDocument(target)
            local.update { it.copy(moduleExport = outcome) }
        }
    }

    fun dismissModuleResult() {
        if (local.value.moduleExport is ModuleExportState.Building) return
        local.update { it.copy(moduleExport = ModuleExportState.Idle) }
    }

    private suspend fun buildAndCopy(target: Uri): ModuleExportState {
        val temp = withContext(ioDispatcher) {
            File(File(context.cacheDir, TEMP_DIR), "module-${System.nanoTime()}.zip")
        }
        try {
            val result = moduleBuilder.build(outFile = temp)
            val summary = ModuleBuildSummary.of(result)
            val built = result.path
            if (!result.produced || built == null) return ModuleExportState.Failed(ModuleExportFailure.BUILD_FAILED, summary)
            val savedAs = try {
                withContext(ioDispatcher) {
                    openForWrite(target).use { out -> built.inputStream().use { input -> input.copyTo(out) } }
                    DocumentFile.fromSingleUri(context, target)?.name ?: target.lastPathSegment ?: suggestedModuleFileName()
                }
            } catch (e: IOException) {
                DftbLog.w(TAG, "Could not write the module to the chosen document", e)
                return ModuleExportState.Failed(ModuleExportFailure.WRITE_FAILED, summary)
            } catch (e: SecurityException) {
                DftbLog.w(TAG, "No permission to write the module to the chosen document", e)
                return ModuleExportState.Failed(ModuleExportFailure.WRITE_FAILED, summary)
            }
            DftbLog.i(TAG, "Companion module saved (${summary.sizeBytes} bytes, missing=${summary.missingTools})")
            return ModuleExportState.Saved(summary, savedAs)
        } finally {
            withContext(ioDispatcher) { if (temp.exists() && !temp.delete()) DftbLog.w(TAG, "Temp module zip not deleted") }
        }
    }

    /** "wt" truncates on providers that support it; a freshly created document is empty either way. */
    private fun openForWrite(target: Uri): OutputStream {
        val resolver = context.contentResolver
        return try {
            resolver.openOutputStream(target, "wt")
        } catch (e: FileNotFoundException) {
            resolver.openOutputStream(target, "w")
        } catch (e: IllegalArgumentException) {
            resolver.openOutputStream(target, "w")
        } ?: throw IOException("The document provider returned no output stream")
    }

    private suspend fun discardDocument(target: Uri) {
        withContext(ioDispatcher) {
            try {
                DocumentsContract.deleteDocument(context.contentResolver, target)
            } catch (e: Exception) {
                DftbLog.d(TAG, "Could not delete the incomplete module document: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun setCandidate(mode: OperatingMode) {
        savedStateHandle[KEY_CANDIDATE] = mode.storageKey
        local.update { it.copy(candidate = mode) }
    }

    private fun runDiagnostics(mode: OperatingMode) {
        diagnosticsJob?.cancel()
        local.update { it.copy(diagnostics = DiagnosticsState.Running(mode)) }
        diagnosticsJob = viewModelScope.launch {
            val result = runCatching { environmentProbe.diagnose(mode) }
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
            // Ignore a stale answer if the user switched candidates while this ran.
            if (local.value.candidate != mode) return@launch
            val report = result.getOrNull()
            local.update {
                it.copy(
                    diagnostics = if (report != null) {
                        DiagnosticsState.Done(report)
                    } else {
                        DftbLog.e(TAG, "Diagnostics for $mode threw", error)
                        DiagnosticsState.Failed(mode, error?.message)
                    },
                )
            }
        }
    }

    private data class LocalState(
        val candidate: OperatingMode?,
        val diagnostics: DiagnosticsState = DiagnosticsState.Idle,
        val applying: Boolean = false,
        val moduleExport: ModuleExportState = ModuleExportState.Idle,
    )

    private companion object {
        const val TAG = "SettingsMode"
        const val KEY_CANDIDATE = "settings_mode_candidate"
        const val TEMP_DIR = "companion_export"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
