package ca.devdcs.backup.core.restore.catalog

import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.FilenameMasker
import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.core.pipeline.archive.DefaultArchiveFactory
import ca.devdcs.backup.core.pipeline.engine.ArtifactNaming
import ca.devdcs.backup.core.pipeline.engine.BackupManifest
import ca.devdcs.backup.core.pipeline.engine.ManifestApp
import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.core.pipeline.engine.ManifestDevice
import ca.devdcs.backup.core.pipeline.engine.ManifestEncryption
import ca.devdcs.backup.core.pipeline.engine.ManifestItem
import ca.devdcs.backup.core.pipeline.engine.ManifestMetadata
import ca.devdcs.backup.core.pipeline.engine.ManifestProfile
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
import ca.devdcs.backup.domain.model.PathTarget
import ca.devdcs.backup.domain.model.TargetSelection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

/** In-memory destination with real directory listing (direct children only). */
internal class TreeStore {
    val files = LinkedHashMap<String, ByteArray>()

    fun put(path: String, bytes: ByteArray) {
        files[path] = bytes
    }

    fun put(path: String, text: String) = put(path, text.toByteArray())
}

internal class TreeSession(override val destination: Destination, private val store: TreeStore) : StorageSession {
    override suspend fun spaceInfo(): SpaceInfo? = null

    override suspend fun list(path: String): List<RemoteEntry> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val children = LinkedHashMap<String, RemoteEntry>()
        for ((file, bytes) in store.files) {
            if (!file.startsWith(prefix)) continue
            val rest = file.removePrefix(prefix)
            val name = rest.substringBefore('/')
            val isDir = rest.contains('/')
            children.putIfAbsent(name, RemoteEntry(prefix + name, name, isDir, if (isDir) null else bytes.size.toLong(), null))
        }
        return children.values.toList()
    }

    override suspend fun stat(path: String): RemoteEntry? {
        store.files[path]?.let { return RemoteEntry(path, path.substringAfterLast('/'), false, it.size.toLong(), null) }
        return if (store.files.keys.any { it.startsWith("$path/") }) RemoteEntry(path, path.substringAfterLast('/'), true, null, null) else null
    }

    override suspend fun mkdirs(path: String) = Unit
    override suspend fun openWrite(path: String, append: Boolean): OutputStream = throw StorageException(StorageException.Reason.UNSUPPORTED, "read-only")

    override suspend fun openRead(path: String, offset: Long): InputStream {
        val data = store.files[path] ?: throw StorageException(StorageException.Reason.NOT_FOUND, "Not found: $path")
        return ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt())
    }

    override suspend fun delete(path: String) {
        store.files.remove(path)
    }

    override suspend fun rename(from: String, to: String) {
        store.files[to] = store.files.remove(from) ?: throw StorageException(StorageException.Reason.NOT_FOUND, from)
    }

    override fun close() = Unit
}

internal class TreeRegistry(private val store: TreeStore) : StorageRegistry {
    private val backend = object : StorageBackend {
        override val types: Set<DestinationType> = DestinationType.entries.toSet()
        override suspend fun connect(destination: Destination): StorageSession = TreeSession(destination, store)
    }

    override fun backendFor(type: DestinationType): StorageBackend = backend

    override suspend fun <T> withSession(destination: Destination, block: suspend (StorageSession) -> T): T =
        backend.connect(destination).use { block(it) }
}

/** "Encrypts" as `XOR1` + bytes xor 0x5A; the key must be the passphrase "secret". */
internal class XorRestoreCrypto : RestoreCrypto {
    var sessions = 0
    var closed = 0

    override fun supports(scheme: String): Boolean = scheme == SCHEME

    override fun session(key: CipherKeySpec): RestoreKeySession {
        sessions++
        val good = key is CipherKeySpec.Passphrase && String(key.chars) == "secret"
        return object : RestoreKeySession {
            override suspend fun decrypt(input: InputStream): InputStream {
                val header = ByteArray(4)
                var n = 0
                while (n < 4) {
                    val r = input.read(header, n, 4 - n)
                    if (r < 0) throw IOException("short header")
                    n += r
                }
                if (String(header) != "XOR1") throw IOException("bad header")
                if (!good) throw ca.devdcs.backup.core.pipeline.crypto.CipherIntegrityException(
                    ca.devdcs.backup.core.pipeline.crypto.CipherIntegrityException.Reason.WRONG_KEY, "wrong key",
                )
                return object : FilterInputStream(input) {
                    override fun read(): Int = super.read().let { if (it < 0) it else it xor 0x5A }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val r = super.read(b, off, len)
                        for (i in off until off + maxOf(r, 0)) b[i] = (b[i].toInt() xor 0x5A).toByte()
                        return r
                    }
                }
            }

            override fun close() {
                closed++
            }
        }
    }

    override suspend fun openMasker(key: CipherKeySpec, params: Map<String, String>): MaskerHandle? =
        if (params["scheme"] == "hex") MaskerHandle(HexNames, onClose = {}) else null

    companion object {
        const val SCHEME = "fake-xor"

        fun encrypt(plain: ByteArray): ByteArray = "XOR1".toByteArray() + ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
    }
}

/** "m" + hex of the UTF-8 bytes. */
internal object HexNames : FilenameMasker {
    override fun mask(name: String): String = "m" + name.toByteArray().joinToString("") { "%02x".format(it) }
    override fun unmask(masked: String): String? =
        if (!masked.startsWith("m")) null else masked.drop(1).chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
}

internal object RestoreFixtures {
    val destination = Destination(id = "d1", name = "Dest", type = DestinationType.LOCAL_INTERNAL, createdAt = 1L, updatedAt = 1L)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun archiveFactory(io: CoroutineDispatcher): DefaultArchiveFactory {
        val staging = Files.createTempDirectory("dftb-restore-staging").toFile()
        return DefaultArchiveFactory({ staging }, io)
    }

    /** A plain tar of [items] written by DFTB's own writer. */
    fun tar(factory: DefaultArchiveFactory, items: List<SourceItem>): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = factory.writer(ArchiveFormat.TAR, out, 0)
        runBlocking {
            items.forEach { writer.write(it) }
            writer.finish()
        }
        writer.close()
        return out.toByteArray()
    }

    fun file(path: String, content: String, mtime: Long = 1_700_000_000_000L): SourceItem.File {
        val bytes = content.toByteArray()
        return SourceItem.File(path, bytes.size.toLong(), ca.devdcs.backup.core.pipeline.EntryMetadata(modifiedAtMillis = mtime)) { ByteArrayInputStream(bytes) }
    }

    fun profile(paths: List<String> = listOf("/storage/emulated/0/DCIM"), id: String = "p1"): ManifestProfile = ManifestProfile.of(
        BackupProfile(id = id, name = "Phone", targets = TargetSelection(paths = paths.map { PathTarget(it) }), destinationId = "d1", createdAt = 1L, updatedAt = 1L),
        DestinationType.LOCAL_INTERNAL,
    )

    fun manifest(
        artifacts: List<ManifestArtifact>,
        created: Long = 1_000L,
        finished: Long = 2_000L,
        profile: ManifestProfile = profile(),
        masking: Map<String, String>? = null,
        jobId: String = "job-1",
    ) = BackupManifest(
        jobId = jobId,
        createdAtMillis = created,
        finishedAtMillis = finished,
        app = ManifestApp("ca.devdcs.backup", "0.7.0", 700L),
        device = ManifestDevice("Google", "Pixel 8", "shiba", "google/shiba/shiba:16", 36, "_b"),
        profile = profile,
        checksumAlgorithm = ChecksumAlgorithm.SHA256,
        artifacts = artifacts,
        masking = masking,
    )

    /** A tar ARCHIVE artifact over [stored] bytes with per-item digests from [contents]. */
    fun tarArtifact(
        sourceId: String,
        storedName: String,
        stored: ByteArray,
        contents: Map<String, String>,
        encrypted: Boolean = false,
        name: String? = storedName,
        digestOverride: String? = null,
        kind: String = "files",
    ) = ManifestArtifact(
        sourceId = sourceId,
        kind = kind,
        name = name,
        storedName = storedName,
        masked = false,
        layout = "archive",
        format = ArchiveFormat.TAR,
        codec = CompressionCodec.NONE,
        compressionLevel = 0,
        sizeBytes = stored.size.toLong(),
        sourceBytes = contents.values.sumOf { it.length.toLong() },
        digests = mapOf("sha256" to (digestOverride ?: sha256(stored))),
        encryption = if (encrypted) ManifestEncryption(XorRestoreCrypto.SCHEME, emptyMap()) else null,
        checksumFile = ArtifactNaming.checksumSidecarName(storedName, ChecksumAlgorithm.SHA256),
        items = contents.map { (path, text) ->
            ManifestItem(
                archivePath = path,
                type = ManifestItem.ItemType.FILE,
                sizeBytes = text.length.toLong(),
                metadata = ManifestMetadata(modifiedAtMillis = 1_700_000_000_000L),
                digests = mapOf("sha256" to sha256(text.toByteArray())),
            )
        },
    )

    fun sidecar(storedName: String, stored: ByteArray): String = "${sha256(stored)}  $storedName\n"
}
