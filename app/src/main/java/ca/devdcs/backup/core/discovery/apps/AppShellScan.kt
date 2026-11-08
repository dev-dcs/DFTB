package ca.devdcs.backup.core.discovery.apps

import ca.devdcs.backup.core.exec.root.RootScriptBuilder

/**
 * Builds the single batched POSIX `sh` script that measures many packages' data dirs in one
 * Shizuku/root round trip. One process per chunk instead of ~7 per package keeps a full-device scan
 * to seconds; every interpolated value is single-quoted so a hostile name cannot inject commands.
 *
 * Output protocol (one record per line, `|`-separated, parsed by [AppShellScanParser]):
 * - `U|<uid>` shell uid (0 = root, 2000 = adb)
 * - `S|<pkg>|<loc>|<kb>|<exit>` `du -sk` result and its exit status
 * - `M|<pkg>|<loc>` location does not exist
 * - `D|<pkg>|<loc>` nearest existing ancestor not searchable: unknown
 * - `C|<pkg>|<loc>|<uid>|<gid>|<context>` owner + SELinux label (CE/DE dirs only)
 */
object AppShellScanScript {

    /** Packages per script: keeps the script far below the 128 KiB single-argument kernel limit. */
    const val DEFAULT_CHUNK_SIZE = 25

    /** Wire tag for each location; stable because the parser maps it back. */
    fun tagOf(location: AppDataLocation): String = when (location) {
        AppDataLocation.CE_DATA -> "ce"
        AppDataLocation.DE_DATA -> "de"
        AppDataLocation.CE_CACHE -> "cache"
        AppDataLocation.EXTERNAL_DATA -> "ext"
        AppDataLocation.MEDIA -> "media"
        AppDataLocation.OBB -> "obb"
    }

    fun locationOf(tag: String): AppDataLocation? = AppDataLocation.entries.firstOrNull { tagOf(it) == tag }

    /** Locations whose ownership/SELinux label is captured for restore. */
    val METADATA_LOCATIONS: Set<AppDataLocation> = setOf(AppDataLocation.CE_DATA, AppDataLocation.DE_DATA)

    // Functions are defined once per script; `${r%%[!0-9]*}` keeps the leading KiB count of du's
    // "<kb>\t<path>" line without depending on awk/cut being present.
    private val PRELUDE = """
        echo "U|${'$'}(id -u)"
        dftb_du() {
          if [ -e "${'$'}3" ]; then
            r=${'$'}(du -sk "${'$'}3" 2>/dev/null); e=${'$'}?
            echo "S|${'$'}1|${'$'}2|${'$'}{r%%[!0-9]*}|${'$'}e"
          else
            p=${'$'}{3%/*}
            while [ -n "${'$'}p" ] && [ ! -e "${'$'}p" ]; do p=${'$'}{p%/*}; done
            if [ -z "${'$'}p" ] || [ -x "${'$'}p" ]; then
              echo "M|${'$'}1|${'$'}2"
            else
              echo "D|${'$'}1|${'$'}2"
            fi
          fi
        }
        dftb_meta() {
          if [ -e "${'$'}3" ]; then
            u=${'$'}(stat -c %u "${'$'}3" 2>/dev/null)
            g=${'$'}(stat -c %g "${'$'}3" 2>/dev/null)
            c=${'$'}(ls -Zd "${'$'}3" 2>/dev/null); c=${'$'}{c%% *}
            echo "C|${'$'}1|${'$'}2|${'$'}u|${'$'}g|${'$'}c"
          fi
        }
    """.trimIndent()

    /**
     * Script for [packageNames] on [userId]. [backingStore] selects /data/media/N (uid-0 shell) vs
     * /storage/emulated/N for shared-storage locations. Invalid package names are skipped.
     */
    fun build(packageNames: List<String>, userId: Int, backingStore: Boolean): String {
        require(userId >= 0) { "userId must be >= 0" }
        val sb = StringBuilder(PRELUDE).append('\n')
        for (pkg in packageNames) {
            if (!AppDataPaths.isValidPackageName(pkg)) continue
            val qPkg = RootScriptBuilder.shellQuote(pkg)
            for (location in AppDataLocation.entries) {
                val path = RootScriptBuilder.shellQuote(AppDataPaths.pathFor(location, pkg, userId, backingStore))
                val tag = tagOf(location)
                sb.append("dftb_du ").append(qPkg).append(' ').append(tag).append(' ').append(path).append('\n')
                if (location in METADATA_LOCATIONS) {
                    sb.append("dftb_meta ").append(qPkg).append(' ').append(tag).append(' ').append(path).append('\n')
                }
            }
        }
        return sb.toString()
    }

    /** Splits [packageNames] into script-sized chunks, dropping invalid names. */
    fun chunks(packageNames: List<String>, chunkSize: Int = DEFAULT_CHUNK_SIZE): List<List<String>> {
        require(chunkSize > 0) { "chunkSize must be > 0" }
        return packageNames.filter(AppDataPaths::isValidPackageName).distinct().chunked(chunkSize)
    }
}

/** Result of measuring one location. */
sealed interface LocationMeasurement {
    /** [complete] is false when du exited non-zero (some entries were unreadable). */
    data class Measured(val kibibytes: Long, val complete: Boolean) : LocationMeasurement {
        val bytes: Long get() = kibibytes * 1024L
    }

    /** Directory does not exist. */
    data object Missing : LocationMeasurement

    /** The shell could not even check: parent directory not searchable. */
    data object Denied : LocationMeasurement
}

/** Parsed output of one or more [AppShellScanScript] runs. */
data class ShellScanOutput(
    val shellUid: Int?,
    val measurements: Map<String, Map<AppDataLocation, LocationMeasurement>>,
    val metadata: Map<String, Map<AppDataLocation, DataDirMetadata>>,
    /** Lines that did not match the protocol (shell noise), kept for diagnostics. */
    val unparsedLines: List<String>,
)

/**
 * Pure parser for the [AppShellScanScript] protocol. Tolerant: unknown or malformed lines are
 * collected rather than failing the scan, because a root shell may print banner noise.
 */
object AppShellScanParser {

    /** Locations under /data/(user|user_de)/N that a non-root shell cannot reliably stat. */
    val PRIVATE_LOCATIONS: Set<AppDataLocation> =
        setOf(AppDataLocation.CE_DATA, AppDataLocation.DE_DATA, AppDataLocation.CE_CACHE)

    fun parse(lines: List<String>): ShellScanOutput {
        var shellUid: Int? = null
        val measurements = LinkedHashMap<String, MutableMap<AppDataLocation, LocationMeasurement>>()
        val metadata = LinkedHashMap<String, MutableMap<AppDataLocation, DataDirMetadata>>()
        val unparsed = ArrayList<String>()

        for (raw in lines) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            val parts = line.split('|')
            when (parts[0]) {
                "U" -> {
                    val uid = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    if (parts.size == 2 && uid != null) shellUid = uid else unparsed += line
                }
                "S" -> {
                    val location = parts.getOrNull(2)?.let(AppShellScanScript::locationOf)
                    val kb = parts.getOrNull(3)?.toLongOrNull()
                    val exit = parts.getOrNull(4)?.toIntOrNull()
                    if (parts.size == 5 && location != null && AppDataPaths.isValidPackageName(parts[1])) {
                        // du printing nothing means it could not stat the dir at all.
                        val value = if (kb == null) LocationMeasurement.Denied else LocationMeasurement.Measured(kb, exit == 0)
                        measurements.getOrPut(parts[1]) { LinkedHashMap() }[location] = value
                    } else {
                        unparsed += line
                    }
                }
                "M", "D" -> {
                    val location = parts.getOrNull(2)?.let(AppShellScanScript::locationOf)
                    if (parts.size == 3 && location != null && AppDataPaths.isValidPackageName(parts[1])) {
                        measurements.getOrPut(parts[1]) { LinkedHashMap() }[location] =
                            if (parts[0] == "M") LocationMeasurement.Missing else LocationMeasurement.Denied
                    } else {
                        unparsed += line
                    }
                }
                "C" -> {
                    val location = parts.getOrNull(2)?.let(AppShellScanScript::locationOf)
                    if (parts.size == 6 && location != null && AppDataPaths.isValidPackageName(parts[1])) {
                        val context = parts[5].trim().takeIf { it.isNotEmpty() && it != "?" && it.contains(':') }
                        metadata.getOrPut(parts[1]) { LinkedHashMap() }[location] = DataDirMetadata(
                            ownerUid = parts[3].trim().toIntOrNull(),
                            ownerGid = parts[4].trim().toIntOrNull(),
                            seLinuxContext = context,
                        )
                    } else {
                        unparsed += line
                    }
                }
                else -> unparsed += line
            }
        }
        return ShellScanOutput(shellUid, measurements, metadata, unparsed)
    }

    /** Merges per-chunk outputs; later chunks win on (unexpected) duplicate packages. */
    fun merge(outputs: List<ShellScanOutput>): ShellScanOutput = ShellScanOutput(
        shellUid = outputs.firstNotNullOfOrNull { it.shellUid },
        measurements = outputs.fold(LinkedHashMap()) { acc, o -> acc.apply { putAll(o.measurements) } },
        metadata = outputs.fold(LinkedHashMap()) { acc, o -> acc.apply { putAll(o.metadata) } },
        unparsedLines = outputs.flatMap { it.unparsedLines },
    )

    /**
     * Converts one package's measurements into an [AppSizeInfo]. Missing → 0, Denied or incomplete
     * → null and listed in [AppSizeInfo.unreadable].
     *
     * [privateDirsTrusted] must be false for a non-root shell: SELinux can deny getattr on
     * /data/data/<pkg> itself while the ancestor looks searchable, so "missing" CE/DE/cache there
     * is really "unknown".
     */
    fun toSizeInfo(
        apkBytes: Long,
        splitBytes: Long,
        measurements: Map<AppDataLocation, LocationMeasurement>,
        privateDirsTrusted: Boolean = true,
    ): AppSizeInfo {
        val unreadable = LinkedHashSet<AppDataLocation>()
        fun bytes(location: AppDataLocation): Long? = when (val m = measurements[location]) {
            is LocationMeasurement.Measured -> if (m.complete) m.bytes else { unreadable += location; null }
            LocationMeasurement.Missing ->
                if (privateDirsTrusted || location !in PRIVATE_LOCATIONS) 0L else { unreadable += location; null }
            LocationMeasurement.Denied, null -> { unreadable += location; null }
        }
        return AppSizeInfo(
            apkBytes = apkBytes,
            splitBytes = splitBytes,
            internalDataBytes = bytes(AppDataLocation.CE_DATA),
            deviceProtectedBytes = bytes(AppDataLocation.DE_DATA),
            externalDataBytes = bytes(AppDataLocation.EXTERNAL_DATA),
            mediaBytes = bytes(AppDataLocation.MEDIA),
            obbBytes = bytes(AppDataLocation.OBB),
            cacheBytes = bytes(AppDataLocation.CE_CACHE),
            source = SizeSource.SHELL_DU,
            unreadable = unreadable,
        )
    }

    /** OBB presence from a measurement: null when it could not be determined. */
    fun hasObb(measurements: Map<AppDataLocation, LocationMeasurement>?): Boolean? =
        when (measurements?.get(AppDataLocation.OBB)) {
            is LocationMeasurement.Measured -> true
            LocationMeasurement.Missing -> false
            LocationMeasurement.Denied, null -> null
        }
}
