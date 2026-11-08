package ca.devdcs.backup.core.storage

/**
 * Pure path arithmetic for [StorageSession] paths.
 *
 * Exists so every backend interprets the contract's '/'-separated, base-relative paths the same
 * way and so path traversal (`../../etc`) is rejected in one audited, unit-tested place instead of
 * ad hoc in each protocol. No Android or I/O dependencies.
 */
object StoragePaths {

    /**
     * Normalizes a session path to its canonical relative form: no leading/trailing '/', no empty
     * or `.` segments, `..` resolved. `""` is the base itself.
     * @throws StorageException (MISCONFIGURED) when the path escapes the base or contains NUL.
     */
    fun normalize(path: String): String = segments(path).joinToString("/")

    /** The normalized segments of [path]; empty for the base. Same rules as [normalize]. */
    fun segments(path: String): List<String> {
        if (path.indexOf('\u0000') >= 0) {
            throw StorageException(StorageException.Reason.MISCONFIGURED, "Path contains a NUL character")
        }
        val out = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (out.isEmpty()) {
                        throw StorageException(
                            StorageException.Reason.MISCONFIGURED,
                            "Path escapes the destination base: $path",
                        )
                    }
                    out.removeAt(out.lastIndex)
                }
                else -> out.add(part)
            }
        }
        return out
    }

    /** Normalized parent of [path]; `""` for top-level entries and for the base itself. */
    fun parent(path: String): String {
        val segs = segments(path)
        return if (segs.size <= 1) "" else segs.subList(0, segs.size - 1).joinToString("/")
    }

    /** Last segment of [path], or `""` for the base. */
    fun name(path: String): String = segments(path).lastOrNull().orEmpty()

    /** Joins [parent] and [child] and normalizes the result (so `child` may not escape either). */
    fun join(parent: String, child: String): String = normalize(
        if (parent.isEmpty()) child else "$parent/$child",
    )

    /**
     * True when absolute filesystem path [candidate] equals or lies under absolute [base].
     * Both are expected to be canonical (symlinks resolved) so a link cannot smuggle a path out.
     * Comparison is segment-wise, so `/data/app2` is NOT under `/data/app`.
     */
    fun isWithin(base: String, candidate: String): Boolean {
        val b = base.trimEnd('/')
        if (b.isEmpty()) return candidate.startsWith("/")
        return candidate == b || candidate.startsWith("$b/")
    }
}
