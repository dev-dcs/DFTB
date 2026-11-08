package ca.devdcs.backup.core.restore.twrp

import ca.devdcs.backup.core.pipeline.layout.TwrpConventions
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import java.util.Locale

/** A directory listing entry, decoupled from any storage backend so parsing is pure. */
data class FolderEntry(val name: String, val isDirectory: Boolean, val sizeBytes: Long?)

/**
 * Pure parser for a TWRP / OrangeFox backup folder listing, following the conventions in
 * [TwrpConventions] (verified against TeamWin's recovery sources): `<name>.<fs>.win`, split parts
 * `<file>%i%02i` (`win000`, `win001`, thread digit first), sidecars `<file>.sha2` / `.sha256` /
 * `.md5`, optional `<name>.info` and `recovery.log`.
 */
object TwrpFolderParser {

    const val IMAGE_NAME_SUFFIX = "_image"
    private const val INFO_EXTENSION = ".info"
    private const val SPLITS_PER_THREAD = 100

    /** TWRP splits names at the first two dots, so neither the name nor the fs may contain one. */
    private val WIN = Regex("^([^.]+)\\.([^.]+)\\.win(\\d{3})?$")

    /** Sidecar extensions in preference order (strongest first). */
    private val SIDECAR_EXTENSIONS: List<Pair<String, ChecksumAlgorithm>> = listOf(
        TwrpConventions.DIGEST_EXTENSION to ChecksumAlgorithm.SHA256,
        ".sha256" to ChecksumAlgorithm.SHA256,
        ".sha256sum" to ChecksumAlgorithm.SHA256,
        ".md5" to ChecksumAlgorithm.MD5,
        ".md5sum" to ChecksumAlgorithm.MD5,
    )

    /** Parsed `.win` file name. */
    data class WinName(val backupName: String, val fileSystem: String, val splitIndex: Int?) {
        /** The unsplit file name (`data.ext4.win` for `data.ext4.win003`). */
        val baseFileName: String get() = "$backupName.$fileSystem.win"
    }

    fun parseWinName(fileName: String): WinName? {
        val m = WIN.matchEntire(fileName) ?: return null
        return WinName(m.groupValues[1], m.groupValues[2], m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt())
    }

    /** `(win file name, algorithm)` when [fileName] is a sidecar of a `.win` file. */
    fun parseSidecarName(fileName: String): Pair<String, ChecksumAlgorithm>? {
        for ((ext, alg) in SIDECAR_EXTENSIONS) {
            if (fileName.endsWith(ext) && fileName.length > ext.length) {
                val target = fileName.dropLast(ext.length)
                if (parseWinName(target) != null) return target to alg
            }
        }
        return null
    }

    /** Device id of a folder under `TWRP/BACKUPS/<id>/<name>`, else null. */
    fun deviceIdOf(folderPath: String): String? {
        val segments = folderPath.split('/').filter { it.isNotEmpty() }
        if (segments.size < 4) return null
        val n = segments.size
        val root = TwrpConventions.ROOT_FOLDER.split('/')
        return if (segments[n - 4].equals(root[0], ignoreCase = true) && segments[n - 3].equals(root[1], ignoreCase = true)) segments[n - 2] else null
    }

    /** True when the listing contains at least one `.win` file. */
    fun looksLikeBackup(entries: List<FolderEntry>): Boolean = entries.any { !it.isDirectory && parseWinName(it.name) != null }

    fun parse(folderPath: String, entries: List<FolderEntry>): TwrpBackup {
        val folder = folderPath.trimEnd('/')
        fun pathOf(name: String) = if (folder.isEmpty()) name else "$folder/$name"
        val files = entries.filter { !it.isDirectory }
        val problems = ArrayList<TwrpFolderProblem>()
        val other = ArrayList<String>()
        var logFile: String? = null

        val wins = LinkedHashMap<String, Pair<FolderEntry, WinName>>()
        val sidecars = HashMap<String, MutableList<Pair<FolderEntry, ChecksumAlgorithm>>>()
        val infos = HashMap<String, String>()
        for (entry in files) {
            val win = parseWinName(entry.name)
            if (win != null) {
                wins[entry.name] = entry to win
                continue
            }
            val sidecar = parseSidecarName(entry.name)
            if (sidecar != null) {
                sidecars.getOrPut(sidecar.first) { ArrayList() } += entry to sidecar.second
                continue
            }
            when {
                entry.name == TwrpConventions.LOG_FILE -> logFile = pathOf(entry.name)
                entry.name.endsWith(INFO_EXTENSION) && '.' !in entry.name.dropLast(INFO_EXTENSION.length) ->
                    infos[entry.name.dropLast(INFO_EXTENSION.length)] = pathOf(entry.name)
                else -> other += entry.name
            }
        }
        for (target in sidecars.keys) if (target !in wins) problems += TwrpFolderProblem(TwrpFolderProblemKind.ORPHAN_SIDECAR, target)

        fun fileOf(entry: FolderEntry, win: WinName): TwrpFile {
            val candidates = sidecars[entry.name].orEmpty()
            if (candidates.size > 1) problems += TwrpFolderProblem(TwrpFolderProblemKind.DUPLICATE_SIDECAR, entry.name)
            val best = candidates.minByOrNull { (e, _) -> SIDECAR_EXTENSIONS.indexOfFirst { e.name.endsWith(it.first) } }
            return TwrpFile(
                fileName = entry.name,
                path = pathOf(entry.name),
                sizeBytes = entry.sizeBytes,
                splitIndex = win.splitIndex,
                sidecar = best?.let { (e, alg) -> TwrpSidecar(e.name, pathOf(e.name), alg) },
            )
        }

        val partitions = wins.values
            .groupBy { (_, win) -> win.backupName to win.fileSystem }
            .map { (key, group) ->
                val (backupName, fs) = key
                val single = group.firstOrNull { it.second.splitIndex == null }
                val splits = group.filter { it.second.splitIndex != null }.sortedBy { it.second.splitIndex }
                val parts = if (single != null) {
                    if (splits.isNotEmpty()) problems += TwrpFolderProblem(TwrpFolderProblemKind.SPLIT_AND_SINGLE, single.first.name)
                    listOf(fileOf(single.first, single.second))
                } else {
                    missingSplits(splits.mapNotNull { it.second.splitIndex }).forEach { missing ->
                        problems += TwrpFolderProblem(TwrpFolderProblemKind.SPLIT_GAP, splits.first().second.baseFileName + String.format(Locale.ROOT, "%03d", missing))
                    }
                    splits.map { fileOf(it.first, it.second) }
                }
                TwrpPartitionBackup(
                    backupName = backupName,
                    fileSystem = fs,
                    kind = if (fs.equals(TwrpConventions.IMAGE_FILE_SYSTEM, ignoreCase = true)) TwrpPayloadKind.RAW_IMAGE else TwrpPayloadKind.FILESYSTEM_ARCHIVE,
                    parts = parts,
                    infoFile = infos[backupName],
                )
            }
            .sortedBy { it.backupName }

        return TwrpBackup(
            folderPath = folder,
            name = folder.substringAfterLast('/'),
            deviceId = deviceIdOf(folder),
            partitions = partitions,
            logFile = logFile,
            otherFiles = other.sorted(),
            problems = problems,
        )
    }

    /**
     * Indexes missing from a split set. Each TWRP thread `t` numbers its archives `t00`, `t01`...,
     * so every thread present must be contiguous from `t00` to its highest index.
     */
    fun missingSplits(indexes: List<Int>): List<Int> =
        indexes.groupBy { it / SPLITS_PER_THREAD }.toSortedMap().flatMap { (thread, members) ->
            val base = thread * SPLITS_PER_THREAD
            val present = members.toSet()
            (base..(members.maxOrNull() ?: base)).filter { it !in present }
        }
}
