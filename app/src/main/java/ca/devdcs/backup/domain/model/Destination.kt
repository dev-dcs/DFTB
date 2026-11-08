package ca.devdcs.backup.domain.model

import kotlinx.serialization.Serializable

/**
 * Non-secret connection settings for a [Destination]. Passwords, keys and tokens live in the
 * credential vault under [Destination.credentialRef]; this object may be logged and exported.
 * Only the fields relevant to the [DestinationType] are populated; [options] carries
 * protocol-specific extras (rclone flags, SMB domain, S3 region...) without schema changes.
 */
@Serializable
data class DestinationConfig(
    val host: String? = null,
    val port: Int? = null,
    val basePath: String? = null,
    val username: String? = null,
    val remoteName: String? = null,
    val treeUri: String? = null,
    val usbVolumeId: String? = null,
    val options: Map<String, String> = emptyMap(),
) {
    companion object {
        val EMPTY = DestinationConfig()
    }
}

/**
 * Somewhere backups are written. Free/total bytes are the last values a connectivity check
 * observed, cached so the dashboard can show capacity without touching the network.
 */
data class Destination(
    val id: String,
    val name: String,
    val type: DestinationType,
    val config: DestinationConfig = DestinationConfig.EMPTY,
    val credentialRef: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastVerifiedAt: Long? = null,
    val lastKnownFreeBytes: Long? = null,
    val lastKnownTotalBytes: Long? = null,
) {
    /** 0f..1f used fraction, or null when capacity has never been observed. */
    val usedFraction: Float?
        get() {
            val total = lastKnownTotalBytes ?: return null
            val free = lastKnownFreeBytes ?: return null
            if (total <= 0L) return null
            return ((total - free).toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        }

    companion object {
        /**
         * Stable, non-localized [name] stored for the built-in "app storage" destination the
         * onboarding wizard creates. It is never shown verbatim: display sites translate it at
         * render time via `R.string.onboarding_destination_app_storage_name`, so a later language
         * switch is reflected instead of freezing the creation-time language into Room.
         */
        const val APP_STORAGE_NAME_MARKER: String = "dftb:builtin-app-storage"
    }
}
