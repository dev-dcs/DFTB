package ca.devdcs.backup.core.discovery.apps

/**
 * Fixed package identities that need special restore handling. Only exact package names: no
 * heuristics about what an app "probably" is, because a wrong guess would either nag the user
 * needlessly or, worse, silently skip a warning.
 */
object AppClassifier {

    /** Root-solution managers whose data restore can break or duplicate root. */
    val ROOT_MANAGER_PACKAGES: Set<String> = setOf(
        "com.topjohnwu.magisk",
        "io.github.huskydg.magisk", // Kitsune / Magisk Delta
        "io.github.vvb2060.magisk", // Magisk Alpha
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext", // KernelSU Next
        "me.bmax.apatch",
    )

    /**
     * Platform packages whose data is tied to the running system image, keys or account state;
     * restoring it onto a live (or different) system risks bootloops or a corrupted settings store.
     */
    val CRITICAL_SYSTEM_PACKAGES: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.shell",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.se",
        "com.android.keychain",
        "com.android.networkstack",
        "com.google.android.networkstack",
        "com.android.server.telecom",
        "com.android.externalstorage",
        "com.android.location.fused",
        "com.android.inputdevices",
        "com.android.providers.settings",
        "com.android.providers.telephony",
        "com.android.providers.contacts",
        "com.android.providers.calendar",
        "com.android.providers.downloads",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.google.android.providers.media.module",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
    )

    fun isRootManager(packageName: String): Boolean = packageName in ROOT_MANAGER_PACKAGES

    fun isCriticalSystem(packageName: String): Boolean = packageName in CRITICAL_SYSTEM_PACKAGES

    /**
     * DFTB itself in any variant: [ownPackageName] is the running applicationId (debug builds add a
     * suffix), and [basePackageName] is the release id so a debug build also recognises release.
     */
    fun isSelf(packageName: String, ownPackageName: String, basePackageName: String): Boolean =
        packageName == ownPackageName || packageName == basePackageName ||
            packageName.startsWith("$basePackageName.")

    /** Classifies by the two ApplicationInfo flags that matter. */
    fun kindOf(isSystem: Boolean, isUpdatedSystem: Boolean): AppKind = when {
        isUpdatedSystem -> AppKind.UPDATED_SYSTEM
        isSystem -> AppKind.SYSTEM
        else -> AppKind.USER
    }
}
