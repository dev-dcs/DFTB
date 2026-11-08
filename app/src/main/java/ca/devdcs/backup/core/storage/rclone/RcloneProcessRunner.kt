package ca.devdcs.backup.core.storage.rclone

import ca.devdcs.backup.core.storage.StorageException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Buffered outcome of one rclone invocation. [logLines] is rclone's stderr (`--use-json-log`). */
data class RcloneResult(
    val exitCode: Int,
    val stdout: String,
    val logLines: List<String>,
    val timedOut: Boolean,
) {
    val isSuccess: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * Runs the rclone binary one way (app-uid process, or through the root shell).
 *
 * Exists so [RcloneSession] issues the same argv regardless of where rclone lives; only process
 * plumbing differs. [env] may carry secrets: implementations pass it via the process environment,
 * never argv, and never log it.
 */
interface RcloneProcessRunner {
    /** Human-readable "how rclone runs" for diagnostics (no secrets). */
    val description: String

    /** Run to completion, buffering stdout and stderr. Throws [StorageException] only on launch failure. */
    suspend fun run(
        args: List<String>,
        env: Map<String, String>,
        stdin: ByteArray? = null,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): RcloneResult

    /**
     * Start rclone (`rcat`) reading from its stdin and return a stream feeding it. Closing the
     * stream ends input and waits for rclone; a non-zero exit throws [RcloneStreamException].
     * [onFinished] runs exactly once after the process is done.
     */
    suspend fun openUpload(args: List<String>, env: Map<String, String>, onFinished: () -> Unit): OutputStream

    /** Start rclone (`cat`) and return its stdout. A non-zero exit surfaces at EOF as [RcloneStreamException]. */
    suspend fun openDownload(args: List<String>, env: Map<String, String>, onFinished: () -> Unit): InputStream

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 10 * 60_000L

        /** Upper bound for rclone to finalize an upload after its input closed (multipart commit). */
        const val FINISH_TIMEOUT_MILLIS: Long = 60 * 60_000L
    }
}

/**
 * The IOException a stream returned by [RcloneProcessRunner] throws, because Java streams can
 * only throw IOException. The mapped [storageException] (with its [StorageException.Reason]) is the
 * cause, so pipelines unwrap it for retry decisions.
 */
class RcloneStreamException(val storageException: StorageException) :
    IOException(storageException.message, storageException)

/** A started rclone process as the stream wrappers see it. Blocking methods; call on IO threads. */
internal interface RunningRclone {
    /** Wait for exit; null when [timeoutMillis] elapsed first. */
    fun awaitExit(timeoutMillis: Long): Int?

    /** stderr lines captured so far (complete once the process exited). */
    fun logLines(): List<String>

    /** Kill the process; idempotent. */
    fun kill()
}

/** Upload wrapper: maps a dead rclone's broken pipe or failing exit into [RcloneStreamException]. */
internal class RcloneUploadStream(
    out: OutputStream,
    private val running: RunningRclone,
    private val operation: String,
    private val onFinished: () -> Unit,
) : FilterOutputStream(out) {

    private val finished = AtomicBoolean(false)

    override fun write(b: Int) = guard { out.write(b) }

    override fun write(b: ByteArray, off: Int, len: Int) = guard { out.write(b, off, len) }

    override fun flush() = guard { out.flush() }

    override fun close() {
        if (finished.get()) return
        var pipeError: IOException? = null
        try {
            out.close()
        } catch (e: IOException) {
            pipeError = e
        }
        val code = running.awaitExit(RcloneProcessRunner.FINISH_TIMEOUT_MILLIS)
        finish()
        when {
            code == null -> {
                running.kill()
                throw RcloneStreamException(StorageException(StorageException.Reason.TIMEOUT, "rclone $operation did not finish in time"))
            }
            code != 0 -> throw RcloneStreamException(RcloneErrorMapper.toException(operation, code, running.logLines()))
            pipeError != null -> throw RcloneStreamException(StorageException(StorageException.Reason.PROTOCOL, "rclone $operation input failed", pipeError))
        }
    }

    private inline fun guard(block: () -> Unit) {
        if (finished.get()) throw IOException("rclone $operation stream is closed")
        try {
            block()
        } catch (e: IOException) {
            // Broken pipe: rclone exited early. Report rclone's own reason instead of EPIPE.
            val code = running.awaitExit(EXIT_GRACE_MILLIS)
            running.kill()
            val logs = running.logLines()
            finish()
            val mapped = if (code != null && code != 0) {
                RcloneErrorMapper.toException(operation, code, logs)
            } else {
                StorageException(StorageException.Reason.PROTOCOL, "rclone $operation stopped accepting data", e)
            }
            throw RcloneStreamException(mapped)
        }
    }

    private fun finish() {
        if (finished.compareAndSet(false, true)) onFinished()
    }

    private companion object {
        const val EXIT_GRACE_MILLIS = 10_000L
    }
}

/** Download wrapper: checks rclone's exit status at EOF so a failed transfer never looks complete. */
internal class RcloneDownloadStream(
    input: InputStream,
    private val running: RunningRclone,
    private val operation: String,
    private val onFinished: () -> Unit,
) : FilterInputStream(input) {

    private val finished = AtomicBoolean(false)
    @Volatile private var reachedEof = false

    override fun read(): Int {
        val r = guard { `in`.read() }
        if (r < 0) onEof()
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val r = guard { `in`.read(b, off, len) }
        if (r < 0) onEof()
        return r
    }

    override fun skip(n: Long): Long = guard { `in`.skip(n) }

    override fun markSupported(): Boolean = false

    override fun close() {
        if (finished.get()) return
        if (!reachedEof) running.kill()
        runCatching { `in`.close() }
        running.awaitExit(CLOSE_WAIT_MILLIS)
        finish()
    }

    private fun onEof() {
        if (reachedEof) return
        reachedEof = true
        val code = running.awaitExit(EOF_WAIT_MILLIS)
        if (code == null) {
            running.kill()
            finish()
            throw RcloneStreamException(StorageException(StorageException.Reason.TIMEOUT, "rclone $operation did not exit after end of data"))
        }
        if (code != 0) {
            finish()
            throw RcloneStreamException(RcloneErrorMapper.toException(operation, code, running.logLines()))
        }
    }

    private inline fun <T> guard(block: () -> T): T {
        if (finished.get()) throw IOException("rclone $operation stream is closed")
        return try {
            block()
        } catch (e: RcloneStreamException) {
            throw e
        } catch (e: IOException) {
            running.kill()
            finish()
            throw RcloneStreamException(StorageException(StorageException.Reason.PROTOCOL, "rclone $operation read failed", e))
        }
    }

    private fun finish() {
        if (finished.compareAndSet(false, true)) onFinished()
    }

    private companion object {
        const val EOF_WAIT_MILLIS = 60_000L
        const val CLOSE_WAIT_MILLIS = 5_000L
    }
}
