package ca.devdcs.backup.domain.model

import kotlinx.serialization.Serializable

/**
 * Declarative include/exclude rules applied to every file the scanner meets under a [PathTarget].
 * All criteria are ANDed; an empty filter matches everything. Extensions are compared without the
 * dot and case-insensitively; regexes are matched anywhere in the relative path (`containsMatchIn`).
 * Timestamps are epoch millis: `modifiedAfter` is exclusive (strictly newer), `modifiedBefore`
 * exclusive (strictly older), so two filters split on the same instant never overlap.
 */
@Serializable
data class FileFilter(
    val includeExtensions: Set<String> = emptySet(),
    val excludeExtensions: Set<String> = emptySet(),
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val modifiedAfterEpochMillis: Long? = null,
    val modifiedBeforeEpochMillis: Long? = null,
    val includeRegex: String? = null,
    val excludeRegex: String? = null,
) {
    val isEmpty: Boolean
        get() = includeExtensions.isEmpty() && excludeExtensions.isEmpty() && minSizeBytes == null &&
            maxSizeBytes == null && modifiedAfterEpochMillis == null && modifiedBeforeEpochMillis == null &&
            includeRegex.isNullOrEmpty() && excludeRegex.isNullOrEmpty()

    /**
     * Pure decision for one file. Compiles the regexes on every call; scanners iterating thousands
     * of entries should call [compile] once and reuse the result.
     *
     * @throws IllegalArgumentException when [includeRegex] or [excludeRegex] is not a valid pattern.
     */
    fun matches(relativePath: String, sizeBytes: Long, modifiedAt: Long): Boolean =
        compile().matches(relativePath, sizeBytes, modifiedAt)

    /** @throws IllegalArgumentException when a regex is malformed; see [regexProblem]. */
    fun compile(): CompiledFileFilter = CompiledFileFilter(
        includeExtensions = includeExtensions.map(::normalizeExtension).toSet(),
        excludeExtensions = excludeExtensions.map(::normalizeExtension).toSet(),
        minSizeBytes = minSizeBytes,
        maxSizeBytes = maxSizeBytes,
        modifiedAfter = modifiedAfterEpochMillis,
        modifiedBefore = modifiedBeforeEpochMillis,
        include = compileRegex(includeRegex),
        exclude = compileRegex(excludeRegex),
    )

    /** Message describing the first malformed regex, or null when both compile. UI validation hook. */
    fun regexProblem(): String? = try {
        compileRegex(includeRegex)
        compileRegex(excludeRegex)
        null
    } catch (error: IllegalArgumentException) {
        error.message ?: error.toString()
    }

    private fun compileRegex(pattern: String?): Regex? {
        if (pattern.isNullOrEmpty()) return null
        return try {
            Regex(pattern)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Invalid regex \"$pattern\": ${error.message}", error)
        }
    }

    companion object {
        val NONE = FileFilter()

        /** Bare lowercase extension: ".JPG" and "jpg" both become "jpg". */
        fun normalizeExtension(extension: String): String = extension.trim().removePrefix(".").lowercase()

        /** Bare lowercase extension of the last path segment, or empty when there is none. */
        fun extensionOf(relativePath: String): String {
            val fileName = relativePath.substringAfterLast('/').substringAfterLast('\\')
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0 || dot == fileName.lastIndex) return ""
            return fileName.substring(dot + 1).lowercase()
        }
    }
}

/** Pre-compiled form of a [FileFilter] for hot scanning loops; see [FileFilter.compile]. */
class CompiledFileFilter internal constructor(
    private val includeExtensions: Set<String>,
    private val excludeExtensions: Set<String>,
    private val minSizeBytes: Long?,
    private val maxSizeBytes: Long?,
    private val modifiedAfter: Long?,
    private val modifiedBefore: Long?,
    private val include: Regex?,
    private val exclude: Regex?,
) {
    fun matches(relativePath: String, sizeBytes: Long, modifiedAt: Long): Boolean {
        val extension = FileFilter.extensionOf(relativePath)
        if (includeExtensions.isNotEmpty() && extension !in includeExtensions) return false
        if (extension.isNotEmpty() && extension in excludeExtensions) return false
        if (minSizeBytes != null && sizeBytes < minSizeBytes) return false
        if (maxSizeBytes != null && sizeBytes > maxSizeBytes) return false
        if (modifiedAfter != null && modifiedAt <= modifiedAfter) return false
        if (modifiedBefore != null && modifiedAt >= modifiedBefore) return false
        if (include != null && !include.containsMatchIn(relativePath)) return false
        if (exclude != null && exclude.containsMatchIn(relativePath)) return false
        return true
    }
}
