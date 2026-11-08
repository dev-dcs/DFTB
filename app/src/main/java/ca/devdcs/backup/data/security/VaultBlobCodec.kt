package ca.devdcs.backup.data.security

import ca.devdcs.backup.domain.security.SecretKind
import ca.devdcs.backup.domain.security.VaultException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The unencrypted part of a vault file. Everything the vault needs to list entries is here, so
 * `list()` never has to touch the Keystore.
 */
data class VaultBlobHeader(
    val version: Int,
    val kind: SecretKind,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A fully parsed vault file: header plus the sealed payload. */
class VaultBlob(
    val kind: SecretKind,
    val createdAt: Long,
    val updatedAt: Long,
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    val header: VaultBlobHeader get() = VaultBlobHeader(VaultBlobCodec.VERSION, kind, createdAt, updatedAt)
}

/**
 * Binary layout of a `.dftbsec` file, kept free of Android and crypto so it can be unit-tested
 * byte-for-byte. All integers are big-endian.
 *
 * ```
 * offset size field
 * 0 8 magic "DFTBSEC1"
 * 8 2 u16 version (= 1)
 * 10 1 u8 SecretKind ordinal
 * 11 8 i64 createdAt (epoch millis)
 * 19 8 i64 updatedAt (epoch millis)
 * 27 2 u16 ivLength
 * 29 n iv
 * 29+n 4 u32 ciphertextLength
 * 33+n m ciphertext (AES-GCM, tag appended)
 * ```
 */
object VaultBlobCodec {

    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 27

    /** Guards against absurd allocations when a corrupt length field is read. */
    const val MAX_CIPHERTEXT_BYTES: Int = 64 * 1024 * 1024
    const val MAX_IV_BYTES: Int = 0xFFFF

    private val MAGIC: ByteArray = "DFTBSEC1".toByteArray(Charsets.US_ASCII)

    fun encode(blob: VaultBlob): ByteArray {
        require(blob.iv.size in 1..MAX_IV_BYTES) { "IV length ${blob.iv.size} out of range" }
        require(blob.ciphertext.size in 0..MAX_CIPHERTEXT_BYTES) { "ciphertext length ${blob.ciphertext.size} out of range" }
        val total = HEADER_SIZE + 2 + blob.iv.size + 4 + blob.ciphertext.size
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC)
        buffer.putShort(VERSION.toShort())
        buffer.put(blob.kind.ordinal.toByte())
        buffer.putLong(blob.createdAt)
        buffer.putLong(blob.updatedAt)
        buffer.putShort(blob.iv.size.toShort())
        buffer.put(blob.iv)
        buffer.putInt(blob.ciphertext.size)
        buffer.put(blob.ciphertext)
        return buffer.array()
    }

    /** Parses only the fixed-size prefix; [bytes] may be the first [HEADER_SIZE] bytes of a file or the whole file. */
    fun decodeHeader(bytes: ByteArray): VaultBlobHeader {
        if (bytes.size < HEADER_SIZE) throw corrupt("file is ${bytes.size} bytes, shorter than the $HEADER_SIZE-byte header")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return readHeader(buffer)
    }

    fun decode(bytes: ByteArray): VaultBlob {
        if (bytes.size < HEADER_SIZE + 2 + 4) throw corrupt("file is ${bytes.size} bytes, too short to hold a payload")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val header = readHeader(buffer)

        val ivLength = buffer.short.toInt() and 0xFFFF
        if (ivLength == 0) throw corrupt("IV length is zero")
        if (buffer.remaining() < ivLength + 4) throw corrupt("truncated before the end of the IV")
        val iv = ByteArray(ivLength).also { buffer.get(it) }

        val ciphertextLength = buffer.int.toLong() and 0xFFFF_FFFFL
        if (ciphertextLength > MAX_CIPHERTEXT_BYTES) throw corrupt("ciphertext length $ciphertextLength exceeds limit")
        if (buffer.remaining() < ciphertextLength) throw corrupt("truncated: expected $ciphertextLength ciphertext bytes, found ${buffer.remaining()}")
        if (buffer.remaining() > ciphertextLength) throw corrupt("${buffer.remaining() - ciphertextLength} trailing bytes after ciphertext")
        val ciphertext = ByteArray(ciphertextLength.toInt()).also { buffer.get(it) }

        return VaultBlob(
            kind = header.kind,
            createdAt = header.createdAt,
            updatedAt = header.updatedAt,
            iv = iv,
            ciphertext = ciphertext,
        )
    }

    private fun readHeader(buffer: ByteBuffer): VaultBlobHeader {
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC)) throw corrupt("bad magic")
        val version = buffer.short.toInt() and 0xFFFF
        if (version != VERSION) throw corrupt("unsupported version $version (expected $VERSION)")
        val kindOrdinal = buffer.get().toInt() and 0xFF
        val kind = SecretKind.fromOrdinal(kindOrdinal) ?: throw corrupt("unknown secret kind $kindOrdinal")
        val createdAt = buffer.long
        val updatedAt = buffer.long
        return VaultBlobHeader(version = version, kind = kind, createdAt = createdAt, updatedAt = updatedAt)
    }

    private fun corrupt(detail: String) = VaultException("Corrupt vault blob: $detail", reason = VaultException.Reason.CORRUPT_BLOB)
}
