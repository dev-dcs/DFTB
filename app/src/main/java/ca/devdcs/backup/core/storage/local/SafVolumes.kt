package ca.devdcs.backup.core.storage.local

/**
 * Pure helpers for relating a Storage Access Framework tree to a physical storage volume.
 *
 * Exists because the SAF exposes no capacity API: the only way to report free space for a picked
 * folder is to recognise ExternalStorageProvider's `"<volume>:<path>"` document ids and stat the
 * matching [android.os.storage.StorageVolume]. Kept free of Android types so it is unit-tested.
 */
object SafVolumes {

    /** Authority of AOSP's ExternalStorageProvider (internal shared storage, SD cards, USB OTG). */
    const val EXTERNAL_STORAGE_AUTHORITY: String = "com.android.externalstorage.documents"

    /** ExternalStorageProvider's volume id for the primary (built-in) shared storage. */
    const val PRIMARY_VOLUME_ID: String = "primary"

    /**
     * The volume id encoded in an ExternalStorageProvider document id (`"1A2B-3C4D:Backups"` ->
     * `"1A2B-3C4D"`, `"primary:"` -> `"primary"`), or null for other providers' opaque ids.
     */
    fun volumeIdFromDocumentId(authority: String?, documentId: String?): String? {
        if (authority != EXTERNAL_STORAGE_AUTHORITY || documentId.isNullOrEmpty()) return null
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        return documentId.substring(0, colon)
    }

    /**
     * True when a StorageVolume with [volumeUuid] / [isPrimary] is the volume named [volumeId].
     * ExternalStorageProvider uses "primary" for the built-in volume and the filesystem UUID
     * (case-insensitive) for removable ones.
     */
    fun matches(volumeId: String, volumeUuid: String?, isPrimary: Boolean): Boolean =
        if (volumeId.equals(PRIMARY_VOLUME_ID, ignoreCase = true)) {
            isPrimary
        } else {
            volumeUuid != null && volumeUuid.equals(volumeId, ignoreCase = true)
        }
}
