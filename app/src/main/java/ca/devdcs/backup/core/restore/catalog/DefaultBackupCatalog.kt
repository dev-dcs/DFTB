package ca.devdcs.backup.core.restore.catalog

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.ArchiveFactory
import ca.devdcs.backup.core.pipeline.ChecksumEngine
import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.archive.ArchiveEntryType
import ca.devdcs.backup.core.pipeline.archive.ArchiveReadSource
import ca.devdcs.backup.core.pipeline.archive.ArchiveReaderFactory
import ca.devdcs.backup.core.pipeline.checksum.ChecksumSidecar
import ca.devdcs.backup.core.pipeline.engine.ArtifactNaming
import ca.devdcs.backup.core.pipeline.engine.ArtifactSpecResolver
import ca.devdcs.backup.core.pipeline.engine.BackupManifest
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.core.pipeline.engine.ManifestItem
import ca.devdcs.backup.core.restore.ArtifactCheck
import ca.devdcs.backup.core.restore.BackupCatalog
import ca.devdcs.backup.core.restore.BackupSummary
import ca.devdcs.backup.core.restore.OpenedBackup
import ca.devdcs.backup.core.storage.RemoteEntry
import ca.devdcs.backup.core.storage.StorageRegistry
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.Destination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * An [OpenedBackup] produced by [DefaultBackupCatalog]. Owns a private copy of the key and the
 * job's [RestoreKeySession]; [close] wipes both.
 */
class CatalogOpenedBackup internal constructor(
    override val destination: Destination,
    override val summary: BackupSummary,
    override val manifest: BackupManifest,
    /** Every manifest file merged into [manifest], oldest run first. */
    val runs: List<ManifestRun>,
    private val ownedKey: CipherKeySpec?,
    private val keySession: RestoreKeySession?,
) : OpenedBackup {

    @Volatile private var closed = false

    /**
     * True when the newest run's manifest is partial (the job was interrupted or is still being
     * resumed): only the artifacts listed are present. Restoring/verifying those still works.
     */
    val partial: Boolean get() = manifest.partial

    internal fun requireKeySession(label: String): RestoreKeySession {
        check(!closed) { "Backup ${summary.jobDir} is closed" }
        return keySession ?: throw CatalogException(CatalogException.Reason.KEY_REQUIRED, "$label is encrypted; open the backup with its key")
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            keySession?.close()
        } finally {
            (ownedKey as? CipherKeySpec.Passphrase)?.chars?.fill('\u0000')
        }
    }
}

/**
 * Finds, opens, decodes and verifies DFTB backups on any destination. Exists as the one reader of
 * the on-disk format so restore, verify and retention cannot disagree about what a backup holds.
 *
 * Decoding undoes the backup chain in reverse: destination → stored-bytes digest tee → decrypt →
 * decompress. The digest is checked when the stored stream ends, so a corrupted artifact always
 * fails the read rather than restoring silently.
 */
@Singleton
class DefaultBackupCatalog @Inject constructor(
    private val storageRegistry: StorageRegistry,
    private val crypto: RestoreCrypto,
    private val archiveFactory: ArchiveFactory,
    private val readerFactory: ArchiveReaderFactory,
    private val checksumEngine: ChecksumEngine,
    private val clock: EngineClock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BackupCatalog, BackupItemReader {

    // --- list ----------------------------------------------------------------------------------

    override suspend fun list(destination: Destination): List<BackupSummary> = listWithState(destination).map { it.summary }

    /** A listed backup plus whether it is partial; null when the manifest is encrypted/unreadable. */
    data class ListedBackup(val summary: BackupSummary, val partial: Boolean?)

    /** [list] plus the partial flag (BackupSummary is a frozen contract, so it travels alongside). */
    suspend fun listWithState(destination: Destination): List<ListedBackup> = storageRegistry.withSession(destination) { session ->
        val folders = session.list("").filter { it.isDirectory }
        folders.mapNotNull { folder ->
            currentCoroutineContext().ensureActive()
            try {
                summarize(session, destination, StoragePathsCompat.childPath("", folder))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Skipping unreadable job folder ${folder.name}: ${e.message}")
                null
            }
        }.sortedByDescending { it.summary.createdAtMillis ?: Long.MIN_VALUE }
    }

    private suspend fun summarize(session: StorageSession, destination: Destination, jobDir: String): ListedBackup? {
        val manifests = manifestEntries(session, jobDir)
        if (manifests.isEmpty()) return null
        val primary = primaryManifest(manifests)
        val plain = manifests.filterNot { ManifestMerger.isEncryptedName(it.name) }
        if (plain.size == manifests.size) {
            val runs = plain.mapNotNull { entry ->
                try {
                    ManifestRun(entry.path, false, readPlain(session, entry.path))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DftbLog.w(TAG, "Unreadable manifest ${entry.path}: ${e.message}")
                    null
                }
            }
            if (runs.isNotEmpty()) {
                val merged = try {
                    ManifestMerger.merge(runs)
                } catch (e: CatalogException) {
                    null
                }
                if (merged != null) return ListedBackup(summaryOf(destination, jobDir, primary.path, merged, encryptedManifest = false), merged.partial)
            }
        }
        val folderName = StoragePathsCompat.name(jobDir)
        val summary = BackupSummary(
            destinationId = destination.id,
            jobDir = jobDir,
            manifestPath = primary.path,
            manifestReadable = false,
            createdAtMillis = FolderNames.timestampMillis(folderName, clock),
            profileName = FolderNames.profileName(folderName),
            totalBytes = null,
            encrypted = manifests.any { ManifestMerger.isEncryptedName(it.name) },
        )
        return ListedBackup(summary, partial = null)
    }

    private fun summaryOf(destination: Destination, jobDir: String, manifestPath: String, manifest: BackupManifest, encryptedManifest: Boolean) =
        BackupSummary(
            destinationId = destination.id,
            jobDir = jobDir,
            manifestPath = manifestPath,
            manifestReadable = true,
            createdAtMillis = manifest.createdAtMillis,
            profileName = manifest.profile.name,
            totalBytes = saturatingSum(manifest.artifacts.map { it.sizeBytes }),
            encrypted = encryptedManifest || manifest.artifacts.any { it.encryption != null || it.items.any { item -> item.encryption != null } },
        )

    private suspend fun manifestEntries(session: StorageSession, jobDir: String): List<RemoteEntry> =
        session.list(jobDir)
            .filter { !it.isDirectory && ManifestMerger.isManifestName(it.name) }
            .map { it.copy(path = StoragePathsCompat.childPath(jobDir, it)) }

    /** The canonical `dftb-manifest.json[.dftbenc]` when present, else the first by name. */
    private fun primaryManifest(entries: List<RemoteEntry>): RemoteEntry =
        entries.firstOrNull { it.name == ArtifactNaming.MANIFEST_NAME || it.name == ArtifactNaming.ENCRYPTED_MANIFEST_NAME }
            ?: entries.minBy { it.name }

    // --- open ----------------------------------------------------------------------------------

    override suspend fun open(destination: Destination, jobDir: String, key: CipherKeySpec?): OpenedBackup {
        val ownedKey = key?.let(::copyKey)
        var keySession: RestoreKeySession? = null
        try {
            keySession = ownedKey?.let { crypto.session(it) }
            val dir = StoragePathsCompat.normalize(jobDir)
            val (runs, primaryPath) = storageRegistry.withSession(destination) { session ->
                val entries = manifestEntries(session, dir)
                if (entries.isEmpty()) throw CatalogException(CatalogException.Reason.NO_MANIFEST, "No manifest in $dir")
                val runs = entries.map { entry ->
                    val encrypted = ManifestMerger.isEncryptedName(entry.name)
                    val manifest = if (encrypted) {
                        val ks = keySession ?: throw CatalogException(CatalogException.Reason.KEY_REQUIRED, "${entry.name} is encrypted")
                        readEncrypted(session, entry.path, ks)
                    } else {
                        readPlain(session, entry.path)
                    }
                    ManifestRun(entry.path, encrypted, manifest)
                }
                runs to primaryManifest(entries).path
            }
            var merged = ManifestMerger.merge(runs)
            merged.artifacts.mapNotNull { it.encryption?.scheme }.distinct().forEach { scheme ->
                if (!crypto.supports(scheme)) throw CatalogException(CatalogException.Reason.UNSUPPORTED_ENCRYPTION, "Unsupported scheme $scheme")
            }
            merged = unmaskNames(merged, ownedKey)
            val encryptedManifest = runs.any { it.encrypted }
            val summary = summaryOf(destination, dir, primaryPath, merged, encryptedManifest)
            return CatalogOpenedBackup(destination, summary, merged, ManifestMerger.chronological(runs), ownedKey, keySession)
        } catch (t: Throwable) {
            keySession?.close()
            (ownedKey as? CipherKeySpec.Passphrase)?.chars?.fill('\u0000')
            throw t
        }
    }

    /** Fills in artifact names omitted from a plaintext manifest by unmasking their stored names. */
    private suspend fun unmaskNames(manifest: BackupManifest, key: CipherKeySpec?): BackupManifest {
        val params = manifest.masking ?: return manifest
        if (key == null || manifest.artifacts.none { it.masked && it.name == null }) return manifest
        val handle = crypto.openMasker(key, params) ?: return manifest
        return handle.use { h ->
            manifest.copy(
                artifacts = manifest.artifacts.map { artifact ->
                    if (artifact.masked && artifact.name == null) artifact.copy(name = h.masker.unmask(artifact.storedName)) else artifact
                },
            )
        }
    }

    private suspend fun readPlain(session: StorageSession, path: String): BackupManifest {
        val bytes = withContext(io) { session.openRead(path, 0L).use { readBounded(it, path) } }
        return decodeManifest(bytes, path)
    }

    private suspend fun readEncrypted(session: StorageSession, path: String, keySession: RestoreKeySession): BackupManifest {
        val raw = session.openRead(path, 0L)
        val bytes = try {
            val plain = keySession.decrypt(raw)
            withContext(io) { plain.use { readBounded(it, path) } }
        } finally {
            raw.close()
        }
        return decodeManifest(bytes, path)
    }

    private fun decodeManifest(bytes: ByteArray, path: String): BackupManifest {
        val manifest = try {
            BackupManifest.decode(bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw CatalogException(CatalogException.Reason.CORRUPT_MANIFEST, "$path: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw CatalogException(CatalogException.Reason.CORRUPT_MANIFEST, "$path: ${e.message}", e)
        }
        if (manifest.schemaVersion > BackupManifest.SCHEMA_VERSION) {
            throw CatalogException(
                CatalogException.Reason.UNSUPPORTED_SCHEMA,
                "$path uses manifest schema ${manifest.schemaVersion}; this build reads up to ${BackupManifest.SCHEMA_VERSION}",
            )
        }
        return manifest
    }

    private fun readBounded(input: InputStream, label: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_MANIFEST_BYTES) {
                throw CatalogException(CatalogException.Reason.CORRUPT_MANIFEST, "$label is larger than $MAX_MANIFEST_BYTES bytes")
            }
        }
        return out.toByteArray()
    }

    // --- decode --------------------------------------------------------------------------------

    override suspend fun openDecoded(backup: OpenedBackup, artifact: ManifestArtifact): InputStream {
        if (isTree(artifact)) {
            val data = artifact.items.filter { it.storedPath != null }
            if (data.size == 1) return openDecodedItem(backup, artifact, data[0])
            throw CatalogException(CatalogException.Reason.NOT_A_SINGLE_STREAM, "${artifact.storedName} is a tree of ${data.size} files")
        }
        return openChain(
            backup = backup,
            path = artifactPath(backup, artifact),
            expected = digestsOf(artifact.digests),
            expectedBytes = artifact.sizeBytes,
            encrypted = artifact.encryption != null,
            artifact = artifact,
            label = artifact.name ?: artifact.storedName,
        )
    }

    override suspend fun openDecodedItem(backup: OpenedBackup, artifact: ManifestArtifact, item: ManifestItem): DecodedArtifactStream {
        val stored = item.storedPath ?: throw CatalogException(CatalogException.Reason.MISSING_FILE, "${item.archivePath} has no stored file")
        return openChain(
            backup = backup,
            path = itemPath(backup, artifact, stored),
            expected = digestsOf(item.storedDigests),
            expectedBytes = item.storedBytes,
            encrypted = item.encryption != null,
            artifact = artifact,
            label = item.archivePath,
        )
    }

    private suspend fun openChain(
        backup: OpenedBackup,
        path: String,
        expected: Map<ChecksumAlgorithm, String>,
        expectedBytes: Long?,
        encrypted: Boolean,
        artifact: ManifestArtifact,
        label: String,
        onStoredBytes: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): VerifiedDecodedStream {
        val keySession = if (encrypted) keySessionOf(backup, label) else null
        val session = storageRegistry.backendFor(backup.destination.type).connect(backup.destination)
        var raw: InputStream? = null
        try {
            raw = session.openRead(path, 0L)
            val verifying = DigestVerifyingInputStream(raw, checksumEngine, expected, expectedBytes, label, onStoredBytes, isCancelled)
            val decrypted = keySession?.decrypt(verifying) ?: verifying
            val decoded = withContext(io) { archiveFactory.decompress(artifact.codec, decrypted) }
            return VerifiedDecodedStream(decoded, verifying) { session.close() }
        } catch (t: Throwable) {
            try {
                raw?.close()
            } catch (e: Exception) {
                DftbLog.d(TAG, "Ignoring close failure of $path: ${e.javaClass.simpleName}")
            }
            session.close()
            throw t
        }
    }

    private fun keySessionOf(backup: OpenedBackup, label: String): RestoreKeySession =
        (backup as? CatalogOpenedBackup)?.requireKeySession(label)
            ?: throw CatalogException(CatalogException.Reason.KEY_REQUIRED, "$label is encrypted and the backup was not opened by this catalog")

    // --- verify --------------------------------------------------------------------------------

    override suspend fun verify(backup: OpenedBackup, deep: Boolean, onProgress: (Long) -> Unit): List<ArtifactCheck> {
        var done = 0L
        val progress: (Long) -> Unit = { n ->
            done += n
            onProgress(done)
        }
        val sidecars = SidecarCache()
        val checks = ArrayList<ArtifactCheck>()
        try {
            for (artifact in backup.manifest.artifacts) {
                currentCoroutineContext().ensureActive()
                checks += if (isTree(artifact)) {
                    verifyTree(backup, artifact, deep, progress, sidecars)
                } else {
                    verifySingle(backup, artifact, deep, progress, sidecars)
                }
            }
        } finally {
            sidecars.close()
        }
        return checks
    }

    private suspend fun verifySingle(
        backup: OpenedBackup,
        artifact: ManifestArtifact,
        deep: Boolean,
        progress: (Long) -> Unit,
        sidecars: SidecarCache,
    ): ArtifactCheck {
        val expected = digestsOf(artifact.digests)
        val path = artifactPath(backup, artifact)
        var stream: DecodedArtifactStream? = null
        return try {
            val stat = sidecars.session(backup).stat(path)
                ?: return ArtifactCheck(artifact, false, null, expected, emptyMap(), ArtifactProblems.format(CatalogException.Reason.MISSING_FILE, artifact.storedName))
            if (stat.sizeBytes != null && stat.sizeBytes != artifact.sizeBytes) {
                return ArtifactCheck(
                    artifact, false, stat.sizeBytes, expected, emptyMap(),
                    ArtifactProblems.format(CatalogException.Reason.SIZE_MISMATCH, "${stat.sizeBytes} != ${artifact.sizeBytes}"),
                )
            }
            val actual: Map<ChecksumAlgorithm, String>
            if (deep) {
                val chain = openChain(backup, path, expected, artifact.sizeBytes, artifact.encryption != null, artifact, artifact.storedName, progress)
                stream = chain
                walkDecoded(artifact, chain)
                actual = chain.storedActual
            } else {
                actual = storedDigest(backup, path, expected, artifact.sizeBytes, artifact.storedName, progress)
            }
            val sidecarProblem = sidecars.check(backup, artifact, artifact.storedName, actual)
            ArtifactCheck(artifact, sidecarProblem == null, artifact.sizeBytes, expected, actual, sidecarProblem)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            ArtifactCheck(artifact, false, artifact.sizeBytes, expected, emptyMap(), ArtifactProblems.of(e))
        } finally {
            stream?.close()
        }
    }

    private suspend fun verifyTree(
        backup: OpenedBackup,
        artifact: ManifestArtifact,
        deep: Boolean,
        progress: (Long) -> Unit,
        sidecars: SidecarCache,
    ): ArtifactCheck {
        var stored = 0L
        val problems = ArrayList<String>()
        for (item in artifact.items) {
            currentCoroutineContext().ensureActive()
            val storedPath = item.storedPath ?: continue
            val path = itemPath(backup, artifact, storedPath)
            val expected = digestsOf(item.storedDigests)
            try {
                val actual = if (deep) {
                    val decoded = openChain(backup, path, expected, item.storedBytes, item.encryption != null, artifact, item.archivePath, progress)
                    decoded.use { d ->
                        val sourceDigests = digestsOf(item.digests)
                        if (sourceDigests.isNotEmpty()) {
                            val got = withContext(io) { checksumEngine.digest(d, sourceDigests.keys) }
                            compareItem(item.archivePath, sourceDigests, got.hex)
                        }
                        d.finishAndVerify()
                    }
                    decoded.storedActual
                } else {
                    storedDigest(backup, path, expected, item.storedBytes, item.archivePath, progress)
                }
                stored += item.storedBytes ?: 0L
                val sidecarName = if (artifact.treeRoot.isNullOrEmpty()) "../$storedPath" else ArtifactNaming.join(artifact.storedName, storedPath)
                sidecars.check(backup, artifact, sidecarName, actual)?.let { problems += it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                problems += ArtifactProblems.of(e)
            }
        }
        return ArtifactCheck(
            artifact = artifact,
            ok = problems.isEmpty(),
            storedBytes = stored,
            expected = emptyMap(),
            actual = emptyMap(),
            problem = problems.firstOrNull()?.let { first -> if (problems.size > 1) "$first (+${problems.size - 1})" else first },
        )
    }

    /** Decodes and walks an archive (checking per-item digests) or reads a raw payload to its end. */
    private suspend fun walkDecoded(artifact: ManifestArtifact, decoded: DecodedArtifactStream) = withContext(io) {
        val readerFormat = readerFormatOf(artifact.format)
        if (readerFormat == null) {
            val item = artifact.items.singleOrNull { it.type == ManifestItem.ItemType.FILE || it.type == ManifestItem.ItemType.STREAM }
            val expected = digestsOf(item?.digests)
            if (expected.isNotEmpty() && item != null) {
                compareItem(item.archivePath, expected, checksumEngine.digest(decoded, expected.keys).hex)
            }
            decoded.finishAndVerify()
            return@withContext
        }
        val byPath = artifact.items.associateBy { it.archivePath }
        val reader = readerFactory.reader(readerFormat, ArchiveReadSource.Streamed { decoded })
        try {
            for (entry in reader.entries()) {
                currentCoroutineContext().ensureActive()
                if (entry.info.type != ArchiveEntryType.FILE) continue
                val expected = digestsOf(byPath[entry.info.path]?.digests)
                val got = checksumEngine.digest(entry.stream, expected.keys.ifEmpty { setOf(ChecksumAlgorithm.SHA256) })
                if (expected.isNotEmpty()) compareItem(entry.info.path, expected, got.hex)
            }
            decoded.finishAndVerify()
        } finally {
            reader.close()
        }
    }

    private fun compareItem(path: String, expected: Map<ChecksumAlgorithm, String>, actual: Map<ChecksumAlgorithm, String>) {
        for ((algorithm, hex) in expected) {
            val got = actual[algorithm] ?: continue
            if (!ChecksumSidecar.matches(hex, got)) {
                throw CatalogException(CatalogException.Reason.ITEM_DIGEST_MISMATCH, "$path: ${algorithm.storageKey} mismatch")
            }
        }
    }

    /** Digests of the stored bytes only (quick verify; no key needed). */
    private suspend fun storedDigest(
        backup: OpenedBackup,
        path: String,
        expected: Map<ChecksumAlgorithm, String>,
        expectedBytes: Long?,
        label: String,
        progress: (Long) -> Unit,
    ): Map<ChecksumAlgorithm, String> = storageRegistry.withSession(backup.destination) { session ->
        val raw = session.openRead(path, 0L)
        withContext(io) {
            DigestVerifyingInputStream(raw, checksumEngine, expected, expectedBytes, label, progress).use { input ->
                input.drainToEof()
                input.actual
            }
        }
    }

    /** Lazily opened session + parsed sidecars, shared by one verify run. */
    private inner class SidecarCache : AutoCloseable {
        private var session: StorageSession? = null
        private val parsed = HashMap<String, ChecksumSidecar.ParseResult?>()

        suspend fun session(backup: OpenedBackup): StorageSession =
            session ?: storageRegistry.backendFor(backup.destination.type).connect(backup.destination).also { session = it }

        /** Problem text when the sidecar disagrees with [actual]; null when it matches. */
        suspend fun check(backup: OpenedBackup, artifact: ManifestArtifact, entryName: String, actual: Map<ChecksumAlgorithm, String>): String? {
            val sidecarPath = ArtifactNaming.join(backup.summary.jobDir, artifact.checksumFile)
            if (!parsed.containsKey(sidecarPath)) {
                val s = session(backup)
                parsed[sidecarPath] = if (s.stat(sidecarPath) == null) {
                    null
                } else {
                    withContext(io) { s.openRead(sidecarPath, 0L).use { ChecksumSidecar.read(it) } }
                }
            }
            val result = parsed[sidecarPath]
                ?: return ArtifactProblems.format(CatalogException.Reason.SIDECAR_MISMATCH, "${artifact.checksumFile} missing")
            val entry = result.entries.firstOrNull { it.fileName == entryName }
                ?: return ArtifactProblems.format(CatalogException.Reason.SIDECAR_MISMATCH, "no line for $entryName")
            val algorithm = entry.algorithm
                ?: ChecksumSidecar.algorithmForSidecarName(artifact.checksumFile)
                ?: ChecksumSidecar.algorithmForHexLength(entry.hex.length)
                ?: return ArtifactProblems.format(CatalogException.Reason.SIDECAR_MISMATCH, "unknown algorithm")
            val got = actual[algorithm] ?: return null // the manifest digest was checked; sidecar uses another algorithm
            return if (ChecksumSidecar.matches(entry.hex, got)) null else ArtifactProblems.format(CatalogException.Reason.SIDECAR_MISMATCH, entryName)
        }

        override fun close() {
            session?.close()
            session = null
        }
    }

    // --- paths ---------------------------------------------------------------------------------

    private fun artifactPath(backup: OpenedBackup, artifact: ManifestArtifact): String =
        ArtifactNaming.join(backup.summary.jobDir, artifact.storedName)

    private fun itemPath(backup: OpenedBackup, artifact: ManifestArtifact, storedPath: String): String {
        val root = artifact.treeRoot ?: ArtifactNaming.join(backup.summary.jobDir, artifact.storedName)
        return ArtifactNaming.join(root, storedPath)
    }

    private fun copyKey(key: CipherKeySpec): CipherKeySpec = when (key) {
        is CipherKeySpec.Passphrase -> CipherKeySpec.Passphrase(key.chars.copyOf())
        is CipherKeySpec.VaultKey -> key
    }

    companion object {
        private const val TAG = "BackupCatalog"
        /** Manifests of a million-file tree stay well below this; anything larger is hostile. */
        const val MAX_MANIFEST_BYTES = 256L * 1024 * 1024

        fun isTree(artifact: ManifestArtifact): Boolean = artifact.layout.lowercase(Locale.ROOT) == "file_tree"

        /**
         * Reader format for the *decoded* stream: tar.* artifacts were already decompressed by the
         * artifact codec, so they read as plain TAR; null for raw payloads (RAW_COPY/DD_IMAGE).
         */
        fun readerFormatOf(format: ArchiveFormat): ArchiveFormat? = when {
            ArtifactSpecResolver.isTarFamily(format) -> ArchiveFormat.TAR
            format == ArchiveFormat.ZIP || format == ArchiveFormat.SEVEN_ZIP -> format
            else -> null
        }

        internal fun saturatingSum(values: List<Long>): Long {
            var total = 0L
            for (value in values) {
                val v = value.coerceAtLeast(0L)
                total = if (total > Long.MAX_VALUE - v) Long.MAX_VALUE else total + v
            }
            return total
        }
    }
}

/** Path helpers over [ca.devdcs.backup.core.storage.StoragePaths] for listing results. */
internal object StoragePathsCompat {
    fun normalize(path: String): String = ca.devdcs.backup.core.storage.StoragePaths.normalize(path)

    fun name(path: String): String = ca.devdcs.backup.core.storage.StoragePaths.name(path)

    /** Session path of a listed child: backends report either a full path or just a name. */
    fun childPath(parent: String, entry: RemoteEntry): String = ca.devdcs.backup.core.storage.StoragePaths.join(parent, entry.name)
}

/** Parses `<profile>_<yyyyMMdd-HHmmss>` job folder names when the manifest cannot be read. */
internal object FolderNames {
    private val PATTERN = Regex("^(.*)_(\\d{8}-\\d{6})$")
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern(ArtifactNaming.TIMESTAMP_PATTERN, Locale.ROOT)

    fun profileName(folder: String): String? = PATTERN.matchEntire(folder)?.groupValues?.get(1)?.ifEmpty { null }

    fun timestampMillis(folder: String, clock: EngineClock): Long? {
        val stamp = PATTERN.matchEntire(folder)?.groupValues?.get(2) ?: return null
        return try {
            LocalDateTime.parse(stamp, FORMAT).atZone(clock.zone()).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
