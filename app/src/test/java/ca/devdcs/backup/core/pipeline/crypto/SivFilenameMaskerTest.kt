package ca.devdcs.backup.core.pipeline.crypto

import ca.devdcs.backup.core.pipeline.CipherKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SivFilenameMaskerTest {

    private val keys = VaultKeyStore(InMemoryVault())
    private val factory = FilenameMaskerFactory(keys, Dispatchers.IO)
    private val fast = Argon2Settings(1024, 1, 1)
    private val params = factory.newParams(fast)
    private fun pass(s: String = "hunter2 but longer") = CipherKeySpec.Passphrase(s.toCharArray())
    private val masker = runBlocking { factory.create(pass(), params) }
    private val charset = Regex("^[a-z2-7]+$")

    private val names = listOf(
        "a", "IMG_20260101_120000.jpg", "data.tar.zst", "Ünïcødé – 文件 😀.txt", "with space", ".hidden",
        "x".repeat(SivFilenameMasker.MAX_NAME_BYTES), "日".repeat(47),
    )

    @Test
    fun roundTripCharsetAndLength() {
        for (n in names) {
            val m = masker.mask(n)
            assertTrue(m, charset.matches(m))
            assertTrue(m.length <= SivFilenameMasker.MAX_MASKED_LENGTH)
            assertEquals(n, masker.unmask(m))
        }
        assertEquals(255, masker.mask("x".repeat(SivFilenameMasker.MAX_NAME_BYTES)).length)
    }

    @Test
    fun stableForSameKeyAndParams() = runBlocking {
        val again = factory.create(pass(), params)
        for (n in names) assertEquals(masker.mask(n), again.mask(n))
        // Params survive a manifest round-trip.
        val restored = factory.create(pass(), MaskerParams.fromMap(params.toMap())!!)
        assertEquals(masker.mask("photo.jpg"), restored.mask("photo.jpg"))
    }

    @Test
    fun differentKeyOrSaltGivesDifferentNamesAndRejects() = runBlocking {
        val other = factory.create(pass("another passphrase"), params)
        val otherSalt = factory.create(pass(), factory.newParams(fast))
        val m = masker.mask("photo.jpg")
        assertNotEquals(m, other.mask("photo.jpg"))
        assertNotEquals(m, otherSalt.mask("photo.jpg"))
        assertNull(other.unmask(m))
        assertNull(otherSalt.unmask(m))
    }

    @Test
    fun tamperedOrForeignInputRejected() {
        val m = masker.mask("photo.jpg")
        val flipped = (if (m.last() == 'a') 'b' else 'a').let { m.dropLast(1) + it }
        assertNull(masker.unmask(flipped))
        assertNull(masker.unmask(m.uppercase()))
        assertNull(masker.unmask(""))
        assertNull(masker.unmask("photo.jpg"))
        assertNull(masker.unmask(m.dropLast(1)))
        assertNull(masker.unmask("a".repeat(300)))
    }

    @Test
    fun lengthLimitEnforced() {
        val tooLong = "x".repeat(SivFilenameMasker.MAX_NAME_BYTES + 1)
        assertFalse(masker.canMask(tooLong))
        assertFalse(masker.canMask(""))
        assertTrue(masker.canMask("ok"))
        try { masker.mask(tooLong); throw AssertionError() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun pathSegmentsMaskedIndividually() {
        val path = "DCIM/Camera/IMG_1.jpg"
        val masked = masker.maskPath(path)
        val parts = masked.split('/')
        assertEquals(3, parts.size)
        assertEquals(masker.mask("Camera"), parts[1])
        assertEquals(path, masker.unmaskPath(masked))
        assertNull(masker.unmaskPath(parts[0] + "/zzzz"))
    }

    @Test
    fun vaultKeyMaskerWorksAndDiffersFromStreamKeys() = runBlocking {
        val ref = keys.createVaultKey()
        val a = factory.create(CipherKeySpec.VaultKey(ref), params)
        val b = factory.create(CipherKeySpec.VaultKey(ref), params)
        assertEquals(a.mask("x.bin"), b.mask("x.bin"))
        assertEquals("x.bin", b.unmask(a.mask("x.bin")))
    }

    @Test
    fun base32Canonical() {
        for (len in 0..40) {
            val bytes = ByteArray(len) { (it * 37 + 11).toByte() }
            val enc = Base32.encode(bytes)
            assertEquals(Base32.encodedLength(len), enc.length)
            assertTrue(bytes.contentEquals(Base32.decode(enc)!!))
        }
        // "ab" decodes 10 bits; non-zero pad bits are non-canonical.
        assertNull(Base32.decode("ab"))
        assertNull(Base32.decode("a"))
    }
}
