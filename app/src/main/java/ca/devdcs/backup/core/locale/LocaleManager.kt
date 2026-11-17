package ca.devdcs.backup.core.locale

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import ca.devdcs.backup.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the UI language.
 *
 * Three layers are kept in agreement:
 * 1. [language] — an in-memory StateFlow that Compose observes. Flipping it re-composes every
 * `stringResource` under [ProvideAppLocale] immediately, with no Activity recreation.
 * 2. DataStore — the persisted choice, restored by [initialize] before the first frame.
 * 3. The platform/AppCompat application-locale list — so Android 13+ "App languages" settings,
 * notifications built from a plain Context, and any non-Compose surface agree with the UI.
 * MainActivity declares `locale|layoutDirection` in configChanges, so this layer never
 * triggers a recreation either.
 */
@Singleton
class LocaleManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferences: AppPreferencesRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _language = MutableStateFlow(AppLanguage.DEFAULT)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    @Volatile private var initialized = false

    /**
     * Seed [language] from DataStore. Called once from Application.onCreate; the blocking read is
     * of a file a few dozen bytes long and is the only synchronous disk access at startup.
     */
    fun initialize() {
        if (initialized) return
        initialized = true
        val stored = runBlocking { preferences.preferences.first().language }
        _language.value = stored
        Locale.setDefault(stored.locale)
        DftbLog.i(TAG, "Runtime language: ${stored.tag}")

        // Keep in-memory state in step with external writes (e.g. a future settings import).
        scope.launch {
            preferences.preferences.map { it.language }.distinctUntilChanged().collect { fromStore ->
                if (fromStore != _language.value) applyRuntime(fromStore)
            }
        }
    }

    /** User picked a language. Takes effect on the next frame; persisted asynchronously. */
    fun setLanguage(language: AppLanguage) {
        if (language == _language.value) return
        applyRuntime(language)
        scope.launch { preferences.setLanguage(language) }
    }

    /**
     * Make AppCompat's/the framework's application-locale list match [language]. Safe to call on
     * every Activity creation; a no-op when they already agree.
     */
    fun syncFrameworkLocales() {
        val wanted = _language.value.tag
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (!current.equals(wanted, ignoreCase = true)) {
            DftbLog.d(TAG, "Syncing framework locales: '$current' -> '$wanted'")
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(wanted))
        }
    }

    /** A Context whose resources resolve in the current (or given) app language. */
    fun localizedContext(base: Context = appContext, language: AppLanguage = _language.value): Context =
        base.createConfigurationContext(configurationFor(base.resources.configuration, language))

    /** Copy of [base] with locale and layout direction set for [language]. */
    fun configurationFor(base: Configuration, language: AppLanguage): Configuration =
        Configuration(base).apply {
            setLocale(language.locale)
            setLayoutDirection(language.locale)
        }

    private fun applyRuntime(language: AppLanguage) {
        _language.value = language
        Locale.setDefault(language.locale)
        DftbLog.i(TAG, "Language switched to ${language.tag}")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            syncFrameworkLocales()
        } else {
            scope.launch { withContext(Dispatchers.Main.immediate) { syncFrameworkLocales() } }
        }
    }

    private companion object {
        const val TAG = "Locale"
    }
}
