package ca.devdcs.backup.core.pipeline.crypto

import java.io.IOException

/**
 * Raised by the decrypting stream (or while opening it) when the ciphertext cannot be proven
 * authentic and complete. It is an [IOException] so it surfaces through ordinary stream reads and
 * aborts copy loops; [reason] lets restore/verify UIs explain *what* is wrong (wrong passphrase vs
 * damaged file) without parsing messages. Any data already returned before this is thrown belongs
 * to authenticated chunks, but the stream as a whole must be treated as untrusted.
 */
class CipherIntegrityException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    enum class Reason {
        /** Header is intact but the key check value does not match: wrong passphrase or vault key. */
        WRONG_KEY,
        /** A passphrase was supplied for a vault-key stream or vice versa. */
        KEY_TYPE_MISMATCH,
        /** A chunk failed GCM authentication: modified, reordered, swapped or cut mid-chunk. */
        TAMPERED,
        /** The stream ended before the final chunk (whole trailing chunks were removed). */
        TRUNCATED,
        /** Bytes follow the final chunk. */
        TRAILING_DATA,
        /** Not a DFTB stream, or a header field is out of its allowed range. */
        BAD_HEADER,
        /** A DFTB stream written by a newer format version or with an unknown KDF. */
        UNSUPPORTED_VERSION,
        /** The header's Argon2 memory cost does not fit this device's heap (or allocation failed). */
        INSUFFICIENT_MEMORY,
    }
}

/**
 * The key a [ca.devdcs.backup.core.pipeline.CipherKeySpec.VaultKey] points to cannot be used.
 * Separate from [CipherIntegrityException] because the archive itself may be perfectly fine; the
 * user has to restore/import the key instead.
 */
class CipherKeyUnavailableException(
    val vaultRef: String,
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    enum class Reason {
        /** No vault entry with that ref. */
        NOT_FOUND,
        /** The entry exists but is not an encryption key, or has the wrong length/encoding. */
        INVALID_ENTRY,
        /** The vault itself failed (see cause, a VaultException). */
        VAULT_ERROR,
    }
}
