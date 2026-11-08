package ca.devdcs.backup.core.mode

import androidx.annotation.StringRes
import ca.devdcs.backup.R

/**
 * The execution tier the device owner selected. Drives which engines are available: pure
 * Kotlin/JNI (Tier 1), Shizuku-brokered shell (Tier 2), libsu root shell with bundled CLI tools
 * (Tier 3), or root plus the systemless companion module and boot service (Tier 4).
 */
enum class OperatingMode(
    val storageKey: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val requiresRoot: Boolean,
) {
    STANDARD("standard", R.string.mode_standard, R.string.mode_standard_desc, requiresRoot = false),
    SHIZUKU("shizuku", R.string.mode_shizuku, R.string.mode_shizuku_desc, requiresRoot = false),
    ROOT("root", R.string.mode_root, R.string.mode_root_desc, requiresRoot = true),
    ROOT_MODULE("root_module", R.string.mode_module, R.string.mode_module_desc, requiresRoot = true);

    companion object {
        fun fromStorageKey(key: String?): OperatingMode? = entries.firstOrNull { it.storageKey == key }
    }
}
