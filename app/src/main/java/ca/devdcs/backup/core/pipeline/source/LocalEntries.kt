package ca.devdcs.backup.core.pipeline.source

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.core.pipeline.SourceItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import java.io.File
import java.io.FileInputStream

/**
 * In-process access to paths the app can read itself (app dirs, legacy/All-files shared storage):
 * lstat-based metadata and a symlink-safe recursive tree emitter. Shared by the app and file-tree
 * sources so both capture metadata the same way.
 */
internal object LocalEntries {

    /** lstat of [path]; null when it vanished or is not stat-able. */
    fun lstat(path: String): android.system.StructStat? = try {
        Os.lstat(path)
    } catch (e: ErrnoException) {
        null
    }

    /** Metadata from [stat]; [preserve] false keeps only the modification time. */
    fun metadataOf(path: String, stat: android.system.StructStat, preserve: Boolean, selinux: Boolean): EntryMetadata {
        val mtime = stat.st_mtim.tv_sec * 1000 + stat.st_mtim.tv_nsec / 1_000_000
        if (!preserve) return EntryMetadata(modifiedAtMillis = mtime)
        val context = if (selinux) readContext(path) else null
        return EntryMetadata(
            modifiedAtMillis = mtime,
            mode = stat.st_mode and PERMISSION_BITS,
            uid = stat.st_uid,
            gid = stat.st_gid,
            seLinuxContext = context,
        )
    }

    private fun readContext(path: String): String? = try {
        val raw = Os.getxattr(path, "security.selinux")
        String(raw, Charsets.UTF_8).trimEnd('\u0000').ifEmpty { null }
    } catch (e: ErrnoException) {
        null
    } catch (e: UnsupportedOperationException) {
        null
    }

    fun isSymlink(stat: android.system.StructStat) = OsConstants.S_ISLNK(stat.st_mode)
    fun isDirectory(stat: android.system.StructStat) = OsConstants.S_ISDIR(stat.st_mode)
    fun isRegular(stat: android.system.StructStat) = OsConstants.S_ISREG(stat.st_mode)

    fun readlink(path: String): String? = try {
        Os.readlink(path)
    } catch (e: ErrnoException) {
        null
    }

    /**
     * Emit [root] (a readable directory) as Directory/File/Symlink items under [archiveRoot].
     * Links are stored, never followed; sockets/devices/FIFOs are skipped. Returns the number of
     * entries that could not be listed or stat-ed.
     */
    suspend fun emitTree(
        collector: FlowCollector<SourceItem>,
        root: File,
        archiveRoot: String,
        preserve: Boolean,
        selinux: Boolean,
        childPath: (String) -> String,
    ): Int {
        var unreadable = 0
        val rootStat = lstat(root.path) ?: return 1
        collector.emit(SourceItem.Directory(archiveRoot, metadataOf(root.path, rootStat, preserve, selinux)))
        val stack = ArrayDeque<Pair<File, String>>()
        stack.addLast(root to "")
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (dir, rel) = stack.removeLast()
            val children = dir.list()
            if (children == null) {
                unreadable++
                continue
            }
            children.sort()
            val subdirs = ArrayList<Pair<File, String>>()
            for (name in children) {
                val file = File(dir, name)
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                val stat = lstat(file.path)
                if (stat == null) {
                    unreadable++
                    continue
                }
                val archivePath = childPath(childRel)
                val meta = metadataOf(file.path, stat, preserve, selinux)
                when {
                    isSymlink(stat) -> {
                        val target = readlink(file.path)
                        if (target == null) unreadable++ else collector.emit(SourceItem.Symlink(archivePath, target, meta))
                    }
                    isDirectory(stat) -> {
                        collector.emit(SourceItem.Directory(archivePath, meta))
                        subdirs += file to childRel
                    }
                    isRegular(stat) -> {
                        val size = stat.st_size
                        val path = file.path
                        collector.emit(SourceItem.File(archivePath, size, meta) { ExactLengthInputStream(FileInputStream(path), size, path) })
                    }
                    else -> Unit
                }
            }
            // Depth-first in name order: push reversed so the first name is processed next.
            for (i in subdirs.indices.reversed()) stack.addLast(subdirs[i])
        }
        return unreadable
    }

    private const val PERMISSION_BITS = 4095 // 07777
}
