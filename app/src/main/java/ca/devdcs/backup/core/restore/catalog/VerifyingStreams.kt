package ca.devdcs.backup.core.restore.catalog

import ca.devdcs.backup.core.pipeline.ChecksumEngine
import ca.devdcs.backup.core.pipeline.DigestingOutputStream
import ca.devdcs.backup.core.pipeline.checksum.ChecksumSidecar
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/** Thrown from blocking reads once the restore/verify job was cancelled. */
class RestoreStreamCancelledException : CancellationException("Restore cancelled")

/** Manifest digest maps are keyed by storage key; unknown algorithms are ignored. */
internal fun digestsOf(map: Map<String, String>?): Map<ChecksumAlgorithm, String> =
    map.orEmpty().mapNotNull { (key, hex) ->
        ChecksumAlgorithm.entries.firstOrNull { it.storageKey == key }?.let { it to hex }
    }.toMap()

internal object DiscardingOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}

/**
 * Digests the stored bytes as they are read and, at EOF, throws [CatalogException] when the byte
 * count or any expected digest differs. Exists so the restore chain fails *at the end of the
 * stored stream* instead of trusting a corrupted artifact. [onBytes] feeds progress.
 */
class DigestVerifyingInputStream(
    private val input: InputStream,
    engine: ChecksumEngine,
    private val expected: Map<ChecksumAlgorithm, String>,
    private val expectedBytes: Long?,
    private val label: String,
    private val onBytes: (Long) -> Unit = {},
    private val isCancelled: () -> Boolean = { false },
) : InputStream() {

    private val digester: DigestingOutputStream =
        engine.tee(DiscardingOutputStream, expected.keys.ifEmpty { setOf(ChecksumAlgorithm.SHA256) })
    private var count = 0L
    private var finished = false
    private var closed = false
    /** A detected mismatch is rethrown by every later read, so no consumer can miss it. */
    private var failure: CatalogException? = null

    /** Digests of everything read, available once EOF was reached. */
    var actual: Map<ChecksumAlgorithm, String> = emptyMap()
        private set

    val bytesRead: Long get() = count

    override fun read(): Int {
        if (isCancelled()) throw RestoreStreamCancelledException()
        failure?.let { throw it }
        if (finished) return -1
        val b = input.read()
        if (b < 0) {
            onEof()
            return -1
        }
        digester.write(b)
        count++
        onBytes(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (isCancelled()) throw RestoreStreamCancelledException()
        failure?.let { throw it }
        if (finished) return -1
        if (len == 0) return 0
        val n = input.read(b, off, len)
        if (n < 0) {
            onEof()
            return -1
        }
        if (n > 0) {
            digester.write(b, off, n)
            count += n
            onBytes(n.toLong())
        }
        return n
    }

    override fun available(): Int = if (finished) 0 else input.available()

    /** Read whatever is left (discarding it) and verify. */
    fun drainToEof() {
        val buffer = ByteArray(BUFFER)
        while (read(buffer, 0, buffer.size) >= 0) Unit
    }

    private fun onEof() {
        finished = true
        digester.close()
        actual = digester.result().hex
        if (expectedBytes != null && expectedBytes != count) {
            fail(CatalogException(CatalogException.Reason.SIZE_MISMATCH, "$label: stored $count bytes, manifest says $expectedBytes"))
        }
        for ((algorithm, hex) in expected) {
            val got = actual[algorithm]
            if (got == null || !ChecksumSidecar.matches(hex, got)) {
                fail(CatalogException(CatalogException.Reason.DIGEST_MISMATCH, "$label: ${algorithm.storageKey} mismatch"))
            }
        }
    }

    private fun fail(e: CatalogException): Nothing {
        failure = e
        throw e
    }

    override fun close() {
        if (closed) return
        closed = true
        input.close()
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/**
 * The decoded chain handed to callers: reading the plaintext to EOF also drains and verifies the
 * stored bytes before -1 is returned. [onClose] releases the storage session.
 */
internal class VerifiedDecodedStream(
    private val decoded: InputStream,
    private val stored: DigestVerifyingInputStream,
    private val onClose: () -> Unit,
) : DecodedArtifactStream() {

    private var verified = false
    private var closed = false

    /** Stored-bytes digests, available once the stored stream reached EOF. */
    val storedActual: Map<ChecksumAlgorithm, String> get() = stored.actual

    override fun read(): Int {
        val b = decoded.read()
        if (b < 0) finishAndVerify()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = decoded.read(b, off, len)
        if (n < 0) finishAndVerify()
        return n
    }

    override fun available(): Int = decoded.available()

    override fun finishAndVerify() {
        if (verified) return
        // Plaintext past the archive trailer (tar padding) and cipher/codec tails are read here.
        val buffer = ByteArray(16 * 1024)
        while (decoded.read(buffer, 0, buffer.size) >= 0) Unit
        stored.drainToEof()
        verified = true
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            decoded.close()
        } finally {
            try {
                stored.close()
            } finally {
                onClose()
            }
        }
    }
}
