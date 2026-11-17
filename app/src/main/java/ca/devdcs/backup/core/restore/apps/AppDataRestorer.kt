package ca.devdcs.backup.core.restore.apps

import android.content.Context
import androidx.annotation.StringRes
import ca.devdcs.backup.R
import ca.devdcs.backup.core.discovery.apps.AppDataPaths
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandResult
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.pipeline.layout.ArchiveLayout.AppArchivePart
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Localized strings of the app restorer. */
@Singleton
class AppRestoreStrings @Inject constructor(@ApplicationContext private val context: Context) {
    fun get(@StringRes id: Int, vararg args: Any): String = if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    fun part(part: AppArchivePart): String = get(
        when (part) {
            AppArchivePart.CE_DATA -> R.string.restore_app_part_ce
            AppArchivePart.DE_DATA -> R.string.restore_app_part_de
            AppArchivePart.CACHE -> R.string.restore_app_part_cache
            AppArchivePart.SELINUX_LISTING -> R.string.restore_app_part_ce
            AppArchivePart.EXTERNAL_DATA -> R.string.restore_app_part_external
            AppArchivePart.MEDIA -> R.string.restore_app_part_media
            AppArchivePart.OBB -> R.string.restore_app_part_obb
        },
    )
}

/**
 * An open privileged `tar -x`: members go into [writer], which validates them on the way.
 * [finish] ends the archive and returns the consumer's status; [abort] kills it.
 */
class TarExtraction internal constructor(
    private val sink: PrivilegedSink,
    private val buffered: BufferedOutputStream,
    val writer: SafeDataTarWriter,
) {
    suspend fun finish(): Pair<SafeTarStats, SinkStatus> {
        val stats = writer.finish()
        buffered.flush()
        return stats to sink.complete()
    }

    fun abort() = sink.abort()
}

/**
 * Shell work around restoring app data: force-stop, conflict handling, privileged extraction,
 * ownership for the CURRENT uid and SELinux relabelling. Private (CE/DE/cache) data is ROOT-only;
 * shared-storage parts work on ROOT (lower filesystem `/data/media/<u>`, owner fixed explicitly)
 * and SHIZUKU (`/storage/emulated/<u>` through FUSE, which assigns ownership itself).
 */
@Singleton
class AppDataRestorer @Inject constructor(
    private val sinks: PrivilegedSinkFactory,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun sh(executor: CommandExecutor, script: String, timeoutMillis: Long = SHORT_TIMEOUT_MILLIS): CommandResult = withContext(io) {
        executor.execute(Command(listOf("sh", "-c", script), timeoutMillis = timeoutMillis))
    }

    suspend fun forceStop(executor: CommandExecutor, packageName: String, userId: Int): Boolean =
        sh(executor, AppRestoreScripts.forceStop(packageName, userId)).isSuccess

    suspend fun dirStates(executor: CommandExecutor, dirs: List<String>): Map<String, AppRestoreScripts.DirState> {
        val result = sh(executor, AppRestoreScripts.dataDirStateScript(dirs))
        return if (result.launched) AppRestoreScripts.parseDirStates(result.stdout) else emptyMap()
    }

    /** Null on success, else the shell's error. */
    suspend fun wipePrivate(executor: CommandExecutor, dir: String): String? =
        errorOf(sh(executor, AppRestoreScripts.wipeDirScript(dir), LONG_TIMEOUT_MILLIS))

    suspend fun wipeExternal(executor: CommandExecutor, dir: String): String? =
        errorOf(sh(executor, AppRestoreScripts.wipeExternalScript(dir), LONG_TIMEOUT_MILLIS))

    /**
     * Whether [dir] contains any symlink (the installd `lib` link excluded when [keepLib]). False =
     * none; true = found; null = could not be checked (treated as unsafe by callers).
     */
    suspend fun firstSymlink(executor: CommandExecutor, dir: String, keepLib: Boolean): Boolean? {
        val result = sh(executor, AppRestoreScripts.findSymlinkScript(dir, keepLib), LONG_TIMEOUT_MILLIS)
        if (!result.isSuccess) return null
        return result.stdout.any { it.isNotBlank() }
    }

    suspend fun owners(executor: CommandExecutor, paths: List<String>): Map<String, AppRestoreScripts.StatOwner> {
        val result = sh(executor, AppRestoreScripts.statOwnerScript(paths))
        return if (result.launched) AppRestoreScripts.parseStatOwner(result.stdout) else emptyMap()
    }

    /** Starts `tar -x` into [parent]; members must be `<root>/...`. */
    suspend fun openExtraction(
        executor: CommandExecutor,
        label: String,
        parent: String,
        root: String,
        includeCache: Boolean,
        expectedBytes: Long?,
        onBytes: (Long) -> Unit,
    ): TarExtraction {
        val sink = sinks.open(
            executor,
            label = label,
            stdinScript = AppRestoreScripts.extractFromStdin(parent),
            fileScript = { path -> AppRestoreScripts.extractFromFile(path, parent) },
            expectedBytes = expectedBytes,
        )
        val buffered = BufferedOutputStream(sink.output, BUFFER)
        return TarExtraction(sink, buffered, SafeDataTarWriter(buffered, root, includeCache, onBytes))
    }

    /** Outcome of fixing one private dir. */
    data class FixResult(val ownerError: String?, val labels: LabelOutcome)

    enum class LabelOutcome { RESTORECON, CHCON_FALLBACK, FAILED }

    /**
     * chown the dir to [uid] (cache dirs to the cache gid) and relabel: `restorecon` first (it
     * derives the MLS categories from the current uid via packages.list); if it is missing or
     * fails, `chcon` with [recordedContext]'s type and categories recomputed for [uid].
     */
    suspend fun fixPrivate(executor: CommandExecutor, dir: String, uid: Int, recordedContext: String?): FixResult {
        val owner = errorOf(sh(executor, AppRestoreScripts.chownDataDirScript(dir, Owner(uid, uid), AppIds.cacheGid(uid)), LONG_TIMEOUT_MILLIS))
        return FixResult(owner, relabel(executor, dir, uid, recordedContext))
    }

    suspend fun relabel(executor: CommandExecutor, dir: String, uid: Int?, recordedContext: String?): LabelOutcome {
        val result = sh(executor, AppRestoreScripts.restoreconScript(listOf(dir)), LONG_TIMEOUT_MILLIS)
        val parsed = AppRestoreScripts.parseRestorecon(result.stdout)
        if (result.launched && !parsed.toolMissing && dir in parsed.ok) return LabelOutcome.RESTORECON
        val context = if (uid != null && recordedContext != null) AppIds.relabel(recordedContext, uid) else recordedContext
        if (context == null) return LabelOutcome.FAILED
        return if (sh(executor, AppRestoreScripts.chconScript(dir, context), LONG_TIMEOUT_MILLIS).isSuccess) LabelOutcome.CHCON_FALLBACK else LabelOutcome.FAILED
    }

    /** chown -R an external package dir (ROOT, lower filesystem). Null on success. */
    suspend fun chownExternal(executor: CommandExecutor, dir: String, owner: Owner): String? =
        errorOf(sh(executor, AppRestoreScripts.chownTreeScript(dir, owner), LONG_TIMEOUT_MILLIS))

    /** Where a shared-storage part lives for this tier. */
    fun externalParent(part: ExternalPart, userId: Int, tier: ElevationTier): String =
        AppDataPaths.emulatedRoot(userId, backingStore = tier == ElevationTier.ROOT) + "/Android/" + part.folder

    private fun errorOf(result: CommandResult): String? =
        if (result.isSuccess) null else result.stderrText.ifBlank { result.stdoutText }.ifBlank { "exit ${result.exitCode}" }

    companion object {
        private const val BUFFER = 256 * 1024
        private const val SHORT_TIMEOUT_MILLIS = 60_000L
        /** chown / restorecon / rm over 100k-file data dirs. */
        private const val LONG_TIMEOUT_MILLIS = 15L * 60 * 1000
    }
}
