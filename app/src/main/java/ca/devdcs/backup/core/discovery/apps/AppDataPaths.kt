package ca.devdcs.backup.core.discovery.apps

/**
 * Pure path rules for an app's data locations on a given Android user. Centralised so the size
 * scan, the backup engine and the restore engine agree on exactly which directories belong to an
 * app, including the multi-user layout (/data/user/N, /storage/emulated/N).
 */
object AppDataPaths {

    /** Android's per-user uid range (UserHandle.PER_USER_RANGE). */
    const val PER_USER_RANGE = 100_000

    fun userIdOf(uid: Int): Int = uid / PER_USER_RANGE

    /** CE data dir. User 0 uses the canonical /data/data alias. */
    fun ceDataDir(packageName: String, userId: Int): String =
        if (userId == 0) "/data/data/$packageName" else "/data/user/$userId/$packageName"

    fun deDataDir(packageName: String, userId: Int): String = "/data/user_de/$userId/$packageName"

    fun ceCacheDir(packageName: String, userId: Int): String = ceDataDir(packageName, userId) + "/cache"

    /**
     * Root of emulated shared storage for [userId]. A uid-0 shell reads the backing dir
     * /data/media/N directly (no FUSE, no per-process mount view); an adb-uid shell and apps go
     * through /storage/emulated/N.
     */
    fun emulatedRoot(userId: Int, backingStore: Boolean): String =
        if (backingStore) "/data/media/$userId" else "/storage/emulated/$userId"

    fun externalDataDir(packageName: String, userId: Int, backingStore: Boolean = false): String =
        emulatedRoot(userId, backingStore) + "/Android/data/$packageName"

    fun mediaDir(packageName: String, userId: Int, backingStore: Boolean = false): String =
        emulatedRoot(userId, backingStore) + "/Android/media/$packageName"

    fun obbDir(packageName: String, userId: Int, backingStore: Boolean = false): String =
        emulatedRoot(userId, backingStore) + "/Android/obb/$packageName"

    /** Path for [location] of [packageName]. */
    fun pathFor(location: AppDataLocation, packageName: String, userId: Int, backingStore: Boolean): String =
        when (location) {
            AppDataLocation.CE_DATA -> ceDataDir(packageName, userId)
            AppDataLocation.DE_DATA -> deDataDir(packageName, userId)
            AppDataLocation.CE_CACHE -> ceCacheDir(packageName, userId)
            AppDataLocation.EXTERNAL_DATA -> externalDataDir(packageName, userId, backingStore)
            AppDataLocation.MEDIA -> mediaDir(packageName, userId, backingStore)
            AppDataLocation.OBB -> obbDir(packageName, userId, backingStore)
        }

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*$")

    /**
     * Android package-name grammar (single-segment names such as `android` are legal). Names are still shell-quoted everywhere; this additionally
     * keeps a malformed name from ever forming a path such as `/data/data/..`.
     */
    fun isValidPackageName(name: String): Boolean = name.length <= 255 && PACKAGE_NAME.matches(name)
}
