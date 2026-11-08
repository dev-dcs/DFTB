package ca.devdcs.backup.core.storage.ssh

import ca.devdcs.backup.core.exec.root.RootScriptBuilder
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageException

/**
 * Pure POSIX `sh` script builders and output parsers shared by every backend that manages files by
 * running shell commands: the SSH exec fallback (server without the SFTP subsystem) and the NFS
 * backend (files under a root-only mountpoint). Kept Android-free so the quoting and parsing, which
 * decide whether we write to the right file, are JVM-unit-tested. Every path is quoted with the
 * project's single audited quoting routine ([RootScriptBuilder.shellQuote]).
 */
internal object PosixShell {

    /** `stat -c` format: size/mtime/type/path. '/' cannot appear in the first three fields. */
    const val STAT_FORMAT = "%s/%Y/%F/%n"
    const val ABSENT_MARKER = "__DFTB_ABSENT__"
    const val NOT_DIR_MARKER = "__DFTB_NOT_DIR__"

    fun quote(arg: String): String = RootScriptBuilder.shellQuote(arg)

    /** Prints one [STAT_FORMAT] line for [absPath] (following links), or [ABSENT_MARKER]. */
    fun statScript(absPath: String): String {
        val q = quote(absPath)
        return "if [ -e $q ]; then stat -L -c '$STAT_FORMAT' $q; else echo $ABSENT_MARKER; fi"
    }

    /**
     * One [STAT_FORMAT] line per direct child of [absDir] (dot-files included, broken links
     * skipped), or [NOT_DIR_MARKER] when [absDir] is missing or not a directory.
     */
    fun listScript(absDir: String): String {
        val q = quote(absDir)
        return "d=$q; if [ ! -d \"\$d\" ]; then echo $NOT_DIR_MARKER; exit 0; fi; " +
            "for f in \"\$d\"/* \"\$d\"/.[!.]* \"\$d\"/..?*; do " +
            "if [ -e \"\$f\" ]; then stat -L -c '$STAT_FORMAT' \"\$f\" || exit 1; fi; done; exit 0"
    }

    fun mkdirsScript(absDir: String): String = "mkdir -p ${quote(absDir)}"

    /** Removes a file, or an empty directory; a missing path succeeds. */
    fun deleteScript(absPath: String): String {
        val q = quote(absPath)
        return "if [ -d $q ] && [ ! -L $q ]; then rmdir $q; else rm -f $q; fi"
    }

    /** Overwriting move; creates the target's parent first (`mv -f` is a rename(2) on one fs). */
    fun renameScript(absFrom: String, absTo: String, absToParent: String): String =
        "mkdir -p ${quote(absToParent)} && mv -f ${quote(absFrom)} ${quote(absTo)}"

    fun dfScript(absPath: String): String = "df -Pk ${quote(absPath)}"

    /** Streams [absPath] from byte [offset] to stdout (`tail -c +N` is 1-based). */
    fun readCommand(absPath: String, offset: Long): String =
        if (offset <= 0L) "cat ${quote(absPath)}" else "tail -c +${offset + 1} ${quote(absPath)}"

    /**
     * Every path from just below [root] down to [absPath] (inclusive when [includeFinal]), e.g.
     * root `/m`, path `/m/a/b` -> `/m/a`, `/m/a/b`. Null when [absPath] is not inside [root].
     */
    fun componentPaths(root: String, absPath: String, includeFinal: Boolean): List<String>? {
        val rootSegs = RemotePaths.segments(root)
        val segs = RemotePaths.segments(absPath)
        if (!root.startsWith("/") || !absPath.startsWith("/") || segs.size < rootSegs.size || segs.subList(0, rootSegs.size) != rootSegs) {
            return null
        }
        val end = if (includeFinal) segs.size else segs.size - 1
        return (rootSegs.size + 1..end).map { n -> "/" + segs.subList(0, n).joinToString("/") }
    }

    /**
     * Shell guard confining a root operation to [root]: fails (exit 1, "Permission denied") when
     * any path component below [root] — or [absPath] itself when [includeFinal] — is a symbolic
     * link, so a link planted on a network share cannot redirect a root write/read/delete outside
     * the mount. `..` cannot occur ([RemotePaths] rejects it). Returns a complete `cmd;` prefix.
     */
    fun symlinkGuard(root: String, absPath: String, includeFinal: Boolean): String {
        val paths = componentPaths(root, absPath, includeFinal)
            ?: return "echo 'Permission denied: path outside the mount' >&2; exit 1; "
        if (paths.isEmpty()) return ""
        return "for __p in ${paths.joinToString(" ") { quote(it) }}; do if [ -L \"\$__p\" ]; then " +
            "echo \"Permission denied: symbolic link \$__p\" >&2; exit 1; fi; done; "
    }

    /** One parsed `stat -c` [STAT_FORMAT] line. */
    data class StatLine(val sizeBytes: Long?, val modifiedAtMillis: Long?, val isDirectory: Boolean, val path: String) {
        val name: String get() = path.trimEnd('/').substringAfterLast('/')
    }

    /** Parse one [STAT_FORMAT] line; null for blank or malformed lines. */
    fun parseStatLine(line: String): StatLine? {
        val trimmed = line.trimEnd('\r')
        if (trimmed.isBlank()) return null
        val parts = trimmed.split('/', limit = 4)
        if (parts.size < 4) return null
        val size = parts[0].trim().toLongOrNull() ?: return null
        val seconds = parts[1].trim().substringBefore('.').toLongOrNull()
        val type = parts[2].trim().lowercase()
        return StatLine(
            sizeBytes = size,
            modifiedAtMillis = seconds?.let { it * 1000L },
            isDirectory = type == "directory",
            path = parts[3],
        )
    }

    /**
     * Parse `df -Pk` output into bytes. Tolerates a wrapped line (long device names on non-POSIX
     * `df`) and spaces in the device or mountpoint by anchoring on the `NN%` capacity column.
     */
    fun parseDf(lines: List<String>): SpaceInfo? {
        val body = lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("Filesystem", ignoreCase = true) }
        if (body.isEmpty()) return null
        val tokens = body.joinToString(" ").split(Regex("\\s+"))
        // Use the last data row: find the last capacity column preceded by three numbers.
        for (i in tokens.indices.reversed()) {
            if (i < 3) break
            val capacity = tokens[i]
            if (!(capacity.matches(Regex("\\d+%")) || capacity == "-")) continue
            val blocks = tokens[i - 3].toLongOrNull() ?: continue
            tokens[i - 2].toLongOrNull() ?: continue
            val available = tokens[i - 1].toLongOrNull() ?: continue
            return SpaceInfo(freeBytes = available * 1024L, totalBytes = blocks * 1024L)
        }
        return null
    }

    /** Best-effort classification of a failed command's stderr into a [StorageException.Reason]. */
    fun classifyError(stderr: String): StorageException.Reason {
        val text = stderr.lowercase()
        return when {
            "no space left" in text || "quota exceeded" in text || "disk quota" in text -> StorageException.Reason.NO_SPACE
            "permission denied" in text || "read-only file system" in text || "operation not permitted" in text ||
                "directory not empty" in text ->
                StorageException.Reason.PERMISSION
            "no such device" in text || "unknown filesystem" in text || "not supported" in text -> StorageException.Reason.UNSUPPORTED
            "no such file" in text || "not a directory" in text || "not found" in text -> StorageException.Reason.NOT_FOUND
            "timed out" in text || "timeout" in text -> StorageException.Reason.TIMEOUT
            "stale" in text || "input/output error" in text || "connection" in text || "network" in text ||
                "no route" in text || "host is down" in text -> StorageException.Reason.NETWORK
            else -> StorageException.Reason.PROTOCOL
        }
    }
}
