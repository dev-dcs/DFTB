package ca.devdcs.backup.core.restore.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Where a file unit is restored to. */
sealed interface FileRestoreTarget {
    /** Back where the files were backed up from (needs the profile snapshot to map roots). */
    data object OriginalLocation : FileRestoreTarget

    /** An absolute folder; written in-process when possible, else through root/Shizuku. */
    data class Folder(val path: String) : FileRestoreTarget

    /** A SAF tree the user granted persistent write access to. */
    data class SafTree(val treeUri: String) : FileRestoreTarget
}

/** What to do with a TWRP (Nandroid) folder backup. */
enum class TwrpRestoreMode {
    /** Copy the TWRP folder verbatim (default), so TWRP/OrangeFox can restore it in recovery. */
    COPY_FOLDER,

    /** Extract the `*.win` tar volumes' files into a chosen folder (never over the live system). */
    EXTRACT_VOLUMES,
}

/** A concrete place to open as a [TargetFileSystem]. */
sealed interface TargetLocation {
    data class AbsolutePath(val path: String) : TargetLocation
    data class Tree(val treeUri: String) : TargetLocation
}

enum class TargetUnavailableReason {
    /** Not writable by the app and no privileged tier is usable in the current mode. */
    NO_PRIVILEGE,
    /** The SAF grant is missing or was revoked. */
    SAF_PERMISSION_MISSING,
    /** Not an absolute path / not a tree URI. */
    INVALID_LOCATION,
}

sealed interface OpenedTarget {
    data class Ready(val target: TargetFileSystem, val tier: ElevationTier) : OpenedTarget
    data class Unavailable(val reason: TargetUnavailableReason, val detail: String) : OpenedTarget
}

/**
 * Opens restore targets. A seam so the file restorer is tested with plain folders while the app
 * chooses NIO, SAF or a privileged shell per location.
 */
interface RestoreTargetOpener {
    suspend fun open(location: TargetLocation): OpenedTarget

    /** Absolute path of the primary shared storage (where TWRP looks for `TWRP/BACKUPS`). */
    fun primaryStorageRoot(): String
}

/**
 * Android [RestoreTargetOpener]: in-process NIO when the app can write the folder (own storage,
 * all-files access), else the current privileged tier, else unavailable with the reason.
 */
@Singleton
class AndroidRestoreTargetOpener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val privileged: PrivilegedTargets,
    @IoDispatcher private val io: CoroutineDispatcher,
) : RestoreTargetOpener {

    override suspend fun open(location: TargetLocation): OpenedTarget = when (location) {
        is TargetLocation.AbsolutePath -> openPath(location.path)
        is TargetLocation.Tree -> openTree(location.treeUri)
    }

    private suspend fun openPath(path: String): OpenedTarget {
        if (!path.startsWith("/")) return OpenedTarget.Unavailable(TargetUnavailableReason.INVALID_LOCATION, path)
        val dir = File(path)
        val writable = withContext(io) { directlyWritable(dir) }
        if (writable) return OpenedTarget.Ready(NioTargetFileSystem(dir, io), ElevationTier.NONE)
        val shell = privileged.open(path)
        if (shell != null) return OpenedTarget.Ready(shell, if (shell.canSetOwnership) ElevationTier.ROOT else ElevationTier.SHIZUKU)
        return OpenedTarget.Unavailable(TargetUnavailableReason.NO_PRIVILEGE, path)
    }

    /** Existing writable dir, or creatable under the nearest existing writable ancestor. */
    private fun directlyWritable(dir: File): Boolean {
        if (dir.isDirectory) return dir.canWrite() && dir.canExecute()
        var ancestor: File? = dir.parentFile
        while (ancestor != null && !ancestor.exists()) ancestor = ancestor.parentFile
        if (ancestor == null || !ancestor.isDirectory || !ancestor.canWrite()) return false
        return dir.mkdirs() || dir.isDirectory
    }

    private suspend fun openTree(treeUri: String): OpenedTarget {
        val uri = try {
            Uri.parse(treeUri)
        } catch (e: Exception) {
            return OpenedTarget.Unavailable(TargetUnavailableReason.INVALID_LOCATION, treeUri)
        }
        if (uri.scheme != "content") return OpenedTarget.Unavailable(TargetUnavailableReason.INVALID_LOCATION, treeUri)
        val granted = withContext(io) {
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        }
        if (!granted) {
            // A grant can also be transient (just picked); probe it before refusing.
            val transient = context.checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!transient) return OpenedTarget.Unavailable(TargetUnavailableReason.SAF_PERMISSION_MISSING, treeUri)
        }
        return try {
            OpenedTarget.Ready(SafTargetFileSystem(context, uri, io), ElevationTier.NONE)
        } catch (e: IllegalArgumentException) {
            OpenedTarget.Unavailable(TargetUnavailableReason.INVALID_LOCATION, treeUri)
        }
    }

    @Suppress("DEPRECATION")
    override fun primaryStorageRoot(): String = Environment.getExternalStorageDirectory().absolutePath
}
