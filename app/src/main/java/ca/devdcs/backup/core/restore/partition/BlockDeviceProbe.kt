package ca.devdcs.backup.core.restore.partition

import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.root.RootScriptBuilder
import ca.devdcs.backup.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure builder and parser of the read-only root probe run before a partition write. Kept free of
 * Android so the quoting (it runs as root) and the section parsing are unit-tested on the JVM.
 */
object BlockDeviceProbeScript {

    const val MARKER = "@@DFTB"
    const val SECTION_BLOCK = "blk"
    const val SECTION_REAL = "real"
    const val SECTION_SIZE = "size"
    const val SECTION_RO = "ro"
    const val SECTION_HOLDERS = "holders"
    const val SECTION_MOUNTS = "mounts"
    const val SECTION_CANON = "canon"
    const val SECTION_SWAPS = "swaps"
    const val SECTION_END = "end"

    private fun q(s: String) = RootScriptBuilder.shellQuote(s)

    private fun section(name: String) = "echo '$MARKER $name'"

    /**
     * Strictly read-only. Reads both this namespace's and init's mount table so a mount hidden by a
     * per-app namespace still counts, and canonicalises every `/dev/...` mount/swap source once.
     */
    fun build(device: String): String = listOf(
        "__d=${q(device)}",
        section(SECTION_BLOCK), "if [ -b \"\$__d\" ]; then echo 1; else echo 0; fi",
        section(SECTION_REAL), "readlink -f \"\$__d\" 2>/dev/null",
        section(SECTION_SIZE), "blockdev --getsize64 \"\$__d\" 2>/dev/null",
        section(SECTION_RO), "blockdev --getro \"\$__d\" 2>/dev/null",
        "__r=\$(readlink -f \"\$__d\" 2>/dev/null); __n=\${__r##*/}",
        section(SECTION_HOLDERS),
        "if [ -n \"\$__n\" ]; then for __h in /sys/class/block/\"\$__n\"/holders/*; do [ -e \"\$__h\" ] || continue; " +
            "__hn=\${__h##*/}; printf '%s\\t%s\\n' \"\$__hn\" \"\$(cat /sys/class/block/\"\$__hn\"/dm/name 2>/dev/null)\"; done; fi",
        section(SECTION_MOUNTS), "cat /proc/mounts 2>/dev/null; cat /proc/1/mounts 2>/dev/null",
        section(SECTION_CANON),
        "{ cat /proc/mounts /proc/1/mounts /proc/swaps 2>/dev/null; } | tr '\\t' ' ' | cut -d' ' -f1 | sort -u | " +
            "while read -r __s; do case \"\$__s\" in /dev/*) printf '%s\\t%s\\n' \"\$__s\" \"\$(readlink -f \"\$__s\" 2>/dev/null)\";; esac; done",
        section(SECTION_SWAPS), "cat /proc/swaps 2>/dev/null",
        section(SECTION_END),
        "true",
    ).joinToString("\n")

    /** Split stdout into sections; null when the end marker is missing (truncated output). */
    fun sections(stdout: List<String>): Map<String, List<String>>? {
        val out = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        var ended = false
        for (line in stdout) {
            if (line.startsWith("$MARKER ")) {
                val name = line.removePrefix("$MARKER ").trim()
                if (name == SECTION_END) {
                    ended = true
                    break
                }
                current = out.getOrPut(name) { ArrayList() }
            } else {
                current?.add(line)
            }
        }
        return if (ended) out else null
    }

    /**
     * Build the state of [device]. [aliases] are other paths of the same node (the by-name or
     * mapper link) that may appear verbatim as a mount source.
     */
    fun parse(device: String, aliases: Set<String>, stdout: List<String>): BlockDeviceState? {
        val s = sections(stdout) ?: return null
        fun first(name: String) = s[name].orEmpty().firstOrNull { it.isNotBlank() }?.trim()
        val canonicalPath = first(SECTION_REAL)?.takeIf { it.startsWith("/") }
        val holders = s[SECTION_HOLDERS].orEmpty().mapNotNull { line ->
            val name = line.substringBefore('\t').trim()
            if (name.isEmpty()) null else BlockHolder(name, line.substringAfter('\t', "").trim().ifEmpty { null })
        }
        val mounts = BlockDeviceUsage.parseMounts(s[SECTION_MOUNTS].orEmpty())
        val canonical = s[SECTION_CANON].orEmpty().mapNotNull { line ->
            val raw = line.substringBefore('\t')
            val resolved = line.substringAfter('\t', "").trim()
            if (raw.isEmpty() || resolved.isEmpty()) null else BlockDeviceUsage.unescape(raw) to resolved
        }.toMap()
        val swaps = BlockDeviceUsage.parseSwaps(s[SECTION_SWAPS].orEmpty())
        val target = canonicalPath ?: device
        val allAliases = aliases + device
        return BlockDeviceState(
            path = device,
            canonicalPath = canonicalPath,
            isBlockDevice = first(SECTION_BLOCK) == "1",
            sizeBytes = first(SECTION_SIZE)?.toLongOrNull()?.takeIf { it >= 0 },
            readOnly = when (first(SECTION_RO)) {
                "0" -> false
                "1" -> true
                else -> null
            },
            holders = holders,
            mountPoints = BlockDeviceUsage.mountPointsOf(target, allAliases, mounts, canonical),
            usedAsSwap = BlockDeviceUsage.isSwap(target, allAliases, swaps, canonical),
        )
    }
}

/** Runs [BlockDeviceProbeScript] through the root executor. */
@Singleton
class BlockDeviceProbe @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Null when the probe could not run or its output was truncated. */
    suspend fun probe(executor: CommandExecutor, device: String, aliases: Set<String>): BlockDeviceState? = withContext(io) {
        val result = executor.execute(
            Command(
                argv = listOf("sh", "-c", BlockDeviceProbeScript.build(device)),
                timeoutMillis = PROBE_TIMEOUT_MILLIS,
                requireGlobalMountNamespace = true,
            ),
        )
        if (!result.launched) null else BlockDeviceProbeScript.parse(device, aliases, result.stdout)
    }

    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 30_000L
    }
}
