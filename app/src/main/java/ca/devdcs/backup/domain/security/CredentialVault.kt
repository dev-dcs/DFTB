package ca.devdcs.backup.domain.security

/**
 * Metadata of one vault entry, obtainable without decrypting it. Lets the UI list connection
 * profiles and keys (and show when they last changed) while the secret itself stays sealed.
 */
data class VaultEntryInfo(
    val ref: String,
    val kind: SecretKind,
    val createdAt: Long,
    val updatedAt: Long,
    /** Size of the encrypted file on disk, in bytes. */
    val sizeBytes: Long,
)

/**
 * The single place secrets are read from and written to. Database rows (destinations, cloud
 * accounts, backup profiles) store only an opaque `ref`; the matching [Secret] lives here,
 * encrypted with a device-bound key. This keeps credentials out of Room, out of DataStore, out of
 * logs and out of any export of DFTB's own data.
 *
 * All suspend functions are safe to call from any dispatcher; implementations move IO off the
 * caller's thread.
 */
interface CredentialVault {

    /** Creates or replaces the secret stored under [ref]; the earlier build creation time is preserved on replace. */
    suspend fun store(ref: String, secret: Secret)

    /** Returns the secret stored under [ref], or null when no such entry exists. */
    suspend fun load(ref: String): Secret?

    /** Removes the entry; returns true when something was actually deleted. */
    suspend fun delete(ref: String): Boolean

    suspend fun exists(ref: String): Boolean

    /** Every entry, sorted by [VaultEntryInfo.ref], without decrypting any of them. */
    suspend fun list(): List<VaultEntryInfo>

    /**
     * Mints a new unique reference of the form `<prefix>-<UUID>`. The prefix is purely a human
     * aid when inspecting the vault directory (e.g. "dest", "sshkey", "rclone", "enckey").
     */
    fun newRef(prefix: String): String
}

/**
 * Raised when the vault cannot complete an operation and retrying will not help: the master key
 * was invalidated or lost, a blob is corrupt or tampered, or the filesystem refused the write.
 * [reason] lets the UI pick a precise, localised explanation instead of surfacing [message].
 */
class VaultException(
    message: String,
    cause: Throwable? = null,
    val reason: Reason = Reason.UNKNOWN,
) : RuntimeException(message, cause) {

    enum class Reason {
        /** The Keystore reported the master key as permanently invalidated (e.g. lock screen removed under a policy). */
        KEY_INVALIDATED,
        /** The master key alias exists but cannot be loaded; every stored secret is unreadable. */
        KEY_UNRECOVERABLE,
        /** The master key alias is gone but encrypted entries remain on disk; they can never be decrypted on this device. */
        KEY_LOST,
        /** AndroidKeyStore itself is missing or refused to generate/load a key. */
        KEYSTORE_UNAVAILABLE,
        /** Authentication tag mismatch: the blob was modified, or decrypted with the wrong ref. */
        INTEGRITY_FAILURE,
        /** The on-disk blob is not a valid vault file (bad magic, version, lengths or JSON). */
        CORRUPT_BLOB,
        /** The ref is empty or too long to be mapped to a filename. */
        INVALID_REF,
        /** Filesystem error while reading, writing or renaming the vault file. */
        IO,
        UNKNOWN,
    }
}
