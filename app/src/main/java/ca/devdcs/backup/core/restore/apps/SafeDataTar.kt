package ca.devdcs.backup.core.restore.apps

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A data tar member that must not reach a root `tar -x` (traversal, write-through-symlink, foreign root). */
class UnsafeDataTarException(message: String) : IOException(message)

/** Counters of what [SafeDataTarWriter] passed through or dropped. */
data class SafeTarStats(
    val entries: Int,
    val bytes: Long,
    /** `lib` and everything below it (installd owns the native-library link). */
    val droppedLib: Int,
    /** Device nodes, FIFOs, sockets: never recreated. */
    val droppedSpecial: Int,
    /** Cache dirs left out because cache restore is off. */
    val droppedCache: Int,
)

/**
 * Re-emits app-data tar members for a privileged `tar -x` after checking every one, because the
 * archive is untrusted input to a root process: names must be relative, free of `..`, below
 * `<root>/`; nothing may be written through a symlink an earlier member created; hard links must
 * stay inside the tree. `<root>/lib` is always dropped (it is installd's link to the current
 * native-library dir and restoring it would point at the old install), as are device nodes and
 * FIFOs. SELinux pax headers are not forwarded: labels are recomputed by `restorecon` for the
 * new uid. Not thread-safe; one instance per extraction.
 */
class SafeDataTarWriter(
    output: OutputStream,
    private val root: String,
    private val includeCache: Boolean,
    private val onBytes: (Long) -> Unit = {},
) {
    private val tar = TarArchiveOutputStream(output).apply {
        setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        setAddPaxHeadersForNonAsciiNames(true)
    }
    private val symlinks = HashSet<String>()
    private val written = HashSet<String>()
    private val dropped = HashSet<String>()
    private var entries = 0
    private var bytes = 0L
    private var droppedLib = 0
    private var droppedSpecial = 0
    private var droppedCache = 0
    private var rootSeen = false

    init {
        require(root.isNotEmpty() && !root.contains('/') && root != "." && root != "..") { "invalid root $root" }
    }

    /** Sanitizes [raw] into `root/...` or throws; returns null when the member is dropped. */
    private fun accept(raw: String, isDirectory: Boolean): String? {
        val path = normalize(raw)
        if (path != root && !path.startsWith("$root/")) throw UnsafeDataTarException("member '$raw' is outside '$root/'")
        val rel = if (path == root) "" else path.substring(root.length + 1)
        val first = rel.substringBefore('/')
        if (first == AppRestoreScripts.LIB_ENTRY) {
            droppedLib++
            dropped += path
            return null
        }
        if (!includeCache && (first == "cache" || first == "code_cache")) {
            droppedCache++
            dropped += path
            return null
        }
        // Writing below a symlink created earlier would escape the tree as root.
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            if (parent in symlinks) throw UnsafeDataTarException("member '$raw' would be written through symlink '$parent'")
            parent = parent.substringBeforeLast('/', "")
        }
        if (path in symlinks) throw UnsafeDataTarException("member '$raw' replaces symlink '$path'")
        if (path == root) {
            if (!isDirectory) throw UnsafeDataTarException("'$root' must be a directory")
            rootSeen = true
        }
        return path
    }

    fun directory(raw: String, mode: Int?, mtimeMillis: Long?) {
        val path = accept(raw, isDirectory = true) ?: return
        if (!written.add(path)) return
        val entry = TarArchiveEntry("$path/", TarConstants.LF_DIR)
        entry.mode = (mode ?: DEFAULT_DIR_MODE) and PERM_MASK or DIR_BITS
        mtimeMillis?.let { entry.setModTime(it) }
        tar.putArchiveEntry(entry)
        tar.closeArchiveEntry()
        entries++
    }

    fun file(raw: String, size: Long, mode: Int?, mtimeMillis: Long?, content: InputStream) {
        val path = accept(raw, isDirectory = false) ?: return
        val entry = TarArchiveEntry(path, TarConstants.LF_NORMAL)
        entry.mode = (mode ?: DEFAULT_FILE_MODE) and PERM_MASK or FILE_BITS
        entry.size = size
        mtimeMillis?.let { entry.setModTime(it) }
        tar.putArchiveEntry(entry)
        val buffer = ByteArray(BUFFER)
        var remaining = size
        while (remaining > 0) {
            val n = content.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("member '$raw' ended $remaining bytes early")
            tar.write(buffer, 0, n)
            remaining -= n
            bytes += n
            onBytes(n.toLong())
        }
        tar.closeArchiveEntry()
        written += path
        entries++
    }

    fun symlink(raw: String, target: String, mtimeMillis: Long?) {
        val path = accept(raw, isDirectory = false) ?: return
        if (path == root) throw UnsafeDataTarException("'$root' must not be a symlink")
        if (target.isEmpty() || target.indexOf('\u0000') >= 0) throw UnsafeDataTarException("symlink '$raw' has an invalid target")
        val entry = TarArchiveEntry(path, TarConstants.LF_SYMLINK)
        entry.linkName = target
        entry.mode = SYMLINK_MODE
        mtimeMillis?.let { entry.setModTime(it) }
        tar.putArchiveEntry(entry)
        tar.closeArchiveEntry()
        symlinks += path
        written += path
        entries++
    }

    fun hardLink(raw: String, target: String) {
        val resolved = normalize(target)
        if (resolved in dropped) {
            accept(raw, isDirectory = false)?.let { dropped += it; droppedCache++ }
            return
        }
        val path = accept(raw, isDirectory = false) ?: return
        if (resolved != root && !resolved.startsWith("$root/")) throw UnsafeDataTarException("hard link '$raw' points outside '$root/'")
        if (resolved !in written || resolved in symlinks) throw UnsafeDataTarException("hard link '$raw' points at '$target', which is not an extracted file")
        val entry = TarArchiveEntry(path, TarConstants.LF_LINK)
        entry.linkName = resolved
        tar.putArchiveEntry(entry)
        tar.closeArchiveEntry()
        written += path
        entries++
    }

    fun special(raw: String) {
        normalize(raw)
        droppedSpecial++
    }

    /** Writes the end-of-archive records (does not close the underlying stream). */
    fun finish(): SafeTarStats {
        tar.finish()
        tar.flush()
        return SafeTarStats(entries, bytes, droppedLib, droppedSpecial, droppedCache)
    }

    val sawRoot: Boolean get() = rootSeen

    companion object {
        private const val BUFFER = 64 * 1024
        private const val PERM_MASK = 0xFFF
        private const val DIR_BITS = 0x4000
        private const val FILE_BITS = 0x8000
        private const val SYMLINK_MODE = 0xA1FF
        private const val DEFAULT_DIR_MODE = 0x1C9 // 0711
        private const val DEFAULT_FILE_MODE = 0x180 // 0600

        /** Drops `./` and empty segments; rejects absolute paths, `..`, NUL and backslash tricks. */
        fun normalize(raw: String): String {
            if (raw.indexOf('\u0000') >= 0) throw UnsafeDataTarException("member name contains NUL")
            if (raw.startsWith("/")) throw UnsafeDataTarException("absolute member '$raw'")
            val segments = raw.split('/').filter { it.isNotEmpty() && it != "." }
            if (segments.isEmpty()) throw UnsafeDataTarException("empty member name")
            if (segments.any { it == ".." || it.split('\\').any { s -> s == ".." } }) throw UnsafeDataTarException("'..' in member '$raw'")
            return segments.joinToString("/")
        }

        /**
         * Copies a toybox-made data tar from [input] into [writer], member by member. Sparse and
         * other exotic members are refused (toybox never writes them without `-S`).
         */
        fun copyTar(input: InputStream, writer: SafeDataTarWriter, isCancelled: () -> Boolean) {
            val tar = TarArchiveInputStream(input)
            while (true) {
                if (isCancelled()) throw kotlinx.coroutines.CancellationException("restore cancelled")
                val entry = tar.nextEntry ?: break
                val mtime = entry.lastModifiedTime?.toMillis()
                when {
                    entry.isSparse -> throw UnsafeDataTarException("sparse member '${entry.name}' is not supported")
                    entry.isDirectory -> writer.directory(entry.name, entry.mode, mtime)
                    entry.isSymbolicLink -> writer.symlink(entry.name, entry.linkName, mtime)
                    entry.isLink -> writer.hardLink(entry.name, entry.linkName)
                    entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> writer.special(entry.name)
                    entry.isFile -> writer.file(entry.name, entry.size, entry.mode, mtime, tar)
                    else -> writer.special(entry.name)
                }
            }
        }
    }
}
