package ca.devdcs.backup.core.restore.twrp

import ca.devdcs.backup.R
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.pipeline.checksum.ChecksumSidecar
import ca.devdcs.backup.core.pipeline.checksum.Digester
import ca.devdcs.backup.core.pipeline.checksum.Digesters
import ca.devdcs.backup.core.pipeline.layout.TwrpConventions
import ca.devdcs.backup.core.restore.RestoreUnitResult
import ca.devdcs.backup.core.restore.engine.RestoreProgress
import ca.devdcs.backup.core.restore.partition.ImageOrigin
import ca.devdcs.backup.core.restore.partition.ImagePayload
import ca.devdcs.backup.core.restore.partition.PartitionImageWriter
import ca.devdcs.backup.core.restore.partition.PartitionRestoreOptions
import ca.devdcs.backup.core.restore.partition.PartitionRestoreStrings
import ca.devdcs.backup.core.restore.partition.SlotMapping
import ca.devdcs.backup.core.restore.partition.SlotPlan
import ca.devdcs.backup.core.restore.partition.TargetInspection
import ca.devdcs.backup.core.restore.partition.WriterEnvironment
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageRegistry
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.Destination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Backups found under a search root, plus why the search was incomplete (if it was). */
data class TwrpScanResult(val backups: List<TwrpBackup>, val error: String? = null)

/**
 * Imports TWRP / OrangeFox backup folders (made by real TWRP or by DFTB's NandroidLayout) from any
 * destination. Exists so a device owner's existing recovery backups are usable from Android:
 * raw `.emmc.win` images are restored through [PartitionImageWriter] (same gates, verification and
 * read-back as DFTB images); filesystem `.win` archives can only be extracted into a folder, because
 * replacing `/data` (or any mounted filesystem) under a running Android is impossible to do safely.
 * Plain and gzip-compressed archives are supported; TWRP-encrypted (OpenAES) ones are detected and
 * refused with a clear message.
 */
@Singleton
class TwrpImporter @Inject constructor(
    private val registry: StorageRegistry,
    private val writer: PartitionImageWriter,
    private val strings: PartitionRestoreStrings,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Find backups under [searchRoot]: the folder itself when it holds `.win` files, else
     * `<root>/<name>` and `<root>/<serial>/<name>` folders that do.
     */
    suspend fun find(destination: Destination, searchRoot: String = TwrpConventions.ROOT_FOLDER): TwrpScanResult = withContext(io) {
        try {
            registry.withSession(destination) { session ->
                val root = searchRoot.trim('/')
                val top = listOrNull(session, root) ?: return@withSession TwrpScanResult(emptyList(), strings.get(R.string.twrp_folder_not_found, searchRoot))
                if (TwrpFolderParser.looksLikeBackup(top)) return@withSession TwrpScanResult(listOf(TwrpFolderParser.parse(root, top)))
                val found = ArrayList<TwrpBackup>()
                for (child in top.filter { it.isDirectory }) {
                    val childPath = join(root, child.name)
                    val entries = listOrNull(session, childPath) ?: continue
                    if (TwrpFolderParser.looksLikeBackup(entries)) {
                        found += TwrpFolderParser.parse(childPath, entries)
                        continue
                    }
                    for (grandchild in entries.filter { it.isDirectory }) {
                        val path = join(childPath, grandchild.name)
                        val inner = listOrNull(session, path) ?: continue
                        if (TwrpFolderParser.looksLikeBackup(inner)) found += TwrpFolderParser.parse(path, inner)
                    }
                }
                TwrpScanResult(found.sortedBy { it.folderPath })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageException) {
            TwrpScanResult(emptyList(), strings.get(R.string.twrp_read_failed, searchRoot, e.message ?: e.reason.name))
        }
    }

    /** Parse the backup folder at [folderPath]; null when it holds no `.win` file or cannot be listed. */
    suspend fun open(destination: Destination, folderPath: String): TwrpBackup? = withContext(io) {
        try {
            registry.withSession(destination) { session ->
                val path = folderPath.trim('/')
                val entries = listOrNull(session, path)
                if (entries == null || !TwrpFolderParser.looksLikeBackup(entries)) null else TwrpFolderParser.parse(path, entries)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageException) {
            null
        }
    }

    /** Format of [partition]'s first stored file. @throws StorageException when the destination cannot be read. */
    suspend fun sniff(destination: Destination, partition: TwrpPartitionBackup): TwrpPayloadFormat = withContext(io) {
        registry.withSession(destination) { session -> sniff(session, partition, partition.parts.first()) }
    }

    /**
     * Compare every stored file with its sidecar. [onProgress] receives bytes read. Per-file read
     * problems are reported as [TwrpCheckStatus.READ_FAILED]; @throws StorageException only when
     * the destination cannot be connected at all.
     */
    suspend fun verify(destination: Destination, backup: TwrpBackup, onProgress: (Long) -> Unit = {}): List<TwrpFileCheck> = withContext(io) {
        registry.withSession(destination) { session ->
            backup.partitions.flatMap { p -> p.parts }.map { file -> checkFile(session, file, onProgress) }
        }
    }

    /**
     * Restore one partition backup in place. Raw images are written to [targetPartitionName]
     * (default: [TwrpPartitionBackup.partitionName]) of this device; filesystem archives are
     * refused with the "use recovery" message (use [extract] to get at their files).
     */
    suspend fun restore(
        destination: Destination,
        backup: TwrpBackup,
        partition: TwrpPartitionBackup,
        options: PartitionRestoreOptions,
        progress: RestoreProgress,
        targetPartitionName: String? = null,
    ): RestoreUnitResult {
        val unitId = unitId(backup, partition)
        return when (partition.kind) {
            TwrpPayloadKind.FILESYSTEM_ARCHIVE ->
                RestoreUnitResult(unitId, ok = false, error = strings.get(R.string.twrp_filesystem_restore_refused, partition.parts.first().fileName, partition.backupName))
            TwrpPayloadKind.RAW_IMAGE -> restoreImage(destination, backup, partition, options, progress, targetPartitionName)
        }
    }

    /** Write a raw `.emmc.win` image through the shared verified writer. */
    suspend fun restoreImage(
        destination: Destination,
        backup: TwrpBackup,
        partition: TwrpPartitionBackup,
        options: PartitionRestoreOptions,
        progress: RestoreProgress,
        targetPartitionName: String? = null,
    ): RestoreUnitResult = withContext(io) {
        val unitId = unitId(backup, partition)
        fun fail(message: String, warnings: List<String> = emptyList()) = RestoreUnitResult(unitId, ok = false, warnings = warnings, error = message)
        val file = partition.parts.first()
        if (partition.kind != TwrpPayloadKind.RAW_IMAGE) return@withContext fail(strings.get(R.string.twrp_not_raw_image, file.fileName))
        if (partition.parts.size != 1 || partition.isSplit) return@withContext fail(strings.get(R.string.twrp_split_image_unsupported, file.fileName))

        val env = when (val e = writer.environment()) {
            is WriterEnvironment.Unavailable -> return@withContext fail(e.message)
            is WriterEnvironment.Ready -> e
        }
        val name = targetPartitionName?.takeIf { it.isNotBlank() } ?: partition.partitionName
        val plan = when (val p = SlotMapping.planTwrp(name, env.scan, options.slotChoice)) {
            is SlotPlan.Failed -> return@withContext fail(writer.slotFailureMessage(p.failure, name))
            is SlotPlan.Planned -> p
        }
        val target = when (val inspection = writer.inspect(env, plan)) {
            is TargetInspection.Failed -> return@withContext fail(inspection.message)
            is TargetInspection.Ready -> inspection
        }
        val origin = ImageOrigin.Twrp(backup.deviceId, currentDeviceId(env.executor))
        try {
            registry.withSession(destination) { session ->
                val warnings = ArrayList<String>()
                val expected = HashMap<ChecksumAlgorithm, String>()
                val sidecar = file.sidecar
                if (sidecar == null) {
                    warnings += strings.get(R.string.twrp_sidecar_missing, file.fileName)
                } else {
                    val hex = readSidecar(session, file) ?: return@withSession fail(strings.get(R.string.twrp_sidecar_unreadable, sidecar.fileName), warnings)
                    expected[sidecar.algorithm] = hex
                }
                val payload = ImagePayload(
                    label = file.fileName,
                    declaredSizeBytes = file.sizeBytes,
                    expectedDigests = expected,
                    open = { session.openRead(file.path) },
                )
                val result = writer.write(unitId, env, target, payload, origin, plan.crossesSlot, options, progress)
                result.copy(warnings = warnings + result.warnings)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageException) {
            fail(strings.get(R.string.twrp_read_failed, file.fileName, e.message ?: e.reason.name))
        }
    }

    /**
     * Extract a filesystem `.win` set (all split parts in order) into [targetFolder] on [target].
     * Regular files and directories are written; links and device nodes are counted and skipped
     * (most destinations cannot store them). Existing files are overwritten. Ownership, modes and
     * SELinux labels are not applied: this is a file export, not a system restore.
     */
    suspend fun extract(
        source: Destination,
        backup: TwrpBackup,
        partition: TwrpPartitionBackup,
        target: Destination,
        targetFolder: String,
        progress: RestoreProgress,
    ): TwrpExtractResult = withContext(io) {
        val first = partition.parts.first()
        if (partition.kind != TwrpPayloadKind.FILESYSTEM_ARCHIVE) {
            return@withContext TwrpExtractResult(false, 0, 0, 0, emptyList(), strings.get(R.string.twrp_not_archive, first.fileName))
        }
        val counters = ExtractCounters()
        val warnings = ArrayList<String>()
        try {
            registry.withSession(source) { src ->
                registry.withSession(target) { dst ->
                    val folder = targetFolder.trim('/')
                    if (folder.isNotEmpty()) dst.mkdirs(folder)
                    val strip = if (partition.isSplit) partition.backupName else null
                    for (part in partition.parts) {
                        when (sniff(src, partition, part)) {
                            TwrpPayloadFormat.ENCRYPTED -> return@withSession counters.result(false, warnings, strings.get(R.string.twrp_encrypted_unsupported, part.fileName))
                            TwrpPayloadFormat.TAR, TwrpPayloadFormat.GZIP -> Unit
                            else -> return@withSession counters.result(false, warnings, strings.get(R.string.twrp_unknown_format, part.fileName))
                        }
                        val hex = extractPart(src, dst, part, folder, strip, counters, progress)
                        val sidecar = part.sidecar
                        if (sidecar != null) {
                            val expected = readSidecar(src, part)
                            when {
                                expected == null -> warnings += strings.get(R.string.twrp_sidecar_unreadable, sidecar.fileName)
                                !ChecksumSidecar.matches(expected, hex.getValue(sidecar.algorithm)) ->
                                    return@withSession counters.result(false, warnings, strings.get(R.string.twrp_extract_digest_mismatch, part.fileName, sidecar.fileName))
                            }
                        } else {
                            warnings += strings.get(R.string.twrp_sidecar_missing, part.fileName)
                        }
                    }
                    if (counters.skippedSpecial > 0) warnings += strings.get(R.string.twrp_extract_skipped_links, counters.skippedSpecial, partition.backupName)
                    if (counters.unsafe > 0) warnings += strings.get(R.string.twrp_extract_unsafe_entries, counters.unsafe, partition.backupName)
                    counters.result(true, warnings, null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            counters.result(false, warnings, strings.get(R.string.twrp_extract_failed, first.fileName, e.message ?: e.javaClass.simpleName))
        }
    }

    private class ExtractCounters {
        var files = 0L
        var dirs = 0L
        var bytes = 0L
        var skippedSpecial = 0
        var unsafe = 0

        fun result(ok: Boolean, warnings: List<String>, error: String?) = TwrpExtractResult(ok, files, dirs, bytes, warnings.toList(), error)
    }

    /** Extract one tar part; returns the stored-bytes digests (SHA-256 and MD5) of the whole file. */
    private suspend fun extractPart(
        src: StorageSession,
        dst: StorageSession,
        part: TwrpFile,
        folder: String,
        strip: String?,
        counters: ExtractCounters,
        progress: RestoreProgress,
    ): Map<ChecksumAlgorithm, String> {
        val digesters = listOf(Digesters.create(ChecksumAlgorithm.SHA256), Digesters.create(ChecksumAlgorithm.MD5))
        val buffer = ByteArray(BUFFER_SIZE)
        src.openRead(part.path).use { raw ->
            val buffered = BufferedInputStream(raw, BUFFER_SIZE)
            val header = ByteArray(2)
            buffered.mark(header.size)
            val n = readFully(buffered, header)
            buffered.reset()
            val stored = DigestingInputStream(buffered, digesters)
            val gzip = n == 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()
            val plain: InputStream = if (gzip) {
                GzipCompressorInputStream.builder().setInputStream(NonClosingInputStream(stored)).setDecompressConcatenated(true).get()
            } else {
                NonClosingInputStream(stored)
            }
            val tar = TarArchiveInputStream(plain, Charsets.UTF_8.name())
            while (true) {
                checkCancelled(progress)
                val entry = tar.nextEntry ?: break
                val rel = TwrpArchivePaths.outputPath(entry.name, strip)
                if (rel == null) {
                    counters.unsafe++
                    continue
                }
                val path = join(folder, rel)
                when {
                    entry.isDirectory -> {
                        dst.mkdirs(path)
                        counters.dirs++
                    }
                    entry.isFile && !entry.isLink -> {
                        progress.onItem(rel)
                        dst.openWrite(path).use { out ->
                            while (true) {
                                val r = tar.read(buffer)
                                if (r < 0) break
                                out.write(buffer, 0, r)
                                counters.bytes += r
                                progress.onBytes(r.toLong())
                                checkCancelled(progress)
                            }
                        }
                        counters.files++
                    }
                    else -> counters.skippedSpecial++
                }
            }
            // Read the archive's trailing blocks (and the gzip trailer) so the digest covers the whole file.
            while (stored.read(buffer) >= 0) checkCancelled(progress)
        }
        return mapOf(ChecksumAlgorithm.SHA256 to digesters[0].finishHex(), ChecksumAlgorithm.MD5 to digesters[1].finishHex())
    }

    private suspend fun checkCancelled(progress: RestoreProgress) {
        if (progress.isCancelled()) throw CancellationException("extraction cancelled")
        currentCoroutineContext().ensureActive()
    }

    private suspend fun sniff(session: StorageSession, partition: TwrpPartitionBackup, file: TwrpFile): TwrpPayloadFormat {
        val header = ByteArray(TwrpPayloadSniffer.HEADER_BYTES)
        val n = session.openRead(file.path).use { readFully(it, header) }
        return TwrpPayloadSniffer.sniff(header, n, partition.kind)
    }

    private suspend fun checkFile(session: StorageSession, file: TwrpFile, onProgress: (Long) -> Unit): TwrpFileCheck {
        val sidecar = file.sidecar ?: return TwrpFileCheck(file, TwrpCheckStatus.NO_SIDECAR, null, null, null)
        val expected = try {
            readSidecar(session, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return TwrpFileCheck(file, TwrpCheckStatus.SIDECAR_UNREADABLE, sidecar.algorithm, null, null, sidecar.fileName)
        val actual = try {
            digestOf(session, file.path, sidecar.algorithm, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return TwrpFileCheck(file, TwrpCheckStatus.READ_FAILED, sidecar.algorithm, expected, null, e.message ?: e.javaClass.simpleName)
        }
        val status = if (ChecksumSidecar.matches(expected, actual)) TwrpCheckStatus.OK else TwrpCheckStatus.MISMATCH
        return TwrpFileCheck(file, status, sidecar.algorithm, expected, actual)
    }

    /** The expected hex for [file] from its sidecar: the line naming it, else the only line. */
    private suspend fun readSidecar(session: StorageSession, file: TwrpFile): String? {
        val sidecar = file.sidecar ?: return null
        val parsed = try {
            session.openRead(sidecar.path).use { ChecksumSidecar.read(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val entry = parsed.entries.firstOrNull { it.fileName.substringAfterLast('/') == file.fileName } ?: parsed.entries.singleOrNull()
        return entry?.hex?.takeIf { it.length == sidecar.algorithm.digestLengthBytes * 2 }
    }

    private suspend fun digestOf(session: StorageSession, path: String, algorithm: ChecksumAlgorithm, onProgress: (Long) -> Unit): String {
        val digester = Digesters.create(algorithm)
        val buffer = ByteArray(BUFFER_SIZE)
        session.openRead(path).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digester.update(buffer, 0, n)
                onProgress(n.toLong())
            }
        }
        return digester.finishHex()
    }

    /** This device's serial, sanitized like TWRP's folder name; null when it cannot be read. */
    private suspend fun currentDeviceId(executor: CommandExecutor): String? {
        for (prop in listOf("ro.serialno", "ro.boot.serialno")) {
            val result = executor.execute(Command(listOf("getprop", prop), timeoutMillis = GETPROP_TIMEOUT_MILLIS))
            val value = if (result.isSuccess) TwrpConventions.deviceId(result.stdoutText.trim()) else ""
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private suspend fun listOrNull(session: StorageSession, path: String): List<FolderEntry>? = try {
        session.list(path).map { FolderEntry(it.name, it.isDirectory, it.sizeBytes) }
    } catch (e: StorageException) {
        null
    }

    private fun unitId(backup: TwrpBackup, partition: TwrpPartitionBackup): String = "twrp:${backup.folderPath}/${partition.backupName}"

    private fun join(a: String, b: String): String = if (a.isEmpty()) b else "$a/$b"

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** Feeds every byte read through it into [digesters] (skip is implemented as read so nothing is missed). */
    private class DigestingInputStream(input: InputStream, private val digesters: List<Digester>) : FilterInputStream(input) {
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) digesters.forEach { it.update(byteArrayOf(b.toByte()), 0, 1) }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) digesters.forEach { it.update(b, off, n) }
            return n
        }

        override fun skip(n: Long): Long {
            val scratch = ByteArray(minOf(n, BUFFER_SIZE.toLong()).toInt().coerceAtLeast(1))
            var left = n
            while (left > 0) {
                val r = read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                if (r < 0) break
                left -= r
            }
            return n - left
        }

        override fun markSupported(): Boolean = false
    }

    /** Keeps the archive readers from closing the shared stored-bytes stream. */
    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private companion object {
        const val BUFFER_SIZE = 1 shl 16
        const val GETPROP_TIMEOUT_MILLIS = 5_000L
    }
}
