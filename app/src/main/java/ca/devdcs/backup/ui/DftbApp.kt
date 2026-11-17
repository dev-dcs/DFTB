package ca.devdcs.backup.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.devdcs.backup.core.locale.ProvideAppLocale
import ca.devdcs.backup.ui.navigation.DftbDestination
import ca.devdcs.backup.ui.shell.DftbShell
import ca.devdcs.backup.ui.theme.DftbTheme
import ca.devdcs.backup.ui.theme.resolvesToDark

/**
 * Root composable: locale → theme → app shell (navigation chrome + NavHost). Order matters: the
 * locale wrapper must sit above the theme so `dynamicLightColorScheme(context)` and every
 * `stringResource` resolve against the localized context, and above navigation so every
 * destination inherits it.
 *
 * The shell is only created once the preferences have been read ([AppUiState.isLoaded]); its
 * start destination is fixed at creation, so building it earlier would send a returning user
 * through onboarding again for one frame and then leave the wrong route on the back stack.
 * After onboarding the start destination is the Dashboard.
 */
@Composable
fun DftbApp(viewModel: AppStateViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ProvideAppLocale(language = state.language) {
        DftbTheme(themeMode = state.themeMode, dynamicColor = state.dynamicColor) {
            SystemBarsEffect(darkTheme = state.themeMode.resolvesToDark())
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                if (state.isLoaded) {
                    // Capture the start destination from the first loaded frame and keep it. Deriving
                    // it from live preference state would flip it from Onboarding to Dashboard the
                    // moment onboarding is persisted, rebuilding the NavHost graph mid-session instead
                    // of navigating. Transitions happen through explicit navigate() calls in the graph.
                    val startDestination = remember {
                        if (state.onboardingCompleted) {
                            DftbDestination.Dashboard
                        } else {
                            DftbDestination.Onboarding
                        }
                    }
                    DftbShell(startDestination = startDestination)
                }
            }
        }
    }
}

/**
 * Keep status/navigation bar icon contrast in step with a *forced* light/dark theme. The default
 * `enableEdgeToEdge()` only follows the system setting.
 */
@Composable
private fun SystemBarsEffect(darkTheme: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    LaunchedEffect(darkTheme) {
        val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
