package ca.devdcs.backup.core.storage.ssh

import ca.devdcs.backup.R
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.Secret
import ca.devdcs.backup.domain.security.VaultException
import kotlinx.coroutines.CancellationException

/**
 * Loads a destination's [Secret] from the vault with uniform failure mapping. Exists so the SSH
 * and SMB backends report a missing or unreadable credential identically (MISCONFIGURED) instead
 * of leaking [VaultException]s through the storage contract.
 */
internal object NetCredentials {

    /** The secret for [destination], or null when it has no credentialRef and [required] is false. */
    suspend fun load(vault: CredentialVault, destination: Destination, strings: StorageStrings, required: Boolean): Secret? {
        val ref = destination.credentialRef?.takeIf { it.isNotBlank() }
            ?: if (required) {
                throw StorageException(StorageException.Reason.MISCONFIGURED, strings.get(R.string.storage_net_missing_credential))
            } else {
                return null
            }
        val secret = try {
            vault.load(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultException) {
            throw StorageException(StorageException.Reason.MISCONFIGURED, strings.get(R.string.storage_net_credential_not_found), e)
        }
        return secret ?: throw StorageException(
            StorageException.Reason.MISCONFIGURED,
            strings.get(R.string.storage_net_credential_not_found),
        )
    }

    fun requireHost(destination: Destination, strings: StorageStrings): String =
        destination.config.host?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw StorageException(StorageException.Reason.MISCONFIGURED, strings.get(R.string.storage_net_missing_host))
}
