package ca.devdcs.backup.core.pipeline.source

import android.content.Context
import ca.devdcs.backup.core.discovery.partition.ResolvedPartition
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.pipeline.BackupSource
import ca.devdcs.backup.core.pipeline.SourceEstimate
import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.core.pipeline.layout.InventoryEntry
import ca.devdcs.backup.core.pipeline.layout.InventoryType
import ca.devdcs.backup.core.pipeline.layout.NandroidContent
import ca.devdcs.backup.core.pipeline.layout.NandroidFilesystemInput
import ca.devdcs.backup.core.pipeline.layout.NandroidImageInput
import ca.devdcs.backup.core.pipeline.layout.NandroidPlan
import ca.devdcs.backup.core.pipeline.layout.NandroidPlanner
import ca.devdcs.backup.core.pipeline.layout.NandroidRequest
import ca.devdcs.backup.core.pipeline.layout.TwrpConventions
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Pure scripts and parsers for the TWRP path (unit-tested). */
internal object NandroidScripts {

    /** `find` inventory of [mountPoint] on one filesystem: `<raw mode hex> <size> <path>`. */
    fun inventoryScript(mountPoint: String, pruned: List<String>): String {
        val q = PrivilegedStreamScripts::q
        val prune = if (pruned.isEmpty()) "" else "\\( ${pruned.joinToString(" -o ") { "-path ${q(it)}" }} \\) -prune -o "
        return "find ${q(mountPoint)} -xdev $prune-exec stat -c '%f %s %n' {} +"
    }

    fun parseInventoryLine(line: String): InventoryEntry? {
        val parts = line.split(' ', limit = 3)
        if (parts.size < 3) return null
        val mode = parts[0].toIntOrNull(16) ?: return null
        val size = parts[1].toLongOrNull() ?: return null
        val path = parts[2].takeIf { it.startsWith("/") } ?: return null
        return InventoryEntry(path, typeOf(mode), size)
    }

    fun typeOf(mode: Int): InventoryType = when (mode and S_IFMT) {
        S_IFREG -> InventoryType.FILE
        S_IFDIR -> InventoryType.DIRECTORY
        S_IFLNK -> InventoryType.SYMLINK
        S_IFIFO -> InventoryType.FIFO
        else -> InventoryType.OTHER
    }

    /** Marker toybox prints for a member that disappeared; only such errors are tolerated. */
    const val VANISHED_MARKER = "No such file or directory"

    /**
     * Volume tar of a LIVE filesystem: the planned member list is re-filtered at run time (members
     * that vanished since the inventory are dropped into [liveListFile]), then tar runs with an
     * exact list, no recursion, TWRP-readable GNU/pax output. If tar still fails only because
     * members vanished between the filter and the read, the exit is turned into success and the
     * messages go to stderr (reported by the caller as a warning). Any other error fails the volume.
     */
    fun tarVolumeScript(baseDirectory: String, listFile: String, liveListFile: String, selinux: Boolean): String {
        val q = PrivilegedStreamScripts::q
        val args = ArrayList<String>()
        args += listOf("tar", "-c", "-f", "-", "--numeric-owner", "--no-recursion")
        if (selinux) args += "--selinux"
        args += listOf("-C", baseDirectory, "-T", liveListFile)
        val tar = PrivilegedStreamScripts.command(args)
        val live = q(liveListFile)
        return "cd ${q(baseDirectory)} || exit 1; " +
            "while IFS= read -r __p; do if [ -e \"\$__p\" ] || [ -L \"\$__p\" ]; then printf '%s\\n' \"\$__p\"; fi; done < ${q(listFile)} > $live || exit 1; " +
            "exec 4>&1; __e=\$( $tar 2>&1 >&4 4>&- ); __rc=\$?; exec 4>&-; rm -f $live; " +
            "if [ \"\$__rc\" -ne 0 ] && [ -n \"\$__e\" ] && ! printf '%s\\n' \"\$__e\" | grep -v -F '$VANISHED_MARKER' | grep -q .; then __rc=0; fi; " +
            "if [ -n \"\$__e\" ]; then printf '%s\\n' \"\$__e\" >&2; fi; exit \"\$__rc\""
    }

    /** Device facts in one call: serial, boot serial, build display id, /data filesystem type. */
    const val FACTS_SCRIPT = "printf 'serial=%s\\n' \"\$(getprop ro.serialno)\"; " +
        "printf 'bootserial=%s\\n' \"\$(getprop ro.boot.serialno)\"; " +
        "printf 'display=%s\\n' \"\$(getprop ro.build.display.id)\"; " +
        "while read -r __d __m __f __r; do if [ \"\$__m\" = /data ]; then printf 'datafs=%s\\n' \"\$__f\"; fi; done < /proc/mounts; true"

    fun parseFacts(lines: List<String>): Map<String, String> {
        val facts = HashMap<String, String>()
        for (line in lines) {
            val key = line.substringBefore('=', "")
            if (key.isEmpty()) continue
            facts[key] = line.substringAfter('=').trim() // last datafs wins (the visible mount)
        }
        return facts
    }

    private const val S_IFMT = 0xF000
    private const val S_IFREG = 0x8000
    private const val S_IFDIR = 0x4000
    private const val S_IFLNK = 0xA000
    private const val S_IFIFO = 0x1000
}

/**
 * Materializes a [NandroidPlan] as source items: raw `dd` images, `/data` tar volumes built from an
 * exact member list, and `.sha2` sidecars whose content is the SHA-256 of the bytes this source just
 * produced for the preceding entry. Exists so twrpCompatible output is one ordinary [BackupSource].
 *
 * The sidecars only match what lands at the destination when entries are stored 1:1 (RAW_COPY,
 * no compression, no encryption), which is also the only form TWRP restores.
 */
class NandroidSource internal constructor(
    val plan: NandroidPlan,
    /** Stable selection keys (partition node names, data flag) for [sourceId]. */
    sourceKeys: List<String>,
    private val sizes: Map<String, Long>,
    private val tarSelinux: Boolean,
    private val cacheDir: File,
    private val streams: RootByteStreams,
    private val io: CoroutineDispatcher,
) : BackupSource {

    private val digests = DigestBook()
    private val vanished = ArrayList<SourceWarning>()

    /**
     * Problems tolerated while items were read (members of a live `/data` that vanished between
     * inventory and tar). Read after the items flow completed; shown with the job result.
     */
    val runtimeWarnings: List<SourceWarning> get() = synchronized(vanished) { vanished.toList() }

    override val sourceId: String = "nandroid:" + stableHash(sourceKeys)

    override suspend fun estimate(): SourceEstimate {
        var bytes = 0L
        var exact = true
        for (entry in plan.entries) {
            when (val c = entry.content) {
                is NandroidContent.RawImage -> {
                    val probed = sizes[c.partition.devicePath]
                    if (probed == null) exact = false
                    bytes += probed ?: c.sizeBytes ?: 0L
                }
                is NandroidContent.TarVolume -> {
                    bytes += c.regularFileBytes
                    exact = false
                }
                is NandroidContent.DigestSidecar -> bytes += TwrpConventions.digestLineLength(c.ofFileName)
                is NandroidContent.Log -> bytes += c.text.toByteArray(Charsets.UTF_8).size
            }
        }
        return SourceEstimate(plan.entries.size.toLong(), bytes, exact)
    }

    override fun items(): Flow<SourceItem> = flow {
        for (entry in plan.entries) {
            val name = entry.fileName
            when (val c = entry.content) {
                is NandroidContent.RawImage -> {
                    val probed = sizes[c.partition.devicePath]
                    val resolved: ResolvedPartition = c.partition
                    emit(SourceItem.Stream(entry.archivePath, probed ?: c.sizeBytes, sizeIsExact = probed != null) {
                        Sha256RecordingInputStream(PartitionImageSource.openImage(streams, resolved, probed), digests, name)
                    })
                }
                is NandroidContent.TarVolume -> emit(SourceItem.Stream(entry.archivePath, null, sizeIsExact = false) { openVolume(c, name) })
                is NandroidContent.DigestSidecar -> {
                    val of = c.ofFileName
                    emit(SourceItem.File(entry.archivePath, TwrpConventions.digestLineLength(of)) {
                        val hex = digests.get(of) ?: throw SourceReadException("$of: digest unavailable (the archive was not fully read)")
                        ByteArrayInputStream(TwrpConventions.digestLine(hex, of).toByteArray(Charsets.UTF_8))
                    })
                }
                is NandroidContent.Log -> {
                    val bytes = c.text.toByteArray(Charsets.UTF_8)
                    emit(SourceItem.File(entry.archivePath, bytes.size.toLong()) { ByteArrayInputStream(bytes) })
                }
            }
        }
    }.flowOn(io)

    private suspend fun openVolume(volume: NandroidContent.TarVolume, name: String): InputStream {
        val list = File(cacheDir, "dftb-twrp-${UUID.randomUUID()}.list")
        try {
            withContext(io) {
                list.bufferedWriter(Charsets.UTF_8).use { w -> volume.members.forEach { w.write(it); w.write("\n") } }
            }
            val live = File(list.path + ".live")
            val raw = streams.open(NandroidScripts.tarVolumeScript(volume.baseDirectory, list.absolutePath, live.absolutePath, tarSelinux), name) { stderr ->
                synchronized(vanished) { vanished += SourceWarning(SourceWarningKind.NANDROID_FILES_VANISHED, name, stderr.take(MAX_WARNING_DETAIL)) }
            }
            val hashed = Sha256RecordingInputStream(raw, digests, name)
            return object : FilterInputStream(hashed) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        list.delete()
                        live.delete()
                    }
                }
            }
        } catch (e: Throwable) {
            list.delete()
            throw e
        }
    }

    private companion object {
        const val MAX_WARNING_DETAIL = 1000
    }
}

/**
 * Prepares a [NandroidSource]: requires root, reads device facts (serial, build id, /data fs),
 * inventories `/data` with TWRP's exclusions and plans TWRP-sized volumes.
 */
@Singleton
class NandroidSourceFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: CommandExecutorRouter,
    private val streams: RootByteStreams,
    private val partitionFactory: PartitionImageSourceFactory,
    private val strings: PipelineSourceStrings,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * [partitions]: raw images (TWRP keeps the active slot of by-name partitions only; others are
     * reported in the plan warnings). [includeData]: tar `/data` (minus media) as `data.<fs>.win`.
     */
    suspend fun prepare(
        partitions: List<ResolvedPartition>,
        includeData: Boolean,
        time: LocalDateTime = LocalDateTime.now(),
        logText: String? = null,
    ): SourcePreparation<NandroidSource> = withContext(io) {
        if (partitions.isEmpty() && !includeData) return@withContext strings.unavailable(SourceUnavailableReason.NOTHING_SELECTED)
        val executor = router.current()
        if (executor.tier != ElevationTier.ROOT) return@withContext strings.unavailable(SourceUnavailableReason.ROOT_REQUIRED, executor.tier.name)
        if (!executor.isAvailable()) return@withContext strings.unavailable(SourceUnavailableReason.ROOT_NOT_GRANTED)

        val warnings = ArrayList<SourceWarning>()
        val factsResult = executor.execute(Command(listOf("sh", "-c", NandroidScripts.FACTS_SCRIPT), timeoutMillis = PROBE_TIMEOUT_MILLIS))
        val facts = if (factsResult.launched) NandroidScripts.parseFacts(factsResult.stdout) else emptyMap()
        val serial = facts["serial"].orEmpty().ifEmpty { facts["bootserial"].orEmpty() }
        val sizes = partitionFactory.probeSizes(partitions.map { it.devicePath }.distinct())
        val tarSelinux = includeData && probeTarSelinux(executor)
        if (includeData && !tarSelinux) warnings += SourceWarning(SourceWarningKind.SELINUX_TAR_UNSUPPORTED, "data")

        val filesystems = ArrayList<NandroidFilesystemInput>()
        if (includeData) {
            val fs = facts["datafs"].orEmpty().ifEmpty { "ext4" }
            val own = ownExclusions()
            val inventory = inventory(DATA_MOUNT, TwrpConventions.DATA_EXCLUSIONS + own, warnings)
            filesystems += NandroidFilesystemInput("data", DATA_MOUNT, fs, inventory, extraExclusions = own)
        }

        val plan = NandroidPlanner.plan(
            NandroidRequest(
                serial = serial,
                buildDisplayId = facts["display"],
                time = time,
                images = partitions.map { NandroidImageInput(it, sizes[it.devicePath] ?: it.partition.sizeBytes) },
                filesystems = filesystems,
                logText = logText,
            ),
        )
        plan.warnings.forEach { warnings += SourceWarning(SourceWarningKind.NANDROID_PLAN, it.subject, "${it.kind.name} x${it.count}") }
        if (plan.isEmpty) return@withContext strings.unavailable(SourceUnavailableReason.NOTHING_SELECTED, plan.warnings.joinToString { it.kind.name })
        SourcePreparation.Ready(NandroidSource(plan, partitions.map { it.partition.name }.sorted() + "data=$includeData", sizes, tarSelinux, context.cacheDir, streams, io), warnings)
    }

    /** DFTB's own cache dirs: they hold the live FIFOs and tar lists of this very job. */
    private fun ownExclusions(): List<String> {
        val pkg = context.packageName
        return listOf("/data/data/$pkg/cache", "/data/data/$pkg/code_cache", "/data/user_de/0/$pkg/cache", "/data/user_de/0/$pkg/code_cache")
    }

    private suspend fun inventory(mount: String, pruned: List<String>, warnings: MutableList<SourceWarning>): List<InventoryEntry> {
        val entries = ArrayList<InventoryEntry>()
        var unparsed = 0
        try {
            streams.open(NandroidScripts.inventoryScript(mount, pruned), "inventory $mount").bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    val entry = NandroidScripts.parseInventoryLine(line)
                    if (entry == null) unparsed++ else entries += entry
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceReadException) {
            // find exits non-zero when some entries were unreadable; what was listed is still exact.
            warnings += SourceWarning(SourceWarningKind.NANDROID_INVENTORY_PARTIAL, mount, e.message)
        }
        if (unparsed > 0) warnings += SourceWarning(SourceWarningKind.NANDROID_INVENTORY_PARTIAL, mount, "$unparsed unparseable line(s)")
        return entries
    }

    private suspend fun probeTarSelinux(executor: CommandExecutor): Boolean {
        val result = executor.execute(Command(listOf("sh", "-c", AppDataScripts.TAR_SELINUX_PROBE), timeoutMillis = PROBE_TIMEOUT_MILLIS))
        return result.isSuccess && result.stdout.any { it.trim() == "yes" }
    }

    private companion object {
        const val DATA_MOUNT = "/data"
        const val PROBE_TIMEOUT_MILLIS = 30_000L
    }
}
