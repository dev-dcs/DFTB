package ca.devdcs.backup.core.pipeline.crypto

import ca.devdcs.backup.core.pipeline.CipherKeySpec
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
import kotlin.random.Random

class PreparedKeyTest {

    private val keys = VaultKeyStore(InMemoryVault())
    private val cipher = AesGcmStreamCipher(keys, Dispatchers.IO)
        .withSettings(StreamCipherSettings(argon2 = Argon2Settings(1024, 1, 1), chunkSize = 4096))
    private fun pass() = CipherKeySpec.Passphrase("one argon2 per job".toCharArray())

    private suspend fun enc(key: PreparedKey, plain: ByteArray): ByteArray {
        val sink = ByteArrayOutputStream()
        cipher.encrypt(sink, key).use { it.write(plain) }
        return sink.toByteArray()
    }

    @Test
    fun streamsFromOnePreparedKeyDecryptIndependentlyWithPassphrase() = runBlocking {
        val a = Random(1).nextBytes(10_000)
        val b = Random(2).nextBytes(5_000)
        val (ca, cb, desc) = cipher.prepare(pass()).use { k -> Triple(enc(k, a), enc(k, b), cipher.describe(k)) }
        // Plain per-stream decrypt with the passphrase still works.
        assertArrayEquals(a, cipher.decrypt(ByteArrayInputStream(ca), pass()).use { it.readBytes() })
        assertArrayEquals(b, cipher.decrypt(ByteArrayInputStream(cb), pass()).use { it.readBytes() })

        val ha = cipher.inspect(ByteArrayInputStream(ca))
        val hb = cipher.inspect(ByteArrayInputStream(cb))
        // Same job-level KDF (salt + params), distinct per-stream salt and nonce prefix.
        assertEquals(ha.kdf, hb.kdf)
        assertFalse(ha.salt.contentEquals(hb.salt))
        assertFalse(ha.keyCheck.contentEquals(hb.keyCheck))
        assertEquals(desc, ha.toEncryptionHeader())
        assertTrue(StreamHeader.P_KDF_SALT in desc.params)
    }

    @Test
    fun streamKeysAreDistinct() = runBlocking {
        cipher.prepare(pass()).use { k ->
            val s1 = ByteArray(32) { 1 }
            val s2 = ByteArray(32) { 2 }
            val d1 = k.withMaster { AesGcmStreamCipher.deriveFromMaster(it, s1) }
            val d2 = k.withMaster { AesGcmStreamCipher.deriveFromMaster(it, s2) }
            assertFalse(d1.streamKey.contentEquals(d2.streamKey))
            // Same plaintext under one PreparedKey never yields the same ciphertext.
            val p = ByteArray(100)
            val c1 = enc(k, p)
            val c2 = enc(k, p)
            val h = StreamHeader.read(ByteArrayInputStream(c1)).bytes.size
            assertFalse(c1.copyOfRange(h, c1.size).contentEquals(c2.copyOfRange(h, c2.size)))
        }
    }

    @Test
    fun decryptCacheDerivesOncePerJobAndIsBounded() = runBlocking {
        val jobs = (0 until 6).map { i ->
            cipher.prepare(pass()).use { k -> List(3) { j -> enc(k, byteArrayOf(i.toByte(), j.toByte())) } }
        }
        DecryptKeyCache(pass(), capacity = 4).use { cache ->
            for ((i, streams) in jobs.withIndex()) for ((j, ct) in streams.withIndex()) {
                val back = cipher.decrypt(ByteArrayInputStream(ct), cache).use { it.readBytes() }
                assertArrayEquals(byteArrayOf(i.toByte(), j.toByte()), back)
            }
            assertEquals(4, cache.size)
        }
        // Wrong passphrase through a cache is still detected.
        DecryptKeyCache(CipherKeySpec.Passphrase("nope".toCharArray())).use { cache ->
            try { cipher.decrypt(ByteArrayInputStream(jobs[0][0]), cache); fail() } catch (e: CipherIntegrityException) {
                assertEquals(CipherIntegrityException.Reason.WRONG_KEY, e.reason)
            }
        }
    }

    @Test
    fun vaultPreparedKeyAndClosedKeyRejected() = runBlocking {
        val spec = CipherKeySpec.VaultKey(cipher.createVaultKey())
        val k = cipher.prepare(spec)
        val ct = enc(k, byteArrayOf(1, 2, 3))
        assertEquals("vault", cipher.describe(k).params["kdf"])
        k.close()
        assertArrayEquals(byteArrayOf(1, 2, 3), cipher.decrypt(ByteArrayInputStream(ct), spec).use { it.readBytes() })
        try { enc(k, ByteArray(1)); fail() } catch (_: IllegalStateException) {}
    }
}
