package ca.devdcs.backup.core.storage.rclone

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.StorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Runs rclone as a child process of the app (same uid) with [ProcessBuilder].
 *
 * The preferred path: the binary is APK-bundled (`nativeLibraryDir/librclone.so`) or a system copy
 * the app uid may execute, so stdin/stdout are real pipes and uploads/downloads stream with no temp
 * files. Secrets travel in the child's environment, which only the app uid and root can read.
 */
internal class DirectRcloneRunner(
    private val binary: String,
    private val workDir: File,
    private val baseEnv: Map<String, String>,
    private val ioDispatcher: CoroutineDispatcher,
) : RcloneProcessRunner {

    override val description: String get() = "app process: $binary"

    override suspend fun run(
        args: List<String>,
        env: Map<String, String>,
        stdin: ByteArray?,
        timeoutMillis: Long,
    ): RcloneResult = withContext(ioDispatcher) {
        val started = start(args, env)
        val process = started.process
        val stdoutBuffer = ByteArrayOutputStream()
        val stdoutPump = pump("rclone-stdout", process.inputStream, stdoutBuffer)
        try {
            process.outputStream.use { out -> if (stdin != null) out.write(stdin) }
        } catch (e: IOException) {
            // rclone may exit before reading stdin; its exit status explains why.
            DftbLog.d(TAG, "stdin write ended early: ${e.message}")
        }
        try {
            val exited = runInterruptible { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
            if (!exited) process.destroyForcibly()
            stdoutPump.join(PUMP_JOIN_MILLIS)
            started.stderr.join(PUMP_JOIN_MILLIS)
            RcloneResult(
                exitCode = if (exited) process.exitValue() else -1,
                stdout = stdoutBuffer.toString(Charsets.UTF_8.name()),
                logLines = started.stderr.snapshot(),
                timedOut = !exited,
            )
        } catch (e: CancellationException) {
            process.destroyForcibly()
            throw e
        }
    }

    override suspend fun openUpload(args: List<String>, env: Map<String, String>, onFinished: () -> Unit): OutputStream =
        withContext(ioDispatcher) {
            val started = start(args, env)
            // rcat prints nothing on success; drain stdout anyway so the child never blocks on it.
            pump("rclone-rcat-stdout", started.process.inputStream, NullSink)
            RcloneUploadStream(BufferedOutputStream(started.process.outputStream, BUFFER), started, "rcat", onFinished)
        }

    override suspend fun openDownload(args: List<String>, env: Map<String, String>, onFinished: () -> Unit): InputStream =
        withContext(ioDispatcher) {
            val started = start(args, env)
            runCatching { started.process.outputStream.close() }
            RcloneDownloadStream(started.process.inputStream.buffered(BUFFER), started, "cat", onFinished)
        }

    private fun start(args: List<String>, env: Map<String, String>): StartedProcess {
        val builder = ProcessBuilder(listOf(binary) + args).directory(workDir)
        builder.environment().putAll(baseEnv)
        builder.environment().putAll(env)
        val process = try {
            builder.start()
        } catch (e: IOException) {
            throw StorageException(StorageException.Reason.TOOL_MISSING, "Could not start rclone at $binary: ${e.message}", e)
        }
        val collector = LogCollector(process.errorStream)
        collector.start()
        return StartedProcess(process, collector)
    }

    private class StartedProcess(val process: Process, val stderr: LogCollector) : RunningRclone {
        override fun awaitExit(timeoutMillis: Long): Int? = try {
            if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                stderr.join(PUMP_JOIN_MILLIS)
                process.exitValue()
            } else {
                null
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }

        override fun logLines(): List<String> = stderr.snapshot()

        override fun kill() {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** Keeps the last [MAX_LOG_LINES] stderr lines; rclone logs one JSON record per line. */
    private class LogCollector(private val stream: InputStream) : Thread("rclone-stderr") {
        private val lines = ArrayDeque<String>()

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                stream.bufferedReader().forEachLine { line ->
                    synchronized(lines) {
                        if (lines.size >= MAX_LOG_LINES) lines.removeFirst()
                        lines.addLast(line)
                    }
                }
            } catch (_: IOException) {
                // Stream closed because the process was killed; keep what was collected.
            }
        }

        fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
    }

    private object NullSink : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private fun pump(name: String, input: InputStream, sink: OutputStream): Thread =
        Thread({
            try {
                input.copyTo(sink, BUFFER)
            } catch (_: IOException) {
                // Process killed; nothing more to read.
            }
        }, name).apply {
            isDaemon = true
            start()
        }

    private companion object {
        const val TAG = "RcloneDirect"
        const val BUFFER = 64 * 1024
        const val MAX_LOG_LINES = 200
        const val PUMP_JOIN_MILLIS = 5_000L
    }
}
