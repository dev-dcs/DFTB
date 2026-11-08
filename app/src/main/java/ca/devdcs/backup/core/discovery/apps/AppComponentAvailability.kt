package ca.devdcs.backup.core.discovery.apps

import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.domain.model.AppComponent

/**
 * The device facts that decide which [AppComponent]s are reachable. Gathered by [AppInspector] and
 * kept as plain values so [AppComponentAvailabilityResolver] is a pure, unit-testable function.
 */
data class AccessEnvironment(
    val tier: ElevationTier,
    /** The tier's executor is running and authorized right now. */
    val executorAvailable: Boolean,
    /** `id -u` of the shell (0 root, 2000 adb), or null if unknown / no shell. */
    val shellUid: Int?,
    val sdkInt: Int,
    /** API 29: Environment.isExternalStorageLegacy(). Ignored on 30+. */
    val legacyExternalStorage: Boolean,
    /** API 29: READ_EXTERNAL_STORAGE granted. */
    val readStorageGranted: Boolean,
    /** API 30+: Environment.isExternalStorageManager(). */
    val allFilesAccess: Boolean,
    /** PackageManager.canRequestPackageInstalls(). Informational; no rule depends on it any more. */
    val canRequestPackageInstalls: Boolean,
)

/**
 * Decides, for each [AppComponent], whether the current tier can capture it and why not. Exists so
 * the picker can grey out components with a precise reason instead of letting a backup fail later.
 *
 * Rules:
 * - APK: always (base/split APKs are world-readable to apps on API 29+).
 * - INTERNAL_DATA / CACHE: a uid-0 shell (root, or Shizuku started by root).
 * - EXTERNAL_DATA (Android/data): any working shell; Standard only on API 29 with legacy storage.
 * - OBB (Android/obb): any working shell; Standard on 29 like external data. On 30+ Standard cannot
 * reach it: the platform's installer OBB exemption also needs WRITE_EXTERNAL_STORAGE, which the
 * manifest caps at API 29, and All files access does not substitute.
 */
object AppComponentAvailabilityResolver {

    const val SHELL_UID_ROOT = 0
    private const val API_R = 30

    fun resolve(env: AccessEnvironment): Map<AppComponent, ComponentAvailability> =
        AppComponent.entries.associateWith { resolve(it, env) }

    fun resolve(component: AppComponent, env: AccessEnvironment): ComponentAvailability {
        val reason = when (component) {
            AppComponent.APK -> ComponentAvailabilityReason.AVAILABLE
            AppComponent.INTERNAL_DATA, AppComponent.CACHE -> privateDataReason(env)
            AppComponent.EXTERNAL_DATA -> sharedAppDirReason(env)
            AppComponent.OBB -> sharedAppDirReason(env)
        }
        return ComponentAvailability(component, reason == ComponentAvailabilityReason.AVAILABLE, reason)
    }

    private fun hasShell(env: AccessEnvironment) = env.tier != ElevationTier.NONE && env.executorAvailable

    private fun privateDataReason(env: AccessEnvironment): ComponentAvailabilityReason = when {
        env.tier == ElevationTier.NONE -> ComponentAvailabilityReason.NEEDS_ROOT
        !env.executorAvailable -> ComponentAvailabilityReason.ELEVATION_UNAVAILABLE
        env.tier == ElevationTier.ROOT -> ComponentAvailabilityReason.AVAILABLE
        env.shellUid == SHELL_UID_ROOT -> ComponentAvailabilityReason.AVAILABLE
        else -> ComponentAvailabilityReason.SHIZUKU_ADB_CANNOT_READ_PRIVATE_DATA
    }

    private fun sharedAppDirReason(env: AccessEnvironment): ComponentAvailabilityReason {
        if (hasShell(env)) return ComponentAvailabilityReason.AVAILABLE
        val standardOk: Boolean
        val standardReason: ComponentAvailabilityReason
        if (env.sdkInt < API_R) {
            standardOk = env.legacyExternalStorage && env.readStorageGranted
            standardReason = ComponentAvailabilityReason.NEEDS_LEGACY_STORAGE_ACCESS
        } else {
            standardOk = false
            standardReason = ComponentAvailabilityReason.NEEDS_SHIZUKU_OR_ROOT
        }
        return when {
            standardOk -> ComponentAvailabilityReason.AVAILABLE
            // A shell tier was chosen but is down: getting it back is the real fix.
            env.tier != ElevationTier.NONE -> ComponentAvailabilityReason.ELEVATION_UNAVAILABLE
            else -> standardReason
        }
    }
}
