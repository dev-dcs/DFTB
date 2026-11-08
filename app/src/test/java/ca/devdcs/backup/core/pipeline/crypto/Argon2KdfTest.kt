package ca.devdcs.backup.core.pipeline.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Argon2KdfTest {

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** RFC 9106 section 5.3 (Argon2id) test vector. */
    @Test
    fun rfc9106Argon2idVector() {
        val tag = Argon2Kdf.derive(
            password = ByteArray(32) { 0x01 },
            salt = ByteArray(16) { 0x02 },
            settings = Argon2Settings(memoryKiB = 32, iterations = 3, parallelism = 4),
            outLength = 32,
            secret = ByteArray(8) { 0x03 },
            associatedData = ByteArray(12) { 0x04 },
        )
        assertArrayEquals(hex("0d 64 0d f5 8d 78 76 6c 08 c0 37 a3 4a 8b 53 c9 d0 1e f0 45 2d 75 b6 5e b5 25 20 e9 6b 01 e6 59"), tag)
    }

    @Test
    fun passphraseDerivationIsDeterministicAndSaltSensitive() {
        val s = Argon2Settings(1024, 1, 1)
        val salt = ByteArray(16) { it.toByte() }
        val a = Argon2Kdf.deriveFromPassphrase("pässwörd".toCharArray(), salt, s)
        val b = Argon2Kdf.deriveFromPassphrase("pässwörd".toCharArray(), salt, s)
        assertArrayEquals(a, b)
        val c = Argon2Kdf.deriveFromPassphrase("pässwörd".toCharArray(), salt.copyOf().also { it[0] = 9 }, s)
        assertFalse(a.contentEquals(c))
        // Our passphrase path equals the raw path over strict UTF-8 bytes.
        assertArrayEquals(a, Argon2Kdf.derive("pässwörd".toByteArray(Charsets.UTF_8), salt, s))
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfRangeSettingsRejected() {
        Argon2Settings(memoryKiB = 4, iterations = 1, parallelism = 1)
    }
}
