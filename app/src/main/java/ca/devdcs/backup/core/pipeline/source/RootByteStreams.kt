package ca.devdcs.backup.core.pipeline.source

import android.content.Context
import android.system.Os
import android.system.OsConstants
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandEvent
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.CommandResult
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.nfs.RootFifoTransfer
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** How [RootByteStreams] carries binary stdout from a privileged process into the app. */
enum class PrivilegedTransport {
    /** Detached root helper writing into a FIFO in the app cache dir (full speed, shell stays free). */
    ROOT_FIFO,

    /** Shizuku child piped through `base64`, decoded from its line stream (adb uid cannot reach app FIFOs). */
    SHIZUKU_BASE64,
}

/** Why no privileged byte stream can be opened right now. */
enum class StreamUnavailableReason {
    /** Standard mode: no shell at all. */
    NO_ELEVATION,
    /** Root mode selected but su is not granted / reachable. */
    ROOT_NOT_GRANTED,
    /** Shizuku mode selected but the service is not running or not authorized. */
    SHIZUKU_NOT_AVAILABLE,
}

sealed interface StreamCapability {
    data class Available(val transport: PrivilegedTransport, val tier: ElevationTier) : StreamCapability
    data class Unavailable(val reason: StreamUnavailableReason) : StreamCapability
}

/**
 * Opens a raw [InputStream] of a privileged command's stdout (`dd if=<blockdev>`, `tar -cf - ...`,
 * `cat <file>`) without blocking the shared libsu root shell.
 *
 * Why not [CommandExecutor.stream]: it delivers text lines and, on the Root tier, keeps the ONE
 * shared su shell busy for the whole multi-gigabyte transfer. Following
 * [RootFifoTransfer]'s pattern (whose API is file-specific, so it is generalized here rather than
 * reused), the Root tier creates a data FIFO and a status FIFO in the app cache dir and launches a
 * detached helper (`setsid sh -c ... &`) that runs the command with stdout into the data FIFO and
 * then writes `exit-code\nstderr` to the status FIFO. The shared shell only runs the short launch
 * and kill commands. On the Shizuku tier (whose adb uid cannot open app-private FIFOs) the command
 * is piped through `base64` and the line stream decoded; each Shizuku command already has its own
 * process, so nothing shared is blocked.
 *
 * Contract of the returned stream: a non-zero exit of the command surfaces as [SourceReadException]
 * at EOF (never a silent short stream); closing early or cancelling kills the helper's process group.
 */
@Singleton
class RootByteStreams @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: CommandExecutorRouter,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    /** Which transport the current operating mode can use right now. Never throws. */
    suspend fun capability(): StreamCapability = withContext(io) {
        val executor = router.current()
        when (executor.tier) {
            ElevationTier.NONE -> StreamCapability.Unavailable(StreamUnavailableReason.NO_ELEVATION)
            ElevationTier.ROOT ->
                if (executor.isAvailable()) StreamCapability.Available(PrivilegedTransport.ROOT_FIFO, ElevationTier.ROOT)
                else StreamCapability.Unavailable(StreamUnavailableReason.ROOT_NOT_GRANTED)
            ElevationTier.SHIZUKU ->
                if (executor.isAvailable()) StreamCapability.Available(PrivilegedTransport.SHIZUKU_BASE64, ElevationTier.SHIZUKU)
                else StreamCapability.Unavailable(StreamUnavailableReason.SHIZUKU_NOT_AVAILABLE)
        }
    }

    /**
     * Stream the stdout of [script] (a POSIX sh fragment whose every path the caller quoted with
     * [PrivilegedStreamScripts.q] / [shellCommand]). [label] names the item in error messages.
     * Throws [SourceReadException] when no privileged tier is usable or the helper cannot start.
     * [onSuccessStderr] receives the command's (non-blank) stderr when it exits 0, so callers can
     * surface tolerated problems (e.g. files that vanished mid-backup) as warnings.
     */
    suspend fun open(script: String, label: String, onSuccessStderr: ((String) -> Unit)? = null): InputStream = withContext(io) {
        val executor = router.current()
        if (executor.tier == ElevationTier.NONE || !executor.isAvailable()) {
            throw SourceReadException("$label: no privileged shell is available in the current mode")
        }
        when (executor.tier) {
            ElevationTier.ROOT -> openRootFifo(executor, script, label, onSuccessStderr)
            ElevationTier.SHIZUKU -> openShizukuBase64(executor, script, label, onSuccessStderr)
            ElevationTier.NONE -> throw SourceReadException("$label: no privileged shell")
        }
    }

    /** [open] for an argv (each token quoted). */
    suspend fun openCommand(argv: List<String>, label: String): InputStream = open(shellCommand(argv), label)

    /** Raw bytes of one file only a privileged shell can read. */
    suspend fun openFile(path: String): InputStream = open(PrivilegedStreamScripts.catFile(path), path)

    /** Quote [argv] into one shell command line. */
    fun shellCommand(argv: List<String>): String = PrivilegedStreamScripts.command(argv)

    // ---- Root: FIFO + detached helper -------------------------------------------------------

    private suspend fun openRootFifo(executor: CommandExecutor, script: String, label: String, onSuccessStderr: ((String) -> Unit)?): InputStream {
        val pipes = Pipes.create(context.cacheDir)
        val helper = PrivilegedStreamScripts.fifoHelper(script, pipes.data, pipes.status)
        val launch = try {
            executor.execute(Command(listOf("sh", "-c", PrivilegedStreamScripts.detachedLaunch(helper)), timeoutMillis = LAUNCH_TIMEOUT_MILLIS))
        } catch (e: CancellationException) {
            pipes.delete()
            throw e
        }
        val pid = if (launch.isSuccess) PrivilegedStreamScripts.parsePid(launch.stdout) else null
        if (pid == null) {
            pipes.delete()
            val detail = launch.stderrText.ifBlank { "exit ${launch.exitCode}" }
            throw SourceReadException("$label: could not start the root helper ($detail)")
        }
        val (stream, timedOut) = try {
            runInterruptible {
                RootFifoTransfer.openWithWatchdog(OPEN_TIMEOUT_MILLIS, unblock = { unblockOpen(pipes.data, OsConstants.O_WRONLY) }) {
                    FileInputStream(pipes.data)
                }
            }
        } catch (e: Throwable) {
            killAndCleanup(executor, pid, pipes)
            throw if (e is CancellationException) e else SourceReadException("$label: cannot open the data pipe", e)
        }
        if (timedOut) {
            runCatching { stream.close() }
            killAndCleanup(executor, pid, pipes)
            throw SourceReadException("$label: the root helper did not start streaming in time")
        }
        return FifoInputStream(stream, pipes, label, onSuccessStderr) { killAndCleanup(executor, pid, pipes) }
    }

    /** Kill the helper group off the caller's thread, then drain/remove the FIFOs. */
    private fun killAndCleanup(executor: CommandExecutor, pid: Int, pipes: Pipes) {
        appScope.launch(io + NonCancellable) {
            val result = executor.execute(Command(listOf("sh", "-c", PrivilegedStreamScripts.killScript(pid)), timeoutMillis = KILL_TIMEOUT_MILLIS))
            if (!result.isSuccess) DftbLog.w(TAG, "kill of stream helper $pid failed: ${result.stderrText}")
            drainNonBlocking(pipes.status)
            pipes.delete()
        }
    }

    /** Reads the helper's status after data EOF (it writes it right after closing the data pipe). */
    private fun readStatus(pipes: Pipes): Pair<Int, String> {
        val (input, timedOut) = RootFifoTransfer.openWithWatchdog(STATUS_TIMEOUT_MILLIS, unblock = { unblockOpen(pipes.status, OsConstants.O_WRONLY) }) {
            FileInputStream(pipes.status)
        }
        val text = input.use { String(it.readBytes(), Charsets.UTF_8) }
        if (timedOut && text.isBlank()) return -1 to "root helper did not report a status"
        return PrivilegedStreamScripts.parseStatus(text)
    }

    private inner class FifoInputStream(
        private val delegate: FileInputStream,
        private val pipes: Pipes,
        private val label: String,
        private val onSuccessStderr: ((String) -> Unit)?,
        private val abort: () -> Unit,
    ) : InputStream() {
        private var eof = false
        private val closed = AtomicBoolean(false)

        override fun read(): Int {
            if (eof) return -1
            val b = guard { delegate.read() }
            if (b < 0) onEof()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            val n = guard { delegate.read(b, off, len) }
            if (n < 0) onEof()
            return n
        }

        override fun available(): Int = if (eof) 0 else guard { delegate.available() }

        private fun onEof() {
            eof = true
            val (code, err) = readStatus(pipes)
            if (code != 0) throw SourceReadException("$label failed (exit $code): ${err.ifBlank { "no error output" }}")
            if (err.isNotBlank()) onSuccessStderr?.invoke(err)
        }

        private inline fun <T> guard(block: () -> T): T = try {
            block()
        } catch (e: SourceReadException) {
            throw e
        } catch (e: IOException) {
            throw SourceReadException("$label: read failed: ${e.message}", e)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { delegate.close() } // an early close makes the writer fail with EPIPE
            if (eof) pipes.delete() else abort()
        }
    }

    /** The data and status FIFOs of one stream, private (0600) in the app cache dir. */
    private class Pipes(val data: String, val status: String) {
        fun delete() {
            File(data).delete()
            File(status).delete()
        }

        companion object {
            fun create(cacheDir: File): Pipes {
                val id = UUID.randomUUID().toString()
                val pipes = Pipes(File(cacheDir, "dftb-src-$id.data").absolutePath, File(cacheDir, "dftb-src-$id.status").absolutePath)
                try {
                    Os.mkfifo(pipes.data, FIFO_MODE)
                    Os.mkfifo(pipes.status, FIFO_MODE)
                } catch (e: Exception) {
                    pipes.delete()
                    throw SourceReadException("cannot create stream pipe: ${e.message}", e)
                }
                return pipes
            }
        }
    }

    // ---- Shizuku: base64 over the line stream ------------------------------------------------

    private fun openShizukuBase64(executor: CommandExecutor, script: String, label: String, onSuccessStderr: ((String) -> Unit)?): InputStream {
        val events = executor.stream(Command(listOf("sh", "-c", PrivilegedStreamScripts.base64Pipeline(script))))
        return Base64LineInputStream(events, appScope, io, label, onSuccessStderr)
    }

    companion object {
        private const val TAG = "RootByteStreams"
        private const val FIFO_MODE = 384 // 0600
        private const val LAUNCH_TIMEOUT_MILLIS = 30_000L
        private const val KILL_TIMEOUT_MILLIS = 15_000L
        private const val OPEN_TIMEOUT_MILLIS = 30_000L
        /** After data EOF the helper reports at once; generous for a slow tail flush. */
        private const val STATUS_TIMEOUT_MILLIS = 120_000L

        private fun unblockOpen(path: String, accessMode: Int) {
            try {
                Os.close(Os.open(path, accessMode or OsConstants.O_NONBLOCK, 0))
            } catch (e: Exception) {
                throw IOException("unblock $path: ${e.message}", e)
            }
        }

        /** Open the read end without blocking so a helper waiting to write its status can finish. */
        private fun drainNonBlocking(path: String) {
            runCatching {
                val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0)
                try {
                    val buffer = ByteArray(PrivilegedStreamScripts.MAX_STDERR_CHARS + 64)
                    runCatching { Os.read(fd, buffer, 0, buffer.size) }
                } finally {
                    Os.close(fd)
                }
            }
        }
    }
}

/**
 * Bridges a [CommandEvent] flow of base64 lines into a blocking [InputStream] with bounded
 * buffering: the collector suspends while the queue is full, which throttles the remote process
 * through Shizuku's synchronous callbacks. Exit status is checked at EOF; [close] cancels the
 * collection, which makes the executor kill the remote child.
 */
internal class Base64LineInputStream(
    events: Flow<CommandEvent>,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val label: String,
    private val onSuccessStderr: ((String) -> Unit)? = null,
) : InputStream() {

    private sealed interface Chunk {
        class Data(val bytes: ByteArray) : Chunk
        class End(val exitCode: Int, val stderr: String) : Chunk
        class Failed(val error: Throwable) : Chunk
    }

    private val queue = ArrayBlockingQueue<Chunk>(QUEUE_CAPACITY)
    private var current: ByteArray = EMPTY
    private var position = 0
    private var finished = false
    private val job: Job

    init {
        job = scope.launch(dispatcher) {
            val stderr = StringBuilder()
            val decoder = Base64.getDecoder()
            var ended = false
            try {
                events.collect { event ->
                    when (event) {
                        is CommandEvent.Stdout -> if (event.line.isNotEmpty()) put(Chunk.Data(decoder.decode(event.line.trim())))
                        is CommandEvent.Stderr -> if (stderr.length < PrivilegedStreamScripts.MAX_STDERR_CHARS) stderr.append(event.line).append('\n')
                        is CommandEvent.Exit -> {
                            ended = true
                            put(Chunk.End(event.exitCode, stderr.toString().trim()))
                        }
                    }
                }
                if (!ended) put(Chunk.End(CommandResult.EXIT_NOT_LAUNCHED, stderr.toString().trim()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                queue.clear()
                queue.offer(Chunk.Failed(e))
            }
        }
    }

    private suspend fun put(chunk: Chunk) = runInterruptible { queue.put(chunk) }

    override fun read(): Int {
        if (!fill()) return -1
        return current[position++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, current.size - position)
        System.arraycopy(current, position, b, off, n)
        position += n
        return n
    }

    override fun available(): Int = current.size - position

    private fun fill(): Boolean {
        while (position >= current.size) {
            if (finished) return false
            val chunk = try {
                queue.take()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("$label: interrupted")
            }
            when (chunk) {
                is Chunk.Data -> {
                    current = chunk.bytes
                    position = 0
                }
                is Chunk.End -> {
                    finished = true
                    if (chunk.exitCode != 0) {
                        throw SourceReadException("$label failed (exit ${chunk.exitCode}): ${chunk.stderr.ifBlank { "no error output" }}")
                    }
                    if (chunk.stderr.isNotBlank()) onSuccessStderr?.invoke(chunk.stderr)
                    return false
                }
                is Chunk.Failed -> {
                    finished = true
                    throw SourceReadException("$label: privileged stream failed: ${chunk.error.message}", chunk.error)
                }
            }
        }
        return true
    }

    override fun close() {
        finished = true
        job.cancel()
        queue.clear()
    }

    private companion object {
        val EMPTY = ByteArray(0)
        /** 8 x 36 KiB decoded chunks in flight at most. */
        const val QUEUE_CAPACITY = 8
    }
}
