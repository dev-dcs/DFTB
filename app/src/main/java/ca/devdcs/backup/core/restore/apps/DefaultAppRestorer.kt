package ca.devdcs.backup.core.restore.apps

import android.content.Context
import android.os.Environment
import android.os.StatFs
import ca.devdcs.backup.R
import ca.devdcs.backup.core.discovery.apps.AppDataPaths
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.core.pipeline.archive.ArchiveEntryInfo
import ca.devdcs.backup.core.pipeline.archive.ArchiveEntryType
import ca.devdcs.backup.core.pipeline.archive.ArchiveReadEntry
import ca.devdcs.backup.core.pipeline.archive.ArchiveReadSource
import ca.devdcs.backup.core.pipeline.archive.ArchiveReaderFactory
import ca.devdcs.backup.core.pipeline.engine.ArtifactSpecResolver
import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.core.pipeline.engine.ManifestItem
import ca.devdcs.backup.core.pipeline.layout.ArchiveLayout
import ca.devdcs.backup.core.pipeline.layout.ArchiveLayout.AppArchivePart
import ca.devdcs.backup.core.pipeline.source.AppManifest
import ca.devdcs.backup.core.restore.BackupCatalog
import ca.devdcs.backup.core.restore.ConflictPolicy
import ca.devdcs.backup.core.restore.OpenedBackup
import ca.devdcs.backup.core.restore.RestoreUnitResult
import ca.devdcs.backup.core.restore.catalog.BackupItemReader
import ca.devdcs.backup.core.restore.catalog.DecodedArtifactStream
import ca.devdcs.backup.core.restore.engine.AppRestorer
import ca.devdcs.backup.core.restore.engine.RestoreOptions
import ca.devdcs.backup.core.restore.engine.RestoreProgress
import ca.devdcs.backup.core.restore.engine.UnitPreflight
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.ArchiveFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Everything one app unit restore did, for the UI and history. */
data class AppRestoreReport(
    val unitId: String,
    val packageName: String?,
    val ok: Boolean,
    val apkAction: ApkAction?,
    /** True only when an install (or install-existing) was committed successfully in this run. */
    val apkInstalled: Boolean,
    val restoredParts: List<AppArchivePart>,
    val skippedParts: List<AppArchivePart>,
    val grantedPermissions: List<String>,
    /** Permission -> reason (localized summary or the `pm grant` error). */
    val notGrantedPermissions: Map<String, String>,
    val warnings: List<String>,
    val error: String?,
) {
    fun toUnitResult(): RestoreUnitResult = RestoreUnitResult(unitId, ok, warnings, error)
}

/**
 * The [AppRestorer]: reinstalls an app's APK split set and puts its data back, reading the app
 * artifact ONCE in archive order (`manifest.json`, APKs, data tars, shared-storage parts). Exists
 * as the single consumer of the layout [ca.devdcs.backup.core.pipeline.source.AppBackupSource]
 * writes, so the order of operations that Android requires — decide from the manifest, install,
 * re-query the NEW uid, then extract, chown and relabel — lives in one place.
 *
 * Tier matrix: STANDARD = PackageInstaller session with system confirmation, no data;
 * SHIZUKU = `pm` install for any user, shared-storage data, `pm grant`; ROOT = everything,
 * including CE/DE private data. Environment limits become warnings or a failed result, never an
 * exception; CancellationException is rethrown after cleaning up sessions and helpers.
 */
@Singleton
class DefaultAppRestorer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: BackupCatalog,
    private val itemReader: BackupItemReader,
    private val readers: ArchiveReaderFactory,
    private val router: CommandExecutorRouter,
    private val query: InstalledPackageQuery,
    private val standardInstaller: StandardApkInstaller,
    private val shellInstaller: ShellApkInstaller,
    private val data: AppDataRestorer,
    private val sinks: PrivilegedSinkFactory,
    private val strings: AppRestoreStrings,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AppRestorer {

    override suspend fun preflight(backup: OpenedBackup, artifacts: List<ManifestArtifact>, options: RestoreOptions): UnitPreflight =
        preflightApp(artifacts, options, options.app ?: AppRestoreOptions())

    override suspend fun restore(backup: OpenedBackup, artifacts: List<ManifestArtifact>, options: RestoreOptions, progress: RestoreProgress): RestoreUnitResult =
        restoreApp(backup, artifacts, options, options.app ?: AppRestoreOptions(), progress).toUnitResult()

    /** Preflight with explicit app options: what can be restored here and with which tier. Never throws except cancellation. */
    suspend fun preflightApp(artifacts: List<ManifestArtifact>, options: RestoreOptions, appOptions: AppRestoreOptions): UnitPreflight = withContext(io) {
        val unitId = artifacts.firstOrNull()?.sourceId ?: UNIT_FALLBACK
        val artifact = artifacts.firstOrNull { it.layout == LAYOUT_ARCHIVE || it.layout == LAYOUT_FILE_TREE }
        val packages = artifacts.flatMap { a -> a.items.mapNotNull { packageOf(it.archivePath) } }.distinct()
        fun blocked(reason: String, tier: ElevationTier = ElevationTier.NONE) = UnitPreflight(unitId, false, tier, reason = reason)
        if (packages.isEmpty()) return@withContext blocked(strings.get(R.string.restore_app_no_package))
        if (packages.size > 1) return@withContext blocked(strings.get(R.string.restore_app_multiple_packages))
        if (artifact == null) return@withContext blocked(strings.get(R.string.restore_app_layout_unsupported))
        val pkg = packages.single()
        refusal(pkg, systemKind = false, appOptions)?.let { return@withContext blocked(it) }

        val executor = router.current()
        val privileged = executor.tier != ElevationTier.NONE && executor.isAvailable()
        val tier = if (privileged) executor.tier else ElevationTier.NONE
        val userId = appOptions.targetUserId ?: query.callerUserId
        if (userId != query.callerUserId && !privileged) return@withContext blocked(strings.get(R.string.restore_app_user_needs_privilege, userId), ElevationTier.SHIZUKU)
        if (userId != query.callerUserId) {
            val users = query.userIds(executor)
            if (users != null && userId !in users) return@withContext blocked(strings.get(R.string.restore_app_user_missing, userId))
        }

        val dir = "${ArchiveLayout.APPS}/${ArchiveLayout.segment(pkg)}"
        val paths = artifact.items.map { it.archivePath }
        val hasApk = paths.any { ApkSetPlanner.isApkMember(dir, it) }
        val privateParts = listOf(AppArchivePart.CE_DATA, AppArchivePart.DE_DATA, AppArchivePart.CACHE)
        val hasPrivate = options.restoreAppData && paths.any { p -> privateParts.any { p == "$dir/${it.fileName}" } }
        val hasExternal = options.restoreAppData && paths.any { p -> externalPartOf(dir, p, appOptions) != null }
        val warnings = ArrayList<String>()
        if (hasPrivate && tier != ElevationTier.ROOT) warnings += strings.get(R.string.restore_app_preflight_root_for_data)
        if (hasExternal && tier == ElevationTier.NONE) warnings += strings.get(R.string.restore_app_external_needs_privilege, pkg)
        val apkPossible = hasApk && options.restoreApks && appOptions.versionPolicy != AppVersionPolicy.DATA_ONLY
        if (apkPossible && tier == ElevationTier.NONE && !standardInstaller.canInstall()) warnings += strings.get(R.string.restore_app_install_permission, pkg)
        val requiredTier = when {
            hasPrivate -> ElevationTier.ROOT
            hasExternal -> ElevationTier.SHIZUKU
            else -> ElevationTier.NONE
        }
        val restorable = apkPossible || (hasPrivate && tier == ElevationTier.ROOT) || (hasExternal && tier != ElevationTier.NONE) ||
            // data-only onto an installed app still grants permissions
            (!apkPossible && query.packageInfo(pkg) != null && tier != ElevationTier.NONE)
        val required = artifact.items.sumOf { it.sizeBytes ?: 0L }.takeIf { it > 0 } ?: artifact.sourceBytes
        val free = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrNull()
        UnitPreflight(
            unitId = unitId,
            available = restorable,
            requiredTier = requiredTier,
            requiredBytes = required,
            freeBytes = free,
            reason = if (restorable) null else strings.get(R.string.restore_app_nothing_restorable, pkg),
            warnings = warnings,
        )
    }

    /** Full restore of one app unit with explicit [appOptions]. Never throws except cancellation. */
    suspend fun restoreApp(
        backup: OpenedBackup,
        artifacts: List<ManifestArtifact>,
        options: RestoreOptions,
        appOptions: AppRestoreOptions,
        progress: RestoreProgress,
    ): AppRestoreReport = withContext(io) {
        val unitId = artifacts.firstOrNull()?.sourceId ?: UNIT_FALLBACK
        val run = Run(unitId, options, appOptions, progress, currentCoroutineContext()[Job])
        try {
            run.execute(backup, artifacts)
        } catch (e: CancellationException) {
            run.cleanup()
            throw e
        } catch (e: Exception) {
            DftbLog.e(TAG, "app restore $unitId failed", e)
            run.cleanup()
            run.fail(strings.get(R.string.restore_app_unexpected, run.displayName, e.message ?: e.javaClass.simpleName))
        }
        run.report()
    }

    /** State of one unit restore; methods run sequentially on the IO dispatcher. */
    private inner class Run(
        val unitId: String,
        val options: RestoreOptions,
        val appOptions: AppRestoreOptions,
        val progress: RestoreProgress,
        val job: Job?,
    ) {
        var pkg: String? = null
        var displayName: String = unitId
        private lateinit var packageDir: String
        private lateinit var executor: CommandExecutor
        private var tier = ElevationTier.NONE
        private var userId = 0
        private var manifest: AppManifest? = null
        private var installedBefore: InstalledPackage? = null
        private var signature = SignatureMatch.UNKNOWN
        private var decision: ApkDecision? = null
        private var apkPlan: List<PlannedApk> = emptyList()
        private var session: ApkInstallSession? = null
        private var apkFailure: String? = null
        private var installPhaseDone = false
        private var installOk: Boolean? = null
        private var deferredStandardCommit = false
        private var currentUid: Int? = null
        private var forceStopped = false
        private var dataBlocked = false
        private var warnedNotInstalled = false
        private var warnedRoot = false
        private var warnedExternal = false
        private var openExternal: ExternalCtx? = null
        private var skippedTreePart: AppArchivePart? = null
        private val restored = ArrayList<AppArchivePart>()
        private val skipped = LinkedHashSet<AppArchivePart>()
        private val warnings = ArrayList<String>()
        private var granted: List<String> = emptyList()
        private val notGranted = LinkedHashMap<String, String>()
        private var error: String? = null

        val isCancelled: () -> Boolean = { progress.isCancelled() || job?.isActive == false }

        fun warn(message: String) {
            warnings += message
            progress.onWarning(message)
        }

        fun fail(message: String) {
            if (error == null) error = message
        }

        fun report() = AppRestoreReport(
            unitId = unitId,
            packageName = pkg,
            ok = error == null,
            apkAction = decision?.action,
            apkInstalled = installOk == true,
            restoredParts = restored.toList(),
            skippedParts = skipped.toList(),
            grantedPermissions = granted,
            notGrantedPermissions = notGranted.toMap(),
            warnings = warnings.toList(),
            error = error,
        )

        fun cleanup() {
            session?.abandon()
            session = null
            openExternal?.let { it.extraction.abort() }
            openExternal = null
        }

        private fun checkCancelled() {
            if (isCancelled()) throw CancellationException("restore cancelled")
        }

        suspend fun execute(backup: OpenedBackup, artifacts: List<ManifestArtifact>) {
            val packages = artifacts.flatMap { a -> a.items.mapNotNull { packageOf(it.archivePath) } }.distinct()
            if (packages.isEmpty()) return fail(strings.get(R.string.restore_app_no_package))
            if (packages.size > 1) return fail(strings.get(R.string.restore_app_multiple_packages))
            val name = packages.single()
            pkg = name
            displayName = name
            packageDir = "${ArchiveLayout.APPS}/${ArchiveLayout.segment(name)}"
            val artifact = artifacts.firstOrNull { it.layout == LAYOUT_ARCHIVE || it.layout == LAYOUT_FILE_TREE }
                ?: return fail(strings.get(R.string.restore_app_layout_unsupported))
            refusal(name, systemKind = false, appOptions)?.let { return fail(it) }

            executor = router.current()
            tier = if (executor.tier != ElevationTier.NONE && executor.isAvailable()) executor.tier else ElevationTier.NONE
            userId = appOptions.targetUserId ?: query.callerUserId
            if (userId != query.callerUserId) {
                if (tier == ElevationTier.NONE) return fail(strings.get(R.string.restore_app_user_needs_privilege, userId))
                val users = query.userIds(executor)
                if (users != null && userId !in users) return fail(strings.get(R.string.restore_app_user_missing, userId))
            }
            itemsForSizing = artifact.items.associate { it.archivePath to it.sizeBytes }
            apkPlan = ApkSetPlanner.plan(packageDir, itemsForSizing, emptyList())
            if (tier == ElevationTier.SHIZUKU) sinks.clearStaging()

            try {
                if (artifact.layout == LAYOUT_FILE_TREE) readTree(backup, artifact) else readArchive(backup, artifact)
                if (error == null) {
                    closeExternal()
                    finishInstallPhase()
                    if (deferredStandardCommit) commitStandard()
                    if (error == null) restorePermissions()
                    if (error == null && installOk != true && currentUid == null) fail(strings.get(R.string.restore_app_not_installed, displayName))
                }
            } finally {
                cleanup()
            }
        }

        /**
         * One archive stream. After the last member the rest of the stored bytes is drained and
         * checked against the manifest digest, before a Standard install is committed.
         */
        private suspend fun readArchive(backup: OpenedBackup, artifact: ManifestArtifact) {
            val decoded = catalog.openDecoded(backup, artifact)
            try {
                val format = if (ArtifactSpecResolver.isTarFamily(artifact.format)) ArchiveFormat.TAR else artifact.format
                readers.reader(format, ArchiveReadSource.Streamed { decoded }).use { reader ->
                    for (entry in reader.entries()) {
                        checkCancelled()
                        if (!handle(entry) || error != null) break
                    }
                    if (error == null) verify { (decoded as? DecodedArtifactStream)?.finishAndVerify() }
                }
            } finally {
                runCatching { decoded.close() }
            }
        }

        /** RAW_COPY app backups: one stored file per item, read in manifest order. */
        private suspend fun readTree(backup: OpenedBackup, artifact: ManifestArtifact) {
            for (item in artifact.items) {
                checkCancelled()
                val type = when (item.type) {
                    ManifestItem.ItemType.FILE, ManifestItem.ItemType.STREAM -> ArchiveEntryType.FILE
                    ManifestItem.ItemType.DIRECTORY -> ArchiveEntryType.DIRECTORY
                    ManifestItem.ItemType.SYMLINK -> ArchiveEntryType.SYMLINK
                }
                val md = item.metadata
                val info = ArchiveEntryInfo(
                    path = item.archivePath,
                    type = type,
                    sizeBytes = item.sizeBytes ?: -1L,
                    linkTarget = item.linkTarget,
                    metadata = EntryMetadata(md?.modifiedAtMillis, md?.mode, md?.uid, md?.gid, md?.seLinuxContext),
                )
                val relevant = item.archivePath.startsWith("$packageDir/")
                if (type != ArchiveEntryType.FILE || item.storedPath == null || !relevant) {
                    if (!handle(ArchiveReadEntry(info, java.io.ByteArrayInputStream(ByteArray(0)))) || error != null) return
                    continue
                }
                val decoded = itemReader.openDecodedItem(backup, artifact, item)
                val counted = CountingInput(decoded)
                try {
                    val proceed = handle(ArchiveReadEntry(info, counted))
                    if (error == null && counted.count > 0) verify { decoded.finishAndVerify() }
                    if (!proceed || error != null) return
                } finally {
                    runCatching { decoded.close() }
                }
            }
        }

        private inline fun verify(block: () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                fail(strings.get(R.string.restore_app_unexpected, displayName, e.message ?: e.javaClass.simpleName))
            }
        }

        /** Returns false to stop reading the archive. */
        private suspend fun handle(entry: ArchiveReadEntry): Boolean {
            val path = entry.info.path
            if (!path.startsWith("$packageDir/") && path != packageDir) return true
            if (path == "$packageDir/${ArchiveLayout.APP_MANIFEST}") {
                readManifest(entry)
                return error == null
            }
            if (manifest == null) {
                // Directory entries of the package folder may precede the manifest; anything else may not.
                if (entry.info.type == ArchiveEntryType.DIRECTORY && path == packageDir) return true
                fail(strings.get(R.string.restore_app_no_manifest, displayName))
                return false
            }
            if (ApkSetPlanner.isApkMember(packageDir, path)) {
                closeExternal()
                writeApk(entry)
                return true
            }
            val treePart = treePartOf(path)
            if (treePart != null) {
                finishInstallPhase()
                feedTree(treePart, entry)
                return true
            }
            closeExternal()
            val part = AppArchivePart.entries.firstOrNull { path == "$packageDir/${it.fileName}" } ?: return true
            finishInstallPhase()
            when (part) {
                AppArchivePart.CE_DATA, AppArchivePart.DE_DATA, AppArchivePart.CACHE -> restorePrivate(part, entry)
                AppArchivePart.EXTERNAL_DATA, AppArchivePart.MEDIA, AppArchivePart.OBB -> restoreExternalTar(part, entry)
                AppArchivePart.SELINUX_LISTING -> Unit // labels are recomputed for the new uid (restorecon / recomputed categories)
            }
            return true
        }

        // ---- manifest & decisions ----------------------------------------------------------

        private suspend fun readManifest(entry: ArchiveReadEntry) {
            val bytes = readBounded(entry.stream, MAX_MANIFEST_BYTES)
            val parsed = try {
                ManifestReader.decodeFromString(AppManifest.serializer(), String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                return fail(strings.get(R.string.restore_app_manifest_unreadable, displayName, e.message ?: e.javaClass.simpleName))
            }
            val name = pkg!!
            if (parsed.packageName != name) return fail(strings.get(R.string.restore_app_manifest_mismatch, name, parsed.packageName))
            manifest = parsed
            displayName = parsed.label.ifBlank { name }
            refusal(name, systemKind = parsed.kind != KIND_USER, appOptions)?.let { return fail(it) }
            if (ProtectedPackages.classify(name, context.packageName, parsed.kind != KIND_USER) == ProtectedPackages.Verdict.WARN_SYSTEM) {
                warn(strings.get(R.string.restore_app_warn_system, displayName))
            }
            apkPlan = ApkSetPlanner.plan(packageDir, apkPlan.associate { it.archivePath to it.sizeBytes }, parsed.apkFiles)

            val installed = query.packageInfo(name)
            installedBefore = installed
            val uidBefore = query.uidForUser(name, userId, executor)
            if (installed != null) {
                signature = SignatureCheck.compare(installed.signers, installed.pastSigners, parsed.signingCertSha256, parsed.pastSigningCertSha256)
                when (signature) {
                    SignatureMatch.MISMATCH ->
                        if (!appOptions.allowSignatureMismatch) return fail(strings.get(R.string.restore_app_signature_mismatch, displayName))
                        else warn(strings.get(R.string.restore_app_signature_mismatch_override, displayName))
                    SignatureMatch.UNKNOWN -> warn(strings.get(R.string.restore_app_signature_unknown, displayName))
                    SignatureMatch.SAME, SignatureMatch.ROTATED -> Unit
                }
            }
            var d = VersionDecision.decide(
                backupVersion = parsed.versionCode,
                installedForUser = if (uidBefore != null) installed?.versionCode else null,
                onDevice = installed?.versionCode,
                policy = appOptions.versionPolicy,
                restoreApks = options.restoreApks,
                apkInBackup = apkPlan.isNotEmpty(),
                allowDowngrade = appOptions.allowDowngrade,
                signature = signature,
                privileged = tier != ElevationTier.NONE,
            )
            if (d.action == ApkAction.INSTALL) {
                val missing = ApkSetPlanner.missing(apkPlan, parsed.apkFiles)
                if (missing.isNotEmpty()) {
                    warn(strings.get(R.string.restore_app_apk_missing_parts, displayName, missing.joinToString(", ")))
                    apkFailure = missing.joinToString(", ")
                } else if (tier == ElevationTier.NONE && !standardInstaller.canInstall()) {
                    standardInstaller.requestInstallPermission(name)
                    apkFailure = strings.get(R.string.restore_app_install_permission, displayName)
                }
            }
            when (d.action) {
                ApkAction.SKIP_INSTALLED_NEWER -> warn(strings.get(R.string.restore_app_installed_newer, displayName))
                ApkAction.SKIP_SAME_VERSION -> DftbLog.i(TAG, strings.get(R.string.restore_app_same_version, displayName, parsed.versionName ?: parsed.versionCode.toString()))
                else -> Unit
            }
            if (d.action == ApkAction.INSTALL && apkPlan.isEmpty()) d = ApkDecision(ApkAction.SKIP_NO_APK)
            decision = d
            currentUid = uidBefore
        }

        // ---- APK ---------------------------------------------------------------------------

        private suspend fun writeApk(entry: ArchiveReadEntry) {
            val d = decision ?: return
            if (d.action != ApkAction.INSTALL || apkFailure != null || installPhaseDone) return
            val path = entry.info.path
            val planned = apkPlan.firstOrNull { it.archivePath == path } ?: return
            progress.onItem(strings.get(R.string.restore_app_progress_item, displayName, planned.sessionName))
            var spool: File? = null
            try {
                val active = session ?: openSession(d) ?: return
                var size = entry.info.sizeBytes.takeIf { it >= 0 } ?: planned.sizeBytes ?: -1L
                var input: InputStream = entry.stream
                if (size < 0 && tier != ElevationTier.NONE) {
                    spool = spoolToCache(entry.stream)
                    size = spool.length()
                    input = FileInputStream(spool)
                }
                input.use { active.write(planned, size, it, isCancelled, progress::onBytes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                apkFailure = e.message ?: e.javaClass.simpleName
                session?.abandon()
                session = null
            } finally {
                spool?.delete()
            }
        }

        private suspend fun openSession(d: ApkDecision): ApkInstallSession? {
            val total = ApkSetPlanner.totalBytes(apkPlan)
            val created = if (tier == ElevationTier.NONE) {
                standardInstaller.create(pkg!!, displayName, total)
            } else {
                val installer = manifest?.installerPackageName?.takeIf { appOptions.restoreInstallerAttribution }
                val (s, failure) = shellInstaller.create(executor, pkg!!, userId, total, d.downgrade, installer)
                if (s == null) apkFailure = failure ?: "pm install-create failed"
                s
            }
            session = created
            return created
        }

        /** Commits (privileged tiers) or schedules the Standard commit, then re-queries the uid. Once. */
        private suspend fun finishInstallPhase() {
            if (installPhaseDone) return
            installPhaseDone = true
            val d = decision ?: return
            when (d.action) {
                ApkAction.INSTALL_EXISTING -> {
                    val failure = shellInstaller.installExisting(executor, pkg!!, userId)
                    installOk = failure == null
                    if (failure != null) installFailed(failure)
                }
                ApkAction.INSTALL -> {
                    val active = session
                    when {
                        apkFailure != null -> installFailed(apkFailure!!)
                        active == null -> installFailed(apkPlan.joinToString(", ") { it.sessionName } + ": not found in the archive")
                        // Standard installs need no uid for data (none is restored); commit after the
                        // whole archive was read so a damaged backup never gets as far as a prompt.
                        tier == ElevationTier.NONE -> deferredStandardCommit = true
                        else -> applyOutcome(active.commit())
                    }
                    if (tier != ElevationTier.NONE) session = null
                }
                else -> Unit
            }
            if (installOk == true) currentUid = query.uidForUser(pkg!!, userId, executor)
        }

        private suspend fun commitStandard() {
            deferredStandardCommit = false
            val active = session ?: return
            session = null
            applyOutcome(active.commit())
            if (installOk == true) currentUid = query.uidForUser(pkg!!, userId, executor)
        }

        private fun applyOutcome(outcome: InstallOutcome) {
            when (outcome) {
                InstallOutcome.Success -> installOk = true
                is InstallOutcome.Failed -> installFailed(outcome.message)
                InstallOutcome.Declined -> {
                    installOk = false
                    fail(strings.get(R.string.restore_app_install_declined, displayName))
                }
                InstallOutcome.TimedOut -> {
                    installOk = false
                    fail(strings.get(R.string.restore_app_install_timeout, displayName))
                }
            }
        }

        private fun installFailed(detail: String) {
            installOk = false
            fail(strings.get(R.string.restore_app_install_failed, displayName, detail))
        }

        // ---- private data ------------------------------------------------------------------

        /** Common gate for every data part; warns once per reason. */
        private fun dataTargetUid(): Int? {
            if (!options.restoreAppData || dataBlocked) return null
            if (installOk == false) {
                dataBlocked = true
                warn(strings.get(R.string.restore_app_data_skipped_after_install_failure, displayName))
                return null
            }
            val uid = currentUid
            if (uid == null) {
                if (!warnedNotInstalled) {
                    warnedNotInstalled = true
                    warn(strings.get(R.string.restore_app_not_installed, displayName))
                }
                return null
            }
            return uid
        }

        private suspend fun ensureStopped() {
            if (forceStopped) return
            forceStopped = true
            if (!data.forceStop(executor, pkg!!, userId)) DftbLog.w(TAG, "am force-stop ${pkg} failed")
        }

        private suspend fun restorePrivate(part: AppArchivePart, entry: ArchiveReadEntry) {
            if (part == AppArchivePart.CACHE && !appOptions.restoreCache) return
            if (!options.restoreAppData) return
            if (tier != ElevationTier.ROOT) {
                skipped += part
                if (!warnedRoot) {
                    warnedRoot = true
                    warn(strings.get(R.string.restore_app_data_needs_root, displayName))
                }
                return
            }
            val uid = dataTargetUid()
            if (uid == null) {
                skipped += part
                return
            }
            val name = pkg!!
            val partName = strings.part(part)
            val dir = if (part == AppArchivePart.DE_DATA) AppDataPaths.deDataDir(name, userId) else AppDataPaths.ceDataDir(name, userId)
            val parent = dir.substringBeforeLast('/')
            ensureStopped()
            // Start the consumer first: if it cannot run, nothing has been wiped yet.
            val extraction = startExtraction(part, partName, parent, appOptions.restoreCache || part == AppArchivePart.CACHE, entry.info.sizeBytes.takeIf { it >= 0 })
                ?: return
            if (!resolveConflict(part, dir, partName, private = true)) {
                extraction.abort()
                return
            }
            progress.onItem(strings.get(R.string.restore_app_progress_item, displayName, partName))
            val ok = runExtraction(part, partName, extraction) { w -> SafeDataTarWriter.copyTar(entry.stream, w, isCancelled) }
            val recorded = when (part) {
                AppArchivePart.DE_DATA -> manifest?.deDataDir?.seLinuxContext
                else -> manifest?.ceDataDir?.seLinuxContext
            }
            val fix = data.fixPrivate(executor, dir, uid, recorded)
            fix.ownerError?.let { warn(strings.get(R.string.restore_app_owner_failed, displayName, partName, it)) }
            when (fix.labels) {
                AppDataRestorer.LabelOutcome.RESTORECON -> Unit
                AppDataRestorer.LabelOutcome.CHCON_FALLBACK -> warn(strings.get(R.string.restore_app_selinux_fallback, displayName, partName))
                AppDataRestorer.LabelOutcome.FAILED -> warn(strings.get(R.string.restore_app_selinux_failed, displayName, partName))
            }
            if (ok) restored += part else skipped += part
        }

        /**
         * Applies the conflict policy to [dir]. Returns false when the part must be skipped. Private
         * dirs must already exist (installd creates them; a missing CE dir usually means a locked user,
         * and extracting into it would bypass file-based encryption).
         */
        private suspend fun resolveConflict(part: AppArchivePart, dir: String, partName: String, private: Boolean): Boolean {
            val state = data.dirStates(executor, listOf(dir))[dir]
            when (state) {
                null, AppRestoreScripts.DirState.MISSING -> {
                    if (private) {
                        skipped += part
                        warn(strings.get(R.string.restore_app_data_dir_missing, displayName, partName))
                        return false
                    }
                    return true
                }
                AppRestoreScripts.DirState.SYMLINK -> {
                    skipped += part
                    warn(strings.get(R.string.restore_app_data_failed, displayName, partName, "symlinked directory"))
                    return false
                }
                AppRestoreScripts.DirState.EMPTY, AppRestoreScripts.DirState.DATA -> Unit
            }
            val hasData = state == AppRestoreScripts.DirState.DATA
            var wiped = false
            when (options.conflictPolicy) {
                ConflictPolicy.SKIP -> if (hasData) {
                    skipped += part
                    warn(strings.get(R.string.restore_app_data_skipped_existing, displayName, partName))
                    return false
                }
                ConflictPolicy.KEEP_BOTH -> if (hasData) warn(strings.get(R.string.restore_app_data_merged, displayName, partName))
                ConflictPolicy.OVERWRITE -> if (part != AppArchivePart.CACHE) {
                    val failure = if (private) data.wipePrivate(executor, dir) else data.wipeExternal(executor, dir)
                    if (failure != null) {
                        skipped += part
                        warn(strings.get(R.string.restore_app_data_failed, displayName, partName, failure))
                        return false
                    }
                    wiped = true
                }
            }
            // A root `tar -x` follows symlinks already on disk (the sanitizer only sees the archive's
            // own), so an app-made link could redirect writes anywhere. Merge only into link-free trees.
            if (!wiped) {
                val link = data.firstSymlink(executor, dir, keepLib = private)
                if (link != false) {
                    skipped += part
                    warn(strings.get(R.string.restore_app_merge_refused_symlink, displayName, partName))
                    return false
                }
            }
            return true
        }

        /** Opens the privileged `tar -x`; null (part skipped, warned) when the helper cannot start. */
        private suspend fun startExtraction(part: AppArchivePart, partName: String, parent: String, includeCache: Boolean, expectedBytes: Long?): TarExtraction? =
            try {
                data.openExtraction(executor, "${pkg} $partName", parent, pkg!!, includeCache, expectedBytes, progress::onBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                skipped += part
                warn(strings.get(R.string.restore_app_data_failed, displayName, partName, e.message ?: e.javaClass.simpleName))
                null
            }

        /** Feeds and finishes [extraction]; true when `tar -x` reported success. */
        private suspend fun runExtraction(part: AppArchivePart, partName: String, extraction: TarExtraction, feed: (SafeDataTarWriter) -> Unit): Boolean {
            try {
                feed(extraction.writer)
            } catch (e: CancellationException) {
                extraction.abort()
                throw e
            } catch (e: IOException) {
                extraction.abort()
                warn(strings.get(R.string.restore_app_data_failed, displayName, partName, e.message ?: e.javaClass.simpleName))
                return false
            }
            return completeExtraction(part, partName, extraction)
        }

        private suspend fun completeExtraction(part: AppArchivePart, partName: String, extraction: TarExtraction): Boolean {
            val (stats, status) = try {
                extraction.finish()
            } catch (e: CancellationException) {
                extraction.abort()
                throw e
            } catch (e: IOException) {
                extraction.abort()
                warn(strings.get(R.string.restore_app_data_failed, displayName, partName, e.message ?: e.javaClass.simpleName))
                return false
            }
            if (stats.droppedSpecial > 0) warn(strings.get(R.string.restore_app_special_dropped, displayName, stats.droppedSpecial))
            if (status.exitCode != 0) {
                warn(strings.get(R.string.restore_app_data_failed, displayName, partName, status.text.ifBlank { "tar exit ${status.exitCode}" }))
                return false
            }
            DftbLog.i(TAG, "${pkg} ${part.fileName}: ${stats.entries} entries, ${stats.bytes} bytes (dropped lib=${stats.droppedLib} cache=${stats.droppedCache})")
            return true
        }

        // ---- shared storage ----------------------------------------------------------------

        private inner class ExternalCtx(
            val part: AppArchivePart,
            val partName: String,
            val packageDir: String,
            val owner: Owner?,
            val extraction: TarExtraction,
        ) {
            var failed = false
        }

        private fun allowedExternal(part: AppArchivePart): Boolean = options.restoreAppData && when (part) {
            AppArchivePart.OBB -> appOptions.restoreObb
            AppArchivePart.EXTERNAL_DATA, AppArchivePart.MEDIA -> appOptions.restoreExternalData
            else -> false
        }

        private suspend fun beginExternal(part: AppArchivePart, expectedBytes: Long?): ExternalCtx? {
            if (!allowedExternal(part)) return null
            if (tier == ElevationTier.NONE) {
                skipped += part
                if (!warnedExternal) {
                    warnedExternal = true
                    warn(strings.get(R.string.restore_app_external_needs_privilege, displayName))
                }
                return null
            }
            val uid = dataTargetUid()
            if (uid == null) {
                skipped += part
                return null
            }
            val name = pkg!!
            val ext = externalOf(part)
            val partName = strings.part(part)
            val parent = data.externalParent(ext, userId, tier)
            val dir = "$parent/$name"
            var owner: Owner? = null
            if (tier == ElevationTier.ROOT) {
                val owners = data.owners(executor, listOf(dir, parent))
                if (parent !in owners) {
                    skipped += part
                    warn(strings.get(R.string.restore_app_data_dir_missing, displayName, partName))
                    return null
                }
                owner = ExternalOwnership.ownerFor(ext, owners[dir]?.owner, owners[parent]?.owner, uid)
            } else {
                val parentState = data.dirStates(executor, listOf(parent))[parent]
                if (parentState != AppRestoreScripts.DirState.EMPTY && parentState != AppRestoreScripts.DirState.DATA) {
                    skipped += part
                    warn(strings.get(R.string.restore_app_data_dir_missing, displayName, partName))
                    return null
                }
            }
            ensureStopped()
            val extraction = startExtraction(part, partName, parent, includeCache = true, expectedBytes = expectedBytes) ?: return null
            if (!resolveConflict(part, dir, partName, private = false)) {
                extraction.abort()
                return null
            }
            progress.onItem(strings.get(R.string.restore_app_progress_item, displayName, partName))
            return ExternalCtx(part, partName, dir, owner, extraction)
        }

        private suspend fun endExternal(ctx: ExternalCtx) {
            val ok = !ctx.failed && completeExtraction(ctx.part, ctx.partName, ctx.extraction)
            if (tier == ElevationTier.ROOT) {
                if (ctx.owner == null) {
                    warn(strings.get(R.string.restore_app_owner_unknown, displayName, ctx.partName))
                } else {
                    data.chownExternal(executor, ctx.packageDir, ctx.owner)?.let { warn(strings.get(R.string.restore_app_owner_failed, displayName, ctx.partName, it)) }
                }
                if (data.relabel(executor, ctx.packageDir, null, null) == AppDataRestorer.LabelOutcome.FAILED) {
                    warn(strings.get(R.string.restore_app_selinux_failed, displayName, ctx.partName))
                }
            }
            if (ok) restored += ctx.part else skipped += ctx.part
        }

        private suspend fun restoreExternalTar(part: AppArchivePart, entry: ArchiveReadEntry) {
            val ctx = beginExternal(part, entry.info.sizeBytes.takeIf { it >= 0 }) ?: return
            try {
                SafeDataTarWriter.copyTar(entry.stream, ctx.extraction.writer, isCancelled)
            } catch (e: CancellationException) {
                ctx.extraction.abort()
                throw e
            } catch (e: IOException) {
                ctx.extraction.abort()
                ctx.failed = true
                warn(strings.get(R.string.restore_app_data_failed, displayName, ctx.partName, e.message ?: e.javaClass.simpleName))
            }
            if (ctx.failed) skipped += part else endExternal(ctx)
        }

        private fun treePartOf(path: String): AppArchivePart? =
            listOf(AppArchivePart.EXTERNAL_DATA, AppArchivePart.MEDIA, AppArchivePart.OBB).firstOrNull { p ->
                val root = "$packageDir/${p.treeName}"
                path == root || path.startsWith("$root/")
            }

        /** Directly-readable trees (`external_data/...`) are re-packed into one tar per part. */
        private suspend fun feedTree(part: AppArchivePart, entry: ArchiveReadEntry) {
            val open = openExternal
            if (open != null && open.part != part) closeExternal()
            if (skippedTreePart == part) return
            val ctx = openExternal ?: run {
                val prefix = "$packageDir/${part.treeName}"
                val expected = currentArtifactItemsBytes(prefix)
                val started = beginExternal(part, expected)
                if (started == null) {
                    skippedTreePart = part
                    return
                }
                openExternal = started
                started
            }
            if (ctx.failed) return
            val name = pkg!!
            val root = "$packageDir/${part.treeName}"
            val rel = entry.info.path.removePrefix(root).trimStart('/')
            val member = if (rel.isEmpty()) name else "$name/$rel"
            val md = entry.info.metadata
            var spool: File? = null
            try {
                when (entry.info.type) {
                    ArchiveEntryType.DIRECTORY -> ctx.extraction.writer.directory(member, md.mode, md.modifiedAtMillis)
                    ArchiveEntryType.FILE -> {
                        var size = entry.info.sizeBytes
                        var input = entry.stream
                        if (size < 0) {
                            spool = spoolToCache(entry.stream)
                            size = spool.length()
                            input = FileInputStream(spool)
                        }
                        input.use { ctx.extraction.writer.file(member, size, md.mode, md.modifiedAtMillis, it) }
                    }
                    ArchiveEntryType.SYMLINK -> entry.info.linkTarget?.let { ctx.extraction.writer.symlink(member, it, md.modifiedAtMillis) }
                    ArchiveEntryType.HARD_LINK -> {
                        val target = entry.info.linkTarget
                        if (target != null && target.startsWith("$root/")) ctx.extraction.writer.hardLink(member, "$name/${target.removePrefix("$root/")}")
                        else ctx.extraction.writer.special(member)
                    }
                    ArchiveEntryType.OTHER -> ctx.extraction.writer.special(member)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ctx.failed = true
                ctx.extraction.abort()
                warn(strings.get(R.string.restore_app_data_failed, displayName, ctx.partName, e.message ?: e.javaClass.simpleName))
            } finally {
                spool?.delete()
            }
        }

        private suspend fun closeExternal() {
            val ctx = openExternal ?: return
            openExternal = null
            if (ctx.failed) skipped += ctx.part else endExternal(ctx)
        }

        private var itemsForSizing: Map<String, Long?> = emptyMap()

        private fun currentArtifactItemsBytes(prefix: String): Long? {
            val sizes = itemsForSizing.filterKeys { it == prefix || it.startsWith("$prefix/") }.values
            return if (sizes.isEmpty()) null else sizes.sumOf { it ?: 0L }
        }

        // ---- permissions -------------------------------------------------------------------

        private suspend fun restorePermissions() {
            val m = manifest ?: return
            if (!appOptions.restoreRuntimePermissions || m.grantedPermissions.isEmpty()) return
            if (installOk == false || currentUid == null) return
            val installed = query.packageInfo(m.packageName) ?: return
            val kinds = query.permissionKinds(m.grantedPermissions)
            val current = if (userId == query.callerUserId) installed.grantedForCaller else emptySet()
            val plan = PermissionPlanner.plan(m.grantedPermissions, installed.requestedPermissions, current, kinds)
            plan.notRequested.forEach { notGranted[it] = "not requested" }
            plan.undefined.forEach { notGranted[it] = "not defined" }
            if (plan.toGrant.isNotEmpty()) {
                if (tier == ElevationTier.NONE) {
                    plan.toGrant.forEach { notGranted[it] = "standard mode" }
                    warn(strings.get(R.string.restore_app_permissions_need_privilege, displayName))
                } else {
                    val result = executor.execute(Command(listOf("sh", "-c", AppRestoreScripts.grantScript(m.packageName, userId, plan.toGrant)), timeoutMillis = GRANT_TIMEOUT_MILLIS))
                    val outcome = AppRestoreScripts.parseGrantOutput(result.stdout)
                    granted = outcome.granted
                    outcome.failed.forEach { (p, why) -> notGranted[p] = why.ifBlank { "pm grant failed" } }
                    plan.toGrant.filter { it !in outcome.granted && it !in outcome.failed }.forEach { notGranted[it] = result.stderrText.ifBlank { "no result" } }
                }
            }
            val reportable = notGranted.keys.toList()
            if (reportable.isNotEmpty() && tier != ElevationTier.NONE) {
                warn(strings.get(R.string.restore_app_permissions_not_granted, displayName, reportable.size, reportable.joinToString(", ") { it.substringAfterLast('.') }))
            }
        }

    }

    // ---- shared helpers --------------------------------------------------------------------

    /** Counts bytes read, to tell whether a FILE_TREE item was consumed (and so must be verified). */
    private class CountingInput(input: InputStream) : java.io.FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun close() = Unit
    }

    /** Localized refusal for protected packages, or null when allowed. */
    private fun refusal(pkg: String, systemKind: Boolean, appOptions: AppRestoreOptions): String? {
        if (appOptions.allowProtectedPackages) return null
        return when (ProtectedPackages.classify(pkg, context.packageName, systemKind)) {
            ProtectedPackages.Verdict.REFUSED_SELF -> strings.get(R.string.restore_app_refused_self, pkg)
            ProtectedPackages.Verdict.REFUSED_PRIVILEGE_PROVIDER -> strings.get(R.string.restore_app_refused_privilege_provider, pkg)
            ProtectedPackages.Verdict.ALLOWED, ProtectedPackages.Verdict.WARN_SYSTEM -> null
        }
    }

    private fun externalPartOf(packageDir: String, path: String, appOptions: AppRestoreOptions): AppArchivePart? {
        val parts = listOf(AppArchivePart.EXTERNAL_DATA, AppArchivePart.MEDIA, AppArchivePart.OBB)
        val part = parts.firstOrNull { p ->
            path == "$packageDir/${p.fileName}" || path == "$packageDir/${p.treeName}" || path.startsWith("$packageDir/${p.treeName}/")
        } ?: return null
        val allowed = if (part == AppArchivePart.OBB) appOptions.restoreObb else appOptions.restoreExternalData
        return part.takeIf { allowed }
    }

    private fun externalOf(part: AppArchivePart): ExternalPart = when (part) {
        AppArchivePart.MEDIA -> ExternalPart.MEDIA
        AppArchivePart.OBB -> ExternalPart.OBB
        else -> ExternalPart.DATA
    }

    private fun spoolToCache(input: InputStream): File {
        val file = File.createTempFile("dftb-rst-", ".spool", context.cacheDir)
        try {
            FileOutputStream(file).use { out -> input.copyTo(out, SPOOL_BUFFER) }
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        return file
    }

    private fun readBounded(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) throw IOException("app manifest larger than $max bytes")
        }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "AppRestorer"
        private const val UNIT_FALLBACK = "app"
        private const val LAYOUT_ARCHIVE = "archive"
        private const val LAYOUT_FILE_TREE = "file_tree"
        private const val KIND_USER = "USER"
        private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        private const val SPOOL_BUFFER = 256 * 1024
        private const val GRANT_TIMEOUT_MILLIS = 120_000L

        /** Tolerant of fields added by newer DFTB versions. */
        private val ManifestReader = Json { ignoreUnknownKeys = true }

        /** `apps/<pkg>/manifest.json` -> `<pkg>`. */
        internal fun packageOf(archivePath: String): String? {
            val prefix = "${ArchiveLayout.APPS}/"
            if (!archivePath.startsWith(prefix) || !archivePath.endsWith("/${ArchiveLayout.APP_MANIFEST}")) return null
            val name = archivePath.removePrefix(prefix).removeSuffix("/${ArchiveLayout.APP_MANIFEST}")
            return name.takeIf { !it.contains('/') && AppDataPaths.isValidPackageName(it) }
        }
    }
}
