package ca.devdcs.backup.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import ca.devdcs.backup.core.locale.AppLanguage
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot of the non-secret, app-wide preferences. Secrets never live here (see ). */
data class AppPreferences(
    val language: AppLanguage = AppLanguage.DEFAULT,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val operatingMode: OperatingMode? = null,
    /** Epoch millis when the user ticked the responsibility/disclaimer acknowledgment; null = never. */
    val disclaimerAcknowledgedAt: Long? = null,
    /** Baseline defaults chosen in onboarding; every new Backup Profile starts from these. */
    val defaultArchiveFormat: ArchiveFormat = ArchiveFormat.DEFAULT,
    /** 0 = store … [ArchiveFormat.maxCompressionLevel]; clamped by the engine per format. */
    val defaultCompressionLevel: Int = 3,
    val defaultChecksumAlgorithm: ChecksumAlgorithm = ChecksumAlgorithm.DEFAULT,
    /** Id of the DestinationEntity used when a profile has none; null = ask every time. */
    val defaultDestinationId: String? = null,
)

/**
 * Jetpack DataStore-backed preferences. Every write is a suspend function on the DataStore's own
 * IO dispatcher; reads are a cold [Flow] that recovers from a corrupt/unreadable file by emitting
 * defaults instead of crashing the process.
 */
@Singleton
class AppPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val preferences: Flow<AppPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                DftbLog.w(TAG, "Preferences unreadable, using defaults", error)
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map(::toModel)

    suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { it[Keys.LANGUAGE] = language.tag }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.storageKey }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setOperatingMode(mode: OperatingMode?) {
        dataStore.edit { prefs ->
            if (mode == null) prefs.remove(Keys.OPERATING_MODE) else prefs[Keys.OPERATING_MODE] = mode.storageKey
        }
    }

    suspend fun setDisclaimerAcknowledged(atEpochMillis: Long?) {
        dataStore.edit { prefs ->
            if (atEpochMillis == null) prefs.remove(Keys.DISCLAIMER_ACKNOWLEDGED_AT) else prefs[Keys.DISCLAIMER_ACKNOWLEDGED_AT] = atEpochMillis
        }
    }

    suspend fun setDefaultArchiveFormat(format: ArchiveFormat) {
        dataStore.edit { it[Keys.DEFAULT_ARCHIVE_FORMAT] = format.storageKey }
    }

    suspend fun setDefaultCompressionLevel(level: Int) {
        dataStore.edit { it[Keys.DEFAULT_COMPRESSION_LEVEL] = level.coerceIn(0, 19) }
    }

    suspend fun setDefaultChecksumAlgorithm(algorithm: ChecksumAlgorithm) {
        dataStore.edit { it[Keys.DEFAULT_CHECKSUM_ALGORITHM] = algorithm.storageKey }
    }

    suspend fun setDefaultDestinationId(destinationId: String?) {
        dataStore.edit { prefs ->
            if (destinationId == null) prefs.remove(Keys.DEFAULT_DESTINATION_ID) else prefs[Keys.DEFAULT_DESTINATION_ID] = destinationId
        }
    }

    /** Atomically records everything the onboarding wizard decided, then marks it complete. */
    suspend fun completeOnboarding(
        operatingMode: OperatingMode,
        disclaimerAcknowledgedAt: Long,
        defaultArchiveFormat: ArchiveFormat,
        defaultCompressionLevel: Int,
        defaultChecksumAlgorithm: ChecksumAlgorithm,
        defaultDestinationId: String?,
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.OPERATING_MODE] = operatingMode.storageKey
            prefs[Keys.DISCLAIMER_ACKNOWLEDGED_AT] = disclaimerAcknowledgedAt
            prefs[Keys.DEFAULT_ARCHIVE_FORMAT] = defaultArchiveFormat.storageKey
            prefs[Keys.DEFAULT_COMPRESSION_LEVEL] = defaultCompressionLevel.coerceIn(0, 19)
            prefs[Keys.DEFAULT_CHECKSUM_ALGORITHM] = defaultChecksumAlgorithm.storageKey
            if (defaultDestinationId == null) prefs.remove(Keys.DEFAULT_DESTINATION_ID) else prefs[Keys.DEFAULT_DESTINATION_ID] = defaultDestinationId
            prefs[Keys.ONBOARDING_COMPLETED] = true
        }
    }

    private fun toModel(prefs: Preferences) = AppPreferences(
        language = AppLanguage.fromTag(prefs[Keys.LANGUAGE]),
        themeMode = ThemeMode.fromStorageKey(prefs[Keys.THEME_MODE]),
        dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
        onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
        operatingMode = OperatingMode.fromStorageKey(prefs[Keys.OPERATING_MODE]),
        disclaimerAcknowledgedAt = prefs[Keys.DISCLAIMER_ACKNOWLEDGED_AT],
        defaultArchiveFormat = ArchiveFormat.fromStorageKey(prefs[Keys.DEFAULT_ARCHIVE_FORMAT]),
        defaultCompressionLevel = prefs[Keys.DEFAULT_COMPRESSION_LEVEL] ?: 3,
        defaultChecksumAlgorithm = ChecksumAlgorithm.fromStorageKey(prefs[Keys.DEFAULT_CHECKSUM_ALGORITHM]),
        defaultDestinationId = prefs[Keys.DEFAULT_DESTINATION_ID],
    )

    private object Keys {
        val LANGUAGE = stringPreferencesKey("language_tag")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val OPERATING_MODE = stringPreferencesKey("operating_mode")
        val DISCLAIMER_ACKNOWLEDGED_AT = longPreferencesKey("disclaimer_acknowledged_at")
        val DEFAULT_ARCHIVE_FORMAT = stringPreferencesKey("default_archive_format")
        val DEFAULT_COMPRESSION_LEVEL = intPreferencesKey("default_compression_level")
        val DEFAULT_CHECKSUM_ALGORITHM = stringPreferencesKey("default_checksum_algorithm")
        val DEFAULT_DESTINATION_ID = stringPreferencesKey("default_destination_id")
    }

    private companion object {
        const val TAG = "Prefs"
    }
}
