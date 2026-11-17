package ca.devdcs.backup.core.restore.apps

import android.content.Context
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandResult
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.source.PrivilegedStreamScripts
import ca.devdcs.backup.core.storage.nfs.RootFifoTransfer
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Exit code and combined stdout+stderr of a privileged consumer process. */
data class SinkStatus(val exitCode: Int, val output: List<String>) {
    val text: String get() = output.joinToString("\n")
}

/** Thrown when a privileged consumer cannot be started at all. */
class SinkUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Where restore bytes go when only a privileged process may write the target: the caller writes
 * [output], then [complete] reports the consumer's status. [abort] (idempotent, never throws)
 * kills the consumer and removes temporary files.
 */
interface PrivilegedSink {
    val output: OutputStream
    suspend fun complete(): SinkStatus
    fun abort()
}

/**
 * Opens [PrivilegedSink]s for the current tier. Exists because the two privileged tiers need
 * different transports for app → privileged-process bytes:
 * - ROOT: a data FIFO + status FIFO in the app cache dir and a detached `setsid` helper running
 * the consumer with stdin from the FIFO (the same protocol as
 * [ca.devdcs.backup.core.pipeline.source.RootByteStreams], reversed), so multi-gigabyte
 * extractions never occupy the one shared libsu shell;
 * - SHIZUKU: the adb uid can neither open app-private FIFOs nor receive more than 256 KiB of
 * stdin over binder, so the bytes are staged in DFTB's app-specific external dir
 * (`Android/data/ca.devdcs.backup/files/dftb-restore`, readable by the shell through FUSE,
 * not by other apps) and the consumer reads that file; the stage is deleted afterwards.
 */
@Singleton
class PrivilegedSinkFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    /**
     * [stdinScript] consumes stdin (ROOT); [fileScript] consumes the staged file at the given path
     * (SHIZUKU). [label] names the transfer in errors. [expectedBytes] is checked against free
     * space when staging.
     */
    suspend fun open(
        executor: CommandExecutor,
        label: String,
        stdinScript: String,
        fileScript: (String) -> String,
        expectedBytes: Long?,
        completionTimeoutMillis: Long = DEFAULT_COMPLETION_TIMEOUT_MILLIS,
    ): PrivilegedSink = withContext(io) {
        when (executor.tier) {
            ElevationTier.ROOT -> openRoot(executor, label, stdinScript, completionTimeoutMillis)
            ElevationTier.SHIZUKU -> openStaged(executor, label, fileScript, expectedBytes, completionTimeoutMillis)
            ElevationTier.NONE -> throw SinkUnavailableException("$label: no privileged shell in the current mode")
        }
    }

    /** Staging dir for the Shizuku tier, or null when shared storage is not mounted. */
    fun stagingDir(): File? {
        val base = context.getExternalFilesDir(null) ?: return null
        if (Environment.getExternalStorageState(base) != Environment.MEDIA_MOUNTED) return null
        val dir = File(base, STAGING_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return dir
    }

    /** Removes stale stage files a killed process left behind. */
    fun clearStaging() {
        stagingDir()?.listFiles()?.forEach { it.delete() }
    }

    // ---- ROOT ----------------------------------------------------------------------------

    private suspend fun openRoot(executor: CommandExecutor, label: String, script: String, completionTimeout: Long): PrivilegedSink {
        val id = UUID.randomUUID().toString()
        val data = File(context.cacheDir, "dftb-rst-$id.data").absolutePath
        val status = File(context.cacheDir, "dftb-rst-$id.status").absolutePath
        try {
            Os.mkfifo(data, FIFO_MODE)
            Os.mkfifo(status, FIFO_MODE)
        } catch (e: Exception) {
            File(data).delete()
            File(status).delete()
            throw SinkUnavailableException("$label: cannot create pipe: ${e.message}", e)
        }
        val helper = sinkHelper(script, data, status)
        val launch = try {
            executor.execute(Command(listOf("sh", "-c", PrivilegedStreamScripts.detachedLaunch(helper)), timeoutMillis = LAUNCH_TIMEOUT_MILLIS))
        } catch (e: CancellationException) {
            File(data).delete()
            File(status).delete()
            throw e
        }
        val pid = if (launch.isSuccess) PrivilegedStreamScripts.parsePid(launch.stdout) else null
        if (pid == null) {
            File(data).delete()
            File(status).delete()
            throw SinkUnavailableException("$label: could not start the root helper (${launch.stderrText.ifBlank { "exit ${launch.exitCode}" }})")
        }
        val sink = RootSink(executor, label, data, status, pid, completionTimeout)
        val (stream, timedOut) = try {
            runInterruptible {
                RootFifoTransfer.openWithWatchdog(OPEN_TIMEOUT_MILLIS, unblock = { unblock(data, OsConstants.O_RDONLY) }) { FileOutputStream(data) }
            }
        } catch (e: Throwable) {
            sink.abort()
            throw if (e is CancellationException) e else SinkUnavailableException("$label: cannot open the data pipe", e)
        }
        if (timedOut) {
            runCatching { stream.close() }
            sink.abort()
            throw SinkUnavailableException("$label: the root helper did not start in time")
        }
        sink.attach(stream)
        return sink
    }

    private inner class RootSink(
        private val executor: CommandExecutor,
        private val label: String,
        private val data: String,
        private val status: String,
        private val pid: Int,
        private val completionTimeout: Long,
    ) : PrivilegedSink {
        private lateinit var stream: FileOutputStream
        private val finished = AtomicBoolean(false)

        fun attach(s: FileOutputStream) {
            stream = s
        }

        override val output: OutputStream get() = stream

        override suspend fun complete(): SinkStatus = withContext(io) {
            check(finished.compareAndSet(false, true)) { "sink already finished" }
            var done = false
            try {
                runCatching { stream.close() } // EOF for the consumer
                val text = awaitStatus(status, completionTimeout)
                val (code, out) = PrivilegedStreamScripts.parseStatus(text)
                // -1 = no (parseable) status: the helper may still be running, so it is killed.
                done = code != -1
                SinkStatus(code, out.lines().filter { it.isNotBlank() })
            } finally {
                if (done) {
                    File(data).delete()
                    File(status).delete()
                } else {
                    kill()
                }
            }
        }

        override fun abort() {
            if (!finished.compareAndSet(false, true)) return
            if (::stream.isInitialized) runCatching { stream.close() }
            kill()
        }

        private fun kill() {
            appScope.launch(io + NonCancellable) {
                val result = executor.execute(Command(listOf("sh", "-c", PrivilegedStreamScripts.killScript(pid)), timeoutMillis = KILL_TIMEOUT_MILLIS))
                if (!result.isSuccess) DftbLog.w(TAG, "$label: kill of sink helper $pid failed: ${result.stderrText}")
                drain(status)
                File(data).delete()
                File(status).delete()
            }
        }
    }

    // ---- SHIZUKU -------------------------------------------------------------------------

    private fun openStaged(executor: CommandExecutor, label: String, fileScript: (String) -> String, expectedBytes: Long?, completionTimeout: Long): PrivilegedSink {
        val dir = stagingDir() ?: throw SinkUnavailableException("$label: shared storage is not available for staging")
        if (expectedBytes != null && dir.usableSpace < expectedBytes + STAGING_MARGIN_BYTES) {
            throw SinkUnavailableException("$label: not enough free space to stage $expectedBytes bytes")
        }
        val file = File(dir, "stage-${UUID.randomUUID()}")
        val stream = try {
            FileOutputStream(file)
        } catch (e: IOException) {
            throw SinkUnavailableException("$label: cannot create stage file", e)
        }
        return object : PrivilegedSink {
            private val finished = AtomicBoolean(false)
            override val output: OutputStream = stream

            override suspend fun complete(): SinkStatus = withContext(io) {
                check(finished.compareAndSet(false, true)) { "sink already finished" }
                try {
                    stream.fd.sync()
                    stream.close()
                    val result = executor.execute(Command(listOf("sh", "-c", fileScript(file.absolutePath)), redirectErrorStream = true, timeoutMillis = completionTimeout))
                    SinkStatus(if (result.launched) result.exitCode else CommandResult.EXIT_NOT_LAUNCHED, result.stdout + result.stderr)
                } finally {
                    file.delete()
                }
            }

            override fun abort() {
                if (!finished.compareAndSet(false, true)) return
                runCatching { stream.close() }
                file.delete()
            }
        }
    }

    companion object {
        private const val TAG = "PrivilegedSink"
        private const val FIFO_MODE = 384 // 0600
        private const val STAGING_DIR = "dftb-restore"
        private const val STAGING_MARGIN_BYTES = 64L * 1024 * 1024
        private const val LAUNCH_TIMEOUT_MILLIS = 30_000L
        private const val OPEN_TIMEOUT_MILLIS = 30_000L
        private const val KILL_TIMEOUT_MILLIS = 15_000L
        private const val STATUS_POLL_MILLIS = 100L
        const val DEFAULT_COMPLETION_TIMEOUT_MILLIS = 30L * 60 * 1000

        /**
         * Detached helper: open the data FIFO for reading (blocks until the app opens the write
         * end), run [script] with stdin from it and stdout+stderr captured, then report
         * `rc\noutput` through the status FIFO.
         */
        internal fun sinkHelper(script: String, dataFifo: String, statusFifo: String): String {
            val q = AppRestoreScripts::q
            return "exec 3<${q(dataFifo)} || exit 1; " +
                "__out=\$( { $script ; } <&3 2>&1 3<&- ); __rc=\$?; exec 3<&-; " +
                "printf '%s\\n%s\\n' \"\$__rc\" \"\$(printf '%s' \"\$__out\" | tail -c ${PrivilegedStreamScripts.MAX_STDERR_CHARS})\" > ${q(statusFifo)}"
        }

        /**
         * Reads the helper's status without a blocking FIFO open (which no coroutine cancellation
         * could interrupt): a non-blocking read end is held open and polled until the helper has
         * written and closed, the [timeoutMillis] passes, or the caller is cancelled.
         */
        private suspend fun awaitStatus(path: String, timeoutMillis: Long): String {
            val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
            try {
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                val deadline = System.nanoTime() + timeoutMillis * 1_000_000
                var sawData = false
                while (true) {
                    val n = try {
                        Os.read(fd, buffer, 0, buffer.size)
                    } catch (e: android.system.ErrnoException) {
                        if (e.errno == OsConstants.EAGAIN) -1 else throw IOException("status pipe: ${e.message}", e)
                    }
                    when {
                        n > 0 -> {
                            out.write(buffer, 0, n)
                            sawData = true
                            continue
                        }
                        n == 0 && sawData -> return String(out.toByteArray(), Charsets.UTF_8)
                    }
                    if (System.nanoTime() > deadline) return if (sawData) String(out.toByteArray(), Charsets.UTF_8) else "-1\nroot helper did not report a status"
                    kotlinx.coroutines.delay(STATUS_POLL_MILLIS)
                }
            } finally {
                runCatching { Os.close(fd) }
            }
        }

        private fun unblock(path: String, accessMode: Int) {
            try {
                Os.close(Os.open(path, accessMode or OsConstants.O_NONBLOCK, 0))
            } catch (e: Exception) {
                throw IOException("unblock $path: ${e.message}", e)
            }
        }

        private fun drain(path: String) {
            runCatching {
                val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
                try {
                    runCatching { Os.read(fd, ByteArray(PrivilegedStreamScripts.MAX_STDERR_CHARS + 64), 0, PrivilegedStreamScripts.MAX_STDERR_CHARS + 64) }
                } finally {
                    Os.close(fd)
                }
            }
        }
    }
}
