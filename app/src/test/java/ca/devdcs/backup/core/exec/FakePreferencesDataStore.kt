package ca.devdcs.backup.core.exec

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An in-memory [DataStore] of [Preferences] for router tests. It lets a real
 * [ca.devdcs.backup.core.preferences.AppPreferencesRepository] be constructed without files or the
 * Android runtime, so tests drive the router through the repository's genuine public API (e.g.
 * `setOperatingMode`) instead of poking private storage keys.
 */
class FakePreferencesDataStore : DataStore<Preferences> {

    private val state = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
