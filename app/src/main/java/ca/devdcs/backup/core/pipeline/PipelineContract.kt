package ca.devdcs.backup.core.pipeline

import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.CompressionCodec
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

/**
 * POSIX/SELinux metadata captured for an entry so a restore can reproduce ownership, mode and
 * labels exactly (app data is unusable with the wrong uid or context). All fields optional:
 * unprivileged sources simply do not know them.
 */
data class EntryMetadata(
    val modifiedAtMillis: Long? = null,
    val mode: Int? = null,
    val uid: Int? = null,
    val gid: Int? = null,
    val seLinuxContext: String? = null,
)

/**
 * One item a [BackupSource] contributes to an archive. [archivePath] is '/'-separated, relative,
 * never starts with '/' or contains '..' (the sorter decides it). Exists so archive writers, the
 * Nandroid builder and the orchestrator agree on one unit of work.
 */
sealed interface SourceItem {
    val archivePath: String
    val metadata: EntryMetadata

    data class Directory(override val archivePath: String, override val metadata: EntryMetadata = EntryMetadata()) : SourceItem

    /** A regular file of known size; [open] yields exactly [sizeBytes] bytes (caller closes). */
    data class File(
        override val archivePath: String,
        val sizeBytes: Long,
        override val metadata: EntryMetadata = EntryMetadata(),
        val open: suspend () -> InputStream,
    ) : SourceItem

    /** A symbolic link stored as a link (never followed). */
    data class Symlink(override val archivePath: String, val target: String, override val metadata: EntryMetadata = EntryMetadata()) : SourceItem

    /**
     * A raw byte stream whose size may be unknown up front (a `dd` partition image, a root `tar`
     * of an app data dir). Archive formats that need the size first (tar headers) must spool or
     * use [expectedSizeBytes] when it is exact ([sizeIsExact]).
     */
    data class Stream(
        override val archivePath: String,
        val expectedSizeBytes: Long?,
        val sizeIsExact: Boolean,
        override val metadata: EntryMetadata = EntryMetadata(),
        val open: suspend () -> InputStream,
    ) : SourceItem
}

/** What a source will contribute, for pre-flight free-space checks and progress totals. */
data class SourceEstimate(val itemCount: Long, val totalBytes: Long, val exact: Boolean)

/**
 * A producer of [SourceItem]s (partitions, apps, files). Cold flow; cancellation stops it and
 * releases any privileged helper it started. Items are emitted in archive order.
 */
interface BackupSource {
    /** Stable id used in checkpoints so a resumed job can skip items already completed. */
    val sourceId: String
    suspend fun estimate(): SourceEstimate
    fun items(): Flow<SourceItem>
}

/**
 * Writes [SourceItem]s into one archive container on [out]. Streaming formats (tar, zip) write
 * straight through; formats that need random access (7z) must stage to a temp file and copy on
 * [finish]. Not thread-safe.
 */
interface ArchiveWriter : AutoCloseable {
    val format: ArchiveFormat
    /** Append one item; for File/Stream items reads [SourceItem.open] to EOF and closes it. */
    suspend fun write(item: SourceItem)
    /** Write the trailer / central directory and flush. Must be called once on success. */
    suspend fun finish()
    /** Release resources without finishing (on failure/cancel). Idempotent; never throws. */
    override fun close()
}

/** Builds writers and codec streams. One implementation, injected. */
interface ArchiveFactory {
    fun writer(format: ArchiveFormat, out: OutputStream, compressionLevel: Int): ArchiveWriter
    /** Wrap [out] in a compressor; [CompressionCodec.NONE] returns [out] itself. Closing closes [out]. */
    fun compress(codec: CompressionCodec, level: Int, out: OutputStream): OutputStream
    fun decompress(codec: CompressionCodec, input: InputStream): InputStream
}

/** Result of digesting a byte stream with several algorithms at once. */
data class DigestResult(val bytes: Long, val hex: Map<ChecksumAlgorithm, String>)

/**
 * Multi-algorithm digests (MD5, SHA-1, SHA-256, SHA-512, BLAKE3). A tee stream lets the pipeline
 * hash the exact bytes written to the destination without a second read.
 */
interface ChecksumEngine {
    /** Pass-through stream that digests everything written; [result] is valid after close(). */
    fun tee(out: OutputStream, algorithms: Set<ChecksumAlgorithm>): DigestingOutputStream
    suspend fun digest(input: InputStream, algorithms: Set<ChecksumAlgorithm>): DigestResult
}

/** An [OutputStream] that also exposes the digest of what passed through it. */
abstract class DigestingOutputStream : OutputStream() {
    /** Available after [close]; throws IllegalStateException before. */
    abstract fun result(): DigestResult
}

/** Parameters persisted in the backup manifest so a restore can decrypt (never the key itself). */
data class EncryptionHeader(val scheme: String, val params: Map<String, String>)

/**
 * Streaming authenticated encryption (AES-256-GCM in independently authenticated chunks, so a
 * multi-GB stream needs no buffering and truncation/reordering is detected). Keys come from a
 * passphrase (KDF) or a vault-held key ref; plaintext keys never leave this component.
 */
interface StreamCipher {
    /** Encrypting wrapper around [out]; the header is written first. Closing finalizes + closes [out]. */
    suspend fun encrypt(out: OutputStream, key: CipherKeySpec): OutputStream
    /** Decrypting wrapper; throws on tamper/truncation (as IOException subclass) while reading. */
    suspend fun decrypt(input: InputStream, key: CipherKeySpec): InputStream
    /** Header that [encrypt] will write for [key] (for the manifest). */
    suspend fun describe(key: CipherKeySpec): EncryptionHeader
}

/** Where the encryption key comes from. */
sealed interface CipherKeySpec {
    /** A user passphrase; the cipher derives the key with a salted, memory-hard KDF. */
    class Passphrase(val chars: CharArray) : CipherKeySpec
    /** A random 256-bit key stored in the CredentialVault under [vaultRef]. */
    data class VaultKey(val vaultRef: String) : CipherKeySpec
}

/** Reversible, keyed obfuscation of archive/remote file names (so names don't leak contents). */
interface FilenameMasker {
    fun mask(name: String): String
    fun unmask(masked: String): String?
}
