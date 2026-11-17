package ca.devdcs.backup.core.restore.apps

/** When a backed-up APK is (re)installed. */
enum class AppVersionPolicy {
    /** Install when missing or when the backup is newer; same version = data only; installed newer = data only (or downgrade if allowed). */
    AUTO,

    /** Always install the backed-up APK, even over the same version (downgrade still needs [AppRestoreOptions.allowDowngrade]). */
    REINSTALL,

    /** Never install; restore data onto the version that is installed (the app must be installed). */
    DATA_ONLY,
}

/**
 * App-specific restore knobs on top of the shared
 * [ca.devdcs.backup.core.restore.engine.RestoreOptions]. Exists because user targeting, downgrade,
 * signature and safety overrides only make sense for apps and are not part of the shared contract.
 */
data class AppRestoreOptions(
    /** Android user to restore into; null = the user DFTB runs as. */
    val targetUserId: Int? = null,
    val versionPolicy: AppVersionPolicy = AppVersionPolicy.AUTO,
    /** Pass `-d` to `pm install-create` when the backup is older than what is installed (privileged tiers only). */
    val allowDowngrade: Boolean = false,
    /**
     * Proceed although the installed app is signed by a different key than the backup. The APK is
     * then never installed (Android would reject it) and only data is restored, which hands that data
     * to whoever controls the installed app's key.
     */
    val allowSignatureMismatch: Boolean = false,
    /** Allow restoring DFTB itself, root managers and the Shizuku manager. */
    val allowProtectedPackages: Boolean = false,
    /** Re-grant recorded runtime permissions with `pm grant` (privileged tiers only). */
    val restoreRuntimePermissions: Boolean = true,
    /** Restore `Android/data` and `Android/media` content. */
    val restoreExternalData: Boolean = true,
    /** Restore `Android/obb` content. */
    val restoreObb: Boolean = true,
    /** Restore `cache` / `code_cache` when the backup contains them. */
    val restoreCache: Boolean = false,
    /** Restore the recorded installer of record with `pm install-create -i` (privileged tiers only). */
    val restoreInstallerAttribution: Boolean = false,
)
