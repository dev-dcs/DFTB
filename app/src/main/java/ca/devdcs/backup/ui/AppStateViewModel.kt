package ca.devdcs.backup.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.core.locale.AppLanguage
import ca.devdcs.backup.core.locale.LocaleManager
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import ca.devdcs.backup.core.preferences.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * App-wide state consumed by the root composable: language, theme and onboarding gate.
 *
 * [isLoaded] is false only for the synthetic initial value; it flips to true with the first
 * DataStore emission so the root can hold a blank surface instead of briefly showing the wrong
 * start destination (the onboarding flag is unknown until that first read).
 */
data class AppUiState(
    val language: AppLanguage,
    val themeMode: ThemeMode,
    val dynamicColor: Boolean,
    val onboardingCompleted: Boolean,
    val operatingMode: OperatingMode?,
    val isLoaded: Boolean,
)

@HiltViewModel
class AppStateViewModel @Inject constructor(
    localeManager: LocaleManager,
    preferences: AppPreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<AppUiState> = combine(localeManager.language, preferences.preferences) { language, prefs ->
        AppUiState(
            // The in-memory language leads: it changes a frame before DataStore catches up.
            language = language,
            themeMode = prefs.themeMode,
            dynamicColor = prefs.dynamicColor,
            onboardingCompleted = prefs.onboardingCompleted,
            operatingMode = prefs.operatingMode,
            // combine() only emits once both sources have, so any emission means DataStore was read.
            isLoaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppUiState(
            language = localeManager.language.value,
            themeMode = ThemeMode.SYSTEM,
            dynamicColor = true,
            onboardingCompleted = false,
            operatingMode = null,
            isLoaded = false,
        ),
    )
}
