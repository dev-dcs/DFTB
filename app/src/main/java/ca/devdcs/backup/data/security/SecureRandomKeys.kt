package ca.devdcs.backup.data.security

import ca.devdcs.backup.domain.security.Secret
import java.security.SecureRandom
import java.util.Base64

/**
 * Generators for the secrets DFTB creates itself: archive encryption keys and the throwaway
 * passwords offered when a user sets up a new remote. Centralised so every caller uses a CSPRNG
 * and unbiased sampling instead of ad-hoc `Random`.
 */
object SecureRandomKeys {

    /** Letters and digits minus the glyphs commonly confused when read aloud or typed from a screen. */
    const val READABLE_ALPHABET: String = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    /** Full printable-ASCII set (no space) for passwords that are only ever pasted, never read. */
    const val FULL_ALPHABET: String =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    private val random = SecureRandom()

    /** Raw random key material, [bits] must be a positive multiple of 8. */
    fun generateKeyBytes(bits: Int = 256): ByteArray {
        require(bits > 0 && bits % 8 == 0) { "key size must be a positive multiple of 8 bits, was $bits" }
        return ByteArray(bits / 8).also(random::nextBytes)
    }

    /** Standard (padded) base64 of a fresh AES key; the intermediate bytes are wiped. */
    fun generateAesKeyBase64(bits: Int = 256): String {
        require(bits == 128 || bits == 192 || bits == 256) { "AES key size must be 128, 192 or 256 bits, was $bits" }
        val key = generateKeyBytes(bits)
        try {
            return Base64.getEncoder().encodeToString(key)
        } finally {
            key.fill(0)
        }
    }

    /** Ready-to-store key secret for the app's own AES-GCM artifact encryption. */
    fun newAesKeySecret(bits: Int = 256): Secret.EncryptionKey =
        Secret.EncryptionKey(keyBase64 = generateAesKeyBase64(bits), algorithm = "AES-$bits-GCM")

    /**
     * Uniformly samples [length] characters from [alphabet]. `SecureRandom.nextInt(bound)` is
     * already rejection-sampled internally, so every character is equally likely.
     */
    fun generatePassword(length: Int, alphabet: String = READABLE_ALPHABET): String {
        require(length > 0) { "password length must be positive, was $length" }
        require(alphabet.length >= 2) { "alphabet needs at least two distinct characters" }
        val chars = CharArray(length) { alphabet[random.nextInt(alphabet.length)] }
        try {
            return String(chars)
        } finally {
            chars.fill('\u0000')
        }
    }
}
