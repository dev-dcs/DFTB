package ca.devdcs.backup.core.restore.catalog

import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.core.pipeline.engine.ManifestItem
import ca.devdcs.backup.core.restore.OpenedBackup
import java.io.IOException
import java.io.InputStream

/**
 * Why the catalog refused to list, open or decode a backup. An [IOException] so it aborts copy
 * loops like any read failure; [reason] lets the UI explain the problem without parsing text.
 */
class CatalogException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    enum class Reason {
        /** The job folder holds no `dftb-manifest*.json[.dftbenc]`. */
        NO_MANIFEST,
        /** The manifest or an artifact is encrypted and no key was supplied. */
        KEY_REQUIRED,
        /** Manifest JSON could not be parsed. */
        CORRUPT_MANIFEST,
        /** Written by a newer DFTB manifest schema than this build understands. */
        UNSUPPORTED_SCHEMA,
        /** Manifests of resumed runs disagree (profile, encryption or masking parameters). */
        INCONSISTENT_RUNS,
        /** An artifact uses an encryption scheme this build cannot decrypt. */
        UNSUPPORTED_ENCRYPTION,
        /** A stored file is missing at the destination. */
        MISSING_FILE,
        /** Stored byte count differs from the manifest. */
        SIZE_MISMATCH,
        /** Stored-bytes digest differs from the manifest. */
        DIGEST_MISMATCH,
        /** A decoded item's digest differs from the manifest (deep verify / restore). */
        ITEM_DIGEST_MISMATCH,
        /** The checksum sidecar is missing, unreadable or disagrees with the stored bytes. */
        SIDECAR_MISMATCH,
        /** A FILE_TREE artifact holds several streams; read its items one by one. */
        NOT_A_SINGLE_STREAM,
    }
}

/**
 * The plaintext stream returned by `openDecoded`. Reading it to EOF verifies the stored bytes;
 * [finishAndVerify] does the same for consumers (tar readers) that stop before EOF. Exists so a
 * restore can prove integrity even when the archive format ends before the physical stream.
 */
abstract class DecodedArtifactStream : InputStream() {
    /** Drain the stored bytes to their end and throw [CatalogException] on mismatch. Idempotent. */
    abstract fun finishAndVerify()
}

/**
 * Per-item decoding for FILE_TREE artifacts (one stored file per item), which the single-stream
 * [ca.devdcs.backup.core.restore.BackupCatalog.openDecoded] cannot express.
 */
interface BackupItemReader {
    /** Decrypted + decompressed bytes of [item] (a FILE/STREAM item with a storedPath). Caller closes. */
    suspend fun openDecodedItem(backup: OpenedBackup, artifact: ManifestArtifact, item: ManifestItem): DecodedArtifactStream
}

/**
 * Stable machine codes put into [ca.devdcs.backup.core.restore.ArtifactCheck.problem] as
 * `CODE` or `CODE|detail`, so the UI localizes the code and may show the technical detail.
 */
object ArtifactProblems {
    const val SEPARATOR = "|"

    fun format(reason: CatalogException.Reason, detail: String?): String =
        if (detail.isNullOrBlank()) reason.name else reason.name + SEPARATOR + detail

    /** Inverse of [format]; null reason when the code is unknown (foreign text). */
    fun parse(problem: String): Pair<CatalogException.Reason?, String?> {
        val code = problem.substringBefore(SEPARATOR)
        val detail = problem.substringAfter(SEPARATOR, "").ifEmpty { null }
        return CatalogException.Reason.entries.firstOrNull { it.name == code } to detail
    }

    /** Problem text for an arbitrary failure (wrong key, I/O). */
    fun of(error: Throwable): String = when (error) {
        is CatalogException -> format(error.reason, error.message)
        is ca.devdcs.backup.core.pipeline.crypto.CipherIntegrityException -> "CIPHER_" + error.reason.name + SEPARATOR + error.message
        else -> "IO" + SEPARATOR + (error.message ?: error.javaClass.simpleName)
    }
}
