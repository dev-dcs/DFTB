package ca.devdcs.backup.ui.profiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import ca.devdcs.backup.R
import ca.devdcs.backup.core.boot.ResumePlanner
import ca.devdcs.backup.core.discovery.apps.AppInspector
import ca.devdcs.backup.core.discovery.apps.ComponentAvailability
import ca.devdcs.backup.core.discovery.apps.InstalledApp
import ca.devdcs.backup.core.discovery.files.FileScanner
import ca.devdcs.backup.core.discovery.files.StorageAccessInspector
import ca.devdcs.backup.core.discovery.files.WalkerDecision
import ca.devdcs.backup.core.discovery.partition.PartitionGroup
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.crypto.VaultKeyStore
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import ca.devdcs.backup.core.schedule.BackupScheduler
import ca.devdcs.backup.core.storage.PreflightResult
import ca.devdcs.backup.domain.model.AppComponent
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.FileCategory
import ca.devdcs.backup.domain.model.PathTarget
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.SlotPolicy
import ca.devdcs.backup.domain.model.TargetSelection
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.domain.repository.ProfileRepository
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.VaultException
import ca.devdcs.backup.ui.discovery.CommonRoot
import ca.devdcs.backup.ui.discovery.FileFilterForm
import ca.devdcs.backup.ui.discovery.FileFilterFormParser
import ca.devdcs.backup.ui.discovery.ParsedFilterForm
import ca.devdcs.backup.ui.navigation.DftbDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** What an estimate was computed for; a changed key means the shown numbers are stale. */
data class EstimateKey(val targets: TargetSelection, val pipeline: PipelineOptions, val destinationId: String?)

/** Size estimate progress of the review / destination pages. */
sealed interface EstimateState {
    data object Idle : EstimateState
    data object Running : EstimateState
    data class Done(val key: EstimateKey, val estimate: PreflightEstimate) : EstimateState
    data class Failed(val key: EstimateKey, val detail: String?) : EstimateState
}

/** Destination free-space check against the estimate. */
sealed interface SpaceCheckState {
    data object Idle : SpaceCheckState
    data object Running : SpaceCheckState
    data class Done(val key: EstimateKey, val requiredBytes: Long, val result: PreflightResult) : SpaceCheckState
}

/** The folder-filter dialog: which folder, the raw form, and its live parse. */
data class FilterEditorState(val location: String, val form: FileFilterForm, val parsed: ParsedFilterForm)

/** Wizard screen state. [draft] is null while loading (or when the edited profile is gone). */
data class WizardUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val draft: ProfileDraft? = null,
    val problems: List<DraftProblem> = emptyList(),
    val destinations: List<Destination>? = null,
    val availability: Map<AppComponent, ComponentAvailability> = emptyMap(),
    val tier: ElevationTier = ElevationTier.NONE,
    val pathAccess: Map<String, WalkerDecision> = emptyMap(),
    val commonRoots: List<CommonRoot> = emptyList(),
    val estimateKey: EstimateKey? = null,
    val estimate: EstimateState = EstimateState.Idle,
    val spaceCheck: SpaceCheckState = SpaceCheckState.Idle,
    val saving: Boolean = false,
    /** Saved profile whose "save & run now" waits for a typed passphrase. */
    val passphrasePrompt: BackupProfile? = null,
    val confirmDiscard: Boolean = false,
    val filterEditor: FilterEditorState? = null,
) {
    val step: WizardStep get() = draft?.step ?: WizardStep.NAME

    val selectedDestination: Destination? get() = draft?.destinationId?.let { id -> destinations?.firstOrNull { it.id == id } }

    fun problemsFor(step: WizardStep): List<DraftProblem> = DraftValidator.forStep(problems, step)

    /** Problems of [step] the user should see now (errors only after they tried to move on). */
    fun visibleProblemsFor(step: WizardStep): List<DraftProblem> {
        val all = problemsFor(step)
        return if (draft != null && step in draft.showErrorsFor) all else all.filterNot { it.isError }
    }

    /** True when [step] may be opened from the step list. */
    fun canOpen(step: WizardStep): Boolean {
        val d = draft ?: return false
        if (!d.isNew || step in d.visited) return true
        val firstInvalid = DraftValidator.firstInvalidStep(problems) ?: WizardStep.REVIEW
        return step.ordinal <= firstInvalid.ordinal
    }

    /** The estimate for the current draft, or null when none was computed or it is stale. */
    val currentEstimate: PreflightEstimate?
        get() = (estimate as? EstimateState.Done)?.takeIf { it.key == estimateKey }?.estimate

    val currentSpaceCheck: SpaceCheckState.Done?
        get() = (spaceCheck as? SpaceCheckState.Done)?.takeIf { it.key == estimateKey }
}

/** One-shot wizard outcomes. */
sealed interface WizardEvent {
    /** Saved; leave the wizard. */
    data object Saved : WizardEvent

    /** Saved and a run was started (as [jobId] when known); open the job monitor. */
    data class SavedAndRunning(val jobId: String?) : WizardEvent

    /** Leave without saving (nothing changed, or the user confirmed the discard). */
    data object Exit : WizardEvent

    data class Message(@StringRes val messageRes: Int) : WizardEvent
}

/**
 * Backs the six-step profile wizard. The whole draft (including half-typed fields, the current
 * step and the open filter dialog) lives in the [SavedStateHandle] as JSON, so the wizard resumes
 * after process death. Validation is [DraftValidator]; option rules are [PipelineRules]; estimates
 * come from [ProfilePreflight]. Saving resolves the encryption key (creating a device vault key on
 * first use; existing keys are reused, never deleted), persists through [ProfileRepository] and
 * re-arms the schedule with [BackupScheduler.schedule].
 */
@HiltViewModel
class ProfileWizardViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val destinationRepository: DestinationRepository,
    private val scheduler: BackupScheduler,
    private val vaultKeyStore: VaultKeyStore,
    private val vault: CredentialVault,
    private val preferences: AppPreferencesRepository,
    private val appInspector: AppInspector,
    private val fileScanner: FileScanner,
    private val storageInspector: StorageAccessInspector,
    private val preflight: ProfilePreflight,
    private val passphraseRunner: PassphraseBackupRunner,
    private val clock: EngineClock,
    router: CommandExecutorRouter,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<DftbDestination.ProfileWizard>()

    private val draft = MutableStateFlow(savedStateHandle.get<String>(KEY_DRAFT)?.let(::decodeDraft))
    private val work = MutableStateFlow(
        WizardUiState(
            loading = draft.value == null,
            commonRoots = commonRoots(),
            filterEditor = savedStateHandle.get<String>(KEY_FILTER_EDITOR)?.let(::decodeEditor),
        ),
    )
    private val vaultKeyMissing = MutableStateFlow(false)

    private val _events = Channel<WizardEvent>(Channel.BUFFERED)
    val events: Flow<WizardEvent> = _events.receiveAsFlow()

    private val external = combine(
        destinationRepository.observeAll().catch { e ->
            DftbLog.e(TAG, "Could not read the destinations", e)
            emit(emptyList())
        },
        profileRepository.observeAll().catch { e ->
            DftbLog.e(TAG, "Could not read the profiles", e)
            emit(emptyList())
        },
        router.activeTier,
    ) { destinations, profiles, tier -> External(destinations, profiles, tier) }

    val uiState: StateFlow<WizardUiState> = combine(draft, work, external, vaultKeyMissing) { d, w, ext, keyMissing ->
        val problems = if (d == null) emptyList() else DraftValidator.validate(d, validationContext(d, w, ext, keyMissing))
        w.copy(
            draft = d,
            problems = problems,
            destinations = ext.destinations,
            tier = ext.tier,
            estimateKey = d?.let { estimateKeyOf(it) },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, work.value)

    private var estimateJob: Job? = null
    private var spaceJob: Job? = null
    private val accessJobs = HashMap<String, Job>()

    init {
        if (draft.value == null) load() else afterLoad()
        refreshAvailability()
        watchNewDestinations()
    }

    // ------------------------------------------------------------------ loading

    private fun load() {
        viewModelScope.launch {
            try {
                val id = route.profileId
                val loaded = if (id == null) {
                    newDraft()
                } else {
                    profileRepository.get(id)?.let { ProfileDraft.fromProfile(it, WizardStep.fromIndex(route.step)) }
                }
                if (loaded == null) {
                    work.update { it.copy(loading = false, notFound = true) }
                    return@launch
                }
                setDraft(loaded)
                work.update { it.copy(loading = false) }
                applyDefaultComponents()
                afterLoad()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.e(TAG, "Could not open the profile wizard", e)
                work.update { it.copy(loading = false, notFound = true) }
            }
        }
    }

    private suspend fun newDraft(): ProfileDraft {
        val prefs = preferences.preferences.first()
        val destinations = destinationRepository.getAll()
        val destinationId = prefs.defaultDestinationId?.takeIf { id -> destinations.any { it.id == id } }
            ?: destinations.singleOrNull()?.id
        val defaults = DraftDefaults(
            archiveFormat = prefs.defaultArchiveFormat,
            compressionLevel = prefs.defaultCompressionLevel,
            checksumAlgorithm = prefs.defaultChecksumAlgorithm,
            destinationId = destinationId,
        )
        return ProfileDraft.create(UUID.randomUUID().toString(), clock.nowMillis(), defaults)
    }

    /** Checks the vault key of the loaded profile and the access of its folders. */
    private fun afterLoad() {
        val d = draft.value ?: return
        val ref = d.existingKeyRef
        if (ref != null) {
            viewModelScope.launch {
                vaultKeyMissing.value = try {
                    !vault.exists(ref)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DftbLog.w(TAG, "Could not check the profile key in the vault", e)
                    false
                }
            }
        }
        refreshPathAccess(force = false)
    }

    /** Re-checks component availability and folder access (e.g. back from Settings or a mode change). */
    fun refreshEnvironment() {
        refreshAvailability()
        refreshPathAccess(force = true)
    }

    private fun refreshAvailability() {
        viewModelScope.launch {
            try {
                val availability = appInspector.componentAvailability()
                work.update { it.copy(availability = availability) }
                applyDefaultComponents()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Component availability unknown", e)
            }
        }
    }

    /**
     * A new draft's default app components follow what the current mode can capture, until the user
     * picks them; never marks the draft dirty.
     */
    private fun applyDefaultComponents() {
        val d = draft.value ?: return
        val availability = work.value.availability
        if (!d.isNew || d.defaultComponentsTouched || availability.isEmpty()) return
        val defaults = ProfileDraft.DEFAULT_COMPONENT_CANDIDATES.filter { availability[it]?.available != false }.toSet() + AppComponent.APK
        if (defaults != d.defaultComponents) setDraft(d.copy(defaultComponents = defaults))
    }

    /** After "add destination", selects the destination the editor just created. */
    private fun watchNewDestinations() {
        viewModelScope.launch {
            destinationRepository.observeAll().collect { list ->
                val d = draft.value ?: return@collect
                if (!d.awaitingNewDestination) return@collect
                val fresh = list.filter { it.id !in d.knownDestinationIds }.maxByOrNull { it.createdAt } ?: return@collect
                edit { it.copy(destinationId = fresh.id, awaitingNewDestination = false) }
            }
        }
    }

    // ------------------------------------------------------------------ generic edits

    /** Applies [transform] to the draft, normalizes it and marks it dirty. */
    fun edit(transform: (ProfileDraft) -> ProfileDraft) {
        val current = draft.value ?: return
        val next = transform(current)
        if (next == current) return
        setDraft(normalize(next).copy(dirty = true))
        if (next.targets.paths != current.targets.paths) refreshPathAccess(force = false)
    }

    fun updatePipeline(transform: (PipelineOptions) -> PipelineOptions) = edit { it.copy(pipeline = transform(it.pipeline)) }

    fun setKeyChoice(choice: KeyChoice) = edit {
        it.copy(keyChoice = choice, unsupportedEncryption = null)
    }

    private fun normalize(d: ProfileDraft): ProfileDraft {
        val keyChoice = PipelineRules.effectiveKeyChoice(d.keyChoice, d.pipeline)
        return d.copy(keyChoice = keyChoice, pipeline = PipelineRules.normalize(d.pipeline, keyChoice))
    }

    private fun setDraft(value: ProfileDraft) {
        draft.value = value
        savedStateHandle[KEY_DRAFT] = value.toJson()
    }

    // ------------------------------------------------------------------ targets

    fun togglePartition(group: PartitionGroup, key: String) = edit {
        it.copy(targets = TargetEdits.togglePartition(it.targets, group, key, it.slotPolicy))
    }

    fun setSlotPolicy(policy: SlotPolicy) = edit {
        it.copy(slotPolicy = policy, targets = TargetEdits.applySlotPolicy(it.targets, policy))
    }

    fun toggleApp(app: InstalledApp) = edit {
        it.copy(targets = TargetEdits.toggleApp(it.targets, app.packageName, it.defaultComponents))
    }

    fun toggleDefaultComponent(component: AppComponent) = edit {
        val next = if (component in it.defaultComponents) it.defaultComponents - component else it.defaultComponents + component
        it.copy(defaultComponents = next, defaultComponentsTouched = true)
    }

    fun applyDefaultsToAll() = edit { it.copy(targets = TargetEdits.applyComponentsToAll(it.targets, it.defaultComponents)) }

    fun toggleAppComponent(packageName: String, component: AppComponent) = edit { d ->
        val app = d.targets.apps.firstOrNull { it.packageName == packageName } ?: return@edit d
        val next = if (component in app.components) app.components - component else app.components + component
        d.copy(targets = TargetEdits.setAppComponents(d.targets, packageName, next))
    }

    fun removeApp(packageName: String) = edit { d ->
        d.copy(targets = d.targets.copy(apps = d.targets.apps.filterNot { it.packageName == packageName }))
    }

    /** Adds a folder target; returns false when [location] is invalid or already present. */
    fun addPath(location: String): Boolean {
        val normalized = TargetEdits.normalizeLocation(location)
        val d = draft.value ?: return false
        if (!DraftValidator.isValidLocation(normalized)) return false
        if (d.targets.paths.any { TargetEdits.normalizeLocation(it.location) == normalized }) return false
        edit { it.copy(targets = it.targets.copy(paths = it.targets.paths + PathTarget(normalized))) }
        return true
    }

    fun removePath(location: String) = edit { d ->
        d.copy(targets = d.targets.copy(paths = d.targets.paths.filterNot { it.location == location }))
    }

    fun setRecursive(location: String, recursive: Boolean) = edit { d ->
        d.copy(targets = d.targets.copy(paths = d.targets.paths.map { if (it.location == location) it.copy(recursive = recursive) else it }))
    }

    fun toggleCategory(category: FileCategory) = edit { d ->
        val next = if (category in d.targets.categories) d.targets.categories - category else d.targets.categories + category
        d.copy(targets = d.targets.copy(categories = next))
    }

    /** Result of the system folder picker: keeps the read grant the SAF walker needs, adds the tree. */
    fun onTreePicked(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            DftbLog.w(TAG, "Could not persist read grant for picked tree", e)
            _events.trySend(WizardEvent.Message(R.string.profiles_files_grant_failed))
        }
        if (!addPath(uri.toString())) _events.trySend(WizardEvent.Message(R.string.profiles_files_already_added))
    }

    /** All files access settings page for this app (API 30+). */
    fun allFilesAccessIntent(): Intent = storageInspector.allFilesAccessIntent()

    private fun refreshPathAccess(force: Boolean) {
        val locations = draft.value?.targets?.paths?.map { it.location }.orEmpty().toSet()
        work.update { state -> state.copy(pathAccess = state.pathAccess.filterKeys { it in locations }) }
        accessJobs.keys.filter { it !in locations }.forEach { accessJobs.remove(it)?.cancel() }
        for (location in locations) {
            if (!force && (location in work.value.pathAccess || accessJobs[location]?.isActive == true)) continue
            accessJobs[location]?.cancel()
            accessJobs[location] = viewModelScope.launch {
                try {
                    val decision = fileScanner.checkAccess(location)
                    work.update { it.copy(pathAccess = it.pathAccess + (location to decision)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DftbLog.w(TAG, "Access check failed for a folder target", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------ folder filter dialog

    fun openFilterEditor(location: String) {
        val target = draft.value?.targets?.paths?.firstOrNull { it.location == location } ?: return
        setEditor(location, FilterFormMapper.toForm(target.filter, ZoneId.systemDefault()))
    }

    fun updateFilterForm(transform: (FileFilterForm) -> FileFilterForm) {
        val editor = work.value.filterEditor ?: return
        setEditor(editor.location, transform(editor.form))
    }

    fun resetFilterForm() {
        val editor = work.value.filterEditor ?: return
        setEditor(editor.location, FileFilterForm(useDefaultExclusions = false))
    }

    /** Stores the edited filter (an empty form clears it); refuses while the form has errors. */
    fun saveFilter(): Boolean {
        val editor = work.value.filterEditor ?: return false
        if (!editor.parsed.isValid) return false
        val filter = editor.parsed.filter.takeUnless { it.isEmpty }
        edit { d ->
            d.copy(targets = d.targets.copy(paths = d.targets.paths.map { if (it.location == editor.location) it.copy(filter = filter) else it }))
        }
        dismissFilterEditor()
        return true
    }

    fun dismissFilterEditor() {
        work.update { it.copy(filterEditor = null) }
        savedStateHandle.remove<String>(KEY_FILTER_EDITOR)
    }

    private fun setEditor(location: String, form: FileFilterForm) {
        val parsed = FileFilterFormParser.parse(form, ZoneId.systemDefault())
        work.update { it.copy(filterEditor = FilterEditorState(location, form, parsed)) }
        savedStateHandle[KEY_FILTER_EDITOR] = EDITOR_JSON.encodeToString(FilterEditorSnapshot.serializer(), FilterEditorSnapshot.of(location, form))
    }

    // ------------------------------------------------------------------ destination

    fun selectDestination(id: String) = edit { it.copy(destinationId = id, awaitingNewDestination = false) }

    /** Remembers the current destinations so the one created next can be selected on return. */
    fun onAddDestination() {
        val known = uiState.value.destinations?.map { it.id }?.toSet() ?: return
        edit { it.copy(awaitingNewDestination = true, knownDestinationIds = known) }
    }

    // ------------------------------------------------------------------ navigation

    /** Next page; stays (and reveals the errors) when the current page has errors. */
    fun next() {
        val state = uiState.value
        val d = state.draft ?: return
        if (DraftValidator.hasErrors(state.problems, d.step)) {
            setDraft(d.copy(showErrorsFor = d.showErrorsFor + d.step))
            _events.trySend(WizardEvent.Message(R.string.profiles_wizard_fix_errors))
            return
        }
        val target = WizardStep.entries.getOrNull(d.step.ordinal + 1) ?: return
        setDraft(d.copy(step = target, visited = d.visited + target, showErrorsFor = d.showErrorsFor + d.step))
    }

    /** Previous page; false on the first page (the caller then asks to exit). */
    fun back(): Boolean {
        val d = draft.value ?: return false
        val previous = WizardStep.entries.getOrNull(d.step.ordinal - 1) ?: return false
        setDraft(d.copy(step = previous))
        return true
    }

    fun goTo(step: WizardStep) {
        val d = draft.value ?: return
        if (!uiState.value.canOpen(step)) return
        setDraft(d.copy(step = step, visited = d.visited + step))
    }

    fun requestExit() {
        val d = draft.value
        if (d != null && d.dirty) work.update { it.copy(confirmDiscard = true) } else _events.trySend(WizardEvent.Exit)
    }

    fun cancelDiscard() = work.update { it.copy(confirmDiscard = false) }

    fun confirmDiscard() {
        work.update { it.copy(confirmDiscard = false) }
        _events.trySend(WizardEvent.Exit)
    }

    // ------------------------------------------------------------------ estimate / space

    /** Estimates the draft with the engine's sources and planner, then checks the destination's space. */
    fun estimate() {
        val state = uiState.value
        val d = state.draft ?: return
        if (d.targets.isEmpty) return
        val key = estimateKeyOf(d)
        estimateJob?.cancel()
        spaceJob?.cancel()
        work.update { it.copy(estimate = EstimateState.Running, spaceCheck = SpaceCheckState.Idle) }
        val destination = state.selectedDestination
        estimateJob = viewModelScope.launch {
            val result = try {
                val profile = d.toProfile(clock.nowMillis(), d.existingKeyRef)
                EstimateState.Done(key, preflight.estimate(profile, destination?.type))
            } catch (e: CancellationException) {
                work.update { it.copy(estimate = EstimateState.Idle) }
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Estimate failed", e)
                EstimateState.Failed(key, e.message)
            }
            work.update { it.copy(estimate = result) }
            if (result is EstimateState.Done && destination != null) checkSpace(destination, key, result.estimate.destinationRequiredBytes)
        }
    }

    fun cancelEstimate() {
        estimateJob?.cancel()
        spaceJob?.cancel()
        work.update { it.copy(estimate = EstimateState.Idle, spaceCheck = SpaceCheckState.Idle) }
    }

    /** Re-runs only the free-space check (destination changed or became reachable). */
    fun recheckSpace() {
        val state = uiState.value
        val estimate = state.currentEstimate ?: return
        val key = state.estimateKey ?: return
        val destination = state.selectedDestination ?: return
        checkSpace(destination, key, estimate.destinationRequiredBytes)
    }

    private fun checkSpace(destination: Destination, key: EstimateKey, requiredBytes: Long) {
        spaceJob?.cancel()
        work.update { it.copy(spaceCheck = SpaceCheckState.Running) }
        spaceJob = viewModelScope.launch {
            val result = try {
                preflight.checkDestination(destination, requiredBytes)
            } catch (e: CancellationException) {
                work.update { it.copy(spaceCheck = SpaceCheckState.Idle) }
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Free-space check failed", e)
                PreflightResult.Unknown(e.message ?: e.javaClass.simpleName)
            }
            work.update { it.copy(spaceCheck = SpaceCheckState.Done(key, requiredBytes, result)) }
        }
    }

    // ------------------------------------------------------------------ save

    /** Validates everything, saves, re-arms the schedule; with [runNow] also starts a backup. */
    fun save(runNow: Boolean) {
        val state = uiState.value
        val d = state.draft ?: return
        if (state.saving) return
        val firstInvalid = DraftValidator.firstInvalidStep(state.problems)
        if (firstInvalid != null) {
            val withErrors = WizardStep.entries.filter { DraftValidator.hasErrors(state.problems, it) }
            setDraft(d.copy(step = firstInvalid, visited = d.visited + firstInvalid, showErrorsFor = d.showErrorsFor + withErrors))
            _events.trySend(WizardEvent.Message(R.string.profiles_wizard_fix_errors))
            return
        }
        work.update { it.copy(saving = true) }
        viewModelScope.launch {
            var createdKey: String? = null
            try {
                val choice = PipelineRules.effectiveKeyChoice(d.keyChoice, d.pipeline)
                val keyRef = when (choice) {
                    KeyChoice.NONE, KeyChoice.PASSPHRASE -> null
                    KeyChoice.STORED_SECRET -> d.existingKeyRef
                    KeyChoice.VAULT_KEY -> d.existingKeyRef?.takeIf { KeyChoice.ofRef(it) == KeyChoice.VAULT_KEY && !vaultKeyMissing.value }
                        ?: vaultKeyStore.createVaultKey().also { createdKey = it }
                }
                val profile = d.toProfile(clock.nowMillis(), keyRef)
                profileRepository.save(profile)
                createdKey = null
                vaultKeyMissing.value = false
                setDraft(d.copy(isNew = false, dirty = false, existingKeyRef = keyRef ?: d.existingKeyRef))
                // The profile is persisted from here on; a scheduler failure must not read as "not saved".
                // The wizard stays open on those failures so the user sees the message and can retry.
                if (!attempt("schedule the profile") { scheduler.schedule(profile) }) {
                    _events.trySend(WizardEvent.Message(R.string.profiles_wizard_saved_not_scheduled))
                } else {
                    when {
                        !runNow -> _events.trySend(WizardEvent.Saved)
                        ResumePlanner.requiresTypedPassphrase(profile) -> work.update { it.copy(passphrasePrompt = profile) }
                        attempt("start the backup") { scheduler.runNow(profile.id) } ->
                            _events.trySend(WizardEvent.SavedAndRunning(jobId = null))
                        else -> _events.trySend(WizardEvent.Message(R.string.profiles_wizard_saved_not_started))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VaultException) {
                DftbLog.e(TAG, "Could not create the profile key (${e.reason})", e)
                _events.trySend(WizardEvent.Message(R.string.profiles_wizard_key_failed))
            } catch (e: Exception) {
                DftbLog.e(TAG, "Saving the profile failed", e)
                _events.trySend(WizardEvent.Message(R.string.profiles_wizard_save_failed))
            } finally {
                // A key minted for a save that did not happen would only clutter the vault.
                createdKey?.let { ref -> withContext(NonCancellable) { deleteQuietly(ref) } }
                work.update { it.copy(saving = false) }
            }
        }
    }

    fun submitPassphrase(passphrase: CharArray) {
        val profile = work.value.passphrasePrompt
        work.update { it.copy(passphrasePrompt = null) }
        if (profile == null) {
            passphrase.fill('\u0000')
            return
        }
        when (val start = passphraseRunner.start(profile, passphrase)) {
            is PassphraseRunStart.Started -> _events.trySend(WizardEvent.SavedAndRunning(start.jobId))
            PassphraseRunStart.AlreadyRunning -> {
                _events.trySend(WizardEvent.Message(R.string.profiles_wizard_already_running))
                _events.trySend(WizardEvent.Saved)
            }
        }
    }

    /** The user declined to type the passphrase: the profile is saved, just not run. */
    fun dismissPassphrase() {
        work.update { it.copy(passphrasePrompt = null) }
        _events.trySend(WizardEvent.Saved)
    }

    // ------------------------------------------------------------------ helpers

    /** Runs a post-save [block]; false (after logging) when it threw, so the caller can say what half-worked. */
    private suspend fun attempt(label: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DftbLog.e(TAG, "Could not $label after saving", e)
        false
    }

    private suspend fun deleteQuietly(ref: String) {
        try {
            vault.delete(ref)
        } catch (e: VaultException) {
            DftbLog.w(TAG, "Could not remove an unused profile key (${e.reason})", e)
        }
    }

    private data class External(val destinations: List<Destination>, val profiles: List<BackupProfile>, val tier: ElevationTier)

    private fun validationContext(d: ProfileDraft, w: WizardUiState, ext: External, keyMissing: Boolean) = ValidationContext(
        otherProfileNames = ext.profiles.filter { it.id != d.profileId }.map { DraftValidator.nameKey(it.name) }.toSet(),
        destinationTypes = ext.destinations.associate { it.id to it.type },
        componentAvailable = w.availability.takeIf { it.isNotEmpty() }?.mapValues { it.value.available },
        tier = ext.tier,
        vaultKeyMissing = keyMissing,
    )

    private fun estimateKeyOf(d: ProfileDraft) = EstimateKey(d.targets, d.effectivePipeline(d.existingKeyRef), d.destinationId)

    private fun commonRoots(): List<CommonRoot> {
        val base = storageInspector.primaryStoragePath.trimEnd('/')
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

    private fun decodeDraft(json: String): ProfileDraft? = try {
        ProfileDraft.fromJson(json)
    } catch (e: IllegalArgumentException) {
        DftbLog.w(TAG, "Discarding an unreadable saved draft", e)
        null
    }

    private fun decodeEditor(json: String): FilterEditorState? = try {
        val snapshot = EDITOR_JSON.decodeFromString(FilterEditorSnapshot.serializer(), json)
        val form = snapshot.toForm()
        FilterEditorState(snapshot.location, form, FileFilterFormParser.parse(form, ZoneId.systemDefault()))
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        const val TAG = "ProfileWizardVM"
        const val KEY_DRAFT = "profile_wizard_draft"
        const val KEY_FILTER_EDITOR = "profile_wizard_filter_editor"
        val EDITOR_JSON = Json { ignoreUnknownKeys = true }
    }
}
