package ca.devdcs.backup.ui.destinations

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import ca.devdcs.backup.R
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType

/*
 * Icons and localized labels for the destination editor. Kept apart from the pure form model so
 * that model stays free of Android resources and is unit-testable.
 */

/** Leading icon for a destination type in lists and the type picker. */
fun DestinationType.icon(): ImageVector = when (this) {
    DestinationType.LOCAL_INTERNAL -> Icons.Outlined.PhoneAndroid
    DestinationType.LOCAL_SAF -> Icons.Outlined.Folder
    DestinationType.USB_OTG -> Icons.Outlined.Usb
    DestinationType.FTP, DestinationType.FTPS -> Icons.Outlined.Dns
    DestinationType.SFTP, DestinationType.SSH -> Icons.Outlined.Terminal
    DestinationType.SMB -> Icons.Outlined.Lan
    DestinationType.WEBDAV -> Icons.Outlined.Public
    DestinationType.NFS -> Icons.Outlined.Storage
    DestinationType.RCLONE, DestinationType.S3, DestinationType.MEGA, DestinationType.PCLOUD -> Icons.Outlined.Cloud
}

@StringRes
fun DestinationType.descriptionRes(): Int = when (this) {
    DestinationType.LOCAL_INTERNAL -> R.string.destinations_type_desc_local_internal
    DestinationType.LOCAL_SAF -> R.string.destinations_type_desc_local_saf
    DestinationType.USB_OTG -> R.string.destinations_type_desc_usb_otg
    DestinationType.FTP -> R.string.destinations_type_desc_ftp
    DestinationType.FTPS -> R.string.destinations_type_desc_ftps
    DestinationType.SFTP -> R.string.destinations_type_desc_sftp
    DestinationType.SSH -> R.string.destinations_type_desc_ssh
    DestinationType.SMB -> R.string.destinations_type_desc_smb
    DestinationType.WEBDAV -> R.string.destinations_type_desc_webdav
    DestinationType.NFS -> R.string.destinations_type_desc_nfs
    DestinationType.RCLONE -> R.string.destinations_type_desc_rclone
    DestinationType.S3 -> R.string.destinations_type_desc_s3
    DestinationType.MEGA -> R.string.destinations_type_desc_mega
    DestinationType.PCLOUD -> R.string.destinations_type_desc_pcloud
}

@StringRes
fun FormError.messageRes(): Int = when (this) {
    FormError.REQUIRED -> R.string.destinations_error_required
    FormError.TOO_LONG -> R.string.destinations_error_too_long
    FormError.INVALID_HOST -> R.string.destinations_error_invalid_host
    FormError.INVALID_URL -> R.string.destinations_error_invalid_url
    FormError.INVALID_PORT -> R.string.destinations_error_invalid_port
    FormError.PATH_TRAVERSAL -> R.string.destinations_error_path_traversal
    FormError.PATH_NOT_ABSOLUTE -> R.string.destinations_error_path_not_absolute
    FormError.INVALID_CHARACTERS -> R.string.destinations_error_invalid_characters
    FormError.INVALID_TIMEOUT -> R.string.destinations_error_invalid_timeout
    FormError.INVALID_PIN -> R.string.destinations_error_invalid_pin
    FormError.INVALID_REMOTE_NAME -> R.string.destinations_error_invalid_remote_name
    FormError.UNKNOWN_REMOTE -> R.string.destinations_error_unknown_remote
    FormError.INVALID_KEY -> R.string.destinations_error_invalid_key
    FormError.NEEDS_SHARE -> R.string.destinations_error_needs_share
    FormError.NEEDS_EXPORT -> R.string.destinations_error_needs_export
    FormError.UNSAFE_MOUNT_OPTIONS -> R.string.destinations_error_unsafe_mount_options
    FormError.VOLUME_MISMATCH -> R.string.destinations_error_volume_mismatch
    FormError.INVALID_EMAIL -> R.string.destinations_error_invalid_email
    FormError.NEEDS_BUCKET -> R.string.destinations_error_needs_bucket
    FormError.INVALID_CHOICE -> R.string.destinations_error_invalid_choice
}

/** Field label; a few fields are named after what they mean for the given type. */
@StringRes
fun FormField.labelRes(type: DestinationType): Int = when (this) {
    FormField.NAME -> R.string.destinations_field_name
    FormField.HOST -> if (type == DestinationType.WEBDAV) R.string.destinations_field_server_url else R.string.destinations_field_host
    FormField.PORT -> R.string.destinations_field_port
    FormField.BASE_PATH -> when (type) {
        DestinationType.LOCAL_INTERNAL -> R.string.destinations_field_local_path
        DestinationType.LOCAL_SAF, DestinationType.USB_OTG -> R.string.destinations_field_subfolder
        DestinationType.SMB -> R.string.destinations_field_smb_path
        DestinationType.NFS -> R.string.destinations_field_nfs_path
        DestinationType.S3 -> R.string.destinations_field_bucket_path
        else -> R.string.destinations_field_base_path
    }
    FormField.USERNAME -> when (type) {
        DestinationType.S3 -> R.string.destinations_field_access_key_id
        DestinationType.MEGA -> R.string.destinations_field_email
        else -> R.string.destinations_field_username
    }
    FormField.PASSWORD -> if (type == DestinationType.S3) R.string.destinations_field_secret_access_key else R.string.destinations_field_password
    FormField.PRIVATE_KEY -> R.string.destinations_field_private_key
    FormField.PASSPHRASE -> R.string.destinations_field_passphrase
    FormField.REMOTE_NAME -> R.string.destinations_field_remote_name
    FormField.TREE_URI -> R.string.destinations_field_folder
    FormField.USB_VOLUME -> R.string.destinations_field_usb_volume
    FormField.FTPS_MODE -> R.string.destinations_field_ftps_mode
    FormField.FTP_CONNECT_TIMEOUT, FormField.WEBDAV_CONNECT_TIMEOUT -> R.string.destinations_field_connect_timeout
    FormField.FTP_DATA_TIMEOUT -> R.string.destinations_field_data_timeout
    FormField.WEBDAV_IO_TIMEOUT -> R.string.destinations_field_io_timeout
    FormField.TLS_PIN -> R.string.destinations_field_tls_pin
    FormField.WEBDAV_SCHEME -> R.string.destinations_field_scheme
    FormField.SMB_SHARE -> R.string.destinations_field_smb_share
    FormField.SMB_DOMAIN -> R.string.destinations_field_smb_domain
    FormField.SMB_ENCRYPT -> R.string.destinations_field_smb_encrypt
    FormField.SMB_GUEST -> R.string.destinations_field_smb_guest
    FormField.NFS_EXPORT -> R.string.destinations_field_nfs_export
    FormField.NFS_MOUNT_OPTIONS -> R.string.destinations_field_nfs_mount_options
    FormField.S3_PROVIDER -> R.string.destinations_field_s3_provider
    FormField.S3_REGION -> R.string.destinations_field_s3_region
    FormField.S3_ENDPOINT -> R.string.destinations_field_s3_endpoint
}

/** Supporting text shown under a field when it has no error, or null for none. */
@StringRes
fun FormField.hintRes(type: DestinationType): Int? = when (this) {
    FormField.HOST -> if (type == DestinationType.WEBDAV) R.string.destinations_hint_webdav_url else R.string.destinations_hint_host
    FormField.BASE_PATH -> when (type) {
        DestinationType.LOCAL_INTERNAL -> R.string.destinations_hint_local_path
        DestinationType.LOCAL_SAF, DestinationType.USB_OTG -> R.string.destinations_hint_subfolder
        DestinationType.SMB -> R.string.destinations_hint_smb_path
        DestinationType.NFS -> R.string.destinations_hint_nfs_path
        DestinationType.S3 -> R.string.destinations_hint_bucket_path
        else -> R.string.destinations_hint_base_path
    }
    FormField.FTP_CONNECT_TIMEOUT, FormField.FTP_DATA_TIMEOUT,
    FormField.WEBDAV_CONNECT_TIMEOUT, FormField.WEBDAV_IO_TIMEOUT -> R.string.destinations_hint_timeout
    FormField.TLS_PIN -> R.string.destinations_hint_tls_pin
    FormField.SMB_SHARE -> R.string.destinations_hint_smb_share
    FormField.SMB_ENCRYPT -> R.string.destinations_hint_smb_encrypt
    FormField.SMB_GUEST -> R.string.destinations_hint_smb_guest
    FormField.NFS_EXPORT -> R.string.destinations_hint_nfs_export
    FormField.NFS_MOUNT_OPTIONS -> R.string.destinations_hint_nfs_mount_options
    FormField.S3_PROVIDER -> R.string.destinations_hint_s3_provider
    FormField.S3_REGION -> R.string.destinations_hint_s3_region
    FormField.S3_ENDPOINT -> R.string.destinations_hint_s3_endpoint
    FormField.PRIVATE_KEY -> R.string.destinations_hint_private_key
    else -> null
}

@StringRes
fun AuthMethod.labelRes(): Int = when (this) {
    AuthMethod.NONE -> R.string.destinations_auth_none
    AuthMethod.PASSWORD -> R.string.destinations_auth_password
    AuthMethod.PRIVATE_KEY -> R.string.destinations_auth_private_key
}

/** The name to show; the onboarding's built-in destination is translated at render time. */
@Composable
fun Destination.displayName(): String = displayName(name)

@Composable
fun displayName(storedName: String): String =
    if (storedName == Destination.APP_STORAGE_NAME_MARKER) {
        stringResource(R.string.onboarding_destination_app_storage_name)
    } else {
        storedName
    }
