package ca.devdcs.backup.core.restore.apps

import ca.devdcs.backup.core.discovery.apps.AppDataPaths

/**
 * Android uid / gid / SELinux category arithmetic for restored app data. Pure (mirrors
 * `multiuser.h` and libselinux `android_seapp.c`) so it is unit-tested; exists because the uid of
 * a reinstalled package usually differs from the one recorded in the backup, and every derived
 * id (cache gid, MLS categories) must be recomputed from the CURRENT uid.
 */
object AppIds {
    const val PER_USER_RANGE = AppDataPaths.PER_USER_RANGE
    const val FIRST_APPLICATION_UID = 10_000
    const val LAST_APPLICATION_UID = 19_999
    const val FIRST_CACHE_GID = 20_000

    fun userId(uid: Int): Int = uid / PER_USER_RANGE

    fun appId(uid: Int): Int = uid % PER_USER_RANGE

    fun uid(userId: Int, appId: Int): Int = userId * PER_USER_RANGE + appId

    fun isApplication(uid: Int): Boolean = appId(uid) in FIRST_APPLICATION_UID..LAST_APPLICATION_UID

    /** `multiuser_get_cache_gid`: owner group of `cache` / `code_cache`; null for non-app uids. */
    fun cacheGid(uid: Int): Int? {
        if (!isApplication(uid)) return null
        return uid(userId(uid), appId(uid) - FIRST_APPLICATION_UID + FIRST_CACHE_GID)
    }

    /** How the MLS categories of a context were derived (`levelFrom` in seapp_contexts). */
    enum class LevelFrom { NONE, APP, USER, ALL }

    /** `c<a&0xff>,c<256+(a>>8&0xff)>` / `c<512+(u&0xff)>,c<768+(u>>8&0xff)>` for [uid]. */
    fun categories(uid: Int, levelFrom: LevelFrom): String {
        val user = userId(uid)
        val app = appId(uid) - FIRST_APPLICATION_UID
        val appCats = "c${app and 0xff},c${256 + ((app shr 8) and 0xff)}"
        val userCats = "c${512 + (user and 0xff)},c${768 + ((user shr 8) and 0xff)}"
        return when (levelFrom) {
            LevelFrom.NONE -> ""
            LevelFrom.APP -> appCats
            LevelFrom.USER -> userCats
            LevelFrom.ALL -> "$appCats,$userCats"
        }
    }

    /** Which [LevelFrom] produced the category list `c1,c2,...` (app categories are < 512). */
    fun levelFromOf(categoryList: String): LevelFrom? {
        if (categoryList.isEmpty()) return LevelFrom.NONE
        val values = categoryList.split(',').map { it.removePrefix("c").toIntOrNull() ?: return null }
        val app = values.count { it < 512 }
        val user = values.count { it >= 512 }
        return when {
            app == 2 && user == 2 -> LevelFrom.ALL
            app == 2 && user == 0 -> LevelFrom.APP
            app == 0 && user == 2 -> LevelFrom.USER
            else -> null
        }
    }

    /**
     * [recorded] (`u:object_r:app_data_file:s0:c12,c257,c512,c768`) with its categories recomputed
     * for [uid], keeping the type and the levelFrom shape. Null when [recorded] is not a parseable
     * `user:role:type:s0[:cats]` context or its categories have no recognizable shape.
     */
    fun relabel(recorded: String, uid: Int): String? {
        val parts = recorded.split(':')
        if (parts.size < 4 || parts.take(4).any { it.isEmpty() }) return null
        val base = parts.take(4).joinToString(":")
        if (!parts[3].startsWith("s")) return null
        val cats = if (parts.size >= 5) parts.subList(4, parts.size).joinToString(":") else ""
        val shape = levelFromOf(cats) ?: return null
        val recomputed = categories(uid, shape)
        return if (recomputed.isEmpty()) base else "$base:$recomputed"
    }
}

/** How the installed app's signing relates to the backup's. */
enum class SignatureMatch {
    /** Same certificate set. */
    SAME,

    /** Single signer whose rotation lineage links the two certificates (APK signature scheme v3). */
    ROTATED,

    /** Different signers: an update would be rejected and the data must not be handed over. */
    MISMATCH,

    /** One side's digests are unknown (old manifest, unreadable package info). */
    UNKNOWN,
}

/** Signing-certificate comparison on SHA-256 hex digests. Pure. */
object SignatureCheck {

    fun normalize(digest: String): String = digest.replace(":", "").trim().lowercase()

    fun compare(
        installedCurrent: List<String>,
        installedPast: List<String>,
        backupCurrent: List<String>,
        backupPast: List<String>,
    ): SignatureMatch {
        val ic = installedCurrent.map(::normalize).filter { it.isNotEmpty() }.toSet()
        val bc = backupCurrent.map(::normalize).filter { it.isNotEmpty() }.toSet()
        if (ic.isEmpty() || bc.isEmpty()) return SignatureMatch.UNKNOWN
        if (ic == bc) return SignatureMatch.SAME
        // Multiple signers have no rotation: Android requires the exact same set.
        if (ic.size > 1 || bc.size > 1) return SignatureMatch.MISMATCH
        val ip = installedPast.map(::normalize).toSet()
        val bp = backupPast.map(::normalize).toSet()
        return if (bc.first() in ip || ic.first() in bp) SignatureMatch.ROTATED else SignatureMatch.MISMATCH
    }
}

/** What to do with the backed-up APK set. */
enum class ApkAction {
    INSTALL,
    /** Package exists on the device for another user with the same version: `pm install-existing`. */
    INSTALL_EXISTING,
    SKIP_SAME_VERSION,
    SKIP_INSTALLED_NEWER,
    SKIP_POLICY,
    SKIP_NO_APK,
    SKIP_SIGNATURE_MISMATCH,
}

data class ApkDecision(val action: ApkAction, val downgrade: Boolean = false)

/** The versionCode policy as a pure decision table. */
object VersionDecision {

    /**
     * [installedForUser]: versionCode installed for the target user (null = not installed there).
     * [onDevice]: versionCode of the package for any user (null = not on the device at all).
     * [privileged]: a shell tier is available (needed for `install-existing` and `-d`).
     */
    fun decide(
        backupVersion: Long,
        installedForUser: Long?,
        onDevice: Long?,
        policy: AppVersionPolicy,
        restoreApks: Boolean,
        apkInBackup: Boolean,
        allowDowngrade: Boolean,
        signature: SignatureMatch,
        privileged: Boolean,
    ): ApkDecision {
        if (!restoreApks || policy == AppVersionPolicy.DATA_ONLY) return ApkDecision(ApkAction.SKIP_POLICY)
        if (!apkInBackup) return ApkDecision(ApkAction.SKIP_NO_APK)
        if (signature == SignatureMatch.MISMATCH) return ApkDecision(ApkAction.SKIP_SIGNATURE_MISMATCH)
        val reference = installedForUser ?: onDevice
        if (installedForUser == null && onDevice != null && onDevice == backupVersion && privileged) {
            return ApkDecision(ApkAction.INSTALL_EXISTING)
        }
        if (reference == null || backupVersion > reference) return ApkDecision(ApkAction.INSTALL)
        if (backupVersion == reference) {
            return if (policy == AppVersionPolicy.REINSTALL || installedForUser == null) ApkDecision(ApkAction.INSTALL)
            else ApkDecision(ApkAction.SKIP_SAME_VERSION)
        }
        // Backup is older than what the device has.
        return if (allowDowngrade && privileged) ApkDecision(ApkAction.INSTALL, downgrade = true)
        else ApkDecision(ApkAction.SKIP_INSTALLED_NEWER)
    }
}

/** One APK of the backed-up split set. */
data class PlannedApk(
    /** Archive member path (`apps/<pkg>/split_config.arm64_v8a.apk`). */
    val archivePath: String,
    /** File name inside the install session (safe for `pm install-write`). */
    val sessionName: String,
    val sizeBytes: Long?,
    val isBase: Boolean,
)

/** Orders and names the APK set for an install session. Pure. */
object ApkSetPlanner {

    private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.apk$")
    const val BASE_APK = "base.apk"

    /** True for `apps/<pkg>/base.apk` / `apps/<pkg>/split_*.apk` members of [packageDir]. */
    fun isApkMember(packageDir: String, path: String): Boolean {
        if (!path.startsWith("$packageDir/")) return false
        val name = path.substring(packageDir.length + 1)
        return !name.contains('/') && (name == BASE_APK || (name.startsWith("split_") && name.endsWith(".apk")))
    }

    /**
     * Base first, then feature splits, then `config.*` splits; manifest order inside each group,
     * members the manifest does not list after it in name order. [members] maps archive path to
     * size (null = unknown).
     */
    fun plan(packageDir: String, members: Map<String, Long?>, manifestApkFiles: List<String>): List<PlannedApk> {
        val apks = members.keys.filter { isApkMember(packageDir, it) }
        val rank = manifestApkFiles.withIndex().associate { it.value to it.index }
        fun fileName(path: String) = path.substringAfterLast('/')
        fun group(name: String) = when {
            name == BASE_APK -> 0
            name.startsWith("split_config.") -> 2
            else -> 1
        }
        val ordered = apks.sortedWith(
            compareBy<String>({ group(fileName(it)) }, { rank[fileName(it)] ?: Int.MAX_VALUE }, { fileName(it) }),
        )
        val used = HashSet<String>()
        return ordered.mapIndexed { index, path ->
            val name = fileName(path)
            var session = if (SAFE_NAME.matches(name)) name else "split_$index.apk"
            var n = 2
            while (!used.add(session.lowercase())) session = session.removeSuffix(".apk") + "_${n++}.apk"
            PlannedApk(path, session, members[path], name == BASE_APK)
        }
    }

    /** Sum of sizes, or null when any is unknown (then `-S` is omitted). */
    fun totalBytes(apks: List<PlannedApk>): Long? =
        if (apks.isEmpty() || apks.any { it.sizeBytes == null || it.sizeBytes < 0 }) null else apks.sumOf { it.sizeBytes!! }

    /** Manifest-listed APK files the archive does not contain (the install would fail). */
    fun missing(apks: List<PlannedApk>, manifestApkFiles: List<String>): List<String> {
        val present = apks.map { it.archivePath.substringAfterLast('/') }.toSet()
        return manifestApkFiles.filter { it !in present }
    }
}

/** How a recorded permission can be handed back. */
enum class PermissionKind {
    /** `dangerous` runtime permission. */
    RUNTIME,
    /** Development permission (`pm grant` works, e.g. WRITE_SECURE_SETTINGS). */
    DEVELOPMENT,
    /** normal / signature / privileged: granted (or not) by the system, never by `pm grant`. */
    SYSTEM_MANAGED,
    /** Not defined on this device. */
    UNDEFINED,
}

/** Result of [PermissionPlanner.plan]. */
data class GrantPlan(
    /** To pass to `pm grant`, foreground before background permissions. */
    val toGrant: List<String>,
    val alreadyGranted: List<String>,
    /** The installed version no longer requests these. */
    val notRequested: List<String>,
    /** Not defined by the platform / any installed package here. */
    val undefined: List<String>,
    /** Silently left to the system (normal, signature, privileged). */
    val systemManaged: List<String>,
)

/** Chooses which recorded permissions to re-grant. Pure. */
object PermissionPlanner {

    fun plan(
        recordedGranted: List<String>,
        requestedByInstalled: Set<String>,
        currentlyGranted: Set<String>,
        kinds: Map<String, PermissionKind>,
    ): GrantPlan {
        val toGrant = ArrayList<String>()
        val already = ArrayList<String>()
        val notRequested = ArrayList<String>()
        val undefined = ArrayList<String>()
        val system = ArrayList<String>()
        for (permission in recordedGranted.distinct()) {
            when {
                permission !in requestedByInstalled -> notRequested += permission
                else -> when (kinds[permission] ?: PermissionKind.UNDEFINED) {
                    PermissionKind.UNDEFINED -> undefined += permission
                    PermissionKind.SYSTEM_MANAGED -> system += permission
                    PermissionKind.RUNTIME, PermissionKind.DEVELOPMENT ->
                        if (permission in currentlyGranted) already += permission else toGrant += permission
                }
            }
        }
        return GrantPlan(toGrant.sortedBy { if (isBackgroundPermission(it)) 1 else 0 }, already, notRequested, undefined, system)
    }

    /** Background permissions are only grantable once their foreground counterpart is granted. */
    fun isBackgroundPermission(permission: String): Boolean =
        permission.endsWith("_BACKGROUND") || permission.contains("BACKGROUND_") || permission == "android.permission.ACCESS_MEDIA_LOCATION"
}

/** Packages whose restore is refused or warned about. Pure. */
object ProtectedPackages {

    /** Root managers and the Shizuku manager: restoring them while they grant DFTB its privileges can lock the device owner out. */
    val PRIVILEGE_PROVIDERS = setOf(
        "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk",
        "io.github.huskydg.magisk",
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "com.sukisu.ultra",
        "me.bmax.apatch",
        "moe.shizuku.privileged.api",
    )

    /** System components whose data restore can break the running OS. */
    val CRITICAL_SYSTEM = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.shell",
        "com.android.bluetooth",
        "com.android.networkstack",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.keychain",
        "com.android.server.telecom",
        "com.android.nfc",
    )

    enum class Verdict { ALLOWED, WARN_SYSTEM, REFUSED_SELF, REFUSED_PRIVILEGE_PROVIDER }

    fun classify(packageName: String, selfPackage: String, systemKind: Boolean): Verdict = when {
        packageName == selfPackage -> Verdict.REFUSED_SELF
        packageName in PRIVILEGE_PROVIDERS -> Verdict.REFUSED_PRIVILEGE_PROVIDER
        systemKind || packageName in CRITICAL_SYSTEM || packageName.startsWith("com.android.providers.") -> Verdict.WARN_SYSTEM
        else -> Verdict.ALLOWED
    }
}

/** uid:gid pair for `chown`. */
data class Owner(val uid: Int, val gid: Int)

/** External-storage part being restored. */
enum class ExternalPart(val folder: String) { DATA("data"), MEDIA("media"), OBB("obb") }

/** Ownership rules for restored shared-storage dirs. Pure. */
object ExternalOwnership {

    /**
     * Owner for everything restored below `Android/<folder>/<pkg>` on the lower filesystem
     * (`/data/media/<u>`). The platform decides the group (ext_data_rw / ext_obb_rw / per-app ext
     * gid / media_rw depending on release and sdcardfs vs FUSE), so it is never hard-coded:
     * - the package dir already exists (vold/installd made it) → match it exactly;
     * - otherwise take the group of the existing `Android/<folder>` parent and the app uid for
     * data/obb (the app owns those dirs) or the parent's uid for media (MediaProvider owns it).
     * Null when neither is known: then ownership is left as tar wrote it and a warning is raised.
     */
    fun ownerFor(part: ExternalPart, existingPackageDir: Owner?, parentDir: Owner?, appUid: Int): Owner? {
        if (existingPackageDir != null) return existingPackageDir
        if (parentDir == null) return null
        return when (part) {
            ExternalPart.MEDIA -> parentDir
            ExternalPart.DATA, ExternalPart.OBB -> Owner(appUid, parentDir.gid)
        }
    }
}
