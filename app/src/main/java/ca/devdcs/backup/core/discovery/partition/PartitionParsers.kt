package ca.devdcs.backup.core.discovery.partition

/** One link found in a by-name or mapper directory, with its `readlink -f` target when known. */
data class BlockLink(
    /** Directory the link lives in, e.g. `/dev/block/by-name`. */
    val directory: String,
    val name: String,
    /** Full link path (`directory/name`). */
    val linkPath: String,
    /** Canonical target, or null when readlink printed nothing. */
    val realPath: String?,
)

/** One row of /proc/partitions. [blocks1k] is the kernel's size column in 1 KiB blocks. */
data class ProcPartition(
    val major: Int,
    val minor: Int,
    val blocks1k: Long,
    val name: String,
) {
    val sizeBytes: Long get() = blocks1k * 1024L
}

/** One device from `dmctl list devices`. */
data class DmctlDevice(
    val name: String,
    val major: Int,
    val minor: Int,
)

/** The four slot/dynamic-partition properties, decoded. */
data class SlotProperties(
    val slotSuffix: String?,
    val abUpdate: Boolean,
    val dynamicPartitions: Boolean,
    val virtualAb: Boolean,
) {
    val activeSlot: PartitionSlot? get() = PartitionSlot.fromSuffix(slotSuffix)

    /** A/B when the build says so, or when the bootloader passed a slot suffix at all. */
    val abDevice: Boolean get() = abUpdate || activeSlot != null
}

/**
 * Pure parsers for the outputs the partition scan script collects. Each one is total: unknown or
 * malformed lines are skipped, never thrown on, because vendor toolboxes (toybox, busybox, old
 * Samsung toolbox) differ in column layout.
 */
object PartitionParsers {

    const val PROP_SLOT_SUFFIX = "ro.boot.slot_suffix"
    const val PROP_AB_UPDATE = "ro.build.ab_update"
    const val PROP_DYNAMIC = "ro.boot.dynamic_partitions"
    const val PROP_VIRTUAL_AB = "ro.virtual_ab.enabled"

    /** Properties the scan queries, in script order. */
    val SCANNED_PROPERTIES: List<String> = listOf(PROP_SLOT_SUFFIX, PROP_AB_UPDATE, PROP_DYNAMIC, PROP_VIRTUAL_AB)

    private val DMCTL_LINE = Regex("""^\s*(\S+)\s*:\s*(\d+):(\d+)\s*$""")
    private val GETPROP_BRACKETS = Regex("""^\[([^\]]+)]:\s*\[(.*)]\s*$""")

    /**
     * Parse `key=value` lines (the script's own format) and `[key]: [value]` lines (plain
     * `getprop` dump format). Empty values are kept as empty strings.
     */
    fun parseProperties(lines: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val bracket = GETPROP_BRACKETS.matchEntire(line)
            if (bracket != null) {
                out[bracket.groupValues[1]] = bracket.groupValues[2].trim()
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            out[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        return out
    }

    /** Decode the slot / dynamic / virtual A/B properties from [parseProperties] output. */
    fun parseSlotProperties(props: Map<String, String>): SlotProperties = SlotProperties(
        slotSuffix = props[PROP_SLOT_SUFFIX]?.takeIf { it.isNotBlank() },
        abUpdate = props[PROP_AB_UPDATE].isTrue(),
        dynamicPartitions = props[PROP_DYNAMIC].isTrue(),
        virtualAb = props[PROP_VIRTUAL_AB].isTrue(),
    )

    /**
     * Parse the script's tab-separated link listing: `D<TAB>dir` starts a directory, then
     * `L<TAB>linkPath<TAB>realPath` per entry (realPath may be empty).
     */
    fun parseLinkListing(lines: List<String>): List<BlockLink> {
        val out = ArrayList<BlockLink>()
        for (raw in lines) {
            val parts = raw.trimEnd('\r').split('\t')
            if (parts.size < 2 || parts[0] != "L") continue
            val linkPath = parts[1].trim()
            if (linkPath.isEmpty() || !linkPath.contains('/')) continue
            val directory = linkPath.substringBeforeLast('/')
            val name = linkPath.substringAfterLast('/')
            if (name.isEmpty() || name == "*") continue
            val real = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
            out += BlockLink(directory, name, linkPath, real)
        }
        return out
    }

    /**
     * Parse `ls -l` output of a by-name/mapper directory. Works for toybox
     * (`lrwxrwxrwx 1 root root 16 2024-01-01 00:00 boot_a -> /dev/block/sda12`) and old toolbox
     * (`lrwxrwxrwx root root 2015-01-01 00:00 BOOT -> /dev/block/mmcblk0p16`). Relative targets
     * are resolved against [directory]. Lines without ` -> ` (totals, non-links) are skipped.
     */
    fun parseLsLong(directory: String, lines: List<String>): List<BlockLink> {
        val dir = directory.trimEnd('/')
        val out = ArrayList<BlockLink>()
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            if (!line.startsWith("l")) continue
            val arrow = line.indexOf(" -> ")
            if (arrow < 0) continue
            val name = line.substring(0, arrow).trim().substringAfterLast(' ')
            val target = line.substring(arrow + 4).trim()
            if (name.isEmpty() || target.isEmpty()) continue
            out += BlockLink(dir, name, "$dir/$name", normalizePath(if (target.startsWith("/")) target else "$dir/$target"))
        }
        return out
    }

    /**
     * Parse the script's `ls -l` section: `D<TAB>dir` headers followed by that directory's raw
     * `ls -l` lines.
     */
    fun parseLsSection(lines: List<String>): List<BlockLink> {
        val out = ArrayList<BlockLink>()
        var dir: String? = null
        val buffer = ArrayList<String>()
        fun flush() {
            dir?.let { out += parseLsLong(it, buffer) }
            buffer.clear()
        }
        for (raw in lines) {
            if (raw.startsWith("D\t")) {
                flush()
                dir = raw.substring(2).trim()
            } else {
                buffer += raw
            }
        }
        flush()
        return out
    }

    /** Parse /proc/partitions. The header and blank lines are skipped; the size column is 1 KiB blocks. */
    fun parseProcPartitions(lines: List<String>): List<ProcPartition> {
        val out = ArrayList<ProcPartition>()
        for (raw in lines) {
            val cols = raw.trim().split(WHITESPACE)
            if (cols.size < 4) continue
            val major = cols[0].toIntOrNull() ?: continue
            val minor = cols[1].toIntOrNull() ?: continue
            val blocks = cols[2].toLongOrNull() ?: continue
            out += ProcPartition(major, minor, blocks, cols[3])
        }
        return out
    }

    /**
     * Parse `name<TAB>value` lines (sysfs `size` in 512-byte sectors, or `dm/name`). Whitespace
     * separation is accepted too so a hand-run `for` loop output also parses.
     */
    fun parseNameValue(lines: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.split(WHITESPACE, limit = 2)
            if (parts.size != 2 || parts[0].isEmpty()) continue
            out[parts[0]] = parts[1].trim()
        }
        return out
    }

    /** sysfs block sizes: device name -> bytes (sectors x 512). Non-numeric values are dropped. */
    fun parseSysfsSizes(lines: List<String>): Map<String, Long> =
        parseNameValue(lines).mapNotNull { (dev, value) -> value.toLongOrNull()?.let { dev to it * SECTOR_BYTES } }.toMap()

    /** sysfs dm names: `dm-N` -> mapper name. */
    fun parseDmNames(lines: List<String>): Map<String, String> =
        parseNameValue(lines).filterValues { it.isNotEmpty() }

    /** Parse `dmctl list devices`; the header and "No devices available" lines are skipped. */
    fun parseDmctl(lines: List<String>): List<DmctlDevice> = lines.mapNotNull { raw ->
        DMCTL_LINE.matchEntire(raw)?.let { m ->
            DmctlDevice(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
    }

    /**
     * Split a slot suffix off [name]. Returns `(baseName, slot)`; only `_a` / `_b` (any case)
     * count, and the base must be non-empty.
     */
    fun splitSlot(name: String): Pair<String, PartitionSlot> {
        if (name.length > 2 && name[name.length - 2] == '_') {
            when (name.last()) {
                'a', 'A' -> return name.dropLast(2) to PartitionSlot.A
                'b', 'B' -> return name.dropLast(2) to PartitionSlot.B
            }
        }
        return name to PartitionSlot.NONE
    }

    /** Collapse `.`/`..`/duplicate slashes in an absolute path, purely lexically. */
    fun normalizePath(path: String): String {
        val stack = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> stack.removeLastOrNull()
                else -> stack.addLast(segment)
            }
        }
        return "/" + stack.joinToString("/")
    }

    private fun String?.isTrue(): Boolean = this?.trim()?.lowercase() in setOf("true", "1", "yes")

    private val WHITESPACE = Regex("""\s+""")
    private const val SECTOR_BYTES = 512L
}
