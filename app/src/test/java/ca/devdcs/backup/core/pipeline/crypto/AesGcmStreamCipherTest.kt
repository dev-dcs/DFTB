package ca.devdcs.backup.core.pipeline.crypto

import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.domain.security.Secret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

class AesGcmStreamCipherTest {

    private val vault = InMemoryVault()
    private val keys = VaultKeyStore(vault)
    private val fastArgon = Argon2Settings(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val small = 4096
    private val cipher = AesGcmStreamCipher(keys, Dispatchers.IO).withSettings(StreamCipherSettings(argon2 = fastArgon, chunkSize = small))
    private val pass = CipherKeySpec.Passphrase("correct horse battery staple".toCharArray())

    private fun data(n: Int) = Random(n).nextBytes(n)

    private fun encrypt(c: AesGcmStreamCipher, plain: ByteArray, key: CipherKeySpec, writeStep: Int = 1000): ByteArray = runBlocking {
        val sink = ByteArrayOutputStream()
        c.encrypt(sink, key).use { out ->
            var i = 0
            while (i < plain.size) {
                val n = minOf(writeStep, plain.size - i)
                out.write(plain, i, n)
                i += n
            }
        }
        sink.toByteArray()
    }

    private fun decrypt(c: AesGcmStreamCipher, ct: ByteArray, key: CipherKeySpec): ByteArray = runBlocking {
        c.decrypt(ByteArrayInputStream(ct), key).use { it.readBytes() }
    }

    private fun expectIntegrity(ct: ByteArray, key: CipherKeySpec = pass, vararg reasons: CipherIntegrityException.Reason) {
        try {
            decrypt(cipher, ct, key)
            fail("tampering not detected")
        } catch (e: CipherIntegrityException) {
            if (reasons.isNotEmpty()) assertTrue("unexpected ${e.reason}", e.reason in reasons)
        }
    }

    private fun headerLength(ct: ByteArray) = StreamHeader.read(ByteArrayInputStream(ct)).bytes.size

    @Test
    fun roundTripAcrossChunkBoundaries() {
        for (n in listOf(0, 1, small - 1, small, small + 1, 2 * small, 3 * small + 7, 50_000)) {
            val plain = data(n)
            for (step in listOf(1, 999, small, 70_000)) {
                if (step == 1 && n > 10_000) continue
                val ct = encrypt(cipher, plain, pass, step)
                // Exactly ceil-or-extra chunks: full chunks plus one short final chunk.
                val chunks = n / small + 1
                assertEquals(headerLength(ct) + n + chunks * 16, ct.size)
                assertArrayEquals("n=$n step=$step", plain, decrypt(cipher, ct, pass))
            }
        }
    }

    @Test
    fun roundTripMultiMiBWithDefaultChunkAndSingleByteReads() = runBlocking {
        val c = AesGcmStreamCipher(keys, Dispatchers.IO).withSettings(StreamCipherSettings(argon2 = fastArgon))
        assertEquals(1 shl 20, c.settings.chunkSize)
        val plain = data(3 * (1 shl 20) + 12345)
        val ct = encrypt(c, plain, pass, 256 * 1024)
        val back = c.decrypt(ByteArrayInputStream(ct), pass).use { it.readBytes() }
        assertArrayEquals(plain, back)
        // Byte-wise reads across a chunk boundary.
        c.decrypt(ByteArrayInputStream(ct), pass).use { s ->
            val buf = ByteArray(1 shl 20)
            var off = 0
            while (off < buf.size) { buf[off] = s.read().toByte(); off++ }
            assertEquals(plain[1 shl 20].toInt() and 0xFF, s.read())
            assertArrayEquals(plain.copyOf(1 shl 20), buf)
        }
    }

    @Test
    fun exactMultipleOfChunkWritesEmptyFinalChunk() {
        val plain = data(2 * small)
        val ct = encrypt(cipher, plain, pass)
        assertEquals(headerLength(ct) + 2 * (small + 16) + 16, ct.size)
        assertArrayEquals(plain, decrypt(cipher, ct, pass))
    }

    @Test
    fun flippedByteDetectedEverywhere() {
        val plain = data(3 * small + 100)
        val ct = encrypt(cipher, plain, pass)
        val positions = listOf(0, 9, 12, headerLength(ct) - 1, headerLength(ct), headerLength(ct) + small + 5, ct.size - 1)
        for (p in positions) {
            val t = ct.copyOf()
            t[p] = (t[p].toInt() xor 0x01).toByte()
            expectIntegrity(t)
        }
    }

    @Test
    fun droppedSwappedTruncatedAppendedDetected() {
        val plain = data(4 * small + 100)
        val ct = encrypt(cipher, plain, pass)
        val h = headerLength(ct)
        val c = small + 16
        fun chunk(i: Int) = ct.copyOfRange(h + i * c, minOf(h + (i + 1) * c, ct.size))
        val header = ct.copyOf(h)

        // Drop a middle chunk.
        expectIntegrity(header + chunk(0) + chunk(2) + chunk(3) + chunk(4), reasons = arrayOf(CipherIntegrityException.Reason.TAMPERED))
        // Swap two chunks.
        expectIntegrity(header + chunk(1) + chunk(0) + chunk(2) + chunk(3) + chunk(4), reasons = arrayOf(CipherIntegrityException.Reason.TAMPERED))
        // Drop the final chunk: stream ends on a chunk boundary.
        expectIntegrity(header + chunk(0) + chunk(1) + chunk(2) + chunk(3), reasons = arrayOf(CipherIntegrityException.Reason.TRUNCATED))
        // Truncate mid-chunk and inside the last tag.
        expectIntegrity(ct.copyOf(h + c + 10))
        expectIntegrity(ct.copyOf(ct.size - 1))
        // Header only.
        expectIntegrity(header, reasons = arrayOf(CipherIntegrityException.Reason.TRUNCATED))
        // Append bytes / a duplicated final chunk.
        expectIntegrity(ct + byteArrayOf(0))
        expectIntegrity(ct + chunk(4))
        // Final chunk replaced by an earlier full chunk re-labelled: still rejected.
        expectIntegrity(header + chunk(0) + chunk(1) + chunk(2) + chunk(3) + chunk(3))
    }

    @Test
    fun appendAfterExactMultipleIsTrailingData() {
        val plain = data(small)
        val ct = encrypt(cipher, plain, pass)
        // Final chunk is the 16-byte empty tag; extra bytes after it must be detected.
        expectIntegrity(ct + ByteArray(5))
    }

    @Test
    fun wrongPassphraseDetectedBeforeAnyData() = runBlocking {
        val ct = encrypt(cipher, data(10_000), pass)
        try {
            cipher.decrypt(ByteArrayInputStream(ct), CipherKeySpec.Passphrase("Correct horse battery staple".toCharArray()))
            fail("wrong passphrase accepted")
        } catch (e: CipherIntegrityException) {
            assertEquals(CipherIntegrityException.Reason.WRONG_KEY, e.reason)
        }
    }

    @Test
    fun vaultKeyRoundTripAndKeyErrors() = runBlocking {
        val ref = cipher.createVaultKey()
        val stored = vault.entries[ref] as Secret.EncryptionKey
        assertEquals(32, java.util.Base64.getDecoder().decode(stored.keyBase64).size)
        val key = CipherKeySpec.VaultKey(ref)
        val plain = data(9000)
        val ct = encrypt(cipher, plain, key)
        assertArrayEquals(plain, decrypt(cipher, ct, key))

        // Another vault key: wrong key.
        val other = CipherKeySpec.VaultKey(cipher.createVaultKey())
        try { decrypt(cipher, ct, other); fail() } catch (e: CipherIntegrityException) {
            assertEquals(CipherIntegrityException.Reason.WRONG_KEY, e.reason)
        }
        // Passphrase for a vault stream.
        try { decrypt(cipher, ct, pass); fail() } catch (e: CipherIntegrityException) {
            assertEquals(CipherIntegrityException.Reason.KEY_TYPE_MISMATCH, e.reason)
        }
        // Missing and wrong-type entries.
        try { decrypt(cipher, ct, CipherKeySpec.VaultKey("enckey-missing")); fail() } catch (e: CipherIntegrityException) {
            fail("expected key-unavailable, got $e")
        } catch (e: CipherKeyUnavailableException) {
            // Header names a different ref, but the supplied ref is what is resolved.
            assertEquals(CipherKeyUnavailableException.Reason.NOT_FOUND, e.reason)
        }
        vault.store("pw", Secret.Password("u", "p"))
        try { cipher.encrypt(ByteArrayOutputStream(), CipherKeySpec.VaultKey("pw")); fail() } catch (e: CipherKeyUnavailableException) {
            assertEquals(CipherKeyUnavailableException.Reason.INVALID_ENTRY, e.reason)
        }
    }

    @Test
    fun headerParseAndDescribe() = runBlocking {
        val ct = encrypt(cipher, data(10), pass)
        assertArrayEquals("DFTBENC1".toByteArray(), ct.copyOf(8))
        val header = cipher.inspect(ByteArrayInputStream(ct))
        assertEquals(StreamHeader.VERSION, header.version)
        assertEquals(small, header.chunkSize)
        assertEquals(fastArgon, (header.kdf as StreamHeader.Kdf.Argon2id).settings)
        assertEquals(StreamHeader.DEFAULT_SALT, header.kdf.kdfSalt.size)
        assertEquals(StreamHeader.DEFAULT_SALT, header.salt.size)
        // encode() reproduces the exact bytes.
        assertArrayEquals(ct.copyOf(headerLength(ct)), header.encode())

        val described = cipher.describe(pass)
        assertEquals("dftb-aesgcm-v1", described.scheme)
        // describe(spec) is the stream's description minus the per-job Argon2 salt.
        assertEquals(header.toEncryptionHeader().params - StreamHeader.P_KDF_SALT, described.params)
        assertFalse(StreamHeader.P_KDF_SALT in described.params)
        assertEquals("argon2id", described.params["kdf"])
        assertEquals("1024", described.params["argon2.memoryKiB"])
        assertFalse(described.params.values.any { it.contains("horse") })

        val vaultDesc = cipher.describe(CipherKeySpec.VaultKey("enckey-x"))
        assertEquals("vault", vaultDesc.params["kdf"])
        assertEquals("enckey-x", vaultDesc.params["vaultRef"])
    }

    @Test
    fun badHeadersRejected() {
        val ct = encrypt(cipher, data(10), pass)
        expectIntegrity("NOTDFTB!".toByteArray() + ct.copyOfRange(8, ct.size), reasons = arrayOf(CipherIntegrityException.Reason.BAD_HEADER))
        val v2 = ct.copyOf().also { it[8] = 2 }
        expectIntegrity(v2, reasons = arrayOf(CipherIntegrityException.Reason.UNSUPPORTED_VERSION))
        // Hostile Argon2 memory (4 GiB) is refused instead of allocated.
        val huge = ct.copyOf().also { for (i in 10..13) it[i] = 0xFF.toByte() }
        expectIntegrity(huge, reasons = arrayOf(CipherIntegrityException.Reason.BAD_HEADER))
        // 1 GiB (just above the 256 MiB cap) is also refused.
        val gib = ct.copyOf().also { it[10] = 0; it[11] = 0x10; it[12] = 0; it[13] = 0 }
        expectIntegrity(gib, reasons = arrayOf(CipherIntegrityException.Reason.BAD_HEADER))
        expectIntegrity(ct.copyOf(20), reasons = arrayOf(CipherIntegrityException.Reason.BAD_HEADER))
        expectIntegrity(ByteArray(0), reasons = arrayOf(CipherIntegrityException.Reason.BAD_HEADER))
    }

    @Test
    fun abortedStreamNeverDecryptsAsComplete() = runBlocking {
        val sink = ByteArrayOutputStream()
        val out = cipher.encrypt(sink, pass)
        out.write(data(small * 2 + 10))
        out.abort()
        expectIntegrity(sink.toByteArray(), reasons = arrayOf(CipherIntegrityException.Reason.TRUNCATED))
    }

    @Test
    fun failureIsSticky() = runBlocking {
        val ct = encrypt(cipher, data(3 * small), pass)
        val t = ct.copyOf().also { val p = headerLength(ct) + small + 16 + 3; it[p] = (it[p] + 1).toByte() }
        val s = cipher.decrypt(ByteArrayInputStream(t), pass)
        val buf = ByteArray(small)
        var total = 0
        try {
            while (true) { val n = s.read(buf); if (n < 0) break; total += n }
            fail()
        } catch (_: CipherIntegrityException) {
        }
        assertEquals(small, total)
        try { s.read(buf); fail() } catch (e: IOException) { assertTrue(e is CipherIntegrityException) }
    }

    @Test
    fun defaultSettingsArePersistedAndWork() = runBlocking {
        val c = AesGcmStreamCipher(keys, Dispatchers.IO)
        assertEquals(Argon2Settings(65536, 3, 1), c.settings.argon2)
        val plain = data(1000)
        val ct = encrypt(c, plain, pass)
        // A cipher configured differently still decrypts: parameters come from the header.
        assertArrayEquals(plain, decrypt(cipher, ct, pass))
    }
}
