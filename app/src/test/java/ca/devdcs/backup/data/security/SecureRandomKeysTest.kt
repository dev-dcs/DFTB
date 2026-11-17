package ca.devdcs.backup.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SecureRandomKeysTest {

    @Test
    fun aesKeyHasRequestedLengthAndIsRandom() {
        for (bits in listOf(128, 192, 256)) {
            val decoded = Base64.getDecoder().decode(SecureRandomKeys.generateAesKeyBase64(bits))
            assertEquals(bits / 8, decoded.size)
        }
        assertNotEquals(SecureRandomKeys.generateAesKeyBase64(), SecureRandomKeys.generateAesKeyBase64())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aesKeyRejectsNonAesSizes() {
        SecureRandomKeys.generateAesKeyBase64(200)
    }

    @Test
    fun aesKeySecretCarriesAlgorithmName() {
        val secret = SecureRandomKeys.newAesKeySecret()
        assertEquals("AES-256-GCM", secret.algorithm)
        assertEquals(32, Base64.getDecoder().decode(secret.keyBase64).size)
    }

    @Test
    fun passwordUsesOnlyTheAlphabet() {
        val alphabet = "abc"
        val password = SecureRandomKeys.generatePassword(500, alphabet)
        assertEquals(500, password.length)
        assertTrue(password.all { it in alphabet })
        assertTrue(alphabet.all { c -> password.contains(c) })
    }

    @Test
    fun defaultAlphabetOmitsConfusableGlyphs() {
        for (c in "0O1lI") assertTrue(c !in SecureRandomKeys.READABLE_ALPHABET)
        assertEquals(SecureRandomKeys.READABLE_ALPHABET.length, SecureRandomKeys.READABLE_ALPHABET.toSet().size)
        assertEquals(SecureRandomKeys.FULL_ALPHABET.length, SecureRandomKeys.FULL_ALPHABET.toSet().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun passwordRejectsZeroLength() {
        SecureRandomKeys.generatePassword(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun passwordRejectsDegenerateAlphabet() {
        SecureRandomKeys.generatePassword(8, "a")
    }
}
