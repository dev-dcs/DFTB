package ca.devdcs.backup.ui.onboarding

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.R
import ca.devdcs.backup.core.environment.EnvironmentProbe
import ca.devdcs.backup.core.environment.ShizukuProbe
import ca.devdcs.backup.core.locale.AppLanguage
import ca.devdcs.backup.core.locale.LocaleManager
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationConfig
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.repository.DestinationRepository
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * Owns every decision the first-launch wizard collects and commits them atomically at the end via
 * [AppPreferencesRepository.completeOnboarding]. Lives in a ViewModel (not composable state) so
 * rotation, live language switches and a backgrounded superuser prompt all resume exactly where
 * the user was. Side effects that need an Activity (permission dialogs, Settings intents) stay in
 * the screen; the ViewModel only reacts to their outcome through [onResumed] and [refreshSystemState].
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferencesRepository,
    private val localeManager: LocaleManager,
    private val environmentProbe: EnvironmentProbe,
    private val shizukuProbe: ShizukuProbe,
    private val destinationRepository: DestinationRepository,
    private val notificationManager: NotificationManagerCompat,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(
        OnboardingUiState(
            language = localeManager.language.value,
            suggestedLanguage = systemLanguageSuggestion(),
        ),
    )

    /** The in-memory language leads so the "Suggested" hint flips the same frame the chips do. */
    val uiState: StateFlow<OnboardingUiState> = combine(_state, localeManager.language) { state, language ->
        state.copy(language = language)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _state.value)

    private val _events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events: Flow<OnboardingEvent> = _events.receiveAsFlow()

    private var diagnosticsJob: Job? = null

    init {
        refreshSystemState()
        viewModelScope.launch {
            shizukuProbe.binderAlive
                .drop(1)
                .catch { error -> DftbLog.w(TAG, "Shizuku binder flow failed", error) }
                .collect { alive ->
                    if (alive && _state.value.selectedMode == OperatingMode.SHIZUKU) {
                        DftbLog.d(TAG, "Shizuku binder came alive; re-running diagnostics")
                        rerunDiagnostics()
                    }
                }
        }
    }

    // ---------------------------------------------------------------- navigation

    fun next() {
        val current = _state.value
        if (!current.canGoNext) return
        if (current.step.isLast) {
            completeOnboarding()
        } else {
            _state.update { it.copy(step = it.step.next) }
        }
    }

    fun back() {
        val current = _state.value
        if (!current.canGoBack) return
        _state.update { it.copy(step = it.step.previous) }
    }

    /** Called on every ON_RESUME: the user may have just returned from a Settings page. */
    fun onResumed() {
        refreshSystemState()
        // Only the MODE step reacts to a Settings round-trip (granting root/Shizuku). Re-running on a
        // later step would momentarily (or, if a transient re-check degrades to UNAVAILABLE, lastingly)
        // flip modeIsUsable to false and refuse Finish, even though the user already locked in a usable
        // mode to get past MODE. Preserve that verdict on BASELINE.
        val current = _state.value
        if (current.step == OnboardingStep.MODE &&
            current.selectedMode != null &&
            current.diagnostics !is DiagnosticsState.Running
        ) {
            rerunDiagnostics()
        }
    }

    fun showMessage(@StringRes messageRes: Int) {
        _events.trySend(OnboardingEvent.ShowMessage(messageRes))
    }

    // ---------------------------------------------------------------- step 1

    fun setLanguage(language: AppLanguage) = localeManager.setLanguage(language)

    // ---------------------------------------------------------------- step 3

    fun setDisclaimerAccepted(accepted: Boolean) {
        _state.update { it.copy(disclaimerAccepted = accepted) }
    }

    // ---------------------------------------------------------------- step 5

    fun selectMode(mode: OperatingMode) {
        if (_state.value.selectedMode == mode && _state.value.diagnostics is DiagnosticsState.Running) return
        _state.update { it.copy(selectedMode = mode) }
        runDiagnostics(mode)
    }

    fun rerunDiagnostics() {
        val mode = _state.value.selectedMode ?: return
        runDiagnostics(mode)
    }

    /**
     * Asks Shizuku for authorization, then re-runs the checks whatever the answer so the row
     * reflects reality. A refusal is reported once via snackbar.
     */
    fun requestShizukuPermission() {
        viewModelScope.launch {
            val granted = runCatching { shizukuProbe.requestPermission() }
                .onFailure { error -> if (error is CancellationException) throw error }
                .getOrElse { error ->
                    DftbLog.w(TAG, "Shizuku permission request failed", error)
                    false
                }
            if (!granted) showMessage(R.string.onboarding_shizuku_denied)
            rerunDiagnostics()
        }
    }

    private fun runDiagnostics(mode: OperatingMode) {
        diagnosticsJob?.cancel()
        _state.update { it.copy(diagnostics = DiagnosticsState.Running(mode)) }
        diagnosticsJob = viewModelScope.launch {
            val result = runCatching { environmentProbe.diagnose(mode) }
            val report = result.getOrNull()
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
            // Ignore a stale answer if the user switched modes while this run was in flight.
            if (_state.value.selectedMode != mode) return@launch
            if (report != null) {
                _state.update { it.copy(diagnostics = DiagnosticsState.Done(report)) }
            } else {
                DftbLog.e(TAG, "Diagnostics for $mode threw", error)
                _state.update { it.copy(diagnostics = DiagnosticsState.Failed(mode, error?.message)) }
            }
        }
    }

    // ---------------------------------------------------------------- step 6

    fun setArchiveFormat(format: ArchiveFormat) {
        _state.update { state ->
            val level = if (format.supportsCompressionLevel) {
                state.compressionLevel.coerceIn(0, format.maxCompressionLevel)
            } else {
                state.compressionLevel
            }
            state.copy(archiveFormat = format, compressionLevel = level)
        }
    }

    fun setCompressionLevel(level: Int) {
        _state.update { it.copy(compressionLevel = level.coerceIn(0, it.archiveFormat.maxCompressionLevel.coerceAtLeast(0))) }
    }

    fun setChecksumAlgorithm(algorithm: ChecksumAlgorithm) {
        _state.update { it.copy(checksumAlgorithm = algorithm) }
    }

    /** A folder picked through the document tree picker becomes a LOCAL_SAF destination. */
    fun useSafFolder(treeUri: Uri) {
        replaceDestination {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
            val name = DocumentFile.fromTreeUri(context, treeUri)?.name?.takeIf { it.isNotBlank() }
                ?: treeUri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
                ?: localizedString(R.string.onboarding_destination_folder_fallback_name)
            val now = System.currentTimeMillis()
            Destination(
                id = UUID.randomUUID().toString(),
                name = name,
                type = DestinationType.LOCAL_SAF,
                config = DestinationConfig(treeUri = treeUri.toString()),
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    /** `<external app files>/backups`: no permission needed, but gone when DFTB is uninstalled. */
    fun useAppStorage() {
        replaceDestination {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(root, APP_STORAGE_SUBDIR)
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IllegalStateException("Cannot create ${dir.absolutePath}")
            }
            val now = System.currentTimeMillis()
            Destination(
                id = UUID.randomUUID().toString(),
                name = Destination.APP_STORAGE_NAME_MARKER,
                type = DestinationType.LOCAL_INTERNAL,
                config = DestinationConfig(basePath = dir.absolutePath),
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    /** Removes whatever the wizard created so far; profiles will ask for a destination. */
    fun decideDestinationLater() {
        replaceDestination { null }
    }

    /**
     * Swaps the wizard-created destination for the one [create] builds (or none). The new row is
     * saved before the previous one is discarded, so a failed save leaves the UI pointing at the
     * destination that still exists rather than at a deleted one. The discard keeps a successful
     * swap from leaving orphan destinations in the catalogue.
     */
    private fun replaceDestination(create: suspend () -> Destination?) {
        if (_state.value.destinationBusy) return
        _state.update { it.copy(destinationBusy = true) }
        viewModelScope.launch {
            val previous = _state.value.destination
            val outcome = runCatching {
                withContext(ioDispatcher) {
                    val created = create()
                    if (created != null) destinationRepository.save(created)
                    if (previous != null) discard(previous)
                    created
                }
            }
            if (outcome.exceptionOrNull() is CancellationException) throw outcome.exceptionOrNull()!!
            outcome.onSuccess { created ->
                val summary = created?.let {
                    DestinationSummary(id = it.id, name = it.name, type = it.type, treeUri = it.config.treeUri)
                }
                _state.update { it.copy(destination = summary, destinationBusy = false) }
                DftbLog.i(TAG, "Default destination is now ${created?.type ?: "none"}")
            }.onFailure { error ->
                DftbLog.w(TAG, "Destination change failed", error)
                _state.update { it.copy(destinationBusy = false) }
                showMessage(R.string.onboarding_destination_error)
            }
        }
    }

    private suspend fun discard(summary: DestinationSummary) {
        destinationRepository.delete(summary.id)
        val uri = summary.treeUri?.let(Uri::parse) ?: return
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (error: SecurityException) {
            DftbLog.d(TAG, "Persistable permission for $uri already gone")
        }
    }

    /** Re-reads the system facts that Settings pages change behind our back. */
    fun refreshSystemState() {
        val notifications = notificationManager.areNotificationsEnabled()
        val battery = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        _state.update { it.copy(notificationsGranted = notifications, batteryExempt = battery) }
    }

    // ---------------------------------------------------------------- finish

    /** Persists every choice in one DataStore transaction and tells the screen to leave. */
    fun completeOnboarding() {
        val current = _state.value
        if (current.finishing) return
        val mode = current.selectedMode
        if (mode == null || !current.modeIsUsable) {
            showMessage(R.string.onboarding_finish_requires_mode)
            return
        }
        _state.update { it.copy(finishing = true) }
        viewModelScope.launch {
            val result = runCatching {
                preferences.completeOnboarding(
                    operatingMode = mode,
                    disclaimerAcknowledgedAt = System.currentTimeMillis(),
                    defaultArchiveFormat = current.archiveFormat,
                    defaultCompressionLevel = current.compressionLevel,
                    defaultChecksumAlgorithm = current.checksumAlgorithm,
                    defaultDestinationId = current.destination?.id,
                )
            }
            if (result.exceptionOrNull() is CancellationException) throw result.exceptionOrNull()!!
            result.onSuccess {
                DftbLog.i(TAG, "Onboarding completed: mode=$mode format=${current.archiveFormat} destination=${current.destination?.type}")
                _events.send(OnboardingEvent.Finished)
            }.onFailure { error ->
                DftbLog.e(TAG, "Could not persist onboarding", error)
                _state.update { it.copy(finishing = false) }
                showMessage(R.string.onboarding_finish_error)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Resolves a resource in the current app language regardless of the calling thread. */
    private fun localizedString(@StringRes resId: Int): String = localeManager.localizedContext().getString(resId)

    /**
     * [AppLanguage.matchingSystemLocale] reads `Locale.getDefault()`, which [LocaleManager] has
     * already overridden with the app language by the time this ViewModel exists. The system
     * configuration still carries the device's real locale, so it is consulted first.
     */
    private fun systemLanguageSuggestion(): AppLanguage? {
        val systemLocale = Resources.getSystem().configuration.locales.let { if (it.isEmpty()) null else it[0] }
        val fromSystem = systemLocale?.let { locale -> AppLanguage.entries.firstOrNull { it.locale.language == locale.language } }
        return fromSystem ?: AppLanguage.matchingSystemLocale()
    }

    private companion object {
        const val TAG = "Onboarding"
        const val APP_STORAGE_SUBDIR = "backups"
    }
}
