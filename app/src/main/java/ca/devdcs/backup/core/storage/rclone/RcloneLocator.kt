package ca.devdcs.backup.core.storage.rclone

import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.provision.BundledTool
import ca.devdcs.backup.core.provision.NativeToolProvisioner
import ca.devdcs.backup.core.provision.ToolSource
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A usable rclone binary and how it must be launched. */
sealed interface RcloneRuntime {
    val path: String
    val source: ToolSource

    /** The app uid can exec [path]: run with ProcessBuilder (full streaming). */
    data class Direct(override val path: String, override val source: ToolSource) : RcloneRuntime

    /** Only the root shell can exec [path]: run through [executor] (streaming via FIFO). */
    data class ViaRoot(
        override val path: String,
        override val source: ToolSource,
        val executor: CommandExecutor,
    ) : RcloneRuntime
}

/**
 * Finds an rclone binary DFTB can actually run, without ever assuming one exists.
 *
 * Exists because rclone is not yet bundled in the APK: it may be shipped later as
 * `librclone.so`, provided by the companion root module, or installed system-wide. Order:
 * 1. APK copy in `nativeLibraryDir` (via [NativeToolProvisioner.locate]) → [RcloneRuntime.Direct];
 * 2. well-known system paths the app uid may exec (works in Standard mode too) → Direct;
 * 3. [NativeToolProvisioner.discoverViaShell] through the current executor: a copy the app uid can
 * also exec directly → Direct; otherwise, on the ROOT tier → [RcloneRuntime.ViaRoot].
 * A Shizuku-only copy is rejected: the shell uid cannot read DFTB's private rclone.conf.
 * Nothing found → [StorageException.Reason.TOOL_MISSING].
 */
@Singleton
class RcloneLocator @Inject constructor(
    private val provisioner: NativeToolProvisioner,
    private val router: CommandExecutorRouter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private var cached: Pair<ElevationTier, RcloneRuntime>? = null

    /** Resolve (cached per operating tier until [invalidate]). */
    suspend fun resolve(): RcloneRuntime = mutex.withLock {
        val executor = router.current()
        cached?.let { (tier, runtime) -> if (tier == executor.tier) return@withLock runtime }
        val runtime = find(executor)
        cached = executor.tier to runtime
        DftbLog.i(TAG, "rclone resolved: ${runtime::class.simpleName} ${runtime.path} (${runtime.source})")
        runtime
    }

    /** Forget the cached result (after a tool install, or when a cached binary stopped working). */
    suspend fun invalidate() = mutex.withLock { cached = null }

    private suspend fun find(executor: CommandExecutor): RcloneRuntime {
        val bundled = provisioner.locate(TOOL_ID)
        if (bundled.available && bundled.path != null) return RcloneRuntime.Direct(bundled.path, ToolSource.NATIVE_LIB_DIR)

        SYSTEM_CANDIDATES.firstOrNull { appCanRun(it) }?.let { return RcloneRuntime.Direct(it, ToolSource.SYSTEM_PATH) }

        val discovered = provisioner.discoverViaShell(executor, TOOL_ID)
        val path = discovered.path
        if (discovered.available && path != null) {
            if (appCanRun(path)) return RcloneRuntime.Direct(path, discovered.source)
            if (executor.tier == ElevationTier.ROOT) return RcloneRuntime.ViaRoot(path, discovered.source, executor)
            throw StorageException(
                StorageException.Reason.TOOL_MISSING,
                "rclone was found at $path but only the ${executor.tier} shell can run it, and that shell cannot read " +
                    "DFTB's private rclone config. Use Root mode or a build of DFTB that bundles rclone.",
            )
        }
        throw StorageException(
            StorageException.Reason.TOOL_MISSING,
            "rclone is not available: this build of DFTB does not bundle it (${BundledTool.RCLONE.libFileName} absent), " +
                "and no rclone binary was found on the device (checked ${SYSTEM_CANDIDATES.joinToString()} and the " +
                "${executor.tier} shell PATH/companion module). Install rclone through the companion module or system-wide, then retry.",
        )
    }

    /** True when the app uid can exec [path] and it answers `rclone version`. */
    private suspend fun appCanRun(path: String): Boolean = withContext(ioDispatcher) {
        val file = File(path)
        if (!file.isFile || !file.canExecute()) return@withContext false
        runCatching {
            val process = ProcessBuilder(path, "version").redirectErrorStream(true).start()
            process.outputStream.close()
            val out = process.inputStream.bufferedReader().use { reader -> reader.readLine().orEmpty() }
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0 && out.startsWith("rclone")
            }
        }.getOrElse { error ->
            DftbLog.d(TAG, "App uid cannot run $path: ${error.message}")
            false
        }
    }

    private companion object {
        const val TAG = "RcloneLocator"
        val TOOL_ID = BundledTool.RCLONE.id
        const val PROBE_TIMEOUT_SECONDS = 10L
        val SYSTEM_CANDIDATES = listOf("/system/bin/rclone", "/system/xbin/rclone", "/product/bin/rclone", "/vendor/bin/rclone")
    }
}
