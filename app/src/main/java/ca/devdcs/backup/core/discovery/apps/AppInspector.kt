package ca.devdcs.backup.core.discovery.apps

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.UserHandle
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.AppComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Output of [AppInspector.measureSizes]. */
data class AppSizeReport(
    val sizes: Map<String, AppSizeInfo>,
    val dataMetadata: Map<String, AppDataMetadata>,
    /** OBB presence learned from the measurement (shell tiers), keyed by package. */
    val obbPresence: Map<String, Boolean?>,
    val usageAccessGranted: Boolean,
    val warnings: List<AppScanWarning>,
)

/**
 * The single source of truth for "what apps are installed and what can be backed up of them".
 * The app picker, the backup planner and the restore engine all read installed packages through
 * this class so API-level differences (PackageInfoFlags on 33+, InstallSourceInfo on 30+, package
 * visibility, usage-access-gated storage stats) are handled once.
 *
 * Never throws for ordinary limits: missing permissions, an unavailable shell or an unreadable
 * package become [AppScanWarning]s and null sizes.
 */
@Singleton
class AppInspector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: CommandExecutorRouter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val packageManager: PackageManager get() = context.packageManager

    /** The Android user DFTB runs as. */
    val currentUserId: Int get() = AppDataPaths.userIdOf(Process.myUid())

    /** Lists installed apps and, if requested, their sizes and restore metadata. */
    suspend fun scan(options: AppScanOptions = AppScanOptions()): AppScanResult = withContext(ioDispatcher) {
        val warnings = ArrayList<AppScanWarning>()
        val limited = isVisibilityLimited()
        if (limited) warnings += AppScanWarning(AppScanWarningKind.LIMITED_PACKAGE_VISIBILITY)

        val executor = router.current()
        val env = accessEnvironment(executor)
        val availability = AppComponentAvailabilityResolver.resolve(env)
        val standardObbCheck = env.tier == ElevationTier.NONE &&
            AppComponentAvailabilityResolver.resolve(AppComponent.OBB, env).available

        var apps = listInstalled(options.includeSystemApps, standardObbCheck, warnings)

        var sizes: Map<String, AppSizeInfo> = emptyMap()
        var metadata: Map<String, AppDataMetadata> = emptyMap()
        val usageGranted: Boolean
        if (options.includeSizes) {
            val report = measureSizesInternal(apps, options.userId ?: currentUserId, executor, env)
            sizes = report.sizes
            metadata = report.dataMetadata
            usageGranted = report.usageAccessGranted
            warnings += report.warnings
            if (report.obbPresence.isNotEmpty()) {
                apps = apps.map { app ->
                    val obb = report.obbPresence[app.packageName]
                    if (obb != null && app.flags.hasObb != obb) app.copy(flags = app.flags.copy(hasObb = obb)) else app
                }
            }
        } else {
            usageGranted = isUsageAccessGranted()
        }

        AppScanResult(
            apps = apps,
            sizes = sizes,
            dataMetadata = metadata,
            limitedVisibility = limited,
            usageAccessGranted = usageGranted,
            usageAccessSettingsAction = USAGE_ACCESS_SETTINGS_ACTION,
            tier = env.tier,
            componentAvailability = availability,
            warnings = warnings,
        )
    }

    /** Measures sizes of [apps] at the current tier for [userId] (default: current user). */
    suspend fun measureSizes(apps: List<InstalledApp>, userId: Int = currentUserId): AppSizeReport =
        withContext(ioDispatcher) {
            val executor = router.current()
            measureSizesInternal(apps, userId, executor, accessEnvironment(executor))
        }

    /** Which components can be captured at the current tier, and why not. */
    suspend fun componentAvailability(): Map<AppComponent, ComponentAvailability> =
        withContext(ioDispatcher) { AppComponentAvailabilityResolver.resolve(accessEnvironment(router.current())) }

    /** Re-reads one package (e.g. after an install during restore). Null when not installed/visible. */
    suspend fun inspect(packageName: String): InstalledApp? = withContext(ioDispatcher) {
        val info = try {
            getPackageInfo(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            return@withContext null
        } catch (e: RuntimeException) {
            DftbLog.w(TAG, "getPackageInfo($packageName) failed", e)
            return@withContext null
        }
        val env = accessEnvironment(router.current())
        val obbCheck = env.tier == ElevationTier.NONE &&
            AppComponentAvailabilityResolver.resolve(AppComponent.OBB, env).available
        try {
            toInstalledApp(info, obbCheck)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            DftbLog.w(TAG, "inspect($packageName) failed", e)
            null
        }
    }

    /** Lazily loads an app icon. Null when the package is gone or the icon cannot be loaded. */
    suspend fun loadIcon(packageName: String): Drawable? = withContext(ioDispatcher) {
        try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: RuntimeException) {
            DftbLog.w(TAG, "Icon load failed for $packageName", e)
            null
        }
    }

    /** [loadIcon] rasterised to a square [sizePx] bitmap, for list rows. */
    suspend fun loadIconBitmap(packageName: String, sizePx: Int): Bitmap? {
        require(sizePx > 0) { "sizePx must be > 0" }
        val drawable = loadIcon(packageName) ?: return null
        return withContext(ioDispatcher) {
            try {
                drawable.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            } catch (e: RuntimeException) {
                DftbLog.w(TAG, "Icon rasterise failed for $packageName", e)
                null
            }
        }
    }

    /** Whether the PACKAGE_USAGE_STATS app-op is granted (needed for StorageStatsManager). */
    fun isUsageAccessGranted(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    /** True on API 30+ when QUERY_ALL_PACKAGES is not held, so the package list is filtered. */
    fun isVisibilityLimited(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES) != PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- listing

    private suspend fun listInstalled(
        includeSystem: Boolean,
        standardObbCheck: Boolean,
        warnings: MutableList<AppScanWarning>,
    ): List<InstalledApp> {
        val packages: List<PackageInfo> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(LIST_FLAGS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(LIST_FLAGS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            DftbLog.e(TAG, "getInstalledPackages failed", e)
            warnings += AppScanWarning(AppScanWarningKind.PACKAGE_LIST_FAILED, detail = e.message)
            return emptyList()
        }

        val result = ArrayList<InstalledApp>(packages.size)
        for (info in packages) {
            currentCoroutineContext().ensureActive()
            try {
                val app = toInstalledApp(info, standardObbCheck) ?: continue
                if (!includeSystem && app.kind == AppKind.SYSTEM) continue
                result += app
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                DftbLog.w(TAG, "Reading ${info.packageName} failed", e)
                warnings += AppScanWarning(AppScanWarningKind.PACKAGE_READ_FAILED, info.packageName, e.message)
            }
        }
        result.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER, InstalledApp::label).thenBy(InstalledApp::packageName))
        return result
    }

    private fun getPackageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(LIST_FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, LIST_FLAGS)
        }

    /** Null for entries without an ApplicationInfo (APEX-only records). */
    private fun toInstalledApp(info: PackageInfo, standardObbCheck: Boolean): InstalledApp? {
        val appInfo: ApplicationInfo = info.applicationInfo ?: return null
        val pkg = info.packageName
        val userId = AppDataPaths.userIdOf(appInfo.uid)
        val isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val isUpdatedSystem = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        val splitPaths = appInfo.splitSourceDirs?.toList().orEmpty()
        val obbDir = AppDataPaths.obbDir(pkg, userId)
        val hasObb: Boolean? = if (standardObbCheck) File(obbDir).isDirectory else null

        return InstalledApp(
            packageName = pkg,
            label = packageManager.getApplicationLabel(appInfo).toString().ifBlank { pkg },
            kind = AppClassifier.kindOf(isSystem, isUpdatedSystem),
            enabled = appInfo.enabled,
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            targetSdk = appInfo.targetSdkVersion,
            minSdk = appInfo.minSdkVersion,
            uid = appInfo.uid,
            userId = userId,
            installSource = installSourceOf(pkg),
            firstInstallTimeMillis = info.firstInstallTime,
            lastUpdateTimeMillis = info.lastUpdateTime,
            baseApkPath = appInfo.sourceDir ?: appInfo.publicSourceDir.orEmpty(),
            splitApkPaths = splitPaths,
            splitNames = appInfo.splitNames?.toList() ?: info.splitNames?.toList().orEmpty(),
            obbDirPath = obbDir,
            nativeLibraryDir = appInfo.nativeLibraryDir,
            flags = AppSpecialFlags(
                isSplitBundle = splitPaths.isNotEmpty(),
                hasObb = hasObb,
                isSelf = AppClassifier.isSelf(pkg, context.packageName, context.packageName.removeSuffix(DEBUG_SUFFIX)),
                isRootManager = AppClassifier.isRootManager(pkg),
                isCriticalSystem = AppClassifier.isCriticalSystem(pkg),
                allowBackup = appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0,
                isDebuggable = appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                isApex = info.isApex,
            ),
        )
    }

    private fun installSourceOf(packageName: String): InstallSource = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val source = packageManager.getInstallSourceInfo(packageName)
            InstallSource(
                installerPackageName = source.installingPackageName,
                initiatingPackageName = source.initiatingPackageName,
                originatingPackageName = source.originatingPackageName,
                packageSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) source.packageSource else null,
            )
        } else {
            @Suppress("DEPRECATION")
            InstallSource(packageManager.getInstallerPackageName(packageName), null, null, null)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        EMPTY_SOURCE
    } catch (e: IllegalArgumentException) {
        EMPTY_SOURCE
    } catch (e: SecurityException) {
        EMPTY_SOURCE
    }

    // ---------------------------------------------------------------- access

    private suspend fun accessEnvironment(executor: CommandExecutor): AccessEnvironment {
        val available = executor.tier != ElevationTier.NONE && executor.isAvailable()
        val shellUid = if (available) probeShellUid(executor) else null
        return AccessEnvironment(
            tier = executor.tier,
            executorAvailable = available,
            shellUid = shellUid,
            sdkInt = Build.VERSION.SDK_INT,
            legacyExternalStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.R && legacyStorage(),
            readStorageGranted = context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED,
            allFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager(),
            canRequestPackageInstalls = packageManager.canRequestPackageInstalls(),
        )
    }

    private fun legacyStorage(): Boolean = Environment.isExternalStorageLegacy()

    private suspend fun probeShellUid(executor: CommandExecutor): Int? {
        val result = executor.execute(Command(listOf("id", "-u"), timeoutMillis = PROBE_TIMEOUT_MILLIS))
        return if (result.isSuccess) result.stdout.firstOrNull()?.trim()?.toIntOrNull() else null
    }

    // ---------------------------------------------------------------- sizes

    private suspend fun measureSizesInternal(
        apps: List<InstalledApp>,
        userId: Int,
        executor: CommandExecutor,
        env: AccessEnvironment,
    ): AppSizeReport {
        val warnings = ArrayList<AppScanWarning>()
        val usageGranted = isUsageAccessGranted()
        val apkSizes = apps.associate { it.packageName to apkBytesOf(it) }

        if (env.tier != ElevationTier.NONE) {
            if (env.executorAvailable) {
                val shell = shellSizes(apps, userId, executor, env, apkSizes, warnings)
                if (shell != null) return shell.copy(usageAccessGranted = usageGranted, warnings = warnings)
            } else {
                warnings += AppScanWarning(AppScanWarningKind.SHELL_UNAVAILABLE)
            }
        }

        if (!usageGranted) {
            warnings += AppScanWarning(AppScanWarningKind.USAGE_ACCESS_NOT_GRANTED)
            val sizes = apps.associate { app ->
                val (base, splits) = apkSizes.getValue(app.packageName)
                app.packageName to AppSizeInfo(apkBytes = base, splitBytes = splits, source = SizeSource.APK_ONLY)
            }
            return AppSizeReport(sizes, emptyMap(), emptyMap(), false, warnings)
        }
        return AppSizeReport(statsSizes(apps, userId, apkSizes, warnings), emptyMap(), emptyMap(), true, warnings)
    }

    private fun apkBytesOf(app: InstalledApp): Pair<Long, Long> =
        File(app.baseApkPath).length() to app.splitApkPaths.sumOf { File(it).length() }

    private suspend fun statsSizes(
        apps: List<InstalledApp>,
        userId: Int,
        apkSizes: Map<String, Pair<Long, Long>>,
        warnings: MutableList<AppScanWarning>,
    ): Map<String, AppSizeInfo> {
        val stats = context.getSystemService(StorageStatsManager::class.java)
        val user: UserHandle = if (userId == currentUserId) {
            Process.myUserHandle()
        } else {
            UserHandle.getUserHandleForUid(userId * AppDataPaths.PER_USER_RANGE)
        }
        val volumeUuids = apps.associate { app ->
            app.packageName to (applicationStorageUuid(app.packageName) ?: StorageManager.UUID_DEFAULT)
        }
        val result = LinkedHashMap<String, AppSizeInfo>(apps.size)
        for (app in apps) {
            currentCoroutineContext().ensureActive()
            val (base, splits) = apkSizes.getValue(app.packageName)
            val apkOnly = AppSizeInfo(apkBytes = base, splitBytes = splits, source = SizeSource.APK_ONLY)
            result[app.packageName] = try {
                val s = stats?.queryStatsForPackage(volumeUuids.getValue(app.packageName), app.packageName, user)
                if (s == null) {
                    apkOnly
                } else {
                    AppSizeInfo(
                        apkBytes = base,
                        splitBytes = splits,
                        cacheBytes = s.cacheBytes,
                        aggregateDataBytes = s.dataBytes,
                        source = SizeSource.STATS_MANAGER,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PackageManager.NameNotFoundException) {
                warnings += AppScanWarning(AppScanWarningKind.STATS_QUERY_FAILED, app.packageName, e.message)
                apkOnly
            } catch (e: IOException) {
                warnings += AppScanWarning(AppScanWarningKind.STATS_QUERY_FAILED, app.packageName, e.message)
                apkOnly
            } catch (e: RuntimeException) {
                // SecurityException (usage access revoked mid-scan, cross-user) and binder failures.
                warnings += AppScanWarning(AppScanWarningKind.STATS_QUERY_FAILED, app.packageName, e.message)
                apkOnly
            }
        }
        return result
    }

    private fun applicationStorageUuid(packageName: String) = try {
        getPackageInfo(packageName).applicationInfo?.storageUuid
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Null when the shell produced nothing usable, so the caller falls back to stats. */
    private suspend fun shellSizes(
        apps: List<InstalledApp>,
        userId: Int,
        executor: CommandExecutor,
        env: AccessEnvironment,
        apkSizes: Map<String, Pair<Long, Long>>,
        warnings: MutableList<AppScanWarning>,
    ): AppSizeReport? {
        val backingStore = env.shellUid == AppComponentAvailabilityResolver.SHELL_UID_ROOT
        val outputs = ArrayList<ShellScanOutput>()
        for (chunk in AppShellScanScript.chunks(apps.map { it.packageName })) {
            currentCoroutineContext().ensureActive()
            val script = AppShellScanScript.build(chunk, userId, backingStore)
            val result = executor.execute(Command(listOf("sh", "-c", script), timeoutMillis = CHUNK_TIMEOUT_MILLIS))
            if (!result.launched) {
                DftbLog.w(TAG, "Shell size scan not launched: ${result.stderrText}")
                warnings += AppScanWarning(AppScanWarningKind.SHELL_SCAN_FAILED, detail = result.stderrText)
                if (outputs.isEmpty()) return null
                break
            }
            // The script's exit status is that of its last test, so only the parsed records count.
            val parsed = AppShellScanParser.parse(result.stdout)
            if (parsed.measurements.isEmpty()) {
                warnings += AppScanWarning(AppScanWarningKind.SHELL_SCAN_FAILED, detail = result.stderrText.take(MAX_DETAIL))
            }
            outputs += parsed
        }
        val merged = AppShellScanParser.merge(outputs)
        if (merged.measurements.isEmpty()) return null

        // Private dirs are expectedly unreadable for an adb-uid shell; only warn where access was expected.
        val expectedReadable = if (backingStore) AppDataLocation.entries.toSet() else SHARED_LOCATIONS
        val sizes = LinkedHashMap<String, AppSizeInfo>(apps.size)
        val obb = LinkedHashMap<String, Boolean?>()
        for (app in apps) {
            val (base, splits) = apkSizes.getValue(app.packageName)
            val measured = merged.measurements[app.packageName]
            if (measured == null) {
                sizes[app.packageName] = AppSizeInfo(apkBytes = base, splitBytes = splits, source = SizeSource.APK_ONLY)
                continue
            }
            val info = AppShellScanParser.toSizeInfo(base, splits, measured, privateDirsTrusted = backingStore)
            sizes[app.packageName] = info
            obb[app.packageName] = AppShellScanParser.hasObb(measured)
            if (info.unreadable.any { it in expectedReadable }) {
                warnings += AppScanWarning(
                    AppScanWarningKind.DATA_UNREADABLE,
                    app.packageName,
                    info.unreadable.joinToString(),
                )
            }
        }
        val metadata = merged.metadata.mapValues { (pkg, byLocation) ->
            AppDataMetadata(
                packageName = pkg,
                userId = userId,
                ceData = byLocation[AppDataLocation.CE_DATA],
                deData = byLocation[AppDataLocation.DE_DATA],
            )
        }
        if (merged.unparsedLines.isNotEmpty()) {
            DftbLog.d(TAG, "Shell scan: ${merged.unparsedLines.size} unparsed line(s)")
        }
        return AppSizeReport(sizes, metadata, obb, usageAccessGranted = false, warnings = warnings)
    }

    companion object {
        private const val TAG = "AppInspector"

        /** Intent action for the system "Usage access" page, for the grant button. */
        const val USAGE_ACCESS_SETTINGS_ACTION: String = Settings.ACTION_USAGE_ACCESS_SETTINGS

        /** Include disabled and disabled-until-used apps; nothing else heavy (keeps the binder reply small). */
        private const val LIST_FLAGS =
            PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS

        private const val PROBE_TIMEOUT_MILLIS = 10_000L
        private const val CHUNK_TIMEOUT_MILLIS = 180_000L
        private const val MAX_DETAIL = 500

        private val SHARED_LOCATIONS = setOf(AppDataLocation.EXTERNAL_DATA, AppDataLocation.MEDIA, AppDataLocation.OBB)

        private val EMPTY_SOURCE = InstallSource(null, null, null, null)

        /** applicationIdSuffix of the debug build type (app/build.gradle.kts). */
        private const val DEBUG_SUFFIX = ".debug"
    }
}
