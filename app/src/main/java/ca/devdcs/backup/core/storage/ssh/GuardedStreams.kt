package ca.devdcs.backup.core.storage.ssh

import ca.devdcs.backup.core.storage.StorageException
import java.io.InputStream
import java.io.OutputStream

/**
 * Upload stream wrapper that turns every protocol exception into a [StorageException] (the contract
 * promises callers nothing else) and runs a single, idempotent [closeAction] that closes the
 * delegate plus whatever remote handle backs it. Used by the SFTP, SSH-exec, SMB and NFS sessions.
 */
internal class GuardedOutputStream(
    private val delegate: OutputStream,
    private val mapError: (Exception) -> StorageException,
    private val closeAction: () -> Unit,
) : OutputStream() {

    private var closed = false

    override fun write(b: Int) = guard { delegate.write(b) }

    override fun write(b: ByteArray, off: Int, len: Int) = guard { delegate.write(b, off, len) }

    override fun flush() = guard { delegate.flush() }

    override fun close() {
        if (closed) return
        closed = true
        guard(closeAction)
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw mapError(e)
        }
    }
}

/**
 * Download stream wrapper mirroring [GuardedOutputStream]. [onEof] runs once when the delegate
 * reports end of stream, so transports that learn about failures only at the end (a shell command's
 * exit status) can fail the read instead of returning a silently truncated file.
 */
internal class GuardedInputStream(
    private val delegate: InputStream,
    private val mapError: (Exception) -> StorageException,
    private val onEof: () -> Unit = {},
    private val closeAction: () -> Unit,
) : InputStream() {

    private var closed = false
    private var eofSeen = false

    override fun read(): Int = guard { delegate.read().also { if (it < 0) reachedEof() } }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        guard { delegate.read(b, off, len).also { if (it < 0) reachedEof() } }

    override fun skip(n: Long): Long = guard { delegate.skip(n) }

    override fun available(): Int = guard { delegate.available() }

    override fun close() {
        if (closed) return
        closed = true
        guard(closeAction)
    }

    private fun reachedEof() {
        if (eofSeen) return
        eofSeen = true
        onEof()
    }

    private inline fun <T> guard(block: () -> T): T {
        try {
            return block()
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw mapError(e)
        }
    }
}
