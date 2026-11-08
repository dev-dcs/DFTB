package ca.devdcs.backup.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.R
import ca.devdcs.backup.core.locale.AppLanguage
import ca.devdcs.backup.core.locale.LocaleManager
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import ca.devdcs.backup.core.preferences.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Language, appearance and the facts the settings root and About page summarise. */
data class GeneralSettingsUiState(
    val language: AppLanguage = AppLanguage.DEFAULT,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val operatingMode: OperatingMode? = null,
    val isLoaded: Boolean = false,
)

/**
 * Backs the settings root plus the language, appearance and about sections. One ViewModel for
 * the four because they read the same two sources (LocaleManager + DataStore) and none of them
 * holds state of its own; theme and language writes take effect app-wide on the next frame
 * through the root composable.
 */
@HiltViewModel
class GeneralSettingsViewModel @Inject constructor(
    private val localeManager: LocaleManager,
    private val preferences: AppPreferencesRepository,
) : ViewModel() {

    private val _messages = Channel<SettingsMessage>(Channel.BUFFERED)
    val messages: Flow<SettingsMessage> = _messages.receiveAsFlow()

    val uiState: StateFlow<GeneralSettingsUiState> =
        combine(localeManager.language, preferences.preferences) { language, prefs ->
            GeneralSettingsUiState(
                // The in-memory language leads DataStore by a frame; show what is on screen.
                language = language,
                themeMode = prefs.themeMode,
                dynamicColor = prefs.dynamicColor,
                operatingMode = prefs.operatingMode,
                isLoaded = true,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            GeneralSettingsUiState(language = localeManager.language.value),
        )

    /** Instant switch: LocaleManager re-roots the Compose tree (incl. RTL) and persists in the background. */
    fun setLanguage(language: AppLanguage) = localeManager.setLanguage(language)

    fun setThemeMode(mode: ThemeMode) = persist("theme") { preferences.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = persist("dynamic color") { preferences.setDynamicColor(enabled) }

    private fun persist(what: String, write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Could not save $what", e)
                _messages.trySend(SettingsMessage(R.string.settings_save_failed))
            }
        }
    }

    private companion object {
        const val TAG = "SettingsGeneral"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
