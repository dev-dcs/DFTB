package ca.devdcs.backup.core.exec

import ca.devdcs.backup.core.mode.OperatingMode

/**
 * The privilege level a [CommandExecutor] runs commands at. Independent of [OperatingMode] because
 * two modes can share a tier: ROOT and ROOT_MODULE both execute through a superuser shell, differing
 * only in whether the systemless companion module is also present.
 */
enum class ElevationTier(val storageKey: String) {
    /** No shell. Only in-process, app-context file operations (SAF / MediaStore / app dirs). */
    NONE("none"),

    /** Commands brokered by the user-authorized Shizuku service (uid 2000 = adb, or 0 = root). */
    SHIZUKU("shizuku"),

    /** Superuser shell on a user-rooted device (libsu). */
    ROOT("root");

    companion object {
        /** The execution tier a given operating mode runs its shell commands at. */
        fun forMode(mode: OperatingMode): ElevationTier = when (mode) {
            OperatingMode.STANDARD -> NONE
            OperatingMode.SHIZUKU -> SHIZUKU
            OperatingMode.ROOT, OperatingMode.ROOT_MODULE -> ROOT
        }
    }
}
