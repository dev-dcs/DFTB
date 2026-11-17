package ca.devdcs.backup.core.pipeline.crypto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Shared chunk framing of `dftb-aesgcm-v1` (see [AesGcmStreamCipher] for the format rationale). */
internal object ChunkFraming {
    const val TAG_BYTES = 16
    private const val TAG_BITS = TAG_BYTES * 8
    const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun nonce(prefix: ByteArray, index: Long): ByteArray {
        val n = ByteArray(12)
        System.arraycopy(prefix, 0, n, 0, 4)
        for (i in 0 until 8) n[4 + i] = (index ushr (56 - 8 * i)).toByte()
        return n
    }

    fun aad(headerHash: ByteArray, index: Long, final: Boolean): ByteArray {
        val a = ByteArray(headerHash.size + 9)
        System.arraycopy(headerHash, 0, a, 0, headerHash.size)
        for (i in 0 until 8) a[headerHash.size + i] = (index ushr (56 - 8 * i)).toByte()
        a[a.size - 1] = if (final) 1 else 0
        return a
    }

    fun init(cipher: Cipher, mode: Int, key: SecretKeySpec, header: StreamHeader.Encoded, index: Long, final: Boolean) {
        cipher.init(mode, key, GCMParameterSpec(TAG_BITS, nonce(header.header.noncePrefix, index)))
        cipher.updateAAD(aad(header.hash, index, final))
    }
}

/**
 * Encrypting side returned by [AesGcmStreamCipher.encrypt]. [close] writes the final chunk and
 * closes the destination; [abort] closes the destination *without* a final chunk, so a failed or
 * cancelled backup can never produce a stream that decrypts as complete. Not thread-safe.
 */
class EncryptingOutputStream internal constructor(
    private val out: OutputStream,
    streamKey: ByteArray,
    private val header: StreamHeader.Encoded,
) : OutputStream() {

    private val key = SecretKeySpec(streamKey, "AES").also { streamKey.fill(0) }
    private val cipher: Cipher = Cipher.getInstance(ChunkFraming.TRANSFORMATION)
    private val chunkSize = header.header.chunkSize
    private val plain = ByteArray(chunkSize)
    private val sealed = ByteArray(chunkSize + ChunkFraming.TAG_BYTES)
    private var fill = 0
    private var index = 0L
    private var closed = false
    private var failed = false

    override fun write(b: Int) {
        ensureWritable()
        plain[fill++] = b.toByte()
        if (fill == chunkSize) seal(final = false)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensureWritable()
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException("off=$off len=$len size=${b.size}")
        var pos = off
        var remaining = len
        while (remaining > 0) {
            val n = minOf(remaining, chunkSize - fill)
            System.arraycopy(b, pos, plain, fill, n)
            fill += n
            pos += n
            remaining -= n
            // A full buffer is sealed immediately as non-final: the final chunk is always short.
            if (fill == chunkSize) seal(final = false)
        }
    }

    override fun flush() {
        ensureWritable()
        out.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (!failed) {
                seal(final = true)
                out.flush()
            }
        } finally {
            try {
                out.close()
            } finally {
                wipe()
            }
        }
    }

    /** Closes the destination without finalizing; the partial output will fail to decrypt. */
    fun abort() {
        if (closed) return
        closed = true
        failed = true
        try {
            out.close()
        } catch (_: IOException) {
            // Already failing; the earlier build error matters more.
        } finally {
            wipe()
        }
    }

    private fun seal(final: Boolean) {
        try {
            ChunkFraming.init(cipher, Cipher.ENCRYPT_MODE, key, header, index, final)
            val n = cipher.doFinal(plain, 0, fill, sealed, 0)
            out.write(sealed, 0, n)
            index++
            fill = 0
        } catch (e: GeneralSecurityException) {
            failed = true
            throw IOException("Encryption failed", e)
        } catch (e: Throwable) {
            failed = true
            throw e
        }
    }

    private fun ensureWritable() {
        if (closed) throw IOException("Stream closed")
        if (failed) throw IOException("Stream failed earlier")
    }

    private fun wipe() {
        plain.fill(0)
        sealed.fill(0)
        Secrets.destroy(key)
    }
}

/**
 * Decrypting side returned by [AesGcmStreamCipher.decrypt]. Only authenticated plaintext is ever
 * returned; any failure throws [CipherIntegrityException] and keeps throwing on later reads.
 */
class DecryptingInputStream internal constructor(
    private val input: InputStream,
    streamKey: ByteArray,
    private val header: StreamHeader.Encoded,
) : InputStream() {

    private val key = SecretKeySpec(streamKey, "AES").also { streamKey.fill(0) }
    private val cipher: Cipher = Cipher.getInstance(ChunkFraming.TRANSFORMATION)
    private val chunkSize = header.header.chunkSize
    private val sealed = ByteArray(chunkSize + ChunkFraming.TAG_BYTES)
    private val plain = ByteArray(chunkSize + ChunkFraming.TAG_BYTES)
    private var pos = 0
    private var limit = 0
    private var index = 0L
    private var finished = false
    private var closed = false
    private var failure: IOException? = null

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException("off=$off len=$len size=${b.size}")
        if (closed) throw IOException("Stream closed")
        failure?.let { throw it }
        if (len == 0) return 0
        while (pos == limit) {
            if (finished) return -1
            try {
                nextChunk()
            } catch (e: IOException) {
                failure = e
                throw e
            }
        }
        val n = minOf(len, limit - pos)
        System.arraycopy(plain, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int = if (closed) 0 else limit - pos

    override fun close() {
        if (closed) return
        closed = true
        try {
            input.close()
        } finally {
            sealed.fill(0)
            plain.fill(0)
            Secrets.destroy(key)
        }
    }

    private fun nextChunk() {
        val n = readFully(sealed, sealed.size)
        val final = n < sealed.size
        if (n < ChunkFraming.TAG_BYTES) {
            throw CipherIntegrityException(CipherIntegrityException.Reason.TRUNCATED, "Stream ended before its final chunk (chunk $index)")
        }
        val produced = try {
            ChunkFraming.init(cipher, Cipher.DECRYPT_MODE, key, header, index, final)
            cipher.doFinal(sealed, 0, n, plain, 0)
        } catch (e: AEADBadTagException) {
            throw CipherIntegrityException(CipherIntegrityException.Reason.TAMPERED, "Chunk $index failed authentication", e)
        } catch (e: GeneralSecurityException) {
            throw CipherIntegrityException(CipherIntegrityException.Reason.TAMPERED, "Chunk $index could not be decrypted", e)
        }
        pos = 0
        limit = produced
        index++
        if (final) {
            if (input.read() != -1) {
                pos = 0
                limit = 0
                plain.fill(0)
                throw CipherIntegrityException(CipherIntegrityException.Reason.TRAILING_DATA, "Data follows the final chunk")
            }
            finished = true
        }
    }

    private fun readFully(buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val r = input.read(buf, off, len - off)
            if (r < 0) break
            off += r
        }
        return off
    }
}
