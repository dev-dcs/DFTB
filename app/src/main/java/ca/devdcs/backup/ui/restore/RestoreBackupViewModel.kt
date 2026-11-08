package ca.devdcs.backup.ui.restore

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.engine.BackupManifest
import ca.devdcs.backup.core.pipeline.engine.DeviceInfoProvider
import ca.devdcs.backup.core.pipeline.engine.ManifestDevice
import ca.devdcs.backup.core.restore.BackupCatalog
import ca.devdcs.backup.core.restore.BackupSummary
import ca.devdcs.backup.core.restore.OpenedBackup
import ca.devdcs.backup.core.restore.apps.InstalledPackageQuery
import ca.devdcs.backup.core.restore.catalog.CatalogOpenedBackup
import ca.devdcs.backup.core.restore.engine.RestoreUnitKind
import ca.devdcs.backup.core.restore.files.RestoreTargetOpener
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.domain.repository.ProfileRepository
import ca.devdcs.backup.ui.destinations.DestinationHousekeeping
import ca.devdcs.backup.ui.navigation.DftbDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/** Where opening the backup stands. */
enum class LockState { OPENING, NEEDS_PASSPHRASE, UNLOCKING, OPEN, FAILED }

/** What the overview shows about an opened backup. */
data class BackupDetails(
    val summary: BackupSummary,
    val manifest: BackupManifest,
    val unlockedWithVaultKey: Boolean,
    val unlockedWithPassphrase: Boolean,
    /** Manifest files merged (resumed runs). */
    val runCount: Int,
)

/** One artifact's verification outcome. */
data class VerifiedArtifact(
    val name: String,
    val unitId: String,
    val kind: RestoreUnitKind,
    val ok: Boolean,
    val storedBytes: Long?,
    val problem: ArtifactProblem?,
)

/** Quick / deep verification progress and results, kept per screen (verification is read-only). */
data class VerifyUiState(
    val running: Boolean = false,
    val deep: Boolean = false,
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val results: List<VerifiedArtifact>? = null,
    val failure: OpenFailure? = null,
) {
    val summary: VerifySummary? get() = results?.let { list -> VerifySummary(list.size, list.count { it.ok }) }
}

/** The dry-run preflight of the current wizard input; reset whenever an option changes. */
data class PreflightUiState(
    val running: Boolean = false,
    val report: PreflightReport? = null,
    val failure: OpenFailure? = null,
)

/** Everything the backup screen renders. */
data class RestoreBackupUiState(
    val destinationId: String,
    val jobDir: String,
    val destination: Destination? = null,
    val lock: LockState = LockState.OPENING,
    val openFailure: OpenFailure? = null,
    val passphraseError: OpenFailure? = null,
    val passphraseDismissed: Boolean = false,
    /** Why the saved key of the producing profile did not open it (missing / unreadable vault entry). */
    val vaultProblem: OpenFailure? = null,
    val details: BackupDetails? = null,
    val units: List<RestoreUnitInfo> = emptyList(),
    val groups: RestoreUnitGroups = RestoreUnitGroups(emptyList(), emptyList(), emptyList()),
    val thisDevice: ManifestDevice? = null,
    val tier: ElevationTier = ElevationTier.NONE,
    val callerUserId: Int = 0,
    val users: List<Int> = emptyList(),
    val verify: VerifyUiState = VerifyUiState(),
    val input: RestoreWizardInput = RestoreWizardInput(),
    val preflight: PreflightUiState = PreflightUiState(),
    val run: RestoreRunState? = null,
    val primaryStorageRoot: String = "",
) {
    val problems: List<WizardProblem> get() = RestoreWizardRules.validate(input, units)
    val selectedUnits: List<RestoreUnitInfo> get() = RestoreWizardRules.selectedUnits(input, units)
    val showPassphraseDialog: Boolean get() = (lock == LockState.NEEDS_PASSPHRASE || lock == LockState.UNLOCKING) && !passphraseDismissed
    val runActive: Boolean get() = run != null && !run.finished
    val canStart: Boolean
        get() {
            val report = preflight.report ?: return false
            return lock == LockState.OPEN && !runActive && !preflight.running &&
                RestoreWizardRules.blocking(problems).isEmpty() &&
                PreflightRules.blocker(report.checks, input.ignoreInsufficientSpace) == null
        }

    /** Selected identity partitions, for the CRITICAL_IDENTITY confirmation. */
    val criticalSelected: List<String> get() = selectedUnits.filter { it.criticalIdentity }.map { it.displayName }
}

/** One-shot messages for a snackbar. */
sealed interface RestoreBackupEvent {
    data object TreeGrantFailed : RestoreBackupEvent
    data object RestoreStarted : RestoreBackupEvent
}

/**
 * Backs one backup's screen: opens it (vault key automatically, else a passphrase held only in
 * memory and wiped on clear), shows details and units, verifies it (quick / deep) and drives the
 * restore wizard — selection, options, dry-run preflight — then hands the run to
 * [RestoreRunCoordinator] so it survives leaving the screen. Wizard input lives in
 * SavedStateHandle; key material and the opened backup never do.
 */
@HiltViewModel
class RestoreBackupViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val destinations: DestinationRepository,
    private val profiles: ProfileRepository,
    private val unlocker: BackupUnlocker,
    private val catalog: BackupCatalog,
    private val preflighter: RestorePreflighter,
    private val coordinator: RestoreRunCoordinator,
    private val deviceInfo: DeviceInfoProvider,
    private val router: CommandExecutorRouter,
    private val packages: InstalledPackageQuery,
    private val targets: RestoreTargetOpener,
    private val housekeeping: DestinationHousekeeping,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<DftbDestination.RestoreBackup>()
    private val _state = MutableStateFlow(
        RestoreBackupUiState(
            destinationId = route.destinationId,
            jobDir = route.jobDir,
            input = RestoreWizardInput.decode(savedStateHandle.get<String>(KEY_INPUT)),
        ),
    )

    val uiState: StateFlow<RestoreBackupUiState> = combine(_state, coordinator.runs) { state, runs ->
        val run = runs.values.filter { it.destinationId == route.destinationId && it.jobDir == route.jobDir }.maxByOrNull { it.startedAtMillis }
        state.copy(run = run)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), _state.value)

    private val _events = Channel<RestoreBackupEvent>(Channel.BUFFERED)
    val events: Flow<RestoreBackupEvent> = _events.receiveAsFlow()

    /** The opened backup and its key; both are private to this ViewModel and released in [onCleared]. */
    private var opened: OpenedBackup? = null
    private var key: CipherKeySpec? = null
    private var verifyJob: Job? = null
    private var preflightJob: Job? = null
    @Volatile private var cleared = false
    /** Guards against a double tap starting the same restore twice before the run is visible. */
    private var starting = false

    init {
        viewModelScope.launch { loadEnvironment() }
        open()
    }

    // --- opening -------------------------------------------------------------------------------

    fun retryOpen() = open()

    private fun open() {
        _state.update { it.copy(lock = LockState.OPENING, openFailure = null) }
        viewModelScope.launch {
            val destination = try {
                withContext(io) { destinations.get(route.destinationId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Destination lookup failed", e)
                null
            }
            if (destination == null) {
                _state.update { it.copy(lock = LockState.FAILED, openFailure = OpenFailure.DestinationMissing) }
                return@launch
            }
            _state.update { it.copy(destination = destination) }
            try {
                when (val result = unlocker.openAutomatically(destination, route.jobDir)) {
                    is UnlockResult.Opened -> adopt(result)
                    is UnlockResult.NeedsPassphrase -> _state.update {
                        it.copy(lock = LockState.NEEDS_PASSPHRASE, vaultProblem = result.vaultProblem, passphraseDismissed = false)
                    }
                    is UnlockResult.Failed -> _state.update { it.copy(lock = LockState.FAILED, openFailure = result.failure) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                DftbLog.w(TAG, "Opening the backup failed", e)
                _state.update { it.copy(lock = LockState.FAILED, openFailure = BackupUnlocker.failureOf(e)) }
            }
        }
    }

    /** Unlock with a typed passphrase; [chars] is wiped here once the unlocker took its copy. */
    fun unlock(chars: CharArray) {
        val destination = _state.value.destination
        if (destination == null || _state.value.lock == LockState.UNLOCKING) {
            chars.fill('\u0000')
            return
        }
        _state.update { it.copy(lock = LockState.UNLOCKING, passphraseError = null) }
        viewModelScope.launch {
            val result = try {
                unlocker.openWithPassphrase(destination, route.jobDir, chars)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                DftbLog.w(TAG, "Unlocking the backup failed", e)
                UnlockResult.Failed(BackupUnlocker.failureOf(e))
            } finally {
                chars.fill('\u0000')
            }
            when (result) {
                is UnlockResult.Opened -> adopt(result)
                is UnlockResult.NeedsPassphrase -> _state.update { it.copy(lock = LockState.NEEDS_PASSPHRASE) }
                is UnlockResult.Failed -> {
                    val keyProblem = result.failure is OpenFailure.Cipher || result.failure is OpenFailure.KeyUnavailable
                    _state.update {
                        if (keyProblem) {
                            it.copy(lock = LockState.NEEDS_PASSPHRASE, passphraseError = result.failure)
                        } else {
                            it.copy(lock = LockState.FAILED, openFailure = result.failure)
                        }
                    }
                }
            }
        }
    }

    fun dismissPassphrase() = _state.update { it.copy(passphraseDismissed = true) }

    fun showPassphrase() = _state.update { it.copy(passphraseDismissed = false) }

    private fun adopt(result: UnlockResult.Opened) {
        if (cleared || opened != null) {
            // A late result after the screen closed, or a duplicate open: keep nothing.
            result.backup.close()
            BackupUnlocker.wipeKey(result.key)
            return
        }
        opened = result.backup
        key = result.key
        val backup = result.backup
        val units = RestoreUnitGrouping.units(backup.manifest)
        val details = BackupDetails(
            summary = backup.summary,
            manifest = backup.manifest,
            unlockedWithVaultKey = result.viaVault,
            unlockedWithPassphrase = result.key != null && !result.viaVault,
            runCount = (backup as? CatalogOpenedBackup)?.runs?.size ?: 1,
        )
        _state.update { state ->
            val input = if (state.input.selected == null) state.input.copy(selected = units.map { it.id }.toSet()) else state.input
            state.copy(
                lock = LockState.OPEN,
                openFailure = null,
                passphraseError = null,
                details = details,
                units = units,
                groups = RestoreUnitGrouping.group(units),
                input = input,
            )
        }
        persistInput()
    }

    private suspend fun loadEnvironment() {
        val device = try {
            deviceInfo.device()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val executor = try {
            router.current().takeIf { it.isAvailable() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val tier = executor?.tier ?: ElevationTier.NONE
        val caller = packages.callerUserId
        val users = executor?.takeIf { tier != ElevationTier.NONE }?.let { shell ->
            try {
                packages.userIds(shell)?.sorted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } ?: listOf(caller)
        val root = withContext(io) { targets.primaryStorageRoot() }
        _state.update { it.copy(thisDevice = device, tier = tier, callerUserId = caller, users = users, primaryStorageRoot = root) }
    }

    // --- verify --------------------------------------------------------------------------------

    fun verify(deep: Boolean) {
        val backup = opened ?: return
        if (verifyJob?.isActive == true) return
        val total = RestoreUnitGrouping.saturatingSum(backup.manifest.artifacts.map { it.sizeBytes })
        _state.update { it.copy(verify = VerifyUiState(running = true, deep = deep, totalBytes = total)) }
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
                val checks = withContext(io) { catalog.verify(backup, deep) { bytes -> done.set(bytes) } }
                val results = checks.map { check ->
                    val a = check.artifact
                    VerifiedArtifact(
                        name = a.name ?: a.storedName,
                        unitId = a.sourceId,
                        kind = RestoreUnitKind.of(a),
                        ok = check.ok,
                        storedBytes = check.storedBytes,
                        problem = ArtifactProblemParser.parse(check.problem),
                    )
                }
                _state.update { it.copy(verify = it.verify.copy(running = false, doneBytes = done.get(), results = results)) }
            } catch (e: CancellationException) {
                _state.update { it.copy(verify = it.verify.copy(running = false)) }
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Verify failed", e)
                _state.update { it.copy(verify = it.verify.copy(running = false, failure = BackupUnlocker.failureOf(e))) }
            } finally {
                ticker.cancel()
            }
        }
    }

    fun cancelVerify() {
        verifyJob?.cancel()
    }

    // --- wizard --------------------------------------------------------------------------------

    fun startWizard() = setStep(RestoreStep.SELECT)

    fun back() = setStep(RestoreWizardRules.previous(_state.value.input.step))

    fun next() {
        val state = _state.value
        val target = RestoreWizardRules.next(state.input.step, state.problems)
        setStep(target)
        if (target == RestoreStep.PREFLIGHT) runPreflight()
    }

    fun setStep(step: RestoreStep) {
        _state.update { it.copy(input = it.input.copy(step = step)) }
        persistInput()
    }

    fun toggleUnit(unitId: String) = editInput { RestoreWizardRules.toggleUnit(it, _state.value.units, unitId) }

    fun toggleKind(kind: RestoreUnitKind) = editInput { RestoreWizardRules.toggleKind(it, _state.value.units, kind) }

    fun selectAll(all: Boolean) = editInput { input -> input.copy(selected = if (all) _state.value.units.map { it.id }.toSet() else emptySet()) }

    /** Any option edit; invalidates a preflight made with the previous options. */
    fun editInput(transform: (RestoreWizardInput) -> RestoreWizardInput) {
        _state.update { state ->
            val next = transform(state.input)
            if (next == state.input) state else state.copy(input = next, preflight = PreflightUiState())
        }
        preflightJob?.cancel()
        persistInput()
    }

    fun setFileTarget(kind: FileTargetKind) = editInput { input ->
        val folder = if (kind == FileTargetKind.FOLDER && input.folderPath.isBlank()) defaultFolder() else input.folderPath
        input.copy(fileTarget = kind, folderPath = folder)
    }

    fun setRisky(option: RiskyOption, on: Boolean) = editInput { it.with(option, on) }

    /** A folder came back from the document-tree picker: keep a persisted write grant for the run. */
    fun onTreePicked(uri: Uri) {
        viewModelScope.launch {
            try {
                housekeeping.takeTree(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                DftbLog.w(TAG, "Could not persist the restore folder grant", e)
                _events.trySend(RestoreBackupEvent.TreeGrantFailed)
                return@launch
            }
            val previous = _state.value.input
            val text = uri.toString()
            if (previous.treeGrantedHere && previous.treeUri != null && previous.treeUri != text) releaseTree(previous.treeUri)
            editInput { it.copy(fileTarget = FileTargetKind.SAF_TREE, treeUri = text, treeGrantedHere = true) }
        }
    }

    private fun defaultFolder(): String {
        val root = _state.value.primaryStorageRoot.ifBlank { DEFAULT_STORAGE_ROOT }
        val name = route.jobDir.trimEnd('/').substringAfterLast('/').ifBlank { "backup" }
        return "$root/$RESTORE_FOLDER/$name"
    }

    // --- preflight & run -----------------------------------------------------------------------

    fun runPreflight() {
        val backup = opened ?: return
        val state = _state.value
        val selected = state.selectedUnits
        if (selected.isEmpty()) return
        preflightJob?.cancel()
        _state.update { it.copy(preflight = PreflightUiState(running = true)) }
        val input = state.input
        preflightJob = viewModelScope.launch {
            try {
                val report = preflighter.check(backup, selected, RestoreWizardRules.options(input), RestoreWizardRules.fileSpec(input))
                _state.update { if (sameOptions(it.input, input)) it.copy(preflight = PreflightUiState(report = report)) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Preflight failed", e)
                _state.update { it.copy(preflight = PreflightUiState(failure = BackupUnlocker.failureOf(e))) }
            }
        }
    }

    private fun sameOptions(a: RestoreWizardInput, b: RestoreWizardInput): Boolean = a.copy(step = RestoreStep.DETAILS) == b.copy(step = RestoreStep.DETAILS)

    fun startRestore() {
        val state = _state.value.copy(run = coordinator.latestFor(route.destinationId, route.jobDir))
        if (starting || !state.canStart) return
        val details = state.details ?: return
        val units = state.selectedUnits
        val input = state.input
        starting = true
        viewModelScope.launch {
            try {
                val profileId = details.manifest.profile.id.takeIf { id ->
                    try {
                        withContext(io) { profiles.get(id) } != null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                }
                val usesTree = input.fileTarget == FileTargetKind.SAF_TREE && units.any { it.kind == RestoreUnitKind.FILE_TREE }
                coordinator.start(
                    RestoreRunSpec(
                        destinationId = route.destinationId,
                        jobDir = route.jobDir,
                        key = key,
                        units = units,
                        fileSpec = RestoreWizardRules.fileSpec(input),
                        options = RestoreWizardRules.options(input),
                        profileId = profileId,
                        title = details.manifest.profile.name,
                        releaseTreeUri = input.treeUri.takeIf { usesTree && input.treeGrantedHere },
                    ),
                )
                // The run owns the grant now; the wizard must not release it on exit.
                _state.update { it.copy(input = it.input.copy(step = RestoreStep.DETAILS, treeGrantedHere = if (usesTree) false else it.input.treeGrantedHere), preflight = PreflightUiState()) }
                persistInput()
                _events.trySend(RestoreBackupEvent.RestoreStarted)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                DftbLog.w(TAG, "Starting the restore failed", e)
                _state.update { it.copy(preflight = PreflightUiState(failure = BackupUnlocker.failureOf(e))) }
            } finally {
                starting = false
            }
        }
    }

    fun cancelRestore() {
        uiState.value.run?.let { coordinator.cancel(it.jobId) }
    }

    fun dismissRun() {
        uiState.value.run?.let { coordinator.dismiss(it.jobId) }
    }

    // --- housekeeping --------------------------------------------------------------------------

    private fun persistInput() {
        savedStateHandle[KEY_INPUT] = RestoreWizardInput.encode(_state.value.input)
    }

    private fun releaseTree(uri: String) {
        if (coordinator.usesTree(uri)) return
        appScope.launch {
            try {
                housekeeping.releaseTreeIfUnused(uri)
            } catch (e: Exception) {
                DftbLog.w(TAG, "Could not release folder grant", e)
            }
        }
    }

    override fun onCleared() {
        cleared = true
        verifyJob?.cancel()
        preflightJob?.cancel()
        opened?.close()
        opened = null
        BackupUnlocker.wipeKey(key)
        key = null
        val input = _state.value.input
        val tree = input.treeUri
        if (tree != null && input.treeGrantedHere) releaseTree(tree)
        super.onCleared()
    }

    private companion object {
        const val TAG = "RestoreBackupVM"
        const val KEY_INPUT = "restoreWizard"
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val PROGRESS_TICK_MILLIS = 200L
        const val RESTORE_FOLDER = "DFTB-Restore"
        const val DEFAULT_STORAGE_ROOT = "/storage/emulated/0"
    }
}
