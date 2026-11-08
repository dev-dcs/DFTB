package ca.devdcs.backup.core.locale

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Re-roots the Compose tree in [language] without touching the Activity.
 *
 * `stringResource`, `pluralStringResource`, `painterResource` and friends all resolve through
 * `LocalContext.current.resources` and re-read when `LocalConfiguration` changes. Providing a
 * configuration context built for [language] (plus the matching [LocalLayoutDirection]) is
 * therefore enough to re-render every string on screen the instant the language flips.
 *
 * The localized context MUST stay rooted at the Activity: it is a [ContextThemeWrapper] whose base
 * is the current (Activity) context, with the locale applied via `applyOverrideConfiguration`. A
 * bare `createConfigurationContext` returns a detached `ContextImpl`, so `Context.findActivity()`
 * fails and `hiltViewModel()` (and any Activity-scoped lookup) crashes with
 * "Expected an activity context … but instead found ContextImpl".
 */
@Composable
fun ProvideAppLocale(language: AppLanguage, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current

    val localized = remember(baseContext, baseConfiguration, language) {
        val configuration = Configuration(baseConfiguration).apply {
            setLocale(language.locale)
            setLayoutDirection(language.locale)
        }
        LocalizedEnvironment(
            configuration = configuration,
            // ContextThemeWrapper keeps baseContext == the Activity, so Context.findActivity()
            // still resolves it (required by hiltViewModel()); applyOverrideConfiguration makes
            // getResources() use the chosen locale. (theme 0 = inherit the base theme.)
            context = ContextThemeWrapper(baseContext, 0).apply { applyOverrideConfiguration(configuration) },
        )
    }

    CompositionLocalProvider(
        LocalContext provides localized.context,
        LocalConfiguration provides localized.configuration,
        LocalLayoutDirection provides if (language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        content = content,
    )
}

private class LocalizedEnvironment(
    val configuration: Configuration,
    val context: android.content.Context,
)
