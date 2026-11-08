package ca.devdcs.backup.core.discovery.files

/**
 * How the privileged shell's `find` reports file metadata.
 *
 * Portability (toybox on stock Android, busybox from Magisk/KernelSU, GNU findutils in Termux):
 * - toybox and GNU support `-printf` with `%s`, `%T@`, `%p` but toybox has no `%y`, so the type is
 * encoded by splitting the expression into `-type d` / `-type f` branches with a literal tag.
 * - busybox `find` has no `-printf` at all; it (and toybox) support `-exec … {} +` and
 * `stat -c '%s %Y %n'`, which is the fallback.
 * Both dialects therefore emit the same line format, parsed by [FindOutputParser]:
 * `D <path>` for a directory and `F <size> <mtime> <path>` for a regular file, single-space
 * separated with the path last (so it may contain spaces). `-mindepth 1` skips the root itself,
 * `-xdev` keeps the walk on one filesystem, and the default `-P` never follows symlinks.
 * The dialect is chosen at run time by probing ([probeCommand]) rather than by guessing the binary.
 */
enum class FindDialect { PRINTF, EXEC_STAT }

/** Pure argv builder for the privileged walker. Executors shell-quote each argv element. */
object FindCommandBuilder {

    const val PROBE_MARKER = "DFTB_FIND_PRINTF_OK"

    /**
     * `find / -maxdepth 0 -printf 'MARKER %s %T@\n'`: exercises the exact directives the walker
     * uses; see [isProbeOutputValid].
     */
    fun probeCommand(): List<String> = listOf("find", "/", "-maxdepth", "0", "-printf", "$PROBE_MARKER %s %T@\\n")

    /** True when a probe stdout line shows -printf expanded `%s` and `%T@` to numbers. */
    fun isProbeOutputValid(line: String): Boolean {
        val parts = line.trim().split(' ')
        return parts.size == 3 && parts[0] == PROBE_MARKER && parts[1].toLongOrNull() != null &&
            FindOutputParser.parseEpochToMillis(parts[2]) != null
    }

    /** Root as passed to find: a trailing slash so a symlinked root such as /sdcard is entered. */
    fun findRoot(root: String, singleFile: Boolean): String {
        val trimmed = root.trimEnd('/')
        if (singleFile) return trimmed.ifEmpty { "/" }
        return if (trimmed.isEmpty()) "/" else "$trimmed/"
    }

    /**
     * Full argv. [pruneDirNames] are pruned by `-name` (case-sensitive glob, a cheap pre-filter;
     * the Kotlin [ExclusionMatcher] stays authoritative). [prunePaths] are `-path` globs, see
     * [defaultPrunePaths].
     */
    fun build(
        root: String,
        recursive: Boolean,
        dialect: FindDialect,
        singleFile: Boolean = false,
        pruneDirNames: Collection<String> = emptyList(),
        prunePaths: Collection<String> = emptyList(),
    ): List<String> {
        val argv = ArrayList<String>()
        argv += "find"
        argv += findRoot(root, singleFile)
        argv += "-xdev"
        if (singleFile) {
            argv += listOf("-maxdepth", "0")
        } else {
            argv += listOf("-mindepth", "1")
            if (!recursive) argv += listOf("-maxdepth", "1")
        }
        if (!singleFile && (pruneDirNames.isNotEmpty() || prunePaths.isNotEmpty())) {
            argv += listOf("(", "-type", "d", "(")
            val tests = pruneDirNames.map { listOf("-name", it) } + prunePaths.map { listOf("-path", it) }
            tests.forEachIndexed { index, test ->
                if (index > 0) argv += "-o"
                argv += test
            }
            argv += listOf(")", "-prune", ")", "-o")
        }
        when (dialect) {
            FindDialect.PRINTF -> {
                argv += listOf("(", "-type", "d", "-printf", "D %p\\n", ")", "-o")
                argv += listOf("(", "-type", "f", "-printf", "F %s %T@ %p\\n", ")")
            }
            FindDialect.EXEC_STAT -> {
                argv += listOf("(", "-type", "d", "-exec", "stat", "-c", "D %n", "{}", "+", ")", "-o")
                argv += listOf("(", "-type", "f", "-exec", "stat", "-c", "F %s %Y %n", "{}", "+", ")")
            }
        }
        return argv
    }

    /** Directory-name patterns for `-prune` matching [ExclusionMatcher]'s default rules. */
    fun defaultPruneNames(): List<String> =
        ExclusionMatcher.EXCLUDED_DIR_NAMES.sorted() + listOf(".trash*", ".Trash*")

    /**
     * `-path` prune globs matching [ExclusionMatcher]'s `Android/data` rule: the pair must lie
     * BELOW [root] (a root of `/sdcard/Android` keeps its `data` child), so the globs are anchored
     * on the glob-escaped root as passed to find.
     */
    fun defaultPrunePaths(root: String): List<String> {
        val anchor = escapeGlob(findRoot(root, singleFile = false))
        return listOf("${anchor}Android/data", "$anchor*/Android/data")
    }

    /** Backslash-escapes fnmatch metacharacters so a literal path can prefix a `-path` glob. */
    fun escapeGlob(path: String): String = buildString {
        for (ch in path) {
            if (ch == '*' || ch == '?' || ch == '[' || ch == ']' || ch == '\\') append('\\')
            append(ch)
        }
    }
}

/** One parsed line of find output. */
sealed interface FindLine {
    data class Directory(val relativePath: String, val absolutePath: String) : FindLine
    data class File(val relativePath: String, val absolutePath: String, val sizeBytes: Long, val modifiedAtMillis: Long) : FindLine

    /** An error on stderr. [isRoot] when it concerns the scan root itself. */
    data class Error(val path: String?, val message: String, val isRoot: Boolean, val problem: RootProblem) : FindLine

    /** A line that matched no known shape (e.g. second half of a file name containing a newline). */
    data class Unparseable(val raw: String) : FindLine

    /** The root itself (depth 0) or a blank line; nothing to report. */
    data object Ignored : FindLine
}

/**
 * Pure line parser for [FindCommandBuilder] output. Known limitation: line-based streaming cannot
 * represent a file name that contains '\n'; its pieces surface as [FindLine.Unparseable] (or, for
 * the first piece, a truncated name that will fail to open later and be reported by the pipeline).
 */
class FindOutputParser(root: String, private val singleFile: Boolean = false) {

    private val rootTrimmed = root.trimEnd('/')

    fun parseStdout(line: String): FindLine {
        if (line.isEmpty()) return FindLine.Ignored
        return when {
            line.startsWith("D ") -> {
                val path = line.substring(2)
                val rel = relativize(path) ?: return FindLine.Unparseable(line)
                if (rel.isEmpty()) FindLine.Ignored else FindLine.Directory(rel, path)
            }
            line.startsWith("F ") -> parseFile(line)
            else -> FindLine.Unparseable(line)
        }
    }

    private fun parseFile(line: String): FindLine {
        val firstSpace = line.indexOf(' ', 2)
        if (firstSpace < 0) return FindLine.Unparseable(line)
        val secondSpace = line.indexOf(' ', firstSpace + 1)
        if (secondSpace < 0) return FindLine.Unparseable(line)
        val size = line.substring(2, firstSpace).toLongOrNull() ?: return FindLine.Unparseable(line)
        val mtime = parseEpochToMillis(line.substring(firstSpace + 1, secondSpace)) ?: return FindLine.Unparseable(line)
        val path = line.substring(secondSpace + 1)
        if (path.isEmpty()) return FindLine.Unparseable(line)
        val rel = relativize(path) ?: return FindLine.Unparseable(line)
        if (rel.isEmpty()) return FindLine.Ignored
        return FindLine.File(rel, path, size, mtime)
    }

    /** Path relative to the root; the file name for single-file scans; null when outside the root. */
    fun relativize(path: String): String? {
        if (singleFile) {
            return if (path.trimEnd('/') == rootTrimmed) rootTrimmed.substringAfterLast('/') else null
        }
        if (rootTrimmed.isEmpty()) return path.trimStart('/')
        if (path.trimEnd('/') == rootTrimmed) return ""
        if (!path.startsWith("$rootTrimmed/")) return null
        // Some find builds double the separator after a root given with a trailing slash.
        return path.substring(rootTrimmed.length).trimStart('/')
    }

    /** Parses a stderr line such as `find: '/x': Permission denied` (GNU) or `find: /x: Permission denied`. */
    fun parseStderr(line: String): FindLine {
        if (line.isBlank()) return FindLine.Ignored
        val match = ERROR_LINE.matchEntire(line.trim())
        if (match == null) return FindLine.Error(null, line.trim(), isRoot = false, problem = RootProblem.TOOL_FAILED)
        val path = match.groupValues[2]
        val message = match.groupValues[3]
        val isRoot = path.trimEnd('/') == rootTrimmed || (rootTrimmed.isEmpty() && path == "/")
        val problem = when {
            message.contains("No such file", ignoreCase = true) -> RootProblem.NOT_FOUND
            message.contains("Not a directory", ignoreCase = true) -> RootProblem.NOT_A_DIRECTORY
            message.contains("Permission denied", ignoreCase = true) ||
                message.contains("Operation not permitted", ignoreCase = true) -> RootProblem.NOT_READABLE
            else -> RootProblem.TOOL_FAILED
        }
        return FindLine.Error(path, message, isRoot, problem)
    }

    companion object {
        private val ERROR_LINE = Regex("^(find|stat): [‘'`\"]?(/.*?)[’'`\"]?: ([^:]+)$")

        /** `1697712345`, `1697712345.5` or `1697712345.1234567890` (seconds) to epoch millis. */
        fun parseEpochToMillis(token: String): Long? {
            val dot = token.indexOf('.')
            val secondsPart = if (dot >= 0) token.substring(0, dot) else token
            val seconds = secondsPart.toLongOrNull() ?: return null
            var millis = seconds * 1000
            if (dot >= 0) {
                val fraction = token.substring(dot + 1)
                if (fraction.isNotEmpty() && fraction.any { !it.isDigit() }) return null
                val ms = (fraction + "000").take(3)
                millis += ms.toLong()
            }
            return millis
        }
    }
}
