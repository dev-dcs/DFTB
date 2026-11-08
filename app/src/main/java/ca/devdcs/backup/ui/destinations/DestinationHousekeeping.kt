package ca.devdcs.backup.ui.destinations

import android.content.Context
import android.content.Intent
import android.net.Uri
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.Secret
import ca.devdcs.backup.domain.security.SecretKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Side effects shared by the destination list and editor: vault secrets and SAF tree grants.
 *
 * Exists so a destination never leaves debris behind — deleting it removes its vault secret and
 * releases its persisted folder grant (unless another destination uses the same folder), and the
 * editor's "plan" for a secret is applied in exactly one place.
 */
@Singleton
class DestinationHousekeeping @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DestinationRepository,
    private val vault: CredentialVault,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Delete [destination], its secret and (if unused elsewhere) its folder grant. */
    suspend fun delete(destination: Destination) = withContext(io) {
        repository.delete(destination.id)
        destination.credentialRef?.let { ref ->
            if (!vault.delete(ref)) DftbLog.d(TAG, "No secret stored for deleted destination ${destination.id}")
        }
        destination.config.treeUri?.let { releaseTreeIfUnused(it) }
    }

    /** Take a persistable read+write grant on a freshly picked tree. */
    suspend fun takeTree(treeUri: Uri) = withContext(io) {
        context.contentResolver.takePersistableUriPermission(treeUri, GRANT_FLAGS)
    }

    /** Release [treeUri]'s persisted grant unless a saved destination still points at it. */
    suspend fun releaseTreeIfUnused(treeUri: String) = withContext(io) {
        if (treeUri.isBlank()) return@withContext
        if (repository.getAll().any { it.config.treeUri == treeUri }) return@withContext
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(treeUri), GRANT_FLAGS)
        } catch (_: SecurityException) {
            DftbLog.d(TAG, "Persisted grant was already gone")
        }
    }

    /** The auth method of the secret stored under [ref], read from vault metadata (no decryption). */
    suspend fun storedMethod(ref: String?): AuthMethod? {
        if (ref == null) return null
        val info = vault.list().firstOrNull { it.ref == ref } ?: return null
        return when (info.kind) {
            SecretKind.PASSWORD -> AuthMethod.PASSWORD
            SecretKind.SSH_KEY -> AuthMethod.PRIVATE_KEY
            else -> null
        }
    }

    /**
     * Apply [plan] for a destination whose current ref is [currentRef]; returns the ref to use.
     * With [targetRef] (the connection test's throw-away ref) new or changed secrets go there and
     * nothing stored is deleted; an unchanged secret is used in place under [currentRef].
     */
    suspend fun applySecretPlan(plan: SecretPlan, currentRef: String?, targetRef: String? = null): String? = when (plan) {
        SecretPlan.Keep -> currentRef
        SecretPlan.Remove -> {
            if (targetRef == null && currentRef != null) vault.delete(currentRef)
            null
        }
        is SecretPlan.Replace -> {
            val ref = targetRef ?: currentRef ?: vault.newRef(REF_PREFIX)
            vault.store(ref, plan.secret.toSecret())
            ref
        }
        is SecretPlan.UpdateUsername -> {
            val ref = currentRef ?: error("UpdateUsername without a stored secret")
            val stored = vault.load(ref)
            if (stored is Secret.Password && stored.username != plan.username) {
                val destination = targetRef ?: ref
                vault.store(destination, stored.copy(username = plan.username))
                destination
            } else {
                ref
            }
        }
    }

    /** A fresh throw-away ref for a connection test of unsaved credentials. */
    fun newTestRef(): String = vault.newRef(TEST_REF_PREFIX)

    /** Remove a throw-away ref created by [applySecretPlan] with a [targetRef]. */
    suspend fun discardRef(ref: String) {
        vault.delete(ref)
    }

    private fun PlannedSecret.toSecret(): Secret = when (this) {
        is PlannedSecret.Password -> Secret.Password(username = username, password = password)
        is PlannedSecret.SshKey -> Secret.SshKey(privateKeyPem = privateKeyPem, passphrase = passphrase, publicKey = null)
    }

    companion object {
        private const val TAG = "DestHousekeeping"
        const val REF_PREFIX: String = "dest"
        const val TEST_REF_PREFIX: String = "desttest"
        private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
