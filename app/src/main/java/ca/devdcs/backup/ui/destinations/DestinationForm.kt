package ca.devdcs.backup.ui.destinations

import ca.devdcs.backup.core.storage.ftp.FtpBackend
import ca.devdcs.backup.core.storage.webdav.WebDavBackend
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationConfig
import ca.devdcs.backup.domain.model.DestinationType

/**
 * `DestinationConfig.options` keys the editor manages. Mirrors the backends' option names (some
 * are private there) so the form writes exactly what each backend reads.
 */
object DestinationOptions {
    const val FTPS_MODE: String = FtpBackend.OPTION_FTPS_MODE
    const val FTPS_MODE_EXPLICIT: String = FtpBackend.FTPS_MODE_EXPLICIT
    const val FTPS_MODE_IMPLICIT: String = FtpBackend.FTPS_MODE_IMPLICIT
    const val FTP_CONNECT_TIMEOUT_S: String = FtpBackend.OPTION_CONNECT_TIMEOUT_S
    const val FTP_DATA_TIMEOUT_S: String = FtpBackend.OPTION_DATA_TIMEOUT_S
    const val TLS_PIN_SHA256: String = FtpBackend.OPTION_TLS_PIN
    const val WEBDAV_SCHEME: String = WebDavBackend.OPTION_SCHEME
    const val WEBDAV_CONNECT_TIMEOUT_S: String = WebDavBackend.OPTION_CONNECT_TIMEOUT_S
    const val WEBDAV_IO_TIMEOUT_S: String = WebDavBackend.OPTION_IO_TIMEOUT_S

    // SmbBackend / NfsBackend keep these private; values must stay in sync with their KDoc.
    const val SMB_SHARE: String = "share"
    const val SMB_DOMAIN: String = "domain"
    const val SMB_ENCRYPT: String = "encrypt"
    const val SMB_GUEST: String = "guest"
    const val NFS_EXPORT: String = "export"
    const val NFS_MOUNT_OPTIONS: String = "mountOptions"

    // Read by RcloneTargets.s3.
    const val S3_PROVIDER: String = "provider"
    const val S3_REGION: String = "region"
    const val S3_ENDPOINT: String = "endpoint"

    const val TRUE: String = "true"
}

/** Every input of the destination editor; also the key of [FormError]s. */
enum class FormField(val optionKey: String? = null) {
    NAME,
    HOST,
    PORT,
    BASE_PATH,
    USERNAME,
    PASSWORD,
    PRIVATE_KEY,
    PASSPHRASE,
    REMOTE_NAME,
    TREE_URI,
    USB_VOLUME,
    FTPS_MODE(DestinationOptions.FTPS_MODE),
    FTP_CONNECT_TIMEOUT(DestinationOptions.FTP_CONNECT_TIMEOUT_S),
    FTP_DATA_TIMEOUT(DestinationOptions.FTP_DATA_TIMEOUT_S),
    TLS_PIN(DestinationOptions.TLS_PIN_SHA256),
    WEBDAV_SCHEME(DestinationOptions.WEBDAV_SCHEME),
    WEBDAV_CONNECT_TIMEOUT(DestinationOptions.WEBDAV_CONNECT_TIMEOUT_S),
    WEBDAV_IO_TIMEOUT(DestinationOptions.WEBDAV_IO_TIMEOUT_S),
    SMB_SHARE(DestinationOptions.SMB_SHARE),
    SMB_DOMAIN(DestinationOptions.SMB_DOMAIN),
    SMB_ENCRYPT(DestinationOptions.SMB_ENCRYPT),
    SMB_GUEST(DestinationOptions.SMB_GUEST),
    NFS_EXPORT(DestinationOptions.NFS_EXPORT),
    NFS_MOUNT_OPTIONS(DestinationOptions.NFS_MOUNT_OPTIONS),
    S3_PROVIDER(DestinationOptions.S3_PROVIDER),
    S3_REGION(DestinationOptions.S3_REGION),
    S3_ENDPOINT(DestinationOptions.S3_ENDPOINT),
    ;

    /** True for option fields that are on/off switches (stored as "true" or absent). */
    val isSwitch: Boolean get() = this == SMB_ENCRYPT || this == SMB_GUEST

    companion object {
        /** Every option key the form owns; other keys (e.g. `rclone.*`) are preserved untouched. */
        val managedOptionKeys: Set<String> = entries.mapNotNull { it.optionKey }.toSet()
    }
}

/** How a destination authenticates; decides which secret shape goes into the vault. */
enum class AuthMethod {
    /** No credential (anonymous FTP, guest SMB, public WebDAV, NFS, SAF, rclone remotes). */
    NONE,

    /** [ca.devdcs.backup.domain.security.Secret.Password]. */
    PASSWORD,

    /** [ca.devdcs.backup.domain.security.Secret.SshKey]. */
    PRIVATE_KEY,
}

/**
 * Which inputs a [DestinationType] uses. Exists so the screen, the validator and the mapper agree
 * on one table instead of each branching on the type.
 */
data class DestinationTypeSpec(
    val type: DestinationType,
    val showsHost: Boolean = false,
    val showsPort: Boolean = false,
    val defaultPort: Int? = null,
    val showsBasePath: Boolean = true,
    val showsUsername: Boolean = false,
    val usernameRequired: Boolean = false,
    /** S3: the access key id lives only in the vault secret, never in the exportable config. */
    val usernameInSecret: Boolean = false,
    val authMethods: List<AuthMethod> = listOf(AuthMethod.NONE),
    val usesTree: Boolean = false,
    val usesUsbVolume: Boolean = false,
    val usesRemoteName: Boolean = false,
    val advanced: List<FormField> = emptyList(),
    /** False for types no backend serves directly (pCloud is reached through an rclone remote). */
    val selectable: Boolean = true,
) {
    val secretRequired: Boolean get() = AuthMethod.NONE !in authMethods
    val defaultAuthMethod: AuthMethod get() = authMethods.first()

    companion object {
        private val NET_PASSWORD_OPTIONAL = listOf(AuthMethod.NONE, AuthMethod.PASSWORD)

        fun of(type: DestinationType): DestinationTypeSpec = when (type) {
            DestinationType.LOCAL_INTERNAL -> DestinationTypeSpec(type)
            DestinationType.LOCAL_SAF -> DestinationTypeSpec(type, usesTree = true)
            DestinationType.USB_OTG -> DestinationTypeSpec(type, usesTree = true, usesUsbVolume = true)
            DestinationType.FTP -> DestinationTypeSpec(
                type, showsHost = true, showsPort = true, defaultPort = FtpBackend.DEFAULT_PORT,
                showsUsername = true, authMethods = NET_PASSWORD_OPTIONAL,
                advanced = listOf(FormField.FTP_CONNECT_TIMEOUT, FormField.FTP_DATA_TIMEOUT),
            )
            DestinationType.FTPS -> DestinationTypeSpec(
                type, showsHost = true, showsPort = true, defaultPort = FtpBackend.DEFAULT_PORT,
                showsUsername = true, authMethods = NET_PASSWORD_OPTIONAL,
                advanced = listOf(FormField.FTPS_MODE, FormField.TLS_PIN, FormField.FTP_CONNECT_TIMEOUT, FormField.FTP_DATA_TIMEOUT),
            )
            DestinationType.SFTP, DestinationType.SSH -> DestinationTypeSpec(
                type, showsHost = true, showsPort = true, defaultPort = SSH_DEFAULT_PORT,
                showsUsername = true, usernameRequired = true,
                authMethods = listOf(AuthMethod.PASSWORD, AuthMethod.PRIVATE_KEY),
            )
            DestinationType.SMB -> DestinationTypeSpec(
                type, showsHost = true, showsPort = true, defaultPort = SMB_DEFAULT_PORT,
                showsUsername = true, authMethods = NET_PASSWORD_OPTIONAL,
                advanced = listOf(FormField.SMB_SHARE, FormField.SMB_DOMAIN, FormField.SMB_ENCRYPT, FormField.SMB_GUEST),
            )
            DestinationType.WEBDAV -> DestinationTypeSpec(
                type, showsHost = true, showsPort = true,
                showsUsername = true, authMethods = NET_PASSWORD_OPTIONAL,
                advanced = listOf(FormField.WEBDAV_SCHEME, FormField.TLS_PIN, FormField.WEBDAV_CONNECT_TIMEOUT, FormField.WEBDAV_IO_TIMEOUT),
            )
            DestinationType.NFS -> DestinationTypeSpec(
                type, showsHost = true,
                advanced = listOf(FormField.NFS_EXPORT, FormField.NFS_MOUNT_OPTIONS),
            )
            DestinationType.RCLONE -> DestinationTypeSpec(type, usesRemoteName = true)
            DestinationType.S3 -> DestinationTypeSpec(
                type, showsUsername = true, usernameRequired = true, usernameInSecret = true,
                authMethods = listOf(AuthMethod.PASSWORD),
                advanced = listOf(FormField.S3_PROVIDER, FormField.S3_REGION, FormField.S3_ENDPOINT),
            )
            DestinationType.MEGA -> DestinationTypeSpec(
                type, showsUsername = true, usernameRequired = true, authMethods = listOf(AuthMethod.PASSWORD),
            )
            DestinationType.PCLOUD -> DestinationTypeSpec(type, selectable = false)
        }

        /** Types offered by the "add destination" picker, in display order. */
        val selectableTypes: List<DestinationType> = DestinationType.entries.filter { of(it).selectable }

        const val SSH_DEFAULT_PORT: Int = 22
        const val SMB_DEFAULT_PORT: Int = 445
    }
}

/**
 * Everything the destination editor holds, as plain strings exactly as typed.
 *
 * Exists so validation and the form→[DestinationConfig] mapping are pure and unit-tested. Secret
 * inputs ([password], [privateKey], [passphrase]) never come from the vault: when editing, they
 * start blank and blank means "keep what is stored" ([hasStoredSecret]).
 */
data class DestinationForm(
    val type: DestinationType,
    val name: String = "",
    val host: String = "",
    val port: String = "",
    val basePath: String = "",
    val username: String = "",
    val remoteName: String = "",
    val treeUri: String = "",
    /** Volume id encoded in [treeUri] (ExternalStorageProvider), derived by the caller; null if unknown. */
    val treeVolumeId: String? = null,
    val usbVolumeId: String = "",
    val options: Map<String, String> = emptyMap(),
    val authMethod: AuthMethod = DestinationTypeSpec.of(type).defaultAuthMethod,
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    /** The destination already has a vault secret of kind [storedSecretMethod]. */
    val hasStoredSecret: Boolean = false,
    val storedSecretMethod: AuthMethod? = null,
) {
    val spec: DestinationTypeSpec get() = DestinationTypeSpec.of(type)

    fun option(field: FormField): String = field.optionKey?.let { options[it] }.orEmpty()

    fun withOption(field: FormField, value: String): DestinationForm {
        val key = field.optionKey ?: return this
        return copy(options = if (value.isEmpty()) options - key else options + (key to value))
    }

    fun switch(field: FormField): Boolean = option(field).equals(DestinationOptions.TRUE, ignoreCase = true)

    fun withSwitch(field: FormField, on: Boolean): DestinationForm = withOption(field, if (on) DestinationOptions.TRUE else "")

    /** A stored secret matching the selected method can be kept by leaving the inputs blank. */
    val canKeepStoredSecret: Boolean get() = hasStoredSecret && storedSecretMethod == authMethod

    companion object {
        /** Editor state for an existing destination; secret inputs stay blank. */
        fun from(destination: Destination, storedSecretMethod: AuthMethod?, treeVolumeId: String?): DestinationForm {
            val spec = DestinationTypeSpec.of(destination.type)
            val config = destination.config
            val method = when {
                storedSecretMethod != null && storedSecretMethod in spec.authMethods -> storedSecretMethod
                destination.credentialRef == null && AuthMethod.NONE in spec.authMethods -> AuthMethod.NONE
                else -> spec.defaultAuthMethod
            }
            return DestinationForm(
                type = destination.type,
                name = destination.name,
                host = config.host.orEmpty(),
                port = config.port?.toString().orEmpty(),
                basePath = config.basePath.orEmpty(),
                username = config.username.orEmpty(),
                remoteName = config.remoteName.orEmpty(),
                treeUri = config.treeUri.orEmpty(),
                treeVolumeId = treeVolumeId,
                usbVolumeId = config.usbVolumeId.orEmpty(),
                options = config.options,
                authMethod = method,
                hasStoredSecret = destination.credentialRef != null,
                storedSecretMethod = storedSecretMethod,
            )
        }
    }
}

/** What the editor must do with the vault when saving or testing [DestinationForm]. */
sealed interface SecretPlan {
    /** Leave the stored secret (if any) as it is. */
    data object Keep : SecretPlan

    /** The form needs no credential: delete any stored one. */
    data object Remove : SecretPlan

    /** Store this new secret (under the existing ref, or a new one). */
    data class Replace(val secret: PlannedSecret) : SecretPlan

    /** Keep the stored password but rewrite its username (it changed in the form). */
    data class UpdateUsername(val username: String?) : SecretPlan
}

/** Vault-independent description of a secret, mapped to [ca.devdcs.backup.domain.security.Secret] by the ViewModel. */
sealed interface PlannedSecret {
    data class Password(val username: String?, val password: String) : PlannedSecret {
        override fun toString(): String = "PlannedSecret.Password([redacted])"
    }

    data class SshKey(val privateKeyPem: String, val passphrase: String?) : PlannedSecret {
        override fun toString(): String = "PlannedSecret.SshKey([redacted])"
    }
}

/** Pure mapping from a [DestinationForm] to what is persisted. */
object DestinationFormMapper {

    /** Non-secret config for [form]; fields irrelevant to the type are dropped, foreign options kept. */
    fun toConfig(form: DestinationForm): DestinationConfig {
        val spec = form.spec
        val relevant = spec.advanced.mapNotNull { it.optionKey }.toSet()
        val options = LinkedHashMap<String, String>()
        form.options.forEach { (key, value) ->
            val trimmed = value.trim()
            when {
                key !in FormField.managedOptionKeys -> options[key] = value
                key in relevant && trimmed.isNotEmpty() -> options[key] = trimmed
            }
        }
        return DestinationConfig(
            host = form.host.trim().takeIf { spec.showsHost && it.isNotEmpty() },
            port = form.port.trim().toIntOrNull().takeIf { spec.showsPort },
            basePath = form.basePath.trim().takeIf { spec.showsBasePath && it.isNotEmpty() },
            username = form.username.trim().takeIf { spec.showsUsername && !spec.usernameInSecret && it.isNotEmpty() },
            remoteName = form.remoteName.trim().takeIf { spec.usesRemoteName && it.isNotEmpty() },
            treeUri = form.treeUri.trim().takeIf { spec.usesTree && it.isNotEmpty() },
            usbVolumeId = form.usbVolumeId.trim().takeIf { spec.usesUsbVolume && it.isNotEmpty() },
            options = options,
        )
    }

    /** The vault action for [form]. Assumes [DestinationFormValidator.validate] passed. */
    fun secretPlan(form: DestinationForm): SecretPlan {
        val username = form.username.trim().takeIf { it.isNotEmpty() }
        return when (form.authMethod) {
            AuthMethod.NONE -> SecretPlan.Remove
            AuthMethod.PASSWORD -> when {
                form.password.isNotEmpty() -> SecretPlan.Replace(PlannedSecret.Password(username, form.password))
                form.canKeepStoredSecret && form.spec.usernameInSecret -> SecretPlan.Keep
                form.canKeepStoredSecret -> SecretPlan.UpdateUsername(username)
                else -> SecretPlan.Remove
            }
            AuthMethod.PRIVATE_KEY -> when {
                form.privateKey.isNotBlank() -> SecretPlan.Replace(
                    PlannedSecret.SshKey(form.privateKey.trim('\n', '\r') + "\n", form.passphrase.takeIf { it.isNotEmpty() }),
                )
                form.canKeepStoredSecret -> SecretPlan.Keep
                else -> SecretPlan.Remove
            }
        }
    }
}
