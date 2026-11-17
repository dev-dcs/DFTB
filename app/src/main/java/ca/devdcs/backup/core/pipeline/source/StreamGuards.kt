package ca.devdcs.backup.core.pipeline.source

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** A privileged or source stream failed: non-zero helper exit, short read, changed size. */
class SourceReadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Enforces the [ca.devdcs.backup.core.pipeline.SourceItem.File] promise of "exactly sizeBytes
 * bytes": a file that shrank or grew between scan and read fails the item instead of producing a
 * tar entry whose header lies about its length. Never pads, never truncates silently.
 */
class ExactLengthInputStream(
    input: InputStream,
    private val expected: Long,
    private val label: String,
) : FilterInputStream(input) {

    private var count = 0L
    private var done = false

    override fun read(): Int {
        if (done) return -1
        val b = super.read()
        if (b < 0) return finish() else count++
        if (count > expected) throw grew()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (done) return -1
        if (len == 0) return 0
        val n = super.read(b, off, len)
        if (n < 0) return finish()
        count += n
        if (count > expected) throw grew()
        return n
    }

    override fun skip(n: Long): Long {
        // Skipping would bypass the accounting; read and discard instead.
        val buffer = ByteArray(8192)
        var left = n
        while (left > 0) {
            val r = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (r < 0) break
            left -= r
        }
        return n - left
    }

    override fun markSupported(): Boolean = false

    private fun finish(): Int {
        if (count != expected) throw SourceReadException("$label: expected $expected bytes, got $count (changed or short read)")
        done = true
        return -1
    }

    private fun grew() = SourceReadException("$label: grew beyond the expected $expected bytes while being read")
}

/** Collects SHA-256 digests of streams as they are consumed, keyed by a name (Nandroid sidecars). */
class DigestBook {
    private val digests = HashMap<String, String>()

    @Synchronized
    fun put(name: String, hex: String) {
        digests[name] = hex
    }

    @Synchronized
    fun get(name: String): String? = digests[name]
}

/**
 * Hashes everything read through it; on EOF records the SHA-256 hex under [name] in [book]. A
 * stream closed before EOF records nothing, so a sidecar can never describe a partial file.
 */
class Sha256RecordingInputStream(
    input: InputStream,
    private val book: DigestBook,
    private val name: String,
) : FilterInputStream(input) {

    private val digest = MessageDigest.getInstance("SHA-256")
    private var recorded = false

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) digest.update(b.toByte()) else record()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) digest.update(b, off, n) else if (n < 0) record()
        return n
    }

    override fun skip(n: Long): Long {
        val buffer = ByteArray(8192)
        var left = n
        while (left > 0) {
            val r = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (r < 0) break
            left -= r
        }
        return n - left
    }

    override fun markSupported(): Boolean = false

    private fun record() {
        if (recorded) return
        recorded = true
        book.put(name, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
