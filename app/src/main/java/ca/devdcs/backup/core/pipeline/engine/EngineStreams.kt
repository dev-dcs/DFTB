package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.pipeline.DigestResult
import ca.devdcs.backup.core.pipeline.DigestingOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Small stream adapters the engine wraps around contract streams. They exist so progress,
 * cancellation, per-item digests and destination-fault attribution work with *any* archive
 * writer, codec or cipher implementation without those components knowing about the engine.
 */

/** Thrown from inside blocking stream calls once the job was cancelled. */
internal class StreamCancelledException : CancellationException("Backup cancelled")

/** Counts bytes written through it and aborts writes once [isCancelled] turns true. */
internal class CountingOutputStream(
    out: OutputStream,
    private val onBytes: (Long) -> Unit,
    private val isCancelled: () -> Boolean,
) : FilterOutputStream(out) {
    @Volatile var count: Long = 0L
        private set

    override fun write(b: Int) {
        if (isCancelled()) throw StreamCancelledException()
        out.write(b)
        count++
        onBytes(1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (isCancelled()) throw StreamCancelledException()
        out.write(b, off, len)
        count += len
        onBytes(len.toLong())
    }
}

/** Lets an inner component "close" its output without closing the real chain underneath. */
internal class CloseShieldOutputStream(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = out.flush()
}

/** Re-labels raw IOExceptions from the destination stream as [DestinationIOException]. */
internal class DestinationGuardOutputStream(out: OutputStream, private val retryable: Boolean) : FilterOutputStream(out) {
    override fun write(b: Int) = guard { out.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) = guard { out.write(b, off, len) }
    override fun flush() = guard { out.flush() }
    override fun close() = guard { out.close() }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: DestinationIOException) {
            throw e
        } catch (e: IOException) {
            throw DestinationIOException(retryable, e)
        }
    }
}

/** Same attribution for destination reads (verification). */
internal class DestinationGuardInputStream(private val input: InputStream, private val retryable: Boolean) : InputStream() {
    override fun read(): Int = guard { input.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int = guard { input.read(b, off, len) }
    override fun available(): Int = guard { input.available() }
    override fun close() = guard { input.close() }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: DestinationIOException) {
        throw e
    } catch (e: IOException) {
        throw DestinationIOException(retryable, e)
    }
}

/**
 * Wraps a source item's stream: counts bytes for progress, checks cancellation, feeds a digest
 * tee, and reports the digest once on close (only when [onClosed] was not already called).
 */
internal class ObservedInputStream(
    private val input: InputStream,
    private val digester: DigestingOutputStream?,
    private val onBytes: (Long) -> Unit,
    private val isCancelled: () -> Boolean,
    private val onClosed: (bytesRead: Long, digest: DigestResult?, reachedEof: Boolean) -> Unit,
) : InputStream() {
    private var bytesRead = 0L
    private var eof = false
    private var closed = false

    override fun read(): Int {
        if (isCancelled()) throw StreamCancelledException()
        val value = input.read()
        if (value < 0) {
            eof = true
        } else {
            bytesRead++
            digester?.write(value)
            onBytes(1)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (isCancelled()) throw StreamCancelledException()
        val n = input.read(b, off, len)
        if (n < 0) {
            eof = true
        } else if (n > 0) {
            bytesRead += n
            digester?.write(b, off, n)
            onBytes(n.toLong())
        }
        return n
    }

    override fun available(): Int = input.available()

    override fun close() {
        if (closed) return
        closed = true
        var digest: DigestResult? = null
        try {
            input.close()
        } finally {
            if (digester != null) {
                digester.close()
                digest = digester.result()
            }
            onClosed(bytesRead, digest, eof)
        }
    }
}

/** Discards everything; the sink behind per-item digest tees. */
internal object NullOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

/** Copies [input] to [out] in 64 KiB chunks; returns the number of bytes copied. */
internal fun copyStream(input: InputStream, out: OutputStream, bufferSize: Int = 64 * 1024): Long {
    val buffer = ByteArray(bufferSize)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        if (n > 0) {
            out.write(buffer, 0, n)
            total += n
        }
    }
    return total
}

/** Skips exactly [count] bytes or throws (a short skip would corrupt a resumed upload). */
internal fun skipFully(input: InputStream, count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (input.read() < 0) {
            throw IOException("Unexpected end of staged file while skipping $count bytes")
        } else {
            remaining--
        }
    }
}
