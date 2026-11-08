package ca.devdcs.backup.core.pipeline.archive

/**
 * The single place that decides whether an archive-internal path is safe. Writers use
 * [requireSafeForWrite] so we never produce an archive that could escape the restore root;
 * readers use [normalizeForRead] so a hostile or foreign archive cannot either (zip-slip).
 */
object ArchivePaths {

    /**
     * Validates a path supplied by our own sources. Strict: absolute paths, empty paths, NUL,
     * '..' segments and empty/'.' segments are rejected rather than repaired, because a source
     * producing them is a bug. A single trailing '/' (directory style) is tolerated and removed.
     *
     * @return the path without a trailing '/'.
     * @throws ArchiveArgumentException with [ArchiveErrorKind.UNSAFE_PATH].
     */
    fun requireSafeForWrite(path: String): String {
        val trimmed = path.removeSuffix("/")
        fun reject(why: String): Nothing =
            throw ArchiveArgumentException(ArchiveErrorKind.UNSAFE_PATH, "Unsafe archive path '${printable(path)}': $why")
        if (trimmed.isEmpty()) reject("empty")
        if (trimmed.indexOf('\u0000') >= 0) reject("contains NUL")
        if (trimmed.startsWith("/")) reject("absolute")
        for (segment in trimmed.split('/')) {
            when (segment) {
                "" -> reject("empty segment")
                "." -> reject("'.' segment")
                ".." -> reject("'..' segment")
            }
        }
        return trimmed
    }

    /**
     * Normalizes a path read from an archive of any origin: drops leading "./" and inner "."
     * segments and collapses repeated '/', then rejects anything absolute, containing NUL or
     * containing '..'. Returns null for the archive root itself ("./").
     *
     * @throws ArchiveIoException with [ArchiveErrorKind.UNSAFE_PATH].
     */
    fun normalizeForRead(raw: String): String? {
        fun reject(why: String): Nothing =
            throw ArchiveIoException(ArchiveErrorKind.UNSAFE_PATH, "Archive entry '${printable(raw)}' refused: $why")
        if (raw.indexOf('\u0000') >= 0) reject("contains NUL")
        if (raw.startsWith("/")) reject("absolute path")
        if (raw.length >= 2 && raw[1] == ':' && raw[0].isLetter()) reject("drive-letter path")
        if (raw.contains('\\') && raw.split('\\', '/').any { it == ".." }) reject("'..' segment")
        val segments = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) reject("'..' segment")
        return if (segments.isEmpty()) null else segments.joinToString("/")
    }

    private fun printable(s: String): String = s.replace("\u0000", "\\0")
}
