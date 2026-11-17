package ca.devdcs.backup.data.security

/**
 * Output of one authenticated encryption: the nonce the cipher chose plus ciphertext with the
 * GCM tag appended. Both are opaque bytes to everything but [VaultCipher].
 */
class EncryptedBlob(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

/**
 * Authenticated encryption primitive the vault is built on. Abstracted so the file format and the
 * vault logic can be exercised in plain JVM tests with a deterministic fake, while production
 * binds the Android Keystore-backed [KeystoreCipher].
 *
 * Implementations must be thread-safe and must bind [aad] into the authentication tag so a blob
 * cannot be moved from one ref to another without detection.
 */
interface VaultCipher {

    fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedBlob

    /** @throws ca.devdcs.backup.domain.security.VaultException on tag mismatch or key problems. */
    fun decrypt(blob: EncryptedBlob, aad: ByteArray): ByteArray

    /**
     * Reports whether the master key already exists, querying the backing keystore WITHOUT
     * generating one. Lets the vault distinguish a genuine first run (no key, no entries) from a
     * lost key (no key, but encrypted entries still on disk) before any operation would silently
     * mint a fresh key.
     */
    fun masterKeyExists(): Boolean
}
