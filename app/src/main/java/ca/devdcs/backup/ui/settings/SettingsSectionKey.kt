package ca.devdcs.backup.ui.settings

import androidx.annotation.StringRes
import ca.devdcs.backup.R
import ca.devdcs.backup.ui.navigation.DftbDestination
import java.util.Locale

/**
 * Headings the settings root groups its rows under. Declaration order is display order, so the
 * root list and the tablet list pane read identically.
 */
enum class SettingsCategory(@StringRes val titleRes: Int) {
    GENERAL(R.string.settings_category_general),
    BACKUP(R.string.settings_category_backup),
    SECURITY(R.string.settings_category_security),
    SYSTEM(R.string.settings_category_system),
    ABOUT(R.string.settings_category_about),
}

/**
 * The settings sub-screens, addressed by the stable string [key] carried in
 * [DftbDestination.SettingsSection]. Exists so other features can deep-link into a section
 * (`DftbDestination.SettingsSection(SettingsSectionKey.MODE.key)`) without depending on screen
 * code, and so a stale or mistyped key degrades to an "unknown section" page instead of a crash.
 * Keys are persisted in back stacks across process death, so they must never be renamed.
 */
enum class SettingsSectionKey(
    val key: String,
    val category: SettingsCategory,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
) {
    LANGUAGE("language", SettingsCategory.GENERAL, R.string.settings_language_title, R.string.settings_language_summary),
    APPEARANCE("appearance", SettingsCategory.GENERAL, R.string.settings_appearance_title, R.string.settings_appearance_summary),
    MODE("mode", SettingsCategory.BACKUP, R.string.settings_mode_title, R.string.settings_mode_summary),
    DEFAULTS("defaults", SettingsCategory.BACKUP, R.string.settings_defaults_title, R.string.settings_defaults_summary),
    SECURITY("security", SettingsCategory.SECURITY, R.string.settings_security_title, R.string.settings_security_summary),
    NOTIFICATIONS("notifications", SettingsCategory.SYSTEM, R.string.settings_notifications_title, R.string.settings_notifications_summary),
    STORAGE("storage", SettingsCategory.SYSTEM, R.string.settings_storage_title, R.string.settings_storage_summary),
    ABOUT("about", SettingsCategory.ABOUT, R.string.settings_about_title, R.string.settings_about_summary);

    /** The navigation route that opens this section on its own screen. */
    val destination: DftbDestination.SettingsSection get() = DftbDestination.SettingsSection(key)

    companion object {
        /** Resolves a route key; tolerant of case and surrounding whitespace, null when unknown. */
        fun fromKey(key: String?): SettingsSectionKey? {
            val normalized = key?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.key == normalized }
        }

        /** Sections grouped by [SettingsCategory] in declaration order; empty categories omitted. */
        fun grouped(): List<Pair<SettingsCategory, List<SettingsSectionKey>>> =
            SettingsCategory.entries
                .map { category -> category to entries.filter { it.category == category } }
                .filter { (_, sections) -> sections.isNotEmpty() }
    }
}
