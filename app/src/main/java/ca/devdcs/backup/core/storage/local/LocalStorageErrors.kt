package ca.devdcs.backup.core.storage.local

import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageException.Reason
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Maps raw local-filesystem and Storage Access Framework failures to [StorageException].
 *
 * Exists because java.io and ContentResolver report disk-full, read-only and permission errors
 * only through exception types and errno text (`ENOSPC`, `EACCES`...); the pipeline needs a
 * [Reason] to choose the UI message and retry policy. Pure JVM so it is unit-tested.
 */
object LocalStorageErrors {

    /** Classifies [error] that occurred while performing [operation] (e.g. "write a/b.tar"). */
    fun map(operation: String, error: Throwable): StorageException {
        if (error is StorageException) return error
        val text = causeChainText(error)
        val reason = when {
            text.containsAny("ENOSPC", "No space left", "EDQUOT", "Disk quota exceeded") -> Reason.NO_SPACE
            text.containsAny("EROFS", "Read-only file system") -> Reason.PERMISSION
            text.containsAny("EACCES", "EPERM", "Permission denied", "Operation not permitted") -> Reason.PERMISSION
            error is SecurityException -> Reason.PERMISSION
            text.containsAny("ENOENT", "No such file") -> Reason.NOT_FOUND
            error is FileNotFoundException -> Reason.NOT_FOUND
            error is UnsupportedOperationException -> Reason.UNSUPPORTED
            error is IllegalArgumentException -> Reason.MISCONFIGURED
            else -> Reason.PROTOCOL
        }
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return StorageException(reason, "Could not $operation: $detail", error)
    }

    /**
     * Runs [block], rethrowing cancellation untouched and every other failure as a mapped
     * [StorageException]. Used around each blocking local/SAF call.
     */
    inline fun <T> guard(operation: String, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: StorageException) {
        throw e
    } catch (e: Exception) {
        throw map(operation, e)
    }

    /** Wraps [out] so write/flush/close failures surface as [StorageException] per the contract. */
    fun mapErrors(out: OutputStream, operation: String): OutputStream = MappedOutputStream(out, operation)

    /** Wraps [input] so read failures surface as [StorageException] per the contract. */
    fun mapErrors(input: InputStream, operation: String): InputStream = MappedInputStream(input, operation)

    private fun causeChainText(error: Throwable): String {
        val sb = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            sb.append(current.message.orEmpty()).append('\n')
            current = current.cause.takeIf { it !== current }
            depth++
        }
        return sb.toString()
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it, ignoreCase = true) }

    private class MappedOutputStream(out: OutputStream, private val operation: String) : FilterOutputStream(out) {
        override fun write(b: Int) = io { out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = io { out.write(b, off, len) }
        override fun flush() = io { out.flush() }
        override fun close() = io { out.close() }

        private inline fun io(block: () -> Unit) = try {
            block()
        } catch (e: IOException) {
            throw map(operation, e)
        }
    }

    private class MappedInputStream(input: InputStream, private val operation: String) : FilterInputStream(input) {
        override fun read(): Int = io { `in`.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = io { `in`.read(b, off, len) }
        override fun skip(n: Long): Long = io { `in`.skip(n) }
        override fun available(): Int = io { `in`.available() }
        override fun close() = io { `in`.close() }

        private inline fun <T> io(block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            throw map(operation, e)
        }
    }
}
