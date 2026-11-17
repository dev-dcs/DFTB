package ca.devdcs.backup.ui.shell

import android.content.Context
import android.content.SharedPreferences
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers one-time UI prompts the shell has already shown (currently: the automatic
 * POST_NOTIFICATIONS request), so the user is asked once and never nagged on every launch.
 *
 * Kept in its own small preferences file because these are presentation flags, not app settings
 * (the settings DataStore belongs to [ca.devdcs.backup.core.preferences.AppPreferencesRepository]).
 * Every access runs on the IO dispatcher; the first read loads the file from disk.
 */
@Singleton
class ShellPromptStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }

    suspend fun notificationPromptShown(): Boolean = withContext(ioDispatcher) {
        prefs.getBoolean(KEY_NOTIFICATION_PROMPT, false)
    }

    suspend fun markNotificationPromptShown() {
        withContext(ioDispatcher) {
            prefs.edit().putBoolean(KEY_NOTIFICATION_PROMPT, true).apply()
        }
    }

    private companion object {
        const val FILE_NAME = "dftb_shell_prompts"
        const val KEY_NOTIFICATION_PROMPT = "notification_permission_requested"
    }
}
