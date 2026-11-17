package ca.devdcs.backup.data.security

import ca.devdcs.backup.domain.security.SecretKind
import ca.devdcs.backup.domain.security.VaultException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VaultBlobCodecTest {

    private val iv = ByteArray(12) { (it + 1).toByte() }
    private val ciphertext = ByteArray(40) { (0xA0 + it).toByte() }

    private fun sample() = VaultBlob(
        kind = SecretKind.OAUTH_TOKEN,
        createdAt = 1_700_000_000_123L,
        updatedAt = 1_700_000_999_456L,
        iv = iv,
        ciphertext = ciphertext,
    )

    @Test
    fun roundTripPreservesEveryField() {
        val decoded = VaultBlobCodec.decode(VaultBlobCodec.encode(sample()))
        assertEquals(SecretKind.OAUTH_TOKEN, decoded.kind)
        assertEquals(1_700_000_000_123L, decoded.createdAt)
        assertEquals(1_700_000_999_456L, decoded.updatedAt)
        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
        assertEquals(VaultBlobHeader(1, SecretKind.OAUTH_TOKEN, 1_700_000_000_123L, 1_700_000_999_456L), decoded.header)
    }

    @Test
    fun layoutMatchesSpecificationByteForByte() {
        val bytes = VaultBlobCodec.encode(sample())
        assertEquals(27 + 2 + 12 + 4 + 40, bytes.size)
        assertEquals("DFTBSEC1", String(bytes, 0, 8, Charsets.US_ASCII))
        assertEquals(0, bytes[8].toInt()); assertEquals(1, bytes[9].toInt()) // u16 version = 1
        assertEquals(SecretKind.OAUTH_TOKEN.ordinal, bytes[10].toInt() and 0xFF)
        assertEquals(1_700_000_000_123L, readLong(bytes, 11))
        assertEquals(1_700_000_999_456L, readLong(bytes, 19))
        assertEquals(0, bytes[27].toInt()); assertEquals(12, bytes[28].toInt()) // u16 ivLength
        assertArrayEquals(iv, bytes.copyOfRange(29, 41))
        assertEquals(40, (bytes[41].toInt() shl 24) or (bytes[42].toInt() shl 16) or (bytes[43].toInt() shl 8) or bytes[44].toInt())
        assertArrayEquals(ciphertext, bytes.copyOfRange(45, bytes.size))
    }

    @Test
    fun decodeHeaderAcceptsExactlyTheHeaderPrefix() {
        val bytes = VaultBlobCodec.encode(sample())
        val header = VaultBlobCodec.decodeHeader(bytes.copyOf(VaultBlobCodec.HEADER_SIZE))
        assertEquals(SecretKind.OAUTH_TOKEN, header.kind)
        assertEquals(1_700_000_000_123L, header.createdAt)
        assertEquals(1_700_000_999_456L, header.updatedAt)
        assertEquals(1, header.version)
    }

    @Test
    fun everyKindOrdinalSurvives() {
        for (kind in SecretKind.entries) {
            val decoded = VaultBlobCodec.decode(VaultBlobCodec.encode(VaultBlob(kind, 1, 2, iv, ciphertext)))
            assertEquals(kind, decoded.kind)
        }
    }

    @Test
    fun emptyCiphertextIsAllowed() {
        val decoded = VaultBlobCodec.decode(VaultBlobCodec.encode(VaultBlob(SecretKind.GENERIC, 0, 0, iv, ByteArray(0))))
        assertEquals(0, decoded.ciphertext.size)
    }

    @Test
    fun rejectsBadMagic() {
        val bytes = VaultBlobCodec.encode(sample())
        bytes[0] = 'X'.code.toByte()
        assertCorrupt(bytes, "magic")
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val bytes = VaultBlobCodec.encode(sample())
        bytes[9] = 2
        assertCorrupt(bytes, "version")
    }

    @Test
    fun rejectsUnknownKind() {
        val bytes = VaultBlobCodec.encode(sample())
        bytes[10] = 0x7F
        assertCorrupt(bytes, "kind")
    }

    @Test
    fun rejectsTruncatedCiphertext() {
        val bytes = VaultBlobCodec.encode(sample())
        assertCorrupt(bytes.copyOf(bytes.size - 5), "truncated")
    }

    @Test
    fun rejectsTrailingGarbage() {
        val bytes = VaultBlobCodec.encode(sample()) + byteArrayOf(1, 2, 3)
        assertCorrupt(bytes, "trailing")
    }

    @Test
    fun rejectsFileShorterThanHeader() {
        assertCorrupt(ByteArray(10), "short")
        try {
            VaultBlobCodec.decodeHeader(ByteArray(10))
            fail("expected VaultException")
        } catch (e: VaultException) {
            assertEquals(VaultException.Reason.CORRUPT_BLOB, e.reason)
        }
    }

    @Test
    fun rejectsZeroLengthIv() {
        val bytes = VaultBlobCodec.encode(sample())
        bytes[28] = 0
        assertCorrupt(bytes, "IV")
    }

    @Test
    fun rejectsOversizedCiphertextLengthWithoutAllocating() {
        val bytes = VaultBlobCodec.encode(sample())
        bytes[41] = 0xFF.toByte(); bytes[42] = 0xFF.toByte(); bytes[43] = 0xFF.toByte(); bytes[44] = 0xFF.toByte()
        assertCorrupt(bytes, "limit")
    }

    @Test(expected = IllegalArgumentException::class)
    fun encodeRefusesEmptyIv() {
        VaultBlobCodec.encode(VaultBlob(SecretKind.GENERIC, 0, 0, ByteArray(0), ciphertext))
    }

    private fun assertCorrupt(bytes: ByteArray, messageFragment: String) {
        try {
            VaultBlobCodec.decode(bytes)
            fail("expected VaultException for $messageFragment")
        } catch (e: VaultException) {
            assertEquals(VaultException.Reason.CORRUPT_BLOB, e.reason)
            assertTrue("message '${e.message}' should mention '$messageFragment'", e.message!!.contains(messageFragment, ignoreCase = true))
        }
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }
}
