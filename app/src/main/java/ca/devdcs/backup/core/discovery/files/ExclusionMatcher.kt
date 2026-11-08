package ca.devdcs.backup.core.discovery.files

/**
 * Default "never worth backing up" exclusions, evaluated on root-relative paths so every walker
 * (NIO, SAF, find, MediaStore) prunes identically.
 *
 * Rules (case-insensitive): any directory named `.thumbnails`, `cache`, `.cache` or `code_cache`;
 * any directory or file whose name starts with `.trash` (covers `.Trash-1000`, MediaStore's
 * `.trashed-…` files); and the `Android/data` segment pair anywhere below the root. Because the
 * check only sees the path *below* the root, a root that is itself `…/Android/data/…` is treated as
 * explicitly targeted and is not excluded.
 */
class ExclusionMatcher private constructor(val enabled: Boolean) {

    /** True when directory [relativePath] (and everything under it) must be skipped. */
    fun excludesDirectory(relativePath: String): Boolean {
        if (!enabled || relativePath.isEmpty()) return false
        return segmentsExcluded(relativePath.split('/').filter { it.isNotEmpty() }, lastIsFile = false)
    }

    /** True when file [relativePath] must be skipped (by its own name or any ancestor directory). */
    fun excludesFile(relativePath: String): Boolean {
        if (!enabled || relativePath.isEmpty()) return false
        return segmentsExcluded(relativePath.split('/').filter { it.isNotEmpty() }, lastIsFile = true)
    }

    private fun segmentsExcluded(segments: List<String>, lastIsFile: Boolean): Boolean {
        val dirCount = if (lastIsFile) segments.size - 1 else segments.size
        for (i in 0 until dirCount) {
            val name = segments[i].lowercase()
            if (name in EXCLUDED_DIR_NAMES || name.startsWith(TRASH_PREFIX)) return true
            if (name == "data" && i > 0 && segments[i - 1].equals("Android", ignoreCase = true)) return true
        }
        if (lastIsFile && segments.isNotEmpty() && segments.last().lowercase().startsWith(TRASH_PREFIX)) return true
        return false
    }

    companion object {
        /** Directory names (lowercase) pruned by default. Shared with the find `-prune` clause. */
        val EXCLUDED_DIR_NAMES: Set<String> = setOf(".thumbnails", "cache", ".cache", "code_cache")
        const val TRASH_PREFIX = ".trash"

        val DEFAULT = ExclusionMatcher(enabled = true)
        val NONE = ExclusionMatcher(enabled = false)

        fun of(useDefaults: Boolean): ExclusionMatcher = if (useDefaults) DEFAULT else NONE
    }
}
