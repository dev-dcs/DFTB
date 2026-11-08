package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.pipeline.ArchiveFactory
import ca.devdcs.backup.core.pipeline.ArchiveWriter
import ca.devdcs.backup.core.pipeline.BackupSource
import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.EncryptionHeader
import ca.devdcs.backup.core.pipeline.FilenameMasker
import ca.devdcs.backup.core.pipeline.SourceEstimate
import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.core.pipeline.StreamCipher
import ca.devdcs.backup.core.storage.RemoteEntry
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageBackend
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageRegistry
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.CompressionCodec
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.HashRecord
import ca.devdcs.backup.domain.model.HistoryRecord
import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.model.JobStatus
import ca.devdcs.backup.domain.model.RetentionPolicy
import ca.devdcs.backup.domain.model.TargetSelection
import ca.devdcs.backup.domain.repository.CheckpointRepository
import ca.devdcs.backup.domain.repository.HashRegistryRepository
import ca.devdcs.backup.domain.repository.HistoryRepository
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.Secret
import ca.devdcs.backup.domain.security.VaultEntryInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZoneOffset

/** In-memory destination shared by every session of one test, with failure injection. */
internal class MemoryStore {
    val files = LinkedHashMap<String, ByteArray>()
    val dirs = LinkedHashSet<String>()
    var space: SpaceInfo? = SpaceInfo(freeBytes = 1L shl 40, totalBytes = 1L shl 41)
    var supportsAppend = true
    var connects = 0
    val writeOpens = ArrayList<Pair<String, Boolean>>()

    /** Fail the Nth openWrite of a path (1-based) with the returned exception, if any. */
    var openWriteFailure: (path: String, attempt: Int) -> Exception? = { _, _ -> null }

    /** When set, the first stream written to a path matching the predicate breaks after this many bytes. */
    var breakAfterBytes: Int? = null
    var breakPath: (String) -> Boolean = { false }

    fun text(path: String): String = files[path]?.toString(Charsets.UTF_8) ?: throw AssertionError("Missing $path in ${files.keys}")
}

internal class MemorySession(override val destination: Destination, private val store: MemoryStore) : StorageSession {
    private val attempts = HashMap<String, Int>()

    override suspend fun spaceInfo(): SpaceInfo? = store.space
    override suspend fun list(path: String): List<RemoteEntry> =
        store.files.keys.filter { it.startsWith("$path/") }.map { RemoteEntry(it, it.substringAfterLast('/'), false, store.files[it]!!.size.toLong(), null) }

    override suspend fun stat(path: String): RemoteEntry? {
        store.files[path]?.let { return RemoteEntry(path, path.substringAfterLast('/'), false, it.size.toLong(), null) }
        if (path in store.dirs) return RemoteEntry(path, path.substringAfterLast('/'), true, null, null)
        return null
    }

    override suspend fun mkdirs(path: String) {
        store.dirs += path
    }

    override suspend fun openWrite(path: String, append: Boolean): OutputStream {
        store.writeOpens += path to append
        if (append && !store.supportsAppend) throw StorageException(StorageException.Reason.UNSUPPORTED, "no append")
        val n = (attempts[path] ?: 0) + 1
        attempts[path] = n
        store.openWriteFailure(path, globalAttempt(path))?.let { throw it }
        val initial = if (append) store.files[path] ?: ByteArray(0) else ByteArray(0)
        store.files[path] = initial
        val limit = store.breakAfterBytes?.takeIf { store.breakPath(path) }
        if (limit != null) store.breakAfterBytes = null
        return object : OutputStream() {
            private val buffer = ByteArrayOutputStream().apply { write(initial) }
            private var written = 0
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (limit != null && written + len > limit) {
                    val keep = limit - written
                    if (keep > 0) buffer.write(b, off, keep)
                    written = limit
                    store.files[path] = buffer.toByteArray()
                    throw IOException("connection reset")
                }
                buffer.write(b, off, len)
                written += len
                store.files[path] = buffer.toByteArray()
            }
            override fun close() {
                store.files[path] = buffer.toByteArray()
            }
        }
    }

    private fun globalAttempt(path: String): Int = store.writeOpens.count { it.first == path }

    override suspend fun openRead(path: String, offset: Long): InputStream {
        val data = store.files[path] ?: throw StorageException(StorageException.Reason.NOT_FOUND, "Not found: $path")
        return ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
    }

    override suspend fun delete(path: String) {
        store.files.remove(path)
        store.dirs.remove(path)
    }

    override suspend fun rename(from: String, to: String) {
        val data = store.files.remove(from) ?: throw StorageException(StorageException.Reason.NOT_FOUND, "Not found: $from")
        store.files[to] = data
    }

    override fun close() = Unit
}

internal class MemoryRegistry(private val store: MemoryStore) : StorageRegistry {
    private val backend = object : StorageBackend {
        override val types: Set<DestinationType> = DestinationType.entries.toSet()
        override suspend fun connect(destination: Destination): StorageSession {
            store.connects++
            return MemorySession(destination, store)
        }
    }

    override fun backendFor(type: DestinationType): StorageBackend = backend

    override suspend fun <T> withSession(destination: Destination, block: suspend (StorageSession) -> T): T {
        val session = backend.connect(destination)
        try {
            return block(session)
        } finally {
            session.close()
        }
    }
}

/** Writer format: one header line per item then its bytes, plus a trailer. Codecs tag the stream. */
internal class FakeArchiveFactory : ArchiveFactory {
    val writerFormats = ArrayList<ArchiveFormat>()

    override fun writer(format: ArchiveFormat, out: OutputStream, compressionLevel: Int): ArchiveWriter {
        writerFormats += format
        return object : ArchiveWriter {
            override val format: ArchiveFormat = format
            override suspend fun write(item: SourceItem) {
                out.write("${item.archivePath}\n".toByteArray())
                when (item) {
                    is SourceItem.File -> item.open().use { it.copyTo(out) }
                    is SourceItem.Stream -> item.open().use { it.copyTo(out) }
                    else -> Unit
                }
            }
            override suspend fun finish() {
                out.write("END\n".toByteArray())
                out.flush()
            }
            override fun close() = Unit
        }
    }

    override fun compress(codec: CompressionCodec, level: Int, out: OutputStream): OutputStream =
        if (codec == CompressionCodec.NONE) out else TaggedStream(out, "[${codec.storageKey}]")

    override fun decompress(codec: CompressionCodec, input: InputStream): InputStream = input
}

/** Writes [tag] first, then passes bytes through; closing closes the target. */
private class TaggedStream(out: OutputStream, tag: String) : FilterOutputStream(out) {
    init {
        out.write(tag.toByteArray())
    }
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
}

/** "Encrypts" by writing a header line and XOR-ing with 0x5A; records aborts vs closes. */
internal class FakeCipher : StreamCipher {
    var opened = 0
    override suspend fun encrypt(out: OutputStream, key: CipherKeySpec): OutputStream {
        opened++
        out.write("ENC\n".toByteArray())
        return object : FilterOutputStream(out) {
            override fun write(b: Int) = out.write(b xor 0x5A)
            override fun write(b: ByteArray, off: Int, len: Int) {
                for (i in off until off + len) out.write(b[i].toInt() xor 0x5A)
            }
        }
    }

    override suspend fun decrypt(input: InputStream, key: CipherKeySpec): InputStream = input
    override suspend fun describe(key: CipherKeySpec): EncryptionHeader =
        EncryptionHeader("fake-xor", mapOf("kdf" to if (key is CipherKeySpec.VaultKey) "vault" else "passphrase"))
}

internal class FakeSource(
    override val sourceId: String,
    private val items: List<SourceItem>,
    private val estimate: SourceEstimate = SourceEstimate(
        items.size.toLong(),
        items.sumOf { (it as? SourceItem.File)?.sizeBytes ?: 0L },
        exact = true,
    ),
    private val flowOverride: Flow<SourceItem>? = null,
) : BackupSource {
    override suspend fun estimate(): SourceEstimate = estimate
    override fun items(): Flow<SourceItem> = flowOverride ?: flow { items.forEach { emit(it) } }
}

internal class FakeSourceFactory(
    private val sources: List<BackupSource>,
    private val issues: List<SourceIssue> = emptyList(),
    private val verbatimIds: Set<String> = emptySet(),
    private val runtimeIssues: Map<String, () -> List<SourceIssue>> = emptyMap(),
) : BackupSourceFactory {
    override suspend fun create(profile: BackupProfile, selection: TargetSelection): SourceResolution = SourceResolution(
        sources.map { source ->
            val kind = SourceKind.fromSourceId(source.sourceId)
            val name = if (kind == SourceKind.OTHER) source.sourceId else source.sourceId.removePrefix(kind.idPrefix)
            ResolvedSource(source, kind, name, storeVerbatim = source.sourceId in verbatimIds, runtimeIssues = runtimeIssues[source.sourceId] ?: { emptyList() })
        },
        issues,
    )
}

internal fun fileItem(path: String, content: String, mtime: Long? = 1_000L): SourceItem.File {
    val bytes = content.toByteArray()
    return SourceItem.File(
        archivePath = path,
        sizeBytes = bytes.size.toLong(),
        metadata = ca.devdcs.backup.core.pipeline.EntryMetadata(modifiedAtMillis = mtime, mode = 420, uid = 10_100, seLinuxContext = "u:object_r:app_data_file:s0"),
    ) { ByteArrayInputStream(bytes) }
}

internal class FakeCheckpoints : CheckpointRepository {
    val rows = LinkedHashMap<String, JobCheckpoint>()
    val history = ArrayList<JobCheckpoint>()
    private val flow = MutableStateFlow<Map<String, JobCheckpoint>>(emptyMap())

    override fun observeAll(): Flow<List<JobCheckpoint>> = flow.map { it.values.toList() }
    override fun observe(jobId: String): Flow<JobCheckpoint?> = flow.map { it[jobId] }
    override fun observeResumableCount(): Flow<Int> = flow.map { m -> m.values.count { it.isResumable } }
    override suspend fun get(jobId: String): JobCheckpoint? = rows[jobId]
    override suspend fun getForProfile(profileId: String): List<JobCheckpoint> = rows.values.filter { it.profileId == profileId }
    override suspend fun resumable(): List<JobCheckpoint> = rows.values.filter { it.isResumable }
    override suspend fun resumableAfterReboot(): List<JobCheckpoint> = resumable().filter { it.resumeAfterReboot }
    override suspend fun save(checkpoint: JobCheckpoint) {
        rows[checkpoint.jobId] = checkpoint
        history += checkpoint
        flow.value = rows.toMap()
    }
    override suspend fun setStatus(jobId: String, status: JobStatus, updatedAt: Long) {
        rows[jobId]?.let { save(it.copy(status = status, updatedAt = updatedAt)) }
    }
    override suspend fun markRunningAsInterrupted(staleBefore: Long, updatedAt: Long): Int = 0
    override suspend fun delete(jobId: String) {
        rows.remove(jobId)
    }
    override suspend fun clearTerminal(): Int = 0
    override suspend fun deleteForProfile(profileId: String): Int = 0
}

internal class FakeHistory : HistoryRepository {
    val rows = LinkedHashMap<String, HistoryRecord>()
    val saves = ArrayList<HistoryRecord>()
    private val flow = MutableStateFlow<Map<String, HistoryRecord>>(emptyMap())

    override fun observeRecent(limit: Int): Flow<List<HistoryRecord>> = flow.map { it.values.toList() }
    override fun observeForProfile(profileId: String): Flow<List<HistoryRecord>> = flow.map { m -> m.values.filter { it.profileId == profileId } }
    override fun observeActive(): Flow<List<HistoryRecord>> = flow.map { m -> m.values.filter { !it.isTerminal } }
    override fun observe(id: String): Flow<HistoryRecord?> = flow.map { it[id] }
    override suspend fun get(id: String): HistoryRecord? = rows[id]
    override suspend fun getByIds(ids: List<String>): List<HistoryRecord> = ids.mapNotNull { rows[it] }
    override suspend fun latestForProfile(profileId: String): HistoryRecord? = rows.values.lastOrNull { it.profileId == profileId }
    override suspend fun latestSuccessfulForProfile(profileId: String): HistoryRecord? =
        rows.values.lastOrNull { it.profileId == profileId && it.isSuccessful }
    override suspend fun getByStatus(statuses: Set<JobStatus>): List<HistoryRecord> = rows.values.filter { it.status in statuses }
    override suspend fun countForProfile(profileId: String): Int = rows.values.count { it.profileId == profileId }
    override suspend fun artifactBytesForDestination(destinationId: String): Long = 0L
    override suspend fun save(record: HistoryRecord) {
        rows[record.id] = record
        saves += record
        flow.value = rows.toMap()
    }
    override suspend fun setOutcome(id: String, status: JobStatus, finishedAt: Long?, errorMessage: String?) {
        rows[id]?.let { save(it.copy(status = status, finishedAt = finishedAt, errorMessage = errorMessage)) }
    }
    override suspend fun markRunningAsInterrupted(): Int = 0
    override suspend fun expiredRecords(profileId: String, policy: RetentionPolicy, nowEpochMillis: Long): List<HistoryRecord> = emptyList()
    override suspend fun deleteOlderThan(profileId: String, cutoffEpochMillis: Long): Int = 0
    override suspend fun delete(id: String) {
        rows.remove(id)
    }
    override suspend fun deleteByIds(ids: List<String>): Int = 0
    override suspend fun deleteForProfile(profileId: String): Int = 0
    override suspend fun deleteAll(): Int = 0
}

internal class FakeHashes : HashRegistryRepository {
    val records = ArrayList<HashRecord>()
    override fun observeForBackup(backupId: String): Flow<List<HashRecord>> = MutableStateFlow(records.filter { it.backupId == backupId })
    override suspend fun forBackup(backupId: String): List<HashRecord> = records.filter { it.backupId == backupId }
    override suspend fun find(backupId: String, relativePath: String, algorithm: ChecksumAlgorithm): HashRecord? =
        records.firstOrNull { it.backupId == backupId && it.relativePath == relativePath && it.algorithm == algorithm }
    override suspend fun latestForPath(relativePath: String, algorithm: ChecksumAlgorithm): HashRecord? =
        records.lastOrNull { it.relativePath == relativePath && it.algorithm == algorithm }
    override suspend fun findByHash(hash: String, algorithm: ChecksumAlgorithm): List<HashRecord> =
        records.filter { it.hash == hash && it.algorithm == algorithm }
    override suspend fun countForBackup(backupId: String): Int = records.count { it.backupId == backupId }
    override suspend fun count(): Long = records.size.toLong()
    override suspend fun record(records: List<HashRecord>): List<HashRecord> {
        this.records += records
        return records
    }
    override suspend fun replaceForBackup(backupId: String, records: List<HashRecord>) {
        this.records.removeAll { it.backupId == backupId }
        this.records += records
    }
    override suspend fun deleteForBackup(backupId: String): Int = 0
    override suspend fun deleteAll(): Int = 0
}

internal class FakeVault(private val secrets: Map<String, Secret>) : CredentialVault {
    override suspend fun store(ref: String, secret: Secret) = Unit
    override suspend fun load(ref: String): Secret? = secrets[ref]
    override suspend fun delete(ref: String): Boolean = false
    override suspend fun exists(ref: String): Boolean = ref in secrets
    override suspend fun list(): List<VaultEntryInfo> = emptyList()
    override fun newRef(prefix: String): String = "$prefix-1"
}

internal class FakeStaging : StagingArea {
    val root: File = Files.createTempDirectory("dftb-engine-test").toFile()
    var free: Long? = 1L shl 40
    override fun dirFor(jobId: String): File = File(root, jobId).apply { mkdirs() }
    override fun freeBytes(): Long? = free
    override fun release(jobId: String): Boolean = File(root, jobId).deleteRecursively()
}

internal class FixedClock(var now: Long = 1_792_397_109_000L) : EngineClock {
    override fun nowMillis(): Long = now
    override fun zone(): ZoneId = ZoneOffset.UTC
}

internal class FakeDeviceInfo : DeviceInfoProvider {
    override suspend fun device(): ManifestDevice = ManifestDevice("Google", "Pixel 8", "shiba", "google/shiba/shiba:16", 36, "_b")
    override fun app(): ManifestApp = ManifestApp("ca.devdcs.backup", "0.5.0", 500L)
}

internal class FakeMessages : BackupMessages {
    override fun planning() = "planning"
    override fun preflight() = "preflight"
    override fun uploading(name: String) = "uploading $name"
    override fun verifying(name: String) = "verifying $name"
    override fun writingManifest() = "manifest"
    override fun completed(artifactCount: Int) = "completed $artifactCount"
    override fun completedWithWarnings(artifactCount: Int, warningCount: Int) = "completed $artifactCount with $warningCount"
    override fun cancelled() = "cancelled"
    override fun failure(failure: BackupFailure) = "failed ${failure.code}"
    override fun warning(warning: BackupWarning) = "warn ${warning.code}"
}

/** Counts preparations so tests can assert one key derivation per job. */
internal class FakeKeyPreparer(private val cipher: FakeCipher) : JobKeyPreparer {
    var prepared = 0
    var closed = 0
    override suspend fun prepare(key: CipherKeySpec): JobStreamKey {
        prepared++
        return object : JobStreamKey {
            override fun describe(): EncryptionHeader = EncryptionHeader("fake-xor", mapOf("kdf" to if (key is CipherKeySpec.VaultKey) "vault" else "passphrase"))
            override suspend fun encrypt(out: OutputStream): OutputStream = cipher.encrypt(out, key)
            override fun close() {
                closed++
            }
        }
    }

    /** Inverse of [FakeCipher.encrypt]: drop the "ENC\n" header, XOR the rest. */
    override suspend fun decrypt(input: InputStream, key: CipherKeySpec): InputStream {
        val bytes = input.use { it.readBytes() }
        require(bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "ENC\n") { "not fake-encrypted" }
        return ByteArrayInputStream(ByteArray(bytes.size - 4) { (bytes[it + 4].toInt() xor 0x5A).toByte() })
    }
}

/** Reversible, filesystem-safe fake: "m" + hex of the UTF-8 bytes. */
internal class HexMasker : FilenameMasker {
    override fun mask(name: String): String = "m" + name.toByteArray().joinToString("") { "%02x".format(it) }
    override fun unmask(masked: String): String? =
        masked.removePrefix("m").chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
}

internal class FakeMaskerProvider : MaskerProvider {
    var closed = false
    var opens = 0
    val received = ArrayList<Map<String, String>?>()
    override suspend fun open(key: CipherKeySpec, params: Map<String, String>?): JobMasker {
        opens++
        received += params
        return JobMasker(HexMasker(), params ?: mapOf("scheme" to "hex", "salt" to "s$opens")) { closed = true }
    }
}
