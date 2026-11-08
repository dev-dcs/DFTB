package ca.devdcs.backup.ui.destinations

import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.ftp.TlsTrust
import ca.devdcs.backup.core.storage.nfs.NfsMountPlanner
import ca.devdcs.backup.core.storage.rclone.RcloneIni
import ca.devdcs.backup.core.storage.smb.SmbLocation
import ca.devdcs.backup.core.storage.webdav.WebDavUrls
import ca.devdcs.backup.domain.model.DestinationType

/** Why one [FormField] is not acceptable; mapped to a localized message by the screen. */
enum class FormError {
    REQUIRED,
    TOO_LONG,
    INVALID_HOST,
    INVALID_URL,
    INVALID_PORT,
    PATH_TRAVERSAL,
    PATH_NOT_ABSOLUTE,
    INVALID_CHARACTERS,
    INVALID_TIMEOUT,
    INVALID_PIN,
    INVALID_REMOTE_NAME,
    UNKNOWN_REMOTE,
    INVALID_KEY,
    NEEDS_SHARE,
    NEEDS_EXPORT,
    UNSAFE_MOUNT_OPTIONS,
    VOLUME_MISMATCH,
    INVALID_EMAIL,
    NEEDS_BUCKET,
    INVALID_CHOICE,
}

/**
 * Inline validation of the destination editor.
 *
 * Exists so mistakes (bad host, out-of-range port, `..` in a path, missing SMB share, unsafe NFS
 * mount options) are flagged while typing instead of surfacing as MISCONFIGURED at the first
 * backup. Where a backend has its own pure parser (WebDAV URLs, SMB share, NFS export, TLS pin)
 * that parser is reused so the form accepts exactly what the backend accepts.
 */
object DestinationFormValidator {

    const val MAX_NAME_LENGTH: Int = 64
    const val MAX_TIMEOUT_SECONDS: Int = 3600

    private val HOST_LABEL = Regex("^[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?$")
    private val IPV6 = Regex("^[0-9A-Fa-f:.]+(%[A-Za-z0-9_.-]+)?$")
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val TOKEN = Regex("^[A-Za-z0-9_.:/+-]+$")

    /**
     * Every problem in [form], keyed by field (one error per field, the most relevant first).
     * [knownRemotes] is the rclone.conf remote list, or null when it could not be read.
     */
    fun validate(form: DestinationForm, knownRemotes: Set<String>? = null): Map<FormField, FormError> {
        val spec = form.spec
        val errors = LinkedHashMap<FormField, FormError>()
        fun put(field: FormField, error: FormError?) {
            if (error != null && field !in errors) errors[field] = error
        }

        put(FormField.NAME, nameError(form.name))

        if (spec.showsHost) put(FormField.HOST, hostError(form.type, form.host))
        if (spec.showsPort) put(FormField.PORT, portError(form.port))
        if (spec.showsBasePath) put(FormField.BASE_PATH, basePathError(form))
        if (spec.showsUsername) put(FormField.USERNAME, usernameError(form))

        if (spec.usesTree && form.treeUri.isBlank()) put(FormField.TREE_URI, FormError.REQUIRED)
        if (spec.usesUsbVolume) {
            if (form.usbVolumeId.isBlank()) {
                put(FormField.USB_VOLUME, FormError.REQUIRED)
            } else if (form.treeVolumeId != null && !form.treeVolumeId.equals(form.usbVolumeId.trim(), ignoreCase = true)) {
                put(FormField.TREE_URI, FormError.VOLUME_MISMATCH)
            }
        }
        if (spec.usesRemoteName) put(FormField.REMOTE_NAME, remoteNameError(form.remoteName, knownRemotes))

        credentialErrors(form).forEach { (field, error) -> put(field, error) }
        spec.advanced.forEach { field -> put(field, optionError(form, field)) }

        // Cross-field rules reuse the backends' own resolution logic (which throws on '..').
        try {
            crossFieldErrors(form, errors).forEach { (field, error) -> put(field, error) }
        } catch (_: StorageException) {
            put(FormField.BASE_PATH, FormError.PATH_TRAVERSAL)
        }
        return errors
    }

    private fun crossFieldErrors(form: DestinationForm, errors: Map<FormField, FormError>): Map<FormField, FormError> {
        val found = LinkedHashMap<FormField, FormError>()
        fun put(field: FormField, error: FormError) {
            found[field] = error
        }
        when (form.type) {
            DestinationType.SMB ->
                if (SmbLocation.parse(form.basePath, form.option(FormField.SMB_SHARE)) == null) {
                    put(FormField.BASE_PATH, FormError.NEEDS_SHARE)
                }
            DestinationType.NFS ->
                if (NfsMountPlanner.resolveTarget(form.basePath, form.option(FormField.NFS_EXPORT)) == null) {
                    val target = if (form.option(FormField.NFS_EXPORT).isBlank()) FormField.BASE_PATH else FormField.NFS_EXPORT
                    put(target, FormError.NEEDS_EXPORT)
                }
            DestinationType.WEBDAV ->
                if (FormField.HOST !in errors && FormField.PORT !in errors && FormField.BASE_PATH !in errors &&
                    FormField.WEBDAV_SCHEME !in errors
                ) {
                    try {
                        WebDavUrls.baseUrl(DestinationFormMapper.toConfig(form))
                    } catch (_: StorageException) {
                        put(FormField.HOST, FormError.INVALID_URL)
                    }
                }
            else -> Unit
        }
        return found
    }

    fun nameError(name: String): FormError? = when {
        name.isBlank() -> FormError.REQUIRED
        name.trim().length > MAX_NAME_LENGTH -> FormError.TOO_LONG
        name.any { it == '\n' || it == '\r' } -> FormError.INVALID_CHARACTERS
        else -> null
    }

    fun hostError(type: DestinationType, raw: String): FormError? {
        val host = raw.trim()
        if (host.isEmpty()) return FormError.REQUIRED
        if (type == DestinationType.WEBDAV && host.contains("://")) {
            val lower = host.lowercase()
            return if ((lower.startsWith("http://") || lower.startsWith("https://")) && host.none { it.isWhitespace() }) {
                null
            } else {
                FormError.INVALID_URL
            }
        }
        if (type == DestinationType.WEBDAV) {
            // WebDAV also accepts "host:port/path" without a scheme; validate only the host part.
            val hostPart = host.substringBefore('/').let { part ->
                if (part.startsWith("[")) part.substringBefore(']') + "]" else part.substringBefore(':')
            }
            return if (isValidHost(hostPart)) null else FormError.INVALID_HOST
        }
        return if (isValidHost(host)) null else FormError.INVALID_HOST
    }

    /** DNS name, IPv4, or IPv6 (optionally bracketed, with zone id). No scheme, port or path. */
    fun isValidHost(raw: String): Boolean {
        val host = raw.trim()
        if (host.isEmpty() || host.length > 253) return false
        if (host.startsWith("[") || host.count { it == ':' } >= 2) {
            val inner = host.removePrefix("[").removeSuffix("]")
            if (host.startsWith("[") != host.endsWith("]")) return false
            return inner.count { it == ':' } >= 2 && IPV6.matches(inner)
        }
        val labels = host.removeSuffix(".").split('.')
        return labels.isNotEmpty() && labels.all { HOST_LABEL.matches(it) }
    }

    fun portError(raw: String): FormError? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val port = text.toIntOrNull() ?: return FormError.INVALID_PORT
        return if (port in 1..65535 && text.all { it.isDigit() }) null else FormError.INVALID_PORT
    }

    private fun basePathError(form: DestinationForm): FormError? {
        val path = form.basePath.trim()
        if (path.any { it == '\u0000' || it == '\n' || it == '\r' }) return FormError.INVALID_CHARACTERS
        if (path.split('/', '\\').any { it == ".." }) return FormError.PATH_TRAVERSAL
        return when (form.type) {
            DestinationType.LOCAL_INTERNAL -> if (path.isNotEmpty() && !path.startsWith("/")) FormError.PATH_NOT_ABSOLUTE else null
            DestinationType.S3 -> if (path.trim('/').isEmpty()) FormError.NEEDS_BUCKET else null
            DestinationType.NFS ->
                if (form.option(FormField.NFS_EXPORT).isBlank() && path.isNotEmpty() && !path.startsWith("/")) {
                    FormError.PATH_NOT_ABSOLUTE
                } else {
                    null
                }
            else -> null
        }
    }

    private fun usernameError(form: DestinationForm): FormError? {
        val spec = form.spec
        val user = form.username.trim()
        if (user.any { it == '\n' || it == '\r' }) return FormError.INVALID_CHARACTERS
        if (spec.usernameInSecret) {
            // Access key id + secret are replaced together; both blank keeps the stored pair.
            val keeping = form.canKeepStoredSecret && form.password.isEmpty()
            return if (user.isEmpty() && !keeping) FormError.REQUIRED else null
        }
        if (user.isEmpty()) {
            val needed = spec.usernameRequired || (form.authMethod == AuthMethod.PASSWORD && form.type != DestinationType.WEBDAV)
            return if (needed) FormError.REQUIRED else null
        }
        if (form.type == DestinationType.MEGA && !EMAIL.matches(user)) return FormError.INVALID_EMAIL
        return null
    }

    private fun credentialErrors(form: DestinationForm): Map<FormField, FormError> {
        val errors = LinkedHashMap<FormField, FormError>()
        when (form.authMethod) {
            AuthMethod.NONE -> Unit
            AuthMethod.PASSWORD -> {
                if (form.password.any { it == '\n' || it == '\r' }) {
                    errors[FormField.PASSWORD] = FormError.INVALID_CHARACTERS
                } else if (form.password.isEmpty()) {
                    val keeping = form.canKeepStoredSecret &&
                        !(form.spec.usernameInSecret && form.username.isNotBlank())
                    if (!keeping) errors[FormField.PASSWORD] = FormError.REQUIRED
                }
            }
            AuthMethod.PRIVATE_KEY -> {
                val key = form.privateKey.trim()
                if (key.isEmpty()) {
                    if (!form.canKeepStoredSecret || form.passphrase.isNotEmpty()) errors[FormField.PRIVATE_KEY] = FormError.REQUIRED
                } else if (!looksLikePrivateKey(key)) {
                    errors[FormField.PRIVATE_KEY] = FormError.INVALID_KEY
                }
                if (form.passphrase.any { it == '\n' || it == '\r' }) errors[FormField.PASSPHRASE] = FormError.INVALID_CHARACTERS
            }
        }
        return errors
    }

    /** PEM / OpenSSH / PKCS#8 (`-----BEGIN ... PRIVATE KEY-----`) or PuTTY `.ppk`. */
    fun looksLikePrivateKey(text: String): Boolean {
        val key = text.trim()
        if (key.startsWith("PuTTY-User-Key-File-")) return true
        return key.startsWith("-----BEGIN ") && key.contains("PRIVATE KEY-----") && key.contains("-----END ")
    }

    fun remoteNameError(raw: String, knownRemotes: Set<String>?): FormError? {
        val name = raw.trim()
        if (name.isEmpty()) return FormError.REQUIRED
        if (!RcloneIni.isValidRemoteName(name)) return FormError.INVALID_REMOTE_NAME
        if (knownRemotes != null && name !in knownRemotes) return FormError.UNKNOWN_REMOTE
        return null
    }

    private fun optionError(form: DestinationForm, field: FormField): FormError? {
        val value = form.option(field)
        if (value.isEmpty() || field.isSwitch) return null
        if (value.any { it == '\n' || it == '\r' }) return FormError.INVALID_CHARACTERS
        return when (field) {
            FormField.FTPS_MODE ->
                if (value == DestinationOptions.FTPS_MODE_EXPLICIT || value == DestinationOptions.FTPS_MODE_IMPLICIT) null else FormError.INVALID_CHOICE
            FormField.WEBDAV_SCHEME -> if (value == "http" || value == "https") null else FormError.INVALID_CHOICE
            FormField.FTP_CONNECT_TIMEOUT, FormField.FTP_DATA_TIMEOUT,
            FormField.WEBDAV_CONNECT_TIMEOUT, FormField.WEBDAV_IO_TIMEOUT -> timeoutError(value)
            FormField.TLS_PIN -> try {
                TlsTrust.parsePin(value)
                null
            } catch (_: StorageException) {
                FormError.INVALID_PIN
            }
            FormField.SMB_SHARE, FormField.SMB_DOMAIN ->
                if (value.trim().any { it.isWhitespace() && it != ' ' } || value.trim('/', '\\', ' ').isEmpty()) FormError.INVALID_CHARACTERS else null
            FormField.NFS_EXPORT -> when {
                !value.trim().startsWith("/") -> FormError.PATH_NOT_ABSOLUTE
                value.split('/').any { it == ".." } -> FormError.PATH_TRAVERSAL
                else -> null
            }
            FormField.NFS_MOUNT_OPTIONS -> if (NfsMountPlanner.optionsAreSafe(value.trim())) null else FormError.UNSAFE_MOUNT_OPTIONS
            FormField.S3_PROVIDER, FormField.S3_REGION -> if (TOKEN.matches(value.trim())) null else FormError.INVALID_CHARACTERS
            FormField.S3_ENDPOINT -> endpointError(value.trim())
            else -> null
        }
    }

    fun timeoutError(raw: String): FormError? {
        val seconds = raw.trim().toIntOrNull() ?: return FormError.INVALID_TIMEOUT
        return if (seconds in 1..MAX_TIMEOUT_SECONDS) null else FormError.INVALID_TIMEOUT
    }

    /** `https://host[:port][/path]`, or a bare `host[:port]` as rclone accepts. */
    private fun endpointError(value: String): FormError? {
        if (value.any { it.isWhitespace() }) return FormError.INVALID_URL
        val lower = value.lowercase()
        val withoutScheme = when {
            lower.startsWith("https://") -> value.substring(8)
            lower.startsWith("http://") -> value.substring(7)
            value.contains("://") -> return FormError.INVALID_URL
            else -> value
        }
        val authority = withoutScheme.substringBefore('/')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        val port = if (authority.startsWith("[")) authority.substringAfter("]", "").removePrefix(":") else authority.substringAfter(':', "")
        if (!isValidHost(host)) return FormError.INVALID_URL
        if (port.isNotEmpty() && portError(port) != null) return FormError.INVALID_URL
        return null
    }
}
