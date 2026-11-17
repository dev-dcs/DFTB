package ca.devdcs.backup.core.locale

import java.util.Locale

/**
 * The seven UI languages DFTB ships. [nativeName] is intentionally untranslated: a language
 * picker must show each entry in its own script so a user who cannot read the current language
 * can still find theirs.
 */
enum class AppLanguage(
    val tag: String,
    val nativeName: String,
    val englishName: String,
    val isRtl: Boolean = false,
) {
    ENGLISH("en", "English", "English"),
    FRENCH("fr", "Français", "French"),
    SPANISH("es", "Español", "Spanish"),
    CHINESE_SIMPLIFIED("zh-CN", "简体中文", "Chinese (Simplified)"),
    JAPANESE("ja", "日本語", "Japanese"),
    GREEK("el", "Ελληνικά", "Greek"),
    ARABIC("ar", "العربية", "Arabic", isRtl = true);

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        /** First-launch default is English regardless of the device locale, per spec. */
        val DEFAULT = ENGLISH

        fun fromTag(tag: String?): AppLanguage {
            if (tag.isNullOrBlank()) return DEFAULT
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }?.let { return it }
            // Tolerate region/script variants ("fr-CA", "zh-Hans-CN", "es-419").
            val language = Locale.forLanguageTag(tag).language
            return entries.firstOrNull { it.locale.language == language } ?: DEFAULT
        }

        /** The supported language closest to the device's current system locale, if any. */
        fun matchingSystemLocale(): AppLanguage? {
            val system = Locale.getDefault()
            return entries.firstOrNull { it.locale.language == system.language }
        }
    }
}
