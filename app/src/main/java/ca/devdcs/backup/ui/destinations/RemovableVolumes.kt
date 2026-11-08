package ca.devdcs.backup.ui.destinations

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import ca.devdcs.backup.core.storage.local.SafVolumes

/** A mounted removable volume the user can pick for a USB OTG destination. */
data class RemovableVolumeOption(
    /** ExternalStorageProvider volume id (filesystem UUID), stored as `usbVolumeId`. */
    val id: String,
    val description: String,
    val writable: Boolean,
)

/**
 * StorageManager/SAF glue for the destination editor.
 *
 * Exists because a USB OTG destination needs both a volume id (so the backend can check the drive
 * is plugged in) and a tree grant on that same volume; this lists candidate volumes, opens the
 * folder picker directly on one, and reads back which volume a picked tree belongs to.
 */
object RemovableVolumes {

    /** Mounted, removable, non-primary volumes with a UUID. */
    fun list(context: Context): List<RemovableVolumeOption> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return manager.storageVolumes
            .filter { it.isRemovable && !it.isPrimary && it.uuid != null }
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .map { volume ->
                RemovableVolumeOption(
                    id = requireNotNull(volume.uuid),
                    description = volume.getDescription(context),
                    writable = volume.state == Environment.MEDIA_MOUNTED,
                )
            }
    }

    /** Folder-picker intent rooted at volume [id], or null when that volume is not present. */
    fun openTreeIntent(context: Context, id: String): Intent? {
        val manager = context.getSystemService(StorageManager::class.java) ?: return null
        val volume: StorageVolume = manager.storageVolumes
            .firstOrNull { SafVolumes.matches(id, it.uuid, it.isPrimary) } ?: return null
        return volume.createOpenDocumentTreeIntent()
    }

    /** Volume id encoded in a tree URI from ExternalStorageProvider, or null for other providers. */
    fun volumeIdOfTree(treeUri: String): String? {
        if (treeUri.isBlank()) return null
        val uri = Uri.parse(treeUri)
        if (!DocumentsContract.isTreeUri(uri)) return null
        return SafVolumes.volumeIdFromDocumentId(uri.authority, DocumentsContract.getTreeDocumentId(uri))
    }

    /** Human-readable form of a tree URI (`1A2B-3C4D:Backups` → `Backups`), for display only. */
    fun displayPath(treeUri: String): String {
        if (treeUri.isBlank()) return ""
        val uri = Uri.parse(treeUri)
        val docId = if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri) else uri.lastPathSegment
        val path = docId?.substringAfter(':', docId).orEmpty()
        return path.ifEmpty { "/" }
    }
}
