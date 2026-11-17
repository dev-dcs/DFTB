package ca.devdcs.backup.domain.security

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Category of a stored [Secret]. Persisted on disk as its ordinal in the vault blob header so the
 * vault can list entries without decrypting them: the order below is append-only and existing
 * constants must never be reordered or removed.
 */
enum class SecretKind {
    PASSWORD,
    SSH_KEY,
    OAUTH_TOKEN,
    RCLONE_CONFIG,
    ENCRYPTION_KEY,
    GENERIC;

    companion object {
        fun fromOrdinal(value: Int): SecretKind? = entries.getOrNull(value)
    }
}

/**
 * Everything DFTB must keep confidential: connection passwords, SSH keys, cloud OAuth tokens,
 * rclone remote definitions and the keys that encrypt backup artifacts. Each variant is a closed
 * data shape so the vault can serialise it to JSON (polymorphic, discriminated by `type`) and the
 * rest of the app never has to parse free-form credential strings.
 *
 * `toString()` is overridden on every variant so a secret that lands in a log line or crash
 * report by accident reveals only its kind, never its content.
 */
@Serializable
sealed class Secret {

    /** Category discriminator, kept out of the JSON body: the vault header carries it instead. */
    abstract val kind: SecretKind

    /** Username/password pair for FTP, SFTP, SMB, WebDAV, S3-style (access/secret) and alike. */
    @Serializable
    @SerialName("password")
    data class Password(
        val username: String?,
        val password: String,
    ) : Secret() {
        override val kind: SecretKind get() = SecretKind.PASSWORD
        override fun toString(): String = "Secret.Password(username=${username?.let { "set" } ?: "null"}, password=[redacted])"
    }

    /** OpenSSH/PEM private key, its optional passphrase and the matching public key line. */
    @Serializable
    @SerialName("ssh_key")
    data class SshKey(
        val privateKeyPem: String,
        val passphrase: String?,
        val publicKey: String?,
    ) : Secret() {
        override val kind: SecretKind get() = SecretKind.SSH_KEY
        override fun toString(): String = "Secret.SshKey(privateKey=[redacted], passphrase=${if (passphrase == null) "null" else "[redacted]"}, publicKey=${publicKey?.let { "set" } ?: "null"})"
    }

    /** OAuth 2 bearer credentials for cloud providers reached directly or through rclone. */
    @Serializable
    @SerialName("oauth_token")
    data class OAuthToken(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtEpochMillis: Long?,
        val tokenType: String?,
        /** Provider-specific extras (scope, account id, ...) kept verbatim as a JSON object text. */
        val extraJson: String?,
    ) : Secret() {
        override val kind: SecretKind get() = SecretKind.OAUTH_TOKEN
        override fun toString(): String = "Secret.OAuthToken(accessToken=[redacted], refreshToken=${if (refreshToken == null) "null" else "[redacted]"}, expiresAt=$expiresAtEpochMillis, tokenType=$tokenType)"
    }

    /** One `[remoteName]` section of an rclone.conf, stored as the INI text rclone itself reads. */
    @Serializable
    @SerialName("rclone_config")
    data class RcloneConfig(
        val remoteName: String,
        val iniText: String,
    ) : Secret() {
        override val kind: SecretKind get() = SecretKind.RCLONE_CONFIG
        override fun toString(): String = "Secret.RcloneConfig(remoteName=$remoteName, iniText=[redacted])"
    }

    /** Symmetric key used to encrypt backup artifacts; [algorithm] names the scheme, e.g. "AES-256-GCM". */
    @Serializable
    @SerialName("encryption_key")
    data class EncryptionKey(
        val keyBase64: String,
        val algorithm: String,
    ) : Secret() {
        override val kind: SecretKind get() = SecretKind.ENCRYPTION_KEY
        override fun toString(): String = "Secret.EncryptionKey(key=[redacted], algorithm=$algorithm)"
    }

    /** Escape hatch for provider payloads that do not fit the shapes above; opaque JSON text. */
    @Serializable
    @SerialName("generic")
    data class Generic(
        val json: String,
    ) : Secret() {
        override val kind: SecretKind get() = SecretKind.GENERIC
        override fun toString(): String = "Secret.Generic(json=[redacted])"
    }
}
