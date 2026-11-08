package ca.devdcs.backup.core.provision

import android.content.Context
import ca.devdcs.backup.core.environment.CompanionModuleProbe
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.nativebridge.DftbNative
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the command-line tools DFTB may ship, makes them runnable, and reports — honestly — which
 * ones are actually usable right now.
 *
 * The lifecycle of a tool is: it is packaged in the APK as `lib<id>.so` ([BundledTool.libFileName])
 * and the installer extracts it, already executable for the app's own uid, into
 * `applicationInfo.nativeLibraryDir`. For work the app does in-process (Standard mode) or for a
 * shell that happens to share the app's uid, that location is enough. When a command must be run by
 * a *different* uid — the Shizuku service (uid 2000) or a root shell (uid 0) — the binary may need
 * to live somewhere that foreign uid can both traverse to and execute; the conventional place on
 * Android is `/data/local/tmp`, which the adb/shell uid owns and root can reach. [stageForShell]
 * copies a tool there and marks it `0755`.
 *
 * Every method is non-throwing: a missing native library, an absent ABI, a dead shell, or an
 * SELinux denial is logged through [DftbLog] and surfaces as an [ToolAvailability] with
 * [ToolSource.ABSENT] (or the tool left unchanged), never as an exception. Callers can therefore
 * treat the result as plain data.
 */
@Singleton
class NativeToolProvisioner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val native: DftbNative,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Absolute path of the directory the installer extracts `lib*.so` into for this app/ABI. */
    fun nativeLibDir(): String = context.applicationInfo.nativeLibraryDir

    /**
     * Look for [toolId] shipped inside the APK. Only inspects the filesystem (no shell, so it is
     * safe in every operating mode, including Standard): it checks for `nativeLibDir/lib<id>.so` and
     * confirms the app's own uid may execute it. Anything else is reported as [ToolSource.ABSENT];
     * use [discoverViaShell] to look on the shell's `PATH` or in the companion module.
     */
    suspend fun locate(toolId: String): ToolAvailability = withContext(ioDispatcher) {
        runCatching {
            locateInDir(nativeLibDir(), toolId) { path -> native.isExecutable(path) || File(path).canExecute() }
        }.getOrElse { error ->
            DftbLog.w(TAG, "locate($toolId) failed", error)
            ToolAvailability.absent(toolId)
        }
    }

    /** [locate] every [BundledTool] in the registry. */
    suspend fun locateAll(): List<ToolAvailability> = BundledTool.entries.map { locate(it.id) }

    /**
     * Look for [toolId] in the environment reachable through [executor]: the systemless companion
     * module's own `system/bin`, then the shell's `PATH` via `command -v`. Used for the
     * Root-with-module tier, where the module may ship a richer/newer copy than the APK, and to
     * discover a tool the device already provides so DFTB need not stage its own.
     *
     * Returns [ToolSource.ABSENT] when the executor cannot run (e.g. Standard mode) or nothing
     * resolves.
     */
    suspend fun discoverViaShell(executor: CommandExecutor, toolId: String): ToolAvailability = withContext(ioDispatcher) {
        val safeId = sanitizeToolId(toolId)
        if (safeId == null) {
            DftbLog.w(TAG, "discoverViaShell: refusing unsafe tool id '$toolId'")
            return@withContext ToolAvailability.absent(toolId)
        }
        runCatching {
            if (!executor.isAvailable()) {
                DftbLog.d(TAG, "discoverViaShell($toolId): ${executor.tier} executor unavailable")
                return@runCatching ToolAvailability.absent(toolId)
            }
            // Prefer the companion module's own tree so a module-provided tool is attributed to it
            // even though it is bind-mounted over /system/bin and would otherwise look like a system tool.
            val moduleBin = "${CompanionModuleProbe.MODULE_DIR}/system/bin/$safeId"
            if (executor.execute(shell("test -x ${singleQuote(moduleBin)}")).isSuccess) {
                DftbLog.d(TAG, "$toolId provided by companion module at $moduleBin")
                return@runCatching ToolAvailability(toolId, available = true, path = moduleBin, source = ToolSource.COMPANION_MODULE, version = null)
            }
            val resolved = executor.execute(shell("command -v ${singleQuote(safeId)}"))
            val path = resolved.stdout.firstOrNull { it.isNotBlank() }?.trim()
            if (resolved.isSuccess && !path.isNullOrEmpty()) {
                DftbLog.d(TAG, "$toolId resolved on PATH at $path")
                ToolAvailability(toolId, available = true, path = path, source = ToolSource.SYSTEM_PATH, version = null)
            } else {
                ToolAvailability.absent(toolId)
            }
        }.getOrElse { error ->
            DftbLog.w(TAG, "discoverViaShell($toolId) failed", error)
            ToolAvailability.absent(toolId)
        }
    }

    /**
     * Make [toolId] runnable by the uid [executor] runs as, by copying it into [tmpDir] as the bare
     * tool name and marking it `0755`.
     *
     * The source is DFTB's own shipped copy (`nativeLibraryDir/lib<id>.so`) when present, otherwise
     * a copy already discoverable through the shell. The copy and the `chmod` are performed *through
     * the executor* because [tmpDir] (`/data/local/tmp` by convention) is owned by the shell/adb
     * uid, not the app: a normal app process cannot write there, so [DftbNative.setMode] cannot be
     * used on the destination either. Staging is only necessary when a foreign uid must exec the
     * tool — the app's own uid can already run the copy in `nativeLibraryDir` directly, so Standard
     * mode never needs this.
     *
     * Returns the staged [ToolAvailability] ([ToolSource.STAGED_TMP]) on success. If no source copy
     * exists, or the executor is unavailable, it degrades to whatever [locate] already found (so a
     * caller still learns about the in-APK copy) or [ToolSource.ABSENT].
     */
    suspend fun stageForShell(
        executor: CommandExecutor,
        toolId: String,
        tmpDir: String = DEFAULT_STAGING_DIR,
    ): ToolAvailability = withContext(ioDispatcher) {
        val safeId = sanitizeToolId(toolId)
        if (safeId == null) {
            DftbLog.w(TAG, "stageForShell: refusing unsafe tool id '$toolId'")
            return@withContext ToolAvailability.absent(toolId)
        }
        runCatching {
            val shipped = locate(toolId)
            if (!executor.isAvailable()) {
                DftbLog.d(TAG, "stageForShell($toolId): ${executor.tier} executor unavailable; returning shipped copy")
                return@runCatching shipped
            }
            val source = shipped.takeIf { it.available }?.path
                ?: discoverViaShell(executor, toolId).takeIf { it.available }?.path
            if (source == null) {
                DftbLog.w(TAG, "stageForShell($toolId): no source copy to stage")
                return@runCatching ToolAvailability.absent(toolId)
            }
            val staged = "$tmpDir/$safeId"
            val script = buildString {
                append("mkdir -p ").append(singleQuote(tmpDir))
                append(" && cp -f ").append(singleQuote(source)).append(' ').append(singleQuote(staged))
                append(" && chmod 0755 ").append(singleQuote(staged))
            }
            val copy = executor.execute(shell(script))
            if (!copy.isSuccess) {
                DftbLog.w(TAG, "stageForShell($toolId) copy failed: exit ${copy.exitCode} ${copy.stderrText}")
                return@runCatching shipped
            }
            if (!executor.execute(shell("test -x ${singleQuote(staged)}")).isSuccess) {
                DftbLog.w(TAG, "stageForShell($toolId): staged copy at $staged is not executable")
                return@runCatching shipped
            }
            DftbLog.i(TAG, "Staged $toolId from $source to $staged")
            ToolAvailability(toolId, available = true, path = staged, source = ToolSource.STAGED_TMP, version = null)
        }.getOrElse { error ->
            DftbLog.w(TAG, "stageForShell($toolId) failed", error)
            ToolAvailability.absent(toolId)
        }
    }

    /**
     * Best-effort: run the located [tool] with a version flag and attach the first non-blank output
     * line as [ToolAvailability.version]. Returns [tool] unchanged if it is not available, the
     * executor cannot run it, or no flag produced output (not every tool reports a version the same
     * way, and some report none). Never throws.
     */
    suspend fun probeVersion(executor: CommandExecutor, tool: ToolAvailability): ToolAvailability = withContext(ioDispatcher) {
        val path = tool.path
        if (!tool.available || path == null) return@withContext tool
        runCatching {
            if (!executor.isAvailable()) return@runCatching tool
            for (flag in VERSION_FLAGS) {
                val result = executor.execute(
                    Command(
                        argv = listOf(path, flag),
                        redirectErrorStream = true,
                        timeoutMillis = PROBE_TIMEOUT_MILLIS,
                    ),
                )
                val line = result.stdout.firstOrNull { it.isNotBlank() }?.trim()
                if (result.isSuccess && !line.isNullOrEmpty()) {
                    DftbLog.d(TAG, "${tool.toolId} version: $line")
                    return@runCatching tool.copy(version = line)
                }
            }
            DftbLog.d(TAG, "${tool.toolId}: no version flag produced output")
            tool
        }.getOrElse { error ->
            DftbLog.w(TAG, "probeVersion(${tool.toolId}) failed", error)
            tool
        }
    }

    companion object {
        private const val TAG = "Provision"

        /**
         * Pure decision logic for [locate], in the companion so it is unit-testable on the JVM
         * without an Android [Context] or the `dftb_native` JNI library: given the directory to
         * search and a way to decide whether a path is executable, it returns the same
         * [ToolAvailability] [locate] would. A bundled tool is available only when `dirPath/lib<id>.so`
         * exists AND [isExecutable] accepts it — exactly the condition that clears a `TOOL_MISSING`
         * for rclone/busybox/zstd once their lib is staged in `nativeLibraryDir`.
         */
        internal fun locateInDir(dirPath: String, toolId: String, isExecutable: (String) -> Boolean): ToolAvailability {
            val libName = BundledTool.fromId(toolId)?.libFileName ?: "lib$toolId.so"
            val path = "$dirPath/$libName"
            return when {
                !File(path).exists() -> {
                    DftbLog.d(TAG, "$toolId: $libName not present in nativeLibraryDir")
                    ToolAvailability.absent(toolId)
                }
                isExecutable(path) -> {
                    DftbLog.d(TAG, "$toolId located at $path (nativeLibraryDir)")
                    ToolAvailability(toolId, available = true, path = path, source = ToolSource.NATIVE_LIB_DIR, version = null)
                }
                else -> {
                    DftbLog.w(TAG, "$toolId present at $path but not executable by the app uid")
                    ToolAvailability.absent(toolId)
                }
            }
        }

        /** Conventional world-traversable staging directory owned by the adb/shell uid. */
        const val DEFAULT_STAGING_DIR: String = "/data/local/tmp/dftb_bin"

        /** Cap on a version probe so a misbehaving binary cannot hang a provisioning sweep. */
        const val PROBE_TIMEOUT_MILLIS: Long = 10_000L

        /** Version flags tried in order; covers GNU-style tools, `-V`-only tools, and dftbtool's subcommand. */
        private val VERSION_FLAGS = listOf("--version", "-V", "version")

        private val SAFE_ID = Regex("^[A-Za-z0-9._-]{1,64}$")

        /**
         * Returns [toolId] if it is a safe bare name (letters, digits, `. _ -`) usable both as a
         * file name and, after single-quoting, inside a shell command; null otherwise. Guards the
         * shell paths against a caller passing something exotic.
         */
        fun sanitizeToolId(toolId: String): String? = toolId.takeIf { SAFE_ID.matches(it) }

        /** Single-quote [value] for safe inclusion in a `sh -c` script. */
        fun singleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        /** A [Command] that runs [script] through `sh -c`, so shell builtins like `command -v` work on every tier. */
        private fun shell(script: String): Command = Command(argv = listOf("sh", "-c", script), redirectErrorStream = true)
    }
}
