package ca.devdcs.backup.core.storage.rclone

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandResult
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs an rclone binary the app uid cannot execute itself (e.g. one installed by the companion
 * Magisk/KernelSU module under `/data/adb`) through the ROOT [CommandExecutor].
 *
 * The executor has no streaming stdin, folds stderr into stdout, and runs every command on ONE
 * shared root shell (a long command blocks all others), so this runner:
 * - passes secrets through a 0600 env file in app storage that the root shell sources and deletes
 * (never argv, never the `env K=V` prefix the root script builder would otherwise use);
 * - sends rclone's stderr to a pre-created log file (app label preserved) so stdout stays clean;
 * - streams `rcat`/`cat` through a FIFO in the app cache, with rclone launched DETACHED
 * (`setsid sh -c … &`) so the root exec returns at once and the shared shell stays free; the
 * helper's PID goes to a pid file (for kill) and its exit code to a status file (polled);
 * - restores app ownership and SELinux context of the files root touched after every run — inside
 * the helper on normal exit, and as a separate root command whenever a run is killed, timed out
 * or cancelled — so the app can always read OAuth tokens rclone rotated into rclone.conf.
 * Stream completion ([RcloneProcessRunner.openUpload]'s onFinished, which releases the config
 * lease and triggers token write-back) runs only after that repair.
 */
internal class RootRcloneRunner(
    private val binary: String,
    private val executor: CommandExecutor,
    private val workDir: File,
    private val fifoDir: File,
    /** Directories root may write into that must be handed back to the app afterwards. */
    private val repairDirs: List<File>,
    private val appUid: Int,
    private val baseEnv: Map<String, String>,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) : RcloneProcessRunner {

    override val description: String get() = "root shell: $binary"

    override suspend fun run(
        args: List<String>,
        env: Map<String, String>,
        stdin: ByteArray?,
        timeoutMillis: Long,
    ): RcloneResult = withContext(ioDispatcher) {
        val files = RunFiles.create(workDir, baseEnv + env, streaming = false)
        var completed = false
        try {
            val script = RootRcloneScript.build(binary, args, files.envFile.absolutePath, files.logFile.absolutePath, null, repairPaths(), appUid)
            val result = executor.execute(
                Command(argv = listOf("sh", "-c", script), stdin = stdin, timeoutMillis = timeoutMillis),
            )
            if (!result.launched) {
                throw StorageException(StorageException.Reason.PERMISSION, "Root shell unavailable for rclone: ${result.stderrText.take(200)}")
            }
            completed = result.exitCode != CommandResult.EXIT_TIMEOUT
            RcloneResult(
                exitCode = result.exitCode,
                stdout = result.stdoutText,
                logLines = files.readLog(),
                timedOut = !completed,
            )
        } finally {
            // A killed (timeout/cancel) script never reached its own repair step.
            if (!completed) withContext(NonCancellable) { repair() }
            files.delete()
        }
    }

    override suspend fun openUpload(args: List<String>, env: Map<String, String>, onFinished: () -> Unit): OutputStream =
        withContext(ioDispatcher) {
            val running = launchDetached(args, env, stdinFromFifo = true)
            val out = openFifoEnd(running, releaser = { unblockOpen(running.fifo, OsConstants.O_RDONLY) }) { FileOutputStream(running.fifo) }
            RcloneUploadStream(out.buffered(BUFFER), running, "rcat") { running.finish(onFinished) }
        }

    override suspend fun openDownload(args: List<String>, env: Map<String, String>, onFinished: () -> Unit): InputStream =
        withContext(ioDispatcher) {
            val running = launchDetached(args, env, stdinFromFifo = false)
            val input = openFifoEnd(running, releaser = { unblockOpen(running.fifo, OsConstants.O_WRONLY) }) { FileInputStream(running.fifo) }
            RcloneDownloadStream(input.buffered(BUFFER), running, "cat") { running.finish(onFinished) }
        }

    private fun repairPaths(): List<String> = repairDirs.map { it.absolutePath }

    /** Hand root-touched files back to the app (separate root command; used when the helper was killed). */
    private suspend fun repair() {
        val result = executor.execute(
            Command(argv = listOf("sh", "-c", RootRcloneScript.repair(repairPaths(), appUid)), timeoutMillis = CONTROL_TIMEOUT_MILLIS),
        )
        if (!result.isSuccess) DftbLog.w(TAG, "rclone file repair failed: exit ${result.exitCode}")
    }

    private suspend fun launchDetached(args: List<String>, env: Map<String, String>, stdinFromFifo: Boolean): RootRunning {
        val files = RunFiles.create(workDir, baseEnv + env, streaming = true)
        val fifo = try {
            createFifo()
        } catch (e: StorageException) {
            files.delete()
            throw e
        }
        val redirect = if (stdinFromFifo) RootRcloneScript.Redirect.StdinFrom(fifo.absolutePath) else RootRcloneScript.Redirect.StdoutTo(fifo.absolutePath)
        val inner = RootRcloneScript.build(binary, args, files.envFile.absolutePath, files.logFile.absolutePath, redirect, repairPaths(), appUid, files.statusFile!!.absolutePath)
        val launcher = RootRcloneScript.detached(inner, files.pidFile!!.absolutePath)
        val result = executor.execute(Command(argv = listOf("sh", "-c", launcher), timeoutMillis = CONTROL_TIMEOUT_MILLIS))
        if (!result.isSuccess) {
            files.delete()
            fifo.delete()
            throw StorageException(StorageException.Reason.PERMISSION, "Root shell could not start rclone: ${result.stdoutText.ifBlank { "exit ${result.exitCode}" }.take(200)}")
        }
        return RootRunning(files, fifo.absolutePath)
    }

    private fun createFifo(): File {
        if (!fifoDir.exists() && !fifoDir.mkdirs()) {
            throw StorageException(StorageException.Reason.PERMISSION, "Cannot create FIFO directory ${fifoDir.absolutePath}")
        }
        val fifo = File(fifoDir, "rclone-${UUID.randomUUID()}.fifo")
        try {
            Os.mkfifo(fifo.absolutePath, FIFO_MODE)
        } catch (e: ErrnoException) {
            throw StorageException(StorageException.Reason.UNSUPPORTED, "Cannot create a FIFO for root rclone streaming: ${e.message}", e)
        }
        return fifo
    }

    /**
     * Open our FIFO end. The open blocks (uninterruptibly) until rclone opens the other end; if
     * rclone exits, never starts, or the caller is cancelled first, a watcher opens the opposite end
     * non-blockingly via [releaser] to free the blocked open, and the mapped failure is thrown.
     */
    private suspend fun <T : Closeable> openFifoEnd(running: RootRunning, releaser: () -> Unit, opener: () -> T): T {
        val opened = AtomicBoolean(false)
        val caller = currentCoroutineContext()[Job]
        val watcher = scope.launch(ioDispatcher) {
            val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MILLIS
            while (!opened.get()) {
                val exited = running.exitCodeOrNull() != null
                if (exited || caller?.isCancelled == true || System.currentTimeMillis() > deadline) {
                    runCatching { releaser() }
                    return@launch
                }
                delay(POLL_MILLIS)
            }
        }
        val stream = try {
            opener()
        } catch (e: IOException) {
            watcher.cancel()
            running.kill()
            running.finish {}
            throw StorageException(StorageException.Reason.PROTOCOL, "Cannot open rclone FIFO: ${e.message}", e)
        }
        opened.set(true)
        watcher.cancelAndJoin()
        // Exit 0 right after the open is legitimate for `cat` of a small file: its data is in the pipe.
        val code = running.exitCodeOrNull()
        if (caller?.isCancelled == true || (code != null && code != 0) || !running.isAlive()) {
            runCatching { stream.close() }
            if (caller?.isCancelled == true) {
                running.kill()
                running.finish {}
                throw CancellationException("rclone stream open cancelled")
            }
            val finalCode = code ?: running.awaitExit(STATUS_GRACE_MILLIS)
            val logs = running.logLines()
            running.kill()
            running.finish {}
            throw when (finalCode) {
                null -> StorageException(StorageException.Reason.TIMEOUT, "rclone did not connect to the transfer pipe in time")
                else -> RcloneErrorMapper.toException("stream", finalCode, logs)
            }
        }
        return stream
    }

    /** Open and close [path] with O_NONBLOCK so a blocked opposite-end open returns. */
    private fun unblockOpen(path: String, accessMode: Int) {
        try {
            Os.close(Os.open(path, accessMode or OsConstants.O_NONBLOCK, 0))
        } catch (e: ErrnoException) {
            throw IOException("unblock $path: ${e.message}", e)
        }
    }

    /** A detached root rclone: exit code via the status file, kill via the pid file. */
    private inner class RootRunning(private val files: RunFiles, val fifo: String) : RunningRclone {
        private val killed = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)
        @Volatile private var killJob: Job? = null

        /** The helper's exit code once it wrote its status, else null. */
        fun exitCodeOrNull(): Int? = runCatching {
            val text = files.statusFile!!.readText()
            if (text.endsWith("\n")) text.trim().toIntOrNull() else null
        }.getOrNull()

        /** False once the helper has been killed (it will never write a status). */
        fun isAlive(): Boolean = !killed.get()

        override fun awaitExit(timeoutMillis: Long): Int? {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (true) {
                exitCodeOrNull()?.let { return it }
                if (killed.get()) return KILLED_EXIT
                if (System.currentTimeMillis() >= deadline) return null
                try {
                    Thread.sleep(POLL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }

        override fun logLines(): List<String> = files.readLog()

        /** Kill the helper's process group, then repair ownership (the helper's own repair never ran). */
        override fun kill() {
            if (exitCodeOrNull() != null || !killed.compareAndSet(false, true)) return
            val pidFile = files.pidFile!!.absolutePath
            killJob = scope.launch(ioDispatcher + NonCancellable) {
                val script = RootRcloneScript.kill(pidFile) + "; " + RootRcloneScript.repair(repairPaths(), appUid)
                val result = executor.execute(Command(argv = listOf("sh", "-c", script), timeoutMillis = CONTROL_TIMEOUT_MILLIS))
                if (!result.isSuccess) DftbLog.w(TAG, "Killing root rclone failed: exit ${result.exitCode}")
            }
        }

        /** Delete run files and FIFO, then (after any pending kill+repair) run [onFinished]. Once only. */
        fun finish(onFinished: () -> Unit) {
            if (!finished.compareAndSet(false, true)) return
            scope.launch(ioDispatcher + NonCancellable) {
                killJob?.join()
                files.delete()
                if (!File(fifo).delete()) DftbLog.d(TAG, "FIFO already gone")
                onFinished()
            }
        }
    }

    /** Per-invocation files, all created by the app so they carry the app's SELinux label. */
    private class RunFiles(val envFile: File, val logFile: File, val statusFile: File?, val pidFile: File?) {
        fun readLog(): List<String> = runCatching { logFile.readLines() }.getOrDefault(emptyList())

        fun delete() {
            listOfNotNull(envFile, logFile, statusFile, pidFile).forEach { it.delete() }
        }

        companion object {
            fun create(dir: File, env: Map<String, String>, streaming: Boolean): RunFiles {
                val id = UUID.randomUUID().toString()
                val files = RunFiles(
                    envFile = File(dir, "env-$id"),
                    logFile = File(dir, "log-$id"),
                    statusFile = if (streaming) File(dir, "status-$id") else null,
                    pidFile = if (streaming) File(dir, "pid-$id") else null,
                )
                try {
                    RcloneFiles.ensurePrivateDir(dir)
                    RcloneFiles.writePrivate(files.envFile, RootRcloneScript.envFileContent(env))
                    RcloneFiles.writePrivate(files.logFile, "")
                    files.statusFile?.let { RcloneFiles.writePrivate(it, "") }
                    files.pidFile?.let { RcloneFiles.writePrivate(it, "") }
                } catch (e: IOException) {
                    files.delete()
                    throw StorageException(StorageException.Reason.PERMISSION, "Cannot write rclone run files: ${e.message}", e)
                }
                return files
            }
        }
    }

    private companion object {
        const val TAG = "RcloneRoot"
        const val BUFFER = 64 * 1024
        const val FIFO_MODE = 384 // 0600
        const val OPEN_TIMEOUT_MILLIS = 60_000L
        const val CONTROL_TIMEOUT_MILLIS = 30_000L
        const val STATUS_GRACE_MILLIS = 5_000L
        const val POLL_MILLIS = 250L
        /** Reported exit code of a helper we killed (128 + SIGKILL). */
        const val KILLED_EXIT = 137
    }
}

/**
 * Pure builder of the `sh -c` scripts the root runner executes. Separate so quoting and ordering
 * (source env, run, repair ownership, report exit code) are unit-tested.
 */
internal object RootRcloneScript {

    sealed interface Redirect {
        data class StdinFrom(val path: String) : Redirect
        data class StdoutTo(val path: String) : Redirect
    }

    private val ENV_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /**
     * The rclone invocation. Without [statusFile] it ends with `exit $rc` (buffered run); with it,
     * the exit code is written there instead (detached run).
     */
    fun build(
        binary: String,
        args: List<String>,
        envFile: String,
        logFile: String,
        redirect: Redirect?,
        repairDirs: List<String>,
        appUid: Int,
        statusFile: String? = null,
    ): String = buildString {
        append(". ").append(q(envFile)).append(" && rm -f ").append(q(envFile)).append(" && ")
        append(q(binary))
        args.forEach { append(' ').append(q(it)) }
        append(" 2>>").append(q(logFile))
        when (redirect) {
            is Redirect.StdinFrom -> append(" <").append(q(redirect.path))
            is Redirect.StdoutTo -> append(" >").append(q(redirect.path))
            null -> Unit
        }
        append("; rc=\$?; ").append(repair(repairDirs, appUid))
        if (statusFile != null) {
            append("; printf '%s\\n' \"\$rc\" > ").append(q(statusFile))
        } else {
            append("; exit \$rc")
        }
    }

    /** chown + chcon (to each dir's own context) so the app can read/delete what root wrote. */
    fun repair(repairDirs: List<String>, appUid: Int): String =
        repairDirs.joinToString("; ") { dir ->
            val d = q(dir)
            "ctx=\$(stat -c %C $d 2>/dev/null); chown -R $appUid:$appUid $d 2>/dev/null; " +
                "[ -n \"\$ctx\" ] && chcon -R \"\$ctx\" $d 2>/dev/null; true"
        }.ifEmpty { "true" }

    /** Launch [inner] in its own session, detached from the shared shell; its PID goes to [pidFile]. */
    fun detached(inner: String, pidFile: String): String =
        "setsid sh -c ${q(inner)} </dev/null >/dev/null 2>&1 & echo \$! > ${q(pidFile)}"

    /** Kill the detached helper's whole process group (sh + rclone) by the PID in [pidFile]. */
    fun kill(pidFile: String): String =
        "p=\$(cat ${q(pidFile)} 2>/dev/null); [ -n \"\$p\" ] && { kill -9 -- -\"\$p\" 2>/dev/null; kill -9 \"\$p\" 2>/dev/null; }; true"

    /** `export K='v'` lines; keys validated so the file can only ever set variables. */
    fun envFileContent(env: Map<String, String>): String = buildString {
        env.forEach { (k, v) ->
            require(ENV_KEY.matches(k)) { "Invalid environment variable name '$k'" }
            append("export ").append(k).append('=').append(q(v)).append('\n')
        }
    }

    fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
