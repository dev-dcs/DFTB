package ca.devdcs.backup.data.security

import ca.devdcs.backup.domain.security.VaultException
import java.security.MessageDigest

/**
 * Deterministic stand-in for the Keystore cipher. Not secure, only shaped like AES-GCM: a 12-byte
 * counter IV, a keystream derived from (key, iv) by SHA-256, and a 16-byte "tag" over
 * (key, iv, aad, plaintext) so tampering and wrong-AAD are detected exactly as in production.
 */
class FakeVaultCipher(private val key: ByteArray = "fake-vault-master-key".toByteArray()) : VaultCipher {

    var encryptCalls: Int = 0
        private set
    var decryptCalls: Int = 0
        private set

    private var counter: Long = 0

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedBlob {
        encryptCalls++
        val iv = ByteArray(12)
        var c = ++counter
        for (i in 11 downTo 4) {
            iv[i] = (c and 0xFF).toByte()
            c = c ushr 8
        }
        val body = xorStream(plaintext, iv)
        val tag = tag(iv, aad, plaintext)
        return EncryptedBlob(iv, body + tag)
    }

    override fun decrypt(blob: EncryptedBlob, aad: ByteArray): ByteArray {
        decryptCalls++
        if (blob.ciphertext.size < TAG_SIZE) throw VaultException("short ciphertext", reason = VaultException.Reason.INTEGRITY_FAILURE)
        val body = blob.ciphertext.copyOfRange(0, blob.ciphertext.size - TAG_SIZE)
        val tag = blob.ciphertext.copyOfRange(blob.ciphertext.size - TAG_SIZE, blob.ciphertext.size)
        val plaintext = xorStream(body, blob.iv)
        if (!MessageDigest.isEqual(tag, tag(blob.iv, aad, plaintext))) {
            throw VaultException("tag mismatch", reason = VaultException.Reason.INTEGRITY_FAILURE)
        }
        return plaintext
    }

    override fun masterKeyExists(): Boolean = true

    private fun xorStream(input: ByteArray, iv: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var block = 0
        var offset = 0
        while (offset < input.size) {
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(key)
                update(iv)
                update(block.toByte())
            }.digest()
            for (i in digest.indices) {
                if (offset + i >= input.size) break
                out[offset + i] = (input[offset + i].toInt() xor digest[i].toInt()).toByte()
            }
            offset += digest.size
            block++
        }
        return out
    }

    private fun tag(iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply {
            update(key)
            update(iv)
            update(byteArrayOf(aad.size.toByte()))
            update(aad)
            update(plaintext)
        }.digest().copyOf(TAG_SIZE)

    private companion object {
        const val TAG_SIZE = 16
    }
}
