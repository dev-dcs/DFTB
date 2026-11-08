package ca.devdcs.backup.core.discovery.apps

import androidx.annotation.StringRes
import ca.devdcs.backup.R
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.domain.model.AppComponent

/**
 * One installed package as the app picker and the backup engine see it. Deliberately icon-free so a
 * list of several hundred apps stays cheap to hold and diff; icons come from
 * [AppInspector.loadIcon] on demand.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val kind: AppKind,
    val enabled: Boolean,
    val versionName: String?,
    val versionCode: Long,
    val targetSdk: Int,
    val minSdk: Int,
    val uid: Int,
    /** Android user the package was listed for (uid / 100000). */
    val userId: Int,
    val installSource: InstallSource,
    val firstInstallTimeMillis: Long,
    val lastUpdateTimeMillis: Long,
    /** Absolute path of base.apk. */
    val baseApkPath: String,
    /** Split APK paths, index-aligned with [splitNames]. Empty for monolithic APKs. */
    val splitApkPaths: List<String>,
    val splitNames: List<String>,
    /** Where the package's OBB expansion files would live for [userId] (may not exist). */
    val obbDirPath: String,
    val nativeLibraryDir: String?,
    val flags: AppSpecialFlags,
) {
    val isSplitBundle: Boolean get() = splitApkPaths.isNotEmpty()
}

/**
 * Where a package came from in the system image sense. [UPDATED_SYSTEM] matters for restore: only
 * the update layer can be reinstalled, the preinstalled base cannot be removed or replaced.
 */
enum class AppKind(@StringRes val labelRes: Int) {
    USER(R.string.apps_kind_user),
    SYSTEM(R.string.apps_kind_system),
    UPDATED_SYSTEM(R.string.apps_kind_updated_system),
}

/**
 * Installer attribution. On API 30+ all three come from InstallSourceInfo; on API 29 only
 * [installerPackageName] is known. [packageSource] is InstallSourceInfo.getPackageSource (API 33+).
 */
data class InstallSource(
    val installerPackageName: String?,
    val initiatingPackageName: String?,
    val originatingPackageName: String?,
    val packageSource: Int?,
)

/**
 * Facts that change how an app must be handled, taken only from what PackageManager reports or
 * from fixed package identities. Nothing here is guessed from app category or name heuristics.
 */
data class AppSpecialFlags(
    val isSplitBundle: Boolean,
    /** True/false when the OBB dir was checked with sufficient access, null when it could not be. */
    val hasObb: Boolean?,
    /** This package is DFTB itself (any build variant); backing it up mid-run is self-referential. */
    val isSelf: Boolean,
    /** Magisk / KernelSU / APatch manager; restoring its data can break root. */
    val isRootManager: Boolean,
    /** Core platform package whose data restore can bootloop or corrupt the system. */
    val isCriticalSystem: Boolean,
    /** ApplicationInfo.FLAG_ALLOW_BACKUP: the developer's own consent to backups. */
    val allowBackup: Boolean,
    val isDebuggable: Boolean,
    /** PackageInfo.isApex: an APEX module, never restorable as an APK. */
    val isApex: Boolean,
) {
    /** True when a restore of this app's data should require an explicit confirmation. */
    val warnOnRestore: Boolean get() = isRootManager || isCriticalSystem || isSelf

    /** Badges to show for this app, in display order. */
    fun badges(): List<AppFlagBadge> = buildList {
        if (isSelf) add(AppFlagBadge.SELF)
        if (isRootManager) add(AppFlagBadge.ROOT_MANAGER)
        if (isCriticalSystem) add(AppFlagBadge.CRITICAL_SYSTEM)
        if (isApex) add(AppFlagBadge.APEX)
        if (isSplitBundle) add(AppFlagBadge.SPLIT)
        if (hasObb == true) add(AppFlagBadge.OBB)
        if (!allowBackup) add(AppFlagBadge.NO_ALLOW_BACKUP)
        if (isDebuggable) add(AppFlagBadge.DEBUGGABLE)
    }
}

/** Localized label for each [AppSpecialFlags] fact the UI surfaces. */
enum class AppFlagBadge(@StringRes val labelRes: Int) {
    SELF(R.string.apps_flag_self),
    ROOT_MANAGER(R.string.apps_flag_root_manager),
    CRITICAL_SYSTEM(R.string.apps_flag_critical_system),
    APEX(R.string.apps_flag_apex),
    SPLIT(R.string.apps_flag_split),
    OBB(R.string.apps_flag_obb),
    NO_ALLOW_BACKUP(R.string.apps_flag_no_allow_backup),
    DEBUGGABLE(R.string.apps_flag_debuggable),
}

/** Where an [AppSizeInfo]'s numbers came from, so the UI can label estimates honestly. */
enum class SizeSource(@StringRes val labelRes: Int) {
    /** StorageStatsManager (Standard mode with usage access). Data is an aggregate, not per location. */
    STATS_MANAGER(R.string.apps_size_source_stats),
    /** `du -sk` through the Shizuku/root shell, per location. */
    SHELL_DU(R.string.apps_size_source_du),
    /** Only APK files could be measured. */
    APK_ONLY(R.string.apps_size_source_apk_only),
}

/** Per-package on-disk location measured by the shell scan. */
enum class AppDataLocation {
    /** Credential-encrypted data dir (/data/user/N/pkg; /data/data/pkg for user 0). */
    CE_DATA,
    /** Device-protected data dir (/data/user_de/N/pkg). */
    DE_DATA,
    /** CE data's cache subdir (already included in [CE_DATA]). */
    CE_CACHE,
    /** Android/data/pkg on emulated storage. */
    EXTERNAL_DATA,
    /** Android/media/pkg on emulated storage. */
    MEDIA,
    /** Android/obb/pkg on emulated storage. */
    OBB,
}

/**
 * Size breakdown for one app. Null means "not measurable at this tier", never "zero": a location
 * that does not exist is reported as 0, one that could not be read is null and listed in
 * [unreadable].
 */
data class AppSizeInfo(
    val apkBytes: Long,
    val splitBytes: Long,
    /** CE data dir, cache included (SHELL_DU). */
    val internalDataBytes: Long? = null,
    val deviceProtectedBytes: Long? = null,
    val externalDataBytes: Long? = null,
    val mediaBytes: Long? = null,
    val obbBytes: Long? = null,
    /** SHELL_DU: CE cache dir. STATS_MANAGER: StorageStats.getCacheBytes (internal + external cache). */
    val cacheBytes: Long? = null,
    /**
     * STATS_MANAGER only: StorageStats.getDataBytes, the system's aggregate of CE + DE data,
     * Android/data and cache. Kept separate because it cannot be split into the fields above.
     */
    val aggregateDataBytes: Long? = null,
    val source: SizeSource,
    val unreadable: Set<AppDataLocation> = emptySet(),
) {
    /**
     * Best known total without double counting: APKs plus either the per-location sizes (cache is
     * inside CE data) or the stats aggregate.
     */
    val totalKnownBytes: Long
        get() = apkBytes + splitBytes + when (source) {
            SizeSource.STATS_MANAGER -> aggregateDataBytes ?: 0L
            SizeSource.SHELL_DU -> listOfNotNull(internalDataBytes, deviceProtectedBytes, externalDataBytes, mediaBytes, obbBytes).sum()
            SizeSource.APK_ONLY -> 0L
        }
}

/** Ownership and SELinux label of a data dir, captured so a restore can recreate it exactly. */
data class DataDirMetadata(
    val ownerUid: Int?,
    val ownerGid: Int?,
    /** e.g. `u:object_r:app_data_file:s0:c512,c768`; null when the shell could not read it. */
    val seLinuxContext: String?,
)

/** Restore metadata for one package's private data dirs (shell tiers only). */
data class AppDataMetadata(
    val packageName: String,
    val userId: Int,
    val ceData: DataDirMetadata?,
    val deData: DataDirMetadata?,
)

/** Why an [AppComponent] can or cannot be captured at the current tier. */
enum class ComponentAvailabilityReason(@StringRes val messageRes: Int) {
    AVAILABLE(R.string.apps_avail_available),
    NEEDS_ROOT(R.string.apps_avail_needs_root),
    /** Shizuku is running as adb (uid 2000), which cannot read /data/data. */
    SHIZUKU_ADB_CANNOT_READ_PRIVATE_DATA(R.string.apps_avail_shizuku_adb_private),
    NEEDS_SHIZUKU_OR_ROOT(R.string.apps_avail_needs_shizuku_or_root),
    /** API 29: legacy external storage is off or READ_EXTERNAL_STORAGE not granted. */
    NEEDS_LEGACY_STORAGE_ACCESS(R.string.apps_avail_needs_legacy_storage),
    /** Retained for source compatibility; no longer produced (API 30+ Standard OBB → NEEDS_SHIZUKU_OR_ROOT). */
    NEEDS_INSTALL_AND_ALL_FILES_ACCESS(R.string.apps_avail_needs_install_all_files),
    /** The selected Shizuku/root tier is not currently usable (not running / not granted). */
    ELEVATION_UNAVAILABLE(R.string.apps_avail_elevation_unavailable),
}

data class ComponentAvailability(
    val component: AppComponent,
    val available: Boolean,
    val reason: ComponentAvailabilityReason,
)

/** Non-fatal problems found during a scan, surfaced instead of thrown. */
enum class AppScanWarningKind(@StringRes val messageRes: Int) {
    LIMITED_PACKAGE_VISIBILITY(R.string.apps_warn_limited_visibility),
    USAGE_ACCESS_NOT_GRANTED(R.string.apps_warn_usage_access),
    PACKAGE_LIST_FAILED(R.string.apps_warn_list_failed),
    PACKAGE_READ_FAILED(R.string.apps_warn_package_failed),
    STATS_QUERY_FAILED(R.string.apps_warn_stats_failed),
    SHELL_UNAVAILABLE(R.string.apps_warn_shell_unavailable),
    SHELL_SCAN_FAILED(R.string.apps_warn_shell_failed),
    DATA_UNREADABLE(R.string.apps_warn_data_unreadable),
}

data class AppScanWarning(
    val kind: AppScanWarningKind,
    val packageName: String? = null,
    /** Raw technical detail (exception message, stderr) for logs/diagnostics; not localized. */
    val detail: String? = null,
)

/** What [AppInspector.scan] should do. */
data class AppScanOptions(
    val includeSystemApps: Boolean = true,
    val includeSizes: Boolean = false,
    /** Target Android user for shell size scans; null = the user DFTB runs as. */
    val userId: Int? = null,
)

/** Everything one scan learned. [sizes] / [dataMetadata] are empty unless sizes were requested. */
data class AppScanResult(
    val apps: List<InstalledApp>,
    val sizes: Map<String, AppSizeInfo>,
    val dataMetadata: Map<String, AppDataMetadata>,
    /** True when QUERY_ALL_PACKAGES is not held on API 30+, so [apps] is likely incomplete. */
    val limitedVisibility: Boolean,
    val usageAccessGranted: Boolean,
    /** Intent action that opens the usage-access settings page (for the "grant" button). */
    val usageAccessSettingsAction: String,
    val tier: ElevationTier,
    val componentAvailability: Map<AppComponent, ComponentAvailability>,
    val warnings: List<AppScanWarning>,
)
