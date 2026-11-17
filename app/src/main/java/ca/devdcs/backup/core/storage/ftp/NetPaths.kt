package ca.devdcs.backup.core.storage.ftp

import ca.devdcs.backup.core.storage.StorageException

/**
 * Session-relative path rules shared by the FTP and WebDAV backends.
 *
 * Exists so both protocols interpret contract paths identically: '/'-separated, relative to the
 * destination base, with `""`/`"/"` meaning the base itself, and `..` rejected so a crafted
 * archive name can never escape the configured base path on the server.
 */
internal object NetPaths {

    /** Clean segments of [path]; empty segments and `.` are dropped, `..` and NUL are refused. */
    fun segments(path: String): List<String> {
        if (path.indexOf('\u0000') >= 0) {
            throw StorageException(StorageException.Reason.MISCONFIGURED, "Path contains a NUL character")
        }
        val out = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> throw StorageException(
                    StorageException.Reason.MISCONFIGURED,
                    "Path must not contain '..' segments",
                )
                else -> out += segment
            }
        }
        return out
    }

    /** Canonical relative form of [path] ("" for the base). */
    fun normalize(path: String): String = segments(path).joinToString("/")

    /** Canonical relative parent of [path] ("" when [path] sits directly in the base). */
    fun parent(path: String): String = segments(path).dropLast(1).joinToString("/")

    /** Last segment of [path], or "" for the base itself. */
    fun name(path: String): String = segments(path).lastOrNull().orEmpty()

    /** Joins a relative directory and a child name into a canonical relative path. */
    fun child(dir: String, name: String): String = (segments(dir) + segments(name)).joinToString("/")
}
