package ca.devdcs.backup.core.restore.partition

/** One `/proc/mounts` line with the kernel's octal escapes (`\040`...) decoded. */
data class MountEntry(val source: String, val mountPoint: String, val fileSystem: String, val options: String)

/** One stacked device from `/sys/class/block/<dev>/holders`, with its dm name when it is a dm node. */
data class BlockHolder(val kernelName: String, val dmName: String?)

/**
 * Everything the write gates need to know about one target node, read in a single root probe.
 * Exists so the "is this partition in use" decision is pure and unit-tested.
 */
data class BlockDeviceState(
    val path: String,
    /** Canonical node (`/dev/block/sda12`, `/dev/block/dm-3`), or null when readlink failed. */
    val canonicalPath: String?,
    val isBlockDevice: Boolean,
    /** Exact size from `blockdev --getsize64`, or null when it failed. */
    val sizeBytes: Long?,
    /** `blockdev --getro`, or null when it failed. */
    val readOnly: Boolean?,
    val holders: List<BlockHolder>,
    /** Mount points whose source canonicalises to this node. */
    val mountPoints: List<String>,
    val usedAsSwap: Boolean,
) {
    val kernelName: String? get() = canonicalPath?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
}

/**
 * Pure parsers for the kernel tables that tell whether a block node is in use: `/proc/mounts`,
 * `/proc/swaps` and sysfs holders. Device-mapper targets matter because a physical partition is
 * rarely mounted directly on modern Android: dm-verity, dm-default-key and dm-linear (logical
 * partitions) sit on top of it and the dm node is what appears in `/proc/mounts`.
 */
object BlockDeviceUsage {

    /** Parse `/proc/mounts` (or `/proc/<pid>/mounts`) lines; malformed lines are skipped. */
    fun parseMounts(lines: List<String>): List<MountEntry> = lines.mapNotNull { line ->
        val fields = line.trim().split(WHITESPACE)
        if (fields.size < 4) null else MountEntry(unescape(fields[0]), unescape(fields[1]), fields[2], fields[3])
    }

    /** Device paths in `/proc/swaps` (the header line is skipped). */
    fun parseSwaps(lines: List<String>): List<String> = lines.mapNotNull { line ->
        val first = line.trim().split(WHITESPACE).firstOrNull().orEmpty()
        if (first.isEmpty() || first == "Filename") null else unescape(first)
    }

    /**
     * Mount points of the node whose canonical path is [canonicalPath]. [canonical] maps each raw
     * mount source to its `readlink -f` result (absent when it could not be resolved). A source
     * matches by its canonical form, or by its raw form when that is already the canonical node.
     */
    fun mountPointsOf(canonicalPath: String, aliases: Set<String>, mounts: List<MountEntry>, canonical: Map<String, String>): List<String> =
        mounts.filter { entry ->
            val resolved = canonical[entry.source] ?: entry.source
            resolved == canonicalPath || entry.source == canonicalPath || entry.source in aliases
        }.map { it.mountPoint }.distinct()

    /** True when any swap entry is the node (by canonical path or alias). */
    fun isSwap(canonicalPath: String, aliases: Set<String>, swaps: List<String>, canonical: Map<String, String>): Boolean =
        swaps.any { s -> (canonical[s] ?: s) == canonicalPath || s in aliases }

    /** Decode the `\ooo` octal escapes the kernel uses for space, tab, newline and backslash. */
    fun unescape(field: String): String {
        if ('\\' !in field) return field
        val out = StringBuilder(field.length)
        var i = 0
        while (i < field.length) {
            val c = field[i]
            if (c == '\\' && isOctal(field, i + 1)) {
                out.append(field.substring(i + 1, i + 4).toInt(8).toChar())
                i += 4
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun isOctal(s: String, from: Int): Boolean =
        from + 3 <= s.length && (from until from + 3).all { s[it] in '0'..'7' }

    private val WHITESPACE = Regex("[ \t]+")
}
