package ca.devdcs.backup.core.discovery.partition

import ca.devdcs.backup.core.exec.root.RootScriptBuilder

/** The scan script's raw output, split per section, before any interpretation. */
data class PartitionScanSections(
    val properties: List<String> = emptyList(),
    val byNameLinks: List<String> = emptyList(),
    val byNameLs: List<String> = emptyList(),
    val procPartitions: List<String> = emptyList(),
    val sysfsSizes: List<String> = emptyList(),
    val dmNames: List<String> = emptyList(),
    val mapperLinks: List<String> = emptyList(),
    val dmctl: List<String> = emptyList(),
    /** True when the script's terminal marker was seen, i.e. the output is not truncated. */
    val complete: Boolean = false,
)

/**
 * Builds the single batched, strictly read-only root script that gathers everything the partition
 * scan needs (one su round-trip instead of ~10), and splits its output back into sections.
 *
 * Only `getprop`, `readlink -f`, `ls -l`, `cat`, shell `read` and `dmctl list devices` are used:
 * nothing opens a block device, and nothing writes anywhere (no redirections other than to
 * /dev/null). The script has no dynamic input; literal paths are still shell-quoted, and only the
 * platform globs are left bare so the shell can expand them.
 */
object PartitionScanScript {

    const val MARKER = "@@DFTB_PART@@"

    const val SECTION_PROPS = "props"
    const val SECTION_BYNAME = "byname"
    const val SECTION_BYNAME_LS = "byname_ls"
    const val SECTION_PROC = "proc"
    const val SECTION_SYS = "sys"
    const val SECTION_DMNAME = "dmname"
    const val SECTION_MAPPER = "mapper"
    const val SECTION_DMCTL = "dmctl"
    const val SECTION_END = "end"

    const val BY_NAME = "/dev/block/by-name"
    const val BOOTDEVICE_BY_NAME = "/dev/block/bootdevice/by-name"
    const val MAPPER_DIR = "/dev/block/mapper"

    /** Candidate by-name roots, most canonical first. The globs must stay unquoted. */
    private val BY_NAME_DIRS: String = listOf(
        RootScriptBuilder.shellQuote(BY_NAME),
        RootScriptBuilder.shellQuote(BOOTDEVICE_BY_NAME),
        "/dev/block/platform/*/by-name",
        "/dev/block/platform/*/*/by-name",
    ).joinToString(" ")

    /** The complete script text, to run as `sh -c <script>` through the ROOT executor. */
    fun build(): String {
        val q = RootScriptBuilder::shellQuote
        val props = PartitionParsers.SCANNED_PROPERTIES.joinToString(" ") { q(it) }
        return buildString {
            appendLine("M=${q(MARKER)}")
            appendLine("echo \"\$M $SECTION_PROPS\"")
            appendLine("for p in $props; do printf '%s=%s\\n' \"\$p\" \"\$(getprop \"\$p\" 2>/dev/null)\"; done")

            appendLine("echo \"\$M $SECTION_BYNAME\"")
            appendLine("for d in $BY_NAME_DIRS; do")
            appendLine("  [ -d \"\$d\" ] || continue")
            appendLine("  printf 'D\\t%s\\n' \"\$d\"")
            appendLine("  for l in \"\$d\"/*; do")
            appendLine("    [ -L \"\$l\" ] || [ -b \"\$l\" ] || continue")
            appendLine("    printf 'L\\t%s\\t%s\\n' \"\$l\" \"\$(readlink -f \"\$l\" 2>/dev/null)\"")
            appendLine("  done")
            appendLine("done")

            appendLine("echo \"\$M $SECTION_BYNAME_LS\"")
            appendLine("for d in $BY_NAME_DIRS; do")
            appendLine("  [ -d \"\$d\" ] || continue")
            appendLine("  printf 'D\\t%s\\n' \"\$d\"")
            appendLine("  ls -l \"\$d\" 2>/dev/null")
            appendLine("done")

            appendLine("echo \"\$M $SECTION_PROC\"")
            appendLine("cat /proc/partitions 2>/dev/null")

            appendLine("echo \"\$M $SECTION_SYS\"")
            appendLine("for f in /sys/class/block/*/size; do")
            appendLine("  [ -r \"\$f\" ] || continue")
            appendLine("  n=\${f%/size}; n=\${n##*/}")
            appendLine("  read -r s < \"\$f\" && printf '%s\\t%s\\n' \"\$n\" \"\$s\"")
            appendLine("done")

            appendLine("echo \"\$M $SECTION_DMNAME\"")
            appendLine("for f in /sys/class/block/dm-*/dm/name; do")
            appendLine("  [ -r \"\$f\" ] || continue")
            appendLine("  n=\${f%/dm/name}; n=\${n##*/}")
            appendLine("  read -r s < \"\$f\" && printf '%s\\t%s\\n' \"\$n\" \"\$s\"")
            appendLine("done")

            appendLine("echo \"\$M $SECTION_MAPPER\"")
            appendLine("if [ -d ${q(MAPPER_DIR)} ]; then")
            appendLine("  for l in ${q(MAPPER_DIR)}/*; do")
            appendLine("    [ -L \"\$l\" ] || [ -b \"\$l\" ] || continue")
            appendLine("    printf 'L\\t%s\\t%s\\n' \"\$l\" \"\$(readlink -f \"\$l\" 2>/dev/null)\"")
            appendLine("  done")
            appendLine("fi")

            appendLine("echo \"\$M $SECTION_DMCTL\"")
            appendLine("if command -v dmctl >/dev/null 2>&1; then dmctl list devices 2>/dev/null; fi")

            appendLine("echo \"\$M $SECTION_END\"")
            append("true")
        }
    }

    /** Split the script's stdout at marker lines. Lines before the first marker are ignored. */
    fun parseSections(stdout: List<String>): PartitionScanSections {
        val buckets = HashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        var complete = false
        val prefix = "$MARKER "
        for (line in stdout) {
            val clean = line.trimEnd('\r')
            if (clean.startsWith(prefix)) {
                val section = clean.removePrefix(prefix).trim()
                if (section == SECTION_END) complete = true
                current = buckets.getOrPut(section) { ArrayList() }
                continue
            }
            current?.add(clean)
        }
        fun get(name: String): List<String> = buckets[name].orEmpty()
        return PartitionScanSections(
            properties = get(SECTION_PROPS),
            byNameLinks = get(SECTION_BYNAME),
            byNameLs = get(SECTION_BYNAME_LS),
            procPartitions = get(SECTION_PROC),
            sysfsSizes = get(SECTION_SYS),
            dmNames = get(SECTION_DMNAME),
            mapperLinks = get(SECTION_MAPPER),
            dmctl = get(SECTION_DMCTL),
            complete = complete,
        )
    }
}
