package ca.devdcs.backup.core.pipeline.checksum

import ca.devdcs.backup.core.pipeline.ChecksumEngine
import ca.devdcs.backup.core.pipeline.DigestResult
import ca.devdcs.backup.core.pipeline.DigestingOutputStream
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's single [ChecksumEngine]. MD5/SHA-* come from the platform JCA provider; BLAKE3 from
 * BouncyCastle. Every requested algorithm is fed from the same buffer, so N algorithms cost one
 * read of the data, which matters for multi-GB partition images.
 */
@Singleton
class DefaultChecksumEngine @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : ChecksumEngine {

    override fun tee(out: OutputStream, algorithms: Set<ChecksumAlgorithm>): DigestingOutputStream =
        TeeDigestingOutputStream(out, Digesters.createAll(algorithms))

    /** Reads [input] to EOF (does not close it) and returns every requested digest. */
    override suspend fun digest(input: InputStream, algorithms: Set<ChecksumAlgorithm>): DigestResult =
        withContext(io) {
            val digesters = Digesters.createAll(algorithms)
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                for (d in digesters) d.update(buffer, 0, n)
                total += n
            }
            DigestResult(total, digesters.associate { it.algorithm to it.finishHex() })
        }

    companion object {
        /** 1 MiB: large enough to amortise syscalls on SAF/network streams, small enough for any heap. */
        const val BUFFER_SIZE = 1 shl 20
    }
}

/**
 * Pass-through stream backing [ChecksumEngine.tee]. Closing it finishes the digests and closes the
 * wrapped stream (normal filter-stream semantics). Not thread-safe.
 */
internal class TeeDigestingOutputStream(
    private val out: OutputStream,
    private val digesters: List<Digester>,
) : DigestingOutputStream() {

    private var bytes = 0L
    private var result: DigestResult? = null
    private var closed = false

    override fun write(b: Int) {
        ensureOpen()
        out.write(b)
        val one = byteArrayOf(b.toByte())
        for (d in digesters) d.update(one, 0, 1)
        bytes++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensureOpen()
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException("off=$off len=$len size=${b.size}")
        if (len == 0) return
        // Write first: only bytes the destination accepted are counted in the digest.
        out.write(b, off, len)
        for (d in digesters) d.update(b, off, len)
        bytes += len
    }

    override fun flush() {
        ensureOpen()
        out.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        // Finish digests before closing [out] so a close() failure still leaves a usable result
        // for diagnostics; the exception still propagates.
        result = DigestResult(bytes, digesters.associate { it.algorithm to it.finishHex() })
        out.close()
    }

    override fun result(): DigestResult =
        result ?: throw IllegalStateException("DigestingOutputStream.result() called before close()")

    private fun ensureOpen() {
        if (closed) throw IOException("Stream closed")
    }
}
