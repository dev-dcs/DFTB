package ca.devdcs.backup.data.security

import ca.devdcs.backup.domain.security.VaultException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VaultRefCodecTest {

    @Test
    fun roundTripsAsciiAndUnicode() {
        for (ref in listOf("dest-1", "sshkey-6f1c2d3e-aaaa-bbbb-cccc-000000000001", "clé/été ☃", "A", "a", "with space", "..")) {
            assertEquals(ref, VaultRefCodec.fromFileName(VaultRefCodec.toFileName(ref)))
        }
    }

    @Test
    fun fileNamesAreLowercaseHexWithExtension() {
        val name = VaultRefCodec.toFileName("AB")
        assertEquals("4142.dftbsec", name)
        assertTrue(name.substringBefore('.').all { it in "0123456789abcdef" })
    }

    @Test
    fun caseDifferentRefsNeverCollideEvenCaseInsensitively() {
        val upper = VaultRefCodec.toFileName("Dest")
        val lower = VaultRefCodec.toFileName("dest")
        assertTrue(!upper.equals(lower, ignoreCase = true))
    }

    @Test
    fun ignoresForeignFiles() {
        assertNull(VaultRefCodec.fromFileName("4142.dftbsec.tmp"))
        assertNull(VaultRefCodec.fromFileName("readme.txt"))
        assertNull(VaultRefCodec.fromFileName(".dftbsec"))
        assertNull(VaultRefCodec.fromFileName("abc.dftbsec"))
        assertNull(VaultRefCodec.fromFileName("zz.dftbsec"))
        assertNull(VaultRefCodec.fromFileName("4A.dftbsec"))
    }

    @Test
    fun rejectsEmptyAndOversizedRefs() {
        assertInvalid("")
        assertInvalid("x".repeat(VaultRefCodec.MAX_REF_BYTES + 1))
        assertEquals(VaultRefCodec.MAX_REF_BYTES * 2 + VaultRefCodec.FILE_EXTENSION.length, VaultRefCodec.toFileName("x".repeat(VaultRefCodec.MAX_REF_BYTES)).length)
        assertTrue(VaultRefCodec.toFileName("x".repeat(VaultRefCodec.MAX_REF_BYTES)).length <= 255)
    }

    private fun assertInvalid(ref: String) {
        try {
            VaultRefCodec.toFileName(ref)
            fail("expected VaultException")
        } catch (e: VaultException) {
            assertEquals(VaultException.Reason.INVALID_REF, e.reason)
        }
    }
}
