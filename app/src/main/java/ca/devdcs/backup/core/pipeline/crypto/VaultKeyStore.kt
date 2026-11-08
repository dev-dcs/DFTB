package ca.devdcs.backup.core.pipeline.crypto

import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.Secret
import ca.devdcs.backup.domain.security.VaultException
import kotlinx.coroutines.CancellationException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates and loads the random 256-bit keys behind [CipherKeySpec.VaultKey]. Keys are stored as
 * [Secret.EncryptionKey] (base64) so they share the vault's device-bound protection and never touch
 * Room or logs. Both the stream cipher and the filename masker resolve vault keys through here.
 */
@Singleton
class VaultKeyStore @Inject constructor(
    private val vault: CredentialVault,
) {

    /** Generates a fresh key, stores it, and returns the new vault ref (prefix "enckey"). */
    suspend fun createVaultKey(): String {
        val ref = vault.newRef(REF_PREFIX)
        val key = Secrets.randomBytes(KEY_BYTES)
        try {
            vault.store(ref, Secret.EncryptionKey(Base64.getEncoder().encodeToString(key), ALGORITHM_LABEL))
        } finally {
            key.fill(0)
        }
        return ref
    }

    /**
     * Returns a copy of the raw key; the caller must wipe it. Throws
     * [CipherKeyUnavailableException] when the ref is missing or not a usable key.
     */
    suspend fun loadKey(ref: String): ByteArray {
        val secret = try {
            vault.load(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultException) {
            throw CipherKeyUnavailableException(ref, CipherKeyUnavailableException.Reason.VAULT_ERROR, "Vault could not load key", e)
        } ?: throw CipherKeyUnavailableException(ref, CipherKeyUnavailableException.Reason.NOT_FOUND, "No vault entry for key ref")
        if (secret !is Secret.EncryptionKey) {
            throw CipherKeyUnavailableException(ref, CipherKeyUnavailableException.Reason.INVALID_ENTRY, "Vault entry is not an encryption key")
        }
        val bytes = try {
            Base64.getDecoder().decode(secret.keyBase64)
        } catch (e: IllegalArgumentException) {
            throw CipherKeyUnavailableException(ref, CipherKeyUnavailableException.Reason.INVALID_ENTRY, "Key is not valid base64", e)
        }
        if (bytes.size != KEY_BYTES) {
            bytes.fill(0)
            throw CipherKeyUnavailableException(ref, CipherKeyUnavailableException.Reason.INVALID_ENTRY, "Key has wrong length")
        }
        return bytes
    }

    companion object {
        const val REF_PREFIX = "enckey"
        const val KEY_BYTES = 32
        /** Recorded in [Secret.EncryptionKey.algorithm]; the key is a generic 256-bit master key. */
        const val ALGORITHM_LABEL = "AES-256-GCM"
    }
}
