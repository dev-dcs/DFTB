package ca.devdcs.backup.core.pipeline.checksum

import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import org.bouncycastle.crypto.digests.Blake3Digest
import java.security.MessageDigest

/**
 * One running hash. Exists so JCA [MessageDigest]s and BouncyCastle's [Blake3Digest] (which has no
 * JCA provider registered on Android) can be driven through a single loop.
 */
internal interface Digester {
    val algorithm: ChecksumAlgorithm
    fun update(bytes: ByteArray, offset: Int, length: Int)
    /** Finishes the hash and returns it as lowercase hex. Call once. */
    fun finishHex(): String
}

internal object Digesters {

    fun create(algorithm: ChecksumAlgorithm): Digester = when (algorithm) {
        ChecksumAlgorithm.MD5 -> JcaDigester(algorithm, "MD5")
        ChecksumAlgorithm.SHA1 -> JcaDigester(algorithm, "SHA-1")
        ChecksumAlgorithm.SHA256 -> JcaDigester(algorithm, "SHA-256")
        ChecksumAlgorithm.SHA512 -> JcaDigester(algorithm, "SHA-512")
        ChecksumAlgorithm.BLAKE3 -> Blake3Digester()
    }

    /** Deterministic order so results and logs are stable regardless of the caller's Set type. */
    fun createAll(algorithms: Set<ChecksumAlgorithm>): List<Digester> =
        algorithms.sortedBy { it.ordinal }.map(::create)

    private val HEX = "0123456789abcdef".toCharArray()

    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    private class JcaDigester(override val algorithm: ChecksumAlgorithm, jcaName: String) : Digester {
        private val md: MessageDigest = MessageDigest.getInstance(jcaName)
        override fun update(bytes: ByteArray, offset: Int, length: Int) = md.update(bytes, offset, length)
        override fun finishHex(): String = toHex(md.digest())
    }

    private class Blake3Digester : Digester {
        override val algorithm: ChecksumAlgorithm = ChecksumAlgorithm.BLAKE3
        // Default constructor = 256-bit output, the standard BLAKE3 hash length.
        private val digest = Blake3Digest()
        override fun update(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
        override fun finishHex(): String {
            val out = ByteArray(digest.digestSize)
            digest.doFinal(out, 0)
            return toHex(out)
        }
    }
}
