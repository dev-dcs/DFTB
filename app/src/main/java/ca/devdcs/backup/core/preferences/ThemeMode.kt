package ca.devdcs.backup.core.preferences

import androidx.annotation.StringRes
import ca.devdcs.backup.R

enum class ThemeMode(val storageKey: String, @StringRes val labelRes: Int) {
    SYSTEM("system", R.string.theme_mode_system),
    LIGHT("light", R.string.theme_mode_light),
    DARK("dark", R.string.theme_mode_dark);

    companion object {
        fun fromStorageKey(key: String?): ThemeMode = entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}
