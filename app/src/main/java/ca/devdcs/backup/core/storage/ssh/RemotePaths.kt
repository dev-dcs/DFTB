package ca.devdcs.backup.core.storage.ssh

import ca.devdcs.backup.core.storage.StorageException

/**
 * Pure helpers that turn the contract's destination-relative paths (`"a/b.tar"`, `""` = base) into
 * protocol paths. Exists so the SFTP, SMB and NFS backends normalise identically and so a `..`
 * segment can never walk a session out of its configured base directory.
 */
internal object RemotePaths {

    /**
     * The non-empty, validated segments of [path]; `.` and empty segments are dropped. Only '/'
     * separates: a backslash is a legal filename character on SFTP/NFS (SMB converts its own).
     */
    fun segments(path: String): List<String> {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        for (part in parts) {
            if (part == ".." || part.indexOf('\u0000') >= 0) {
                throw StorageException(
                    StorageException.Reason.MISCONFIGURED,
                    "Illegal path segment in \"$path\"",
                )
            }
        }
        return parts
    }

    /** Canonical relative form: segments joined by '/', `""` for the base itself. */
    fun normalize(path: String): String = segments(path).joinToString("/")

    /** Relative parent of [path] (`""` when [path] is directly under the base). */
    fun parent(path: String): String = segments(path).dropLast(1).joinToString("/")

    /** Last segment of [path], or `""` for the base. */
    fun name(path: String): String = segments(path).lastOrNull().orEmpty()

    /** Relative path of [name] inside the relative directory [parent]. */
    fun child(parent: String, name: String): String {
        val p = normalize(parent)
        return if (p.isEmpty()) name else "$p/$name"
    }

    /**
     * Join a protocol base ([base], absolute like `/srv/b`, `/`, or relative like `backups` / `""`
     * meaning the login directory) with a contract-relative [relative] path.
     */
    fun join(base: String, relative: String): String {
        val rel = segments(relative)
        val absolute = base.startsWith("/")
        val baseSegs = segments(base)
        val all = baseSegs + rel
        return when {
            absolute -> "/" + all.joinToString("/")
            all.isEmpty() -> "."
            else -> all.joinToString("/")
        }
    }
}
