package ca.devdcs.backup.ui.restore

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.crypto.CipherIntegrityException
import ca.devdcs.backup.core.pipeline.crypto.CipherKeyUnavailableException
import ca.devdcs.backup.core.restore.BackupCatalog
import ca.devdcs.backup.core.restore.OpenedBackup
import ca.devdcs.backup.core.restore.catalog.BackupItemReader
import ca.devdcs.backup.core.restore.catalog.CatalogException
import ca.devdcs.backup.core.restore.catalog.CatalogOpenedBackup
import ca.devdcs.backup.core.restore.catalog.DefaultBackupCatalog
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.repository.ProfileRepository
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.Secret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Why a backup could not be opened, in a form the UI can localize. */
sealed interface OpenFailure {
    data class Catalog(val reason: CatalogException.Reason, val detail: String?) : OpenFailure
    data class Cipher(val reason: CipherIntegrityException.Reason) : OpenFailure
    data class KeyUnavailable(val reason: CipherKeyUnavailableException.Reason) : OpenFailure
    data class Storage(val reason: StorageException.Reason, val detail: String?) : OpenFailure
    data object DestinationMissing : OpenFailure
    data class Other(val detail: String) : OpenFailure
}

/** Outcome of [BackupUnlocker]. */
sealed interface UnlockResult {
    /**
     * The backup is open. [key] is the caller's own copy (null for plain backups); the caller wipes
     * it when done, and closes [backup].
     */
    class Opened(val backup: OpenedBackup, val key: CipherKeySpec?, val viaVault: Boolean) : UnlockResult

    /** Encrypted and no stored key opened it; [vaultProblem] tells why a saved key did not work. */
    data class NeedsPassphrase(val vaultProblem: OpenFailure? = null) : UnlockResult

    data class Failed(val failure: OpenFailure) : UnlockResult
}

/**
 * Opens a backup for the restore screen, unlocking it with the producing profile's vault key (or
 * vault-held passphrase) when possible so most encrypted backups need no typing. A plaintext
 * manifest says nothing about the key, so a candidate key is proven by decrypting the first chunk
 * of one encrypted payload before it is accepted. Never retains key material: every key it hands
 * out is a fresh copy the caller owns.
 */
@Singleton
class BackupUnlocker @Inject constructor(
    private val catalog: BackupCatalog,
    private val itemReader: BackupItemReader,
    private val profiles: ProfileRepository,
    private val vault: CredentialVault,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Plain open first; for encrypted backups, the matching profiles' stored keys. */
    suspend fun openAutomatically(destination: Destination, jobDir: String): UnlockResult = withContext(io) { autoOpen(destination, jobDir) }

    /** Open with a typed passphrase. [passphrase] is copied, never kept; the caller wipes it. */
    suspend fun openWithPassphrase(destination: Destination, jobDir: String, passphrase: CharArray): UnlockResult =
        withContext(io) { passphraseOpen(destination, jobDir, passphrase) }

    private suspend fun autoOpen(destination: Destination, jobDir: String): UnlockResult {
        var hintId: String? = null
        var hintName: String? = null
        val keyless = try {
            catalog.open(destination, jobDir, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CatalogException) {
            if (e.reason != CatalogException.Reason.KEY_REQUIRED) return UnlockResult.Failed(failureOf(e))
            null
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return UnlockResult.Failed(failureOf(e))
        }
        if (keyless != null) {
            if (!keyless.summary.encrypted) return UnlockResult.Opened(keyless, null, viaVault = false)
            hintId = keyless.manifest.profile.id
            hintName = keyless.manifest.profile.name
            keyless.close()
        }
        return tryVault(destination, jobDir, hintId, hintName ?: folderProfileHint(jobDir))
    }

    private suspend fun passphraseOpen(destination: Destination, jobDir: String, passphrase: CharArray): UnlockResult {
        val key = CipherKeySpec.Passphrase(passphrase.copyOf())
        return when (val attempt = attempt(destination, jobDir, key)) {
            is Attempt.Ok -> UnlockResult.Opened(attempt.backup, key, viaVault = false)
            is Attempt.WrongKey -> {
                wipe(key)
                UnlockResult.Failed(OpenFailure.Cipher(attempt.reason))
            }
            is Attempt.Error -> {
                wipe(key)
                UnlockResult.Failed(attempt.failure)
            }
        }
    }

    private suspend fun tryVault(destination: Destination, jobDir: String, profileId: String?, profileName: String?): UnlockResult {
        val candidates = try {
            orderedCandidates(profiles.getAll(), profileId, profileName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "Profiles unavailable for key lookup", e)
            emptyList()
        }
        var vaultProblem: OpenFailure? = null
        var passphraseTries = 0
        for (profile in candidates) {
            val ref = profile.pipeline.encryptionKeyRef ?: continue
            val secret = try {
                vault.load(ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Vault entry for ${profile.id} unreadable", e)
                vaultProblem = OpenFailure.KeyUnavailable(CipherKeyUnavailableException.Reason.VAULT_ERROR)
                continue
            }
            val key = when (secret) {
                is Secret.EncryptionKey -> CipherKeySpec.VaultKey(ref)
                is Secret.Password -> {
                    // A vault passphrase costs one KDF run per try: only the matching profiles.
                    if (!matches(profile, profileId, profileName) || passphraseTries >= MAX_PASSPHRASE_TRIES) continue
                    passphraseTries++
                    CipherKeySpec.Passphrase(secret.password.toCharArray())
                }
                else -> continue
            }
            when (val attempt = attempt(destination, jobDir, key)) {
                is Attempt.Ok -> return UnlockResult.Opened(attempt.backup, key, viaVault = true)
                is Attempt.WrongKey -> wipe(key)
                is Attempt.Error -> {
                    wipe(key)
                    if (attempt.failure is OpenFailure.KeyUnavailable) {
                        vaultProblem = attempt.failure
                    } else {
                        return UnlockResult.Failed(attempt.failure)
                    }
                }
            }
        }
        return UnlockResult.NeedsPassphrase(vaultProblem)
    }

    private sealed interface Attempt {
        class Ok(val backup: OpenedBackup) : Attempt
        data class WrongKey(val reason: CipherIntegrityException.Reason) : Attempt
        data class Error(val failure: OpenFailure) : Attempt
    }

    /** Open with [key] (catalog copies it) and prove the key against an encrypted payload. */
    private suspend fun attempt(destination: Destination, jobDir: String, key: CipherKeySpec): Attempt {
        val opened = try {
            catalog.open(destination, jobDir, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CipherIntegrityException) {
            return if (isWrongKey(e.reason)) Attempt.WrongKey(e.reason) else Attempt.Error(OpenFailure.Cipher(e.reason))
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return Attempt.Error(failureOf(e))
        }
        val manifestProvedKey = (opened as? CatalogOpenedBackup)?.runs?.any { it.encrypted } == true
        if (manifestProvedKey) return Attempt.Ok(opened)
        return try {
            probe(opened)
            Attempt.Ok(opened)
        } catch (e: CancellationException) {
            opened.close()
            throw e
        } catch (e: CipherIntegrityException) {
            if (isWrongKey(e.reason)) {
                opened.close()
                Attempt.WrongKey(e.reason)
            } else {
                // Right key, damaged data: open it so verification can show what is wrong.
                Attempt.Ok(opened)
            }
        } catch (e: CipherKeyUnavailableException) {
            opened.close()
            Attempt.Error(OpenFailure.KeyUnavailable(e.reason))
        } catch (e: Exception) {
            // The probe could not read a payload (missing file, storage): the key is unproven, but
            // verification and restore report that precisely, so open the backup anyway.
            DftbLog.w(TAG, "Key probe inconclusive for $jobDir: ${e.message}")
            Attempt.Ok(opened)
        }
    }

    /** Decrypts the first bytes of the smallest encrypted payload; wrong keys throw. */
    private suspend fun probe(backup: OpenedBackup) {
        val artifacts = backup.manifest.artifacts
        val single = artifacts
            .filter { it.encryption != null && !DefaultBackupCatalog.isTree(it) }
            .minByOrNull { it.sizeBytes }
        if (single != null) {
            catalog.openDecoded(backup, single).use { it.read() }
            return
        }
        for (artifact in artifacts.filter { DefaultBackupCatalog.isTree(it) }) {
            val item = artifact.items
                .filter { it.encryption != null && it.storedPath != null }
                .minByOrNull { it.storedBytes ?: Long.MAX_VALUE } ?: continue
            itemReader.openDecodedItem(backup, artifact, item).use { it.read() }
            return
        }
    }

    private fun matches(profile: BackupProfile, id: String?, name: String?): Boolean =
        (id != null && profile.id == id) || (name != null && profile.name.equals(name, ignoreCase = true)) ||
            (name != null && sanitized(profile.name).isNotEmpty() && sanitized(profile.name) == sanitized(name))

    /** Encrypted profiles, the one that made the backup first, then same-named ones, then the rest. */
    private fun orderedCandidates(all: List<BackupProfile>, id: String?, name: String?): List<BackupProfile> =
        all.filter { it.pipeline.isEncrypted && !it.pipeline.encryptionKeyRef.isNullOrBlank() }
            .sortedWith(
                compareBy<BackupProfile> { profile ->
                    when {
                        id != null && profile.id == id -> 0
                        matches(profile, null, name) -> 1
                        else -> 2
                    }
                }.thenBy { it.name },
            )

    /** Job folders start with the profile name: `<name>_<timestamp>`; a hint only, never trusted. */
    private fun folderProfileHint(jobDir: String): String? {
        val folder = jobDir.trimEnd('/').substringAfterLast('/')
        val cut = folder.lastIndexOf('_')
        return if (cut > 0) folder.substring(0, cut) else folder.ifBlank { null }
    }

    private fun sanitized(name: String): String = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun isWrongKey(reason: CipherIntegrityException.Reason): Boolean =
        reason == CipherIntegrityException.Reason.WRONG_KEY || reason == CipherIntegrityException.Reason.KEY_TYPE_MISMATCH

    private fun wipe(key: CipherKeySpec) {
        (key as? CipherKeySpec.Passphrase)?.chars?.fill('\u0000')
    }

    companion object {
        private const val TAG = "BackupUnlocker"
        private const val MAX_PASSPHRASE_TRIES = 3

        fun failureOf(e: Throwable): OpenFailure = when (e) {
            is CatalogException -> OpenFailure.Catalog(e.reason, e.message)
            is CipherIntegrityException -> OpenFailure.Cipher(e.reason)
            is CipherKeyUnavailableException -> OpenFailure.KeyUnavailable(e.reason)
            is StorageException -> OpenFailure.Storage(e.reason, e.message)
            else -> OpenFailure.Other(e.message ?: e.javaClass.simpleName)
        }

        /** A private copy of [key] (passphrases duplicated, vault refs shared). */
        fun copyKey(key: CipherKeySpec): CipherKeySpec = when (key) {
            is CipherKeySpec.Passphrase -> CipherKeySpec.Passphrase(key.chars.copyOf())
            is CipherKeySpec.VaultKey -> key
        }

        fun wipeKey(key: CipherKeySpec?) {
            (key as? CipherKeySpec.Passphrase)?.chars?.fill('\u0000')
        }
    }
}
