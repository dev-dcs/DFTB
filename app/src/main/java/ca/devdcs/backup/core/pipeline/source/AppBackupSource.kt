package ca.devdcs.backup.core.pipeline.source

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import ca.devdcs.backup.core.discovery.apps.AppDataLocation
import ca.devdcs.backup.core.discovery.apps.AppDataPaths
import ca.devdcs.backup.core.discovery.apps.AppInspector
import ca.devdcs.backup.core.discovery.apps.InstalledApp
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.BackupSource
import ca.devdcs.backup.core.pipeline.SourceEstimate
import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.core.pipeline.layout.ArchiveLayout
import ca.devdcs.backup.core.pipeline.layout.ArchiveLayout.AppArchivePart
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.AppComponent
import ca.devdcs.backup.domain.model.AppTarget
import ca.devdcs.backup.domain.model.PipelineOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** How one app component is captured. */
internal sealed interface AppCapture {
    val component: AppComponent
    val part: AppArchivePart?

    /** Plain file the app can read itself (APKs). */
    data class ApkFile(override val component: AppComponent, val path: String, val archivePath: String, val size: Long) : AppCapture {
        override val part: AppArchivePart? get() = null
    }

    /** Root/Shizuku tar stream of [members] relative to [parent]. */
    data class PrivilegedTar(
        override val component: AppComponent,
        override val part: AppArchivePart,
        val parent: String,
        val members: List<String>,
        val excludes: List<String>,
        val archivePath: String,
        val sourcePath: String,
        /** `du -sk` of the members (an upper bound when caches are excluded); null = unmeasured. */
        val estimatedBytes: Long? = null,
    ) : AppCapture

    /** Per-file label/owner listing of the private dirs. */
    data class SelinuxListing(override val component: AppComponent, val roots: List<String>, val pruned: List<String>, val archivePath: String) : AppCapture {
        override val part: AppArchivePart get() = AppArchivePart.SELINUX_LISTING
    }

    /** Directory the app reads directly (API 29 legacy storage), emitted as a file tree. */
    data class DirectTree(
        override val component: AppComponent,
        override val part: AppArchivePart,
        val dir: String,
        val archiveRoot: String,
        val estimatedBytes: Long? = null,
    ) : AppCapture
}

/** One app, fully resolved at prepare time. */
internal data class PreparedApp(
    val app: InstalledApp,
    val manifestPath: String,
    val manifestBytes: ByteArray,
    val captures: List<AppCapture>,
)

/**
 * Emits each selected app as: `manifest.json`, `base.apk` + `split_*.apk` (plain files), then the
 * private data as root `tar` streams (`data.tar`, `data_de.tar` or `cache.tar`) plus a
 * `data_selinux.json` label listing, then external data / media / OBB as tars (privileged tier) or
 * plain file trees (directly readable). Exists as the single producer of the app archive layout
 * the restore engine consumes.
 */
class AppBackupSource internal constructor(
    private val apps: List<PreparedApp>,
    private val tarSelinux: Boolean,
    private val options: PipelineOptions,
    private val layout: ArchiveLayout,
    private val streams: RootByteStreams,
    private val io: CoroutineDispatcher,
) : BackupSource {

    override val sourceId: String = "apps:" + stableHash(apps.map { it.app.packageName }.sorted())

    override suspend fun estimate(): SourceEstimate {
        var count = 0L
        var bytes = 0L
        var exact = true
        for (app in apps) {
            count += 1 + app.captures.size
            bytes += app.manifestBytes.size
            for (capture in app.captures) {
                when (capture) {
                    is AppCapture.ApkFile -> bytes += capture.size
                    is AppCapture.PrivilegedTar -> {
                        bytes += capture.estimatedBytes ?: 0L
                        exact = false
                    }
                    is AppCapture.DirectTree -> {
                        bytes += capture.estimatedBytes ?: 0L
                        exact = false
                    }
                    is AppCapture.SelinuxListing -> exact = false
                }
            }
        }
        return SourceEstimate(count, bytes, exact)
    }

    override fun items(): Flow<SourceItem> = flow {
        for (prepared in apps) {
            val manifest = prepared.manifestBytes
            emit(SourceItem.File(prepared.manifestPath, manifest.size.toLong()) { ByteArrayInputStream(manifest) })
            for (capture in prepared.captures) {
                when (capture) {
                    is AppCapture.ApkFile -> {
                        val path = capture.path
                        val size = capture.size
                        emit(SourceItem.File(capture.archivePath, size) { ExactLengthInputStream(FileInputStream(path), size, path) })
                    }
                    is AppCapture.PrivilegedTar -> {
                        val script = AppDataScripts.tarScript(capture.parent, capture.members, capture.excludes, tarSelinux)
                        val label = "${prepared.app.packageName} ${capture.part.fileName}"
                        emit(SourceItem.Stream(capture.archivePath, expectedSizeBytes = null, sizeIsExact = false) { streams.open(script, label) })
                    }
                    is AppCapture.SelinuxListing -> {
                        val script = AppDataScripts.statListingScript(capture.roots, capture.pruned)
                        val label = "${prepared.app.packageName} labels"
                        emit(SourceItem.Stream(capture.archivePath, expectedSizeBytes = null, sizeIsExact = false) { StatListingJsonInputStream(streams.open(script, label)) })
                    }
                    is AppCapture.DirectTree -> {
                        val pkg = prepared.app.packageName
                        val part = capture.part
                        val unreadable = LocalEntries.emitTree(
                            this, File(capture.dir), capture.archiveRoot,
                            preserve = options.preservePosixMetadata, selinux = options.preserveSelinuxContext,
                        ) { rel -> layout.appTreeEntry(pkg, part, rel) }
                        if (unreadable > 0) DftbLog.w(TAG, "$pkg ${part.treeName}: $unreadable unreadable entr(ies)")
                    }
                }
            }
        }
    }.flowOn(io)

    private companion object {
        const val TAG = "AppBackupSource"
    }
}

/**
 * Prepares [AppBackupSource]s: re-reads each package, decides per component whether it is captured
 * through the privileged tier, directly, or not at all (with a warning), probes which data dirs
 * exist, and builds the restore manifest.
 */
@Singleton
class AppBackupSourceFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inspector: AppInspector,
    private val router: CommandExecutorRouter,
    private val streams: RootByteStreams,
    private val strings: PipelineSourceStrings,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun prepare(targets: List<AppTarget>, options: PipelineOptions, layout: ArchiveLayout): SourcePreparation<AppBackupSource> = withContext(io) {
        if (targets.isEmpty()) return@withContext strings.unavailable(SourceUnavailableReason.NOTHING_SELECTED)
        val warnings = ArrayList<SourceWarning>()
        val availability = inspector.componentAvailability()
        val executor = router.current()
        val capability = streams.capability()
        val privileged = capability is StreamCapability.Available
        val backingStore = executor.tier == ElevationTier.ROOT
        val tarSelinux = privileged && options.preserveSelinuxContext && probeTarSelinux(executor)
        if (privileged && options.preserveSelinuxContext && !tarSelinux &&
            targets.any { t -> t.components.any { it == AppComponent.INTERNAL_DATA || it == AppComponent.CACHE } }
        ) {
            warnings += SourceWarning(SourceWarningKind.SELINUX_TAR_UNSUPPORTED, "tar")
        }

        val prepared = ArrayList<PreparedApp>()
        for (target in targets.distinctBy { it.packageName }) {
            val pkg = target.packageName
            if (!AppDataPaths.isValidPackageName(pkg)) {
                warnings += SourceWarning(SourceWarningKind.APP_INVALID_PACKAGE, pkg)
                continue
            }
            val app = inspector.inspect(pkg)
            if (app == null) {
                warnings += SourceWarning(SourceWarningKind.APP_NOT_INSTALLED, pkg)
                continue
            }
            fun available(component: AppComponent): Boolean {
                if (component !in target.components) return false
                val ok = availability[component]?.available == true
                if (!ok) warnings += SourceWarning(SourceWarningKind.APP_COMPONENT_UNAVAILABLE, "$pkg/${component.name.lowercase()}", availability[component]?.reason?.name)
                return ok
            }
            val wantApk = available(AppComponent.APK)
            val wantData = available(AppComponent.INTERNAL_DATA) && privileged
            val wantCache = available(AppComponent.CACHE) && privileged
            val wantExternal = available(AppComponent.EXTERNAL_DATA)
            val wantObb = available(AppComponent.OBB)

            val user = app.userId
            val ce = AppDataPaths.ceDataDir(pkg, user)
            val de = AppDataPaths.deDataDir(pkg, user)
            val shared = listOf(
                AppArchivePart.EXTERNAL_DATA to AppDataPaths.externalDataDir(pkg, user, backingStore),
                AppArchivePart.MEDIA to AppDataPaths.mediaDir(pkg, user, backingStore),
                AppArchivePart.OBB to AppDataPaths.obbDir(pkg, user, backingStore),
            ).filter { (part, _) -> if (part == AppArchivePart.OBB) wantObb else wantExternal }

            val probePaths = buildList {
                if (wantData || wantCache) {
                    add(ce); add(de)
                    AppDataScripts.CACHE_DIRS.forEach { add("$ce/$it"); add("$de/$it") }
                }
                if (privileged) shared.forEach { add(it.second) }
            }
            val existing = if (privileged && probePaths.isNotEmpty()) existingDirs(executor, probePaths) else emptySet()

            val captures = ArrayList<AppCapture>()
            if (wantApk) captures += apkCaptures(app, layout, warnings)
            if (wantData || wantCache) captures += privateDataCaptures(pkg, ce, de, wantData, wantCache, existing, layout, options)
            for ((part, dir) in shared) {
                val component = if (part == AppArchivePart.OBB) AppComponent.OBB else AppComponent.EXTERNAL_DATA
                if (privileged) {
                    if (dir in existing) {
                        captures += AppCapture.PrivilegedTar(
                            component, part, parent = dir.substringBeforeLast('/'), members = listOf(pkg), excludes = emptyList(),
                            archivePath = layout.appArchive(pkg, part), sourcePath = AppDataPaths.pathFor(locationOf(part), pkg, user, backingStore = false),
                        )
                    }
                } else {
                    val direct = AppDataPaths.pathFor(locationOf(part), pkg, user, backingStore = false)
                    val file = File(direct)
                    if (file.isDirectory && file.canRead()) {
                        captures += AppCapture.DirectTree(component, part, direct, layout.appTreeRoot(pkg, part), estimatedBytes = treeBytes(file))
                    }
                }
            }

            if (privileged) {
                val measured = measureTars(executor, captures.filterIsInstance<AppCapture.PrivilegedTar>())
                if (measured.isNotEmpty()) {
                    for (i in captures.indices) {
                        val c = captures[i]
                        if (c is AppCapture.PrivilegedTar) {
                            val sizes = c.members.map { measured["${c.parent}/$it"] }
                            if (sizes.all { it != null }) captures[i] = c.copy(estimatedBytes = sizes.sumOf { it!! })
                        }
                    }
                }
            }

            val owners = if (privileged && (wantData || wantCache)) dirOwners(executor, listOf(ce, de).filter { it in existing }) else emptyMap()
            val manifest = buildManifest(app, captures, owners, ce, de, tarSelinux)
            prepared += PreparedApp(app, layout.appManifest(pkg), ManifestJson.encodeToString(AppManifest.serializer(), manifest).toByteArray(Charsets.UTF_8), captures)
        }
        SourcePreparation.Ready(AppBackupSource(prepared, tarSelinux, options, layout, streams, io), warnings)
    }

    private fun locationOf(part: AppArchivePart) = when (part) {
        AppArchivePart.MEDIA -> AppDataLocation.MEDIA
        AppArchivePart.OBB -> AppDataLocation.OBB
        else -> AppDataLocation.EXTERNAL_DATA
    }

    private fun apkCaptures(app: InstalledApp, layout: ArchiveLayout, warnings: MutableList<SourceWarning>): List<AppCapture> {
        val list = ArrayList<AppCapture>()
        val files = listOf(app.baseApkPath to layout.appBaseApk(app.packageName)) +
            app.splitApkPaths.mapIndexed { i, path ->
                val name = app.splitNames.getOrNull(i) ?: File(path).nameWithoutExtension
                path to layout.appSplitApk(app.packageName, name)
            }
        for ((path, archivePath) in files) {
            val file = File(path)
            if (!file.isFile || !file.canRead()) {
                warnings += SourceWarning(SourceWarningKind.APP_APK_UNREADABLE, app.packageName, path)
                continue
            }
            list += AppCapture.ApkFile(AppComponent.APK, path, archivePath, file.length())
        }
        return list
    }

    private fun privateDataCaptures(
        pkg: String,
        ce: String,
        de: String,
        wantData: Boolean,
        wantCache: Boolean,
        existing: Set<String>,
        layout: ArchiveLayout,
        options: PipelineOptions,
    ): List<AppCapture> {
        val list = ArrayList<AppCapture>()
        val labelRoots = ArrayList<String>()
        val pruned = ArrayList<String>()
        if (wantData) {
            val excludes = if (wantCache) emptyList() else AppDataScripts.CACHE_DIRS.map { "$pkg/$it" }
            for ((dir, part) in listOf(ce to AppArchivePart.CE_DATA, de to AppArchivePart.DE_DATA)) {
                if (dir !in existing) continue
                list += AppCapture.PrivilegedTar(AppComponent.INTERNAL_DATA, part, dir.substringBeforeLast('/'), listOf(pkg), excludes, layout.appArchive(pkg, part), dir)
                labelRoots += dir
                if (!wantCache) AppDataScripts.CACHE_DIRS.forEach { pruned += "$dir/$it" }
            }
        } else if (wantCache) {
            // CACHE alone: one tar of the cache dirs of CE (members relative to the CE parent).
            val members = AppDataScripts.CACHE_DIRS.map { "$ce/$it" }.filter { it in existing }
            if (members.isNotEmpty()) {
                val parent = ce.substringBeforeLast('/')
                list += AppCapture.PrivilegedTar(AppComponent.CACHE, AppArchivePart.CACHE, parent, members.map { it.removePrefix("$parent/") }, emptyList(), layout.appArchive(pkg, AppArchivePart.CACHE), ce)
                labelRoots += members
            }
        }
        if (options.preserveSelinuxContext && labelRoots.isNotEmpty()) {
            list += AppCapture.SelinuxListing(if (wantData) AppComponent.INTERNAL_DATA else AppComponent.CACHE, labelRoots, pruned, layout.appArchive(pkg, AppArchivePart.SELINUX_LISTING))
        }
        return list
    }

    /** Absolute member path -> bytes, from one `du -sk` round trip. */
    private suspend fun measureTars(executor: CommandExecutor, tars: List<AppCapture.PrivilegedTar>): Map<String, Long> {
        val paths = tars.flatMap { t -> t.members.map { "${t.parent}/$it" } }.distinct()
        if (paths.isEmpty()) return emptyMap()
        val result = executor.execute(Command(listOf("sh", "-c", AppDataScripts.duScript(paths)), timeoutMillis = DU_TIMEOUT_MILLIS))
        return if (result.launched) AppDataScripts.parseDu(result.stdout) else emptyMap()
    }

    /** Bytes of regular files below [dir] (directly readable trees only; symlinks not followed). */
    private fun treeBytes(dir: File): Long? {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val children = stack.removeLast().listFiles() ?: continue
            for (child in children) {
                val stat = LocalEntries.lstat(child.path) ?: continue
                when {
                    LocalEntries.isDirectory(stat) -> stack.addLast(child)
                    LocalEntries.isRegular(stat) -> total += stat.st_size
                }
            }
        }
        return total
    }

    private suspend fun probeTarSelinux(executor: CommandExecutor): Boolean {
        val result = executor.execute(Command(listOf("sh", "-c", AppDataScripts.TAR_SELINUX_PROBE), timeoutMillis = PROBE_TIMEOUT_MILLIS))
        return result.isSuccess && result.stdout.any { it.trim() == "yes" }
    }

    private suspend fun existingDirs(executor: CommandExecutor, paths: List<String>): Set<String> {
        val result = executor.execute(Command(listOf("sh", "-c", AppDataScripts.existingDirsScript(paths.distinct())), timeoutMillis = PROBE_TIMEOUT_MILLIS))
        return if (result.launched) result.stdout.map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
    }

    private suspend fun dirOwners(executor: CommandExecutor, dirs: List<String>): Map<String, AppDataScripts.OwnerLine> {
        if (dirs.isEmpty()) return emptyMap()
        val result = executor.execute(Command(listOf("sh", "-c", AppDataScripts.dirOwnerScript(dirs)), timeoutMillis = PROBE_TIMEOUT_MILLIS))
        if (!result.launched) return emptyMap()
        return result.stdout.mapNotNull { AppDataScripts.parseOwnerLine(it.trim()) }.associateBy { it.path }
    }

    private fun buildManifest(
        app: InstalledApp,
        captures: List<AppCapture>,
        owners: Map<String, AppDataScripts.OwnerLine>,
        ce: String,
        de: String,
        tarSelinux: Boolean,
    ): AppManifest {
        val info = packageInfo(app.packageName)
        val signing = info?.signingInfo
        val current = signing?.apkContentsSigners.orEmpty().map(::sha256)
        val past = if (signing != null && signing.hasPastSigningCertificates()) signing.signingCertificateHistory.orEmpty().map(::sha256) else emptyList()
        val requested = info?.requestedPermissions.orEmpty()
        val flags = info?.requestedPermissionsFlags
        val granted = requested.filterIndexed { i, _ -> flags != null && i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 }
        val denied = requested.filterNot { it in granted }
        fun dirInfo(path: String): DataDirInfo? {
            val owner = owners[path] ?: return null
            return DataDirInfo(path, owner.uid, owner.gid, owner.context)
        }
        val components = captures.map { capture ->
            when (capture) {
                is AppCapture.ApkFile -> ManifestComponent(capture.component.storageName(), "file", capture.archivePath, capture.path)
                is AppCapture.PrivilegedTar -> ManifestComponent(capture.component.storageName(), "tar", capture.archivePath, capture.sourcePath, tarBaseDirectory = capture.parent)
                is AppCapture.SelinuxListing -> ManifestComponent(capture.component.storageName(), "file", capture.archivePath, capture.roots.joinToString(":"))
                is AppCapture.DirectTree -> ManifestComponent(capture.component.storageName(), "tree", capture.archiveRoot, capture.dir)
            }
        }
        return AppManifest(
            packageName = app.packageName,
            label = app.label,
            versionCode = app.versionCode,
            versionName = app.versionName,
            minSdk = app.minSdk,
            targetSdk = app.targetSdk,
            uid = app.uid,
            userId = app.userId,
            kind = app.kind.name,
            installerPackageName = app.installSource.installerPackageName,
            signingCertSha256 = current,
            pastSigningCertSha256 = past,
            multipleSigners = signing?.hasMultipleSigners() == true,
            splitNames = app.splitNames,
            apkFiles = captures.filterIsInstance<AppCapture.ApkFile>().map { it.archivePath.substringAfterLast('/') },
            grantedPermissions = granted,
            deniedPermissions = denied,
            ceDataDir = dirInfo(ce),
            deDataDir = dirInfo(de),
            tarHasSelinuxLabels = tarSelinux,
            components = components,
            createdAtMillis = System.currentTimeMillis(),
        )
    }

    private fun packageInfo(packageName: String): PackageInfo? = try {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: RuntimeException) {
        DftbLog.w(TAG, "getPackageInfo($packageName) for manifest failed", e)
        null
    }

    private fun sha256(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun AppComponent.storageName(): String = when (this) {
        AppComponent.APK -> "apk"
        AppComponent.INTERNAL_DATA -> "internal_data"
        AppComponent.EXTERNAL_DATA -> "external_data"
        AppComponent.OBB -> "obb"
        AppComponent.CACHE -> "cache"
    }

    private companion object {
        const val TAG = "AppBackupSource"
        const val PROBE_TIMEOUT_MILLIS = 30_000L
        /** du over large Android/data trees can take a while. */
        const val DU_TIMEOUT_MILLIS = 180_000L
    }
}
