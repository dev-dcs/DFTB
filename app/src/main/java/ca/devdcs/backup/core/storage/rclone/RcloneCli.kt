package ca.devdcs.backup.core.storage.rclone

import android.content.Context
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point to the rclone binary for everything that is not a storage session: building the
 * right [RcloneProcessRunner] for the located [RcloneRuntime], obscuring passwords for config
 * sections, and reporting the version.
 *
 * Exists so the backend, the setup UI and diagnostics share one runner configuration (work/cache
 * dirs, base environment) instead of each re-deriving it.
 */
@Singleton
class RcloneCli @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locator: RcloneLocator,
    private val configStore: RcloneConfigStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val runDir: File get() = configStore.runDir

    /** rclone's `--cache-dir`, kept under the private rclone dir so root-run files get repaired too. */
    val cacheDir: File get() = File(configStore.rcloneDir, "cache")

    private val fifoDir: File get() = configStore.fifoDir

    /** Resolve rclone and build a runner for it. @throws StorageException TOOL_MISSING. */
    suspend fun runner(): RcloneProcessRunner {
        val runtime = locator.resolve()
        val baseEnv = mapOf(
            "HOME" to configStore.rcloneDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
        )
        return when (runtime) {
            is RcloneRuntime.Direct -> {
                try {
                    withContext(ioDispatcher) { RcloneFiles.ensurePrivateDir(configStore.rcloneDir) }
                } catch (e: IOException) {
                    throw StorageException(StorageException.Reason.PERMISSION, "Cannot prepare ${configStore.rcloneDir}: ${e.message}", e)
                }
                DirectRcloneRunner(runtime.path, configStore.rcloneDir, baseEnv, ioDispatcher)
            }
            is RcloneRuntime.ViaRoot -> RootRcloneRunner(
                binary = runtime.path,
                executor = runtime.executor,
                workDir = runDir,
                fifoDir = fifoDir,
                repairDirs = listOf(configStore.rcloneDir),
                appUid = android.os.Process.myUid(),
                baseEnv = baseEnv,
                ioDispatcher = ioDispatcher,
                scope = scope,
            )
        }
    }

    /**
     * rclone-obscure [plain] (required for `pass`-style config options) using the real binary via
     * `rclone obscure -`, with the secret on stdin, never argv.
     * @throws StorageException TOOL_MISSING / PROTOCOL.
     */
    suspend fun obscure(plain: String): String {
        if (plain.contains('\n') || plain.contains('\r')) {
            throw StorageException(StorageException.Reason.MISCONFIGURED, "Passwords containing line breaks cannot be stored in rclone.conf")
        }
        val result = runner().run(listOf("obscure", "-"), emptyMap(), stdin = (plain + "\n").toByteArray(Charsets.UTF_8), timeoutMillis = SHORT_TIMEOUT)
        if (!result.isSuccess) throw RcloneErrorMapper.toException("obscure", result.exitCode, result.logLines)
        return result.stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: throw StorageException(StorageException.Reason.PROTOCOL, "rclone obscure produced no output")
    }

    /** First line of `rclone version`, or null when rclone is unavailable. */
    suspend fun version(): String? = try {
        val result = runner().run(listOf("version"), emptyMap(), timeoutMillis = SHORT_TIMEOUT)
        if (result.isSuccess) result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim() else null
    } catch (_: StorageException) {
        null
    }

    /** Flags every DFTB rclone invocation carries. */
    fun commonFlags(configPath: String): List<String> = listOf(
        "--config", configPath,
        "--cache-dir", cacheDir.absolutePath,
        "--low-level-retries", "3",
        "--stats", "0",
        "--use-json-log",
        "--ask-password=false",
    )

    private companion object {
        const val SHORT_TIMEOUT = 30_000L
    }
}
