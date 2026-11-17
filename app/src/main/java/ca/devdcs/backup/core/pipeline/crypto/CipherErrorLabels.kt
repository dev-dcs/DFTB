package ca.devdcs.backup.core.pipeline.crypto

import androidx.annotation.StringRes
import ca.devdcs.backup.R

/*
 * Localized explanations for cipher failures, so restore/verify screens and notifications show
 * the user what went wrong (wrong passphrase vs damaged file vs missing key) instead of a message.
 */

@StringRes
fun CipherIntegrityException.Reason.labelRes(): Int = when (this) {
    CipherIntegrityException.Reason.WRONG_KEY -> R.string.crypto_reason_wrong_key
    CipherIntegrityException.Reason.KEY_TYPE_MISMATCH -> R.string.crypto_reason_key_type_mismatch
    CipherIntegrityException.Reason.TAMPERED -> R.string.crypto_reason_tampered
    CipherIntegrityException.Reason.TRUNCATED -> R.string.crypto_reason_truncated
    CipherIntegrityException.Reason.TRAILING_DATA -> R.string.crypto_reason_trailing_data
    CipherIntegrityException.Reason.BAD_HEADER -> R.string.crypto_reason_bad_header
    CipherIntegrityException.Reason.UNSUPPORTED_VERSION -> R.string.crypto_reason_unsupported_version
    CipherIntegrityException.Reason.INSUFFICIENT_MEMORY -> R.string.crypto_reason_insufficient_memory
}

@StringRes
fun CipherKeyUnavailableException.Reason.labelRes(): Int = when (this) {
    CipherKeyUnavailableException.Reason.NOT_FOUND -> R.string.crypto_key_not_found
    CipherKeyUnavailableException.Reason.INVALID_ENTRY -> R.string.crypto_key_invalid_entry
    CipherKeyUnavailableException.Reason.VAULT_ERROR -> R.string.crypto_key_vault_error
}
