package ca.devdcs.backup.core.restore

import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.engine.BackupManifest
import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.Destination
import java.io.InputStream

/** A backup job found on a destination (one `dftb-manifest.json[.dftbenc]` job folder). */
data class BackupSummary(
    val destinationId: String,
    /** Job folder path relative to the destination base. */
    val jobDir: String,
    val manifestPath: String,
    /** False when the manifest is encrypted and no key was supplied (only folder-level info known). */
    val manifestReadable: Boolean,
    val createdAtMillis: Long?,
    val profileName: String?,
    val totalBytes: Long?,
    val encrypted: Boolean,
)

/**
 * A backup whose manifest has been read (and decrypted / merged across resumed runs). Holds the
 * prepared key material for the job's artifacts; [close] wipes it. Exists so restore, verify and
 * retention all read a backup the same way.
 */
interface OpenedBackup : AutoCloseable {
    val destination: Destination
    val summary: BackupSummary
    /** Merged manifest (all runs of a resumed job). */
    val manifest: BackupManifest
    override fun close()
}

/** Outcome of comparing stored bytes with the manifest / sidecar digests. */
data class ArtifactCheck(
    val artifact: ManifestArtifact,
    val ok: Boolean,
    val storedBytes: Long?,
    val expected: Map<ChecksumAlgorithm, String>,
    val actual: Map<ChecksumAlgorithm, String>,
    /** Localizable failure (missing file, size mismatch, digest mismatch, decrypt integrity, …). */
    val problem: String? = null,
)

/**
 * Lists, opens and reads backups on destinations. The decoded artifact stream undoes the backup
 * chain in reverse (destination → digest check → decrypt → decompress) and throws an IOException
 * at EOF if the stored-bytes digest does not match the manifest, so a corrupted artifact can never
 * be restored silently.
 */
interface BackupCatalog {
    suspend fun list(destination: Destination): List<BackupSummary>

    /** [key] is required for encrypted backups; a wrong key fails with a CipherIntegrityException. */
    suspend fun open(destination: Destination, jobDir: String, key: CipherKeySpec?): OpenedBackup

    /**
     * Plaintext (decrypted + decompressed) bytes of [artifact]: an archive stream (tar/zip/7z) for
     * archive formats, or the raw payload for RAW_COPY/DD_IMAGE. Caller closes.
     */
    suspend fun openDecoded(backup: OpenedBackup, artifact: ManifestArtifact): InputStream

    /** Quick verify = stored-bytes digests only; deep = also decode and walk every entry. */
    suspend fun verify(backup: OpenedBackup, deep: Boolean, onProgress: (Long) -> Unit = {}): List<ArtifactCheck>
}

/** How to treat an existing file/app/partition at the restore target. */
enum class ConflictPolicy { SKIP, OVERWRITE, KEEP_BOTH }

/** Result of restoring one unit (an app, a partition, a file tree). */
data class RestoreUnitResult(
    val unitId: String,
    val ok: Boolean,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)
