package ca.devdcs.backup.data.security

import android.content.Context
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.Secret
import ca.devdcs.backup.domain.security.SecretKind
import ca.devdcs.backup.domain.security.VaultEntryInfo
import ca.devdcs.backup.domain.security.VaultException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CredentialVault] that keeps one encrypted file per ref under `filesDir/vault/`.
 *
 * Why files and not a database: each entry is independently sealed by [VaultCipher] with the ref
 * and its own cleartext header as additional authenticated data, so a blob cannot be swapped
 * between refs, its header cannot be edited undetected, a partially written file is rejected by
 * its tag, and deleting a secret is a plain unlink with no row residue in a WAL. Writes go to
 * `<name>.tmp`, are fsync'ed, then atomically renamed over the target, after which the directory
 * itself is fsync'ed so the new entry survives power loss.
 *
 * Device-bound by design: the master key never leaves the Android Keystore, so a copy of this
 * directory (adb backup, cloud app-data backup, a cloned image) is undecryptable anywhere else.
 * DFTB's own "export settings" feature must therefore re-prompt for credentials instead of
 * carrying these files along.
 *
 * Plaintext byte arrays are zeroed after use. JSON `String`s produced in between are immutable
 * and collected by the GC; that residual exposure is accepted, the secret-free header design
 * and short lifetimes keep it minimal.
 */
@Singleton
class FileCredentialVault(
    private val vaultDir: File,
    private val cipher: VaultCipher,
    private val ioDispatcher: CoroutineDispatcher,
) : CredentialVault {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        cipher: VaultCipher,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(File(context.filesDir, VAULT_DIR_NAME), cipher, ioDispatcher)

    private val mutex = Mutex()

    override suspend fun store(ref: String, secret: Secret) = withContext(ioDispatcher) {
        val target = fileFor(ref)
        mutex.withLock {
            ensureDirectory()
            guardAgainstLostKey()
            val now = System.currentTimeMillis()
            val createdAt = existingCreatedAt(target) ?: now
            val plaintext = SecretJson.encodeToBytes(secret)
            val encoded = try {
                val sealed = cipher.encrypt(plaintext, aad(ref, secret.kind, createdAt, now))
                VaultBlobCodec.encode(
                    VaultBlob(
                        kind = secret.kind,
                        createdAt = createdAt,
                        updatedAt = now,
                        iv = sealed.iv,
                        ciphertext = sealed.ciphertext,
                    ),
                )
            } finally {
                plaintext.fill(0)
            }
            writeAtomically(target, encoded)
            DftbLog.d(TAG, "Stored ${secret.kind} secret (${encoded.size} bytes on disk)")
        }
    }

    override suspend fun load(ref: String): Secret? = withContext(ioDispatcher) {
        val file = fileFor(ref)
        mutex.withLock {
            if (!file.isFile) return@withLock null
            guardAgainstLostKey()
            val blob = VaultBlobCodec.decode(readFully(file))
            val plaintext = cipher.decrypt(
                EncryptedBlob(blob.iv, blob.ciphertext),
                aad(ref, blob.kind, blob.createdAt, blob.updatedAt),
            )
            try {
                SecretJson.decodeFromBytes(plaintext)
            } finally {
                plaintext.fill(0)
            }
        }
    }

    override suspend fun delete(ref: String): Boolean = withContext(ioDispatcher) {
        val file = fileFor(ref)
        mutex.withLock {
            File(file.path + VaultRefCodec.TEMP_SUFFIX).delete()
            val existed = file.isFile
            if (existed && !file.delete()) {
                throw VaultException("Could not delete vault entry ${file.name}", reason = VaultException.Reason.IO)
            }
            if (existed) DftbLog.d(TAG, "Deleted vault entry")
            existed
        }
    }

    override suspend fun exists(ref: String): Boolean = withContext(ioDispatcher) {
        val file = fileFor(ref)
        mutex.withLock { file.isFile }
    }

    override suspend fun list(): List<VaultEntryInfo> = withContext(ioDispatcher) {
        mutex.withLock {
            val files = vaultDir.listFiles() ?: return@withLock emptyList<VaultEntryInfo>()
            files.mapNotNull { file ->
                if (!file.isFile) return@mapNotNull null
                val ref = VaultRefCodec.fromFileName(file.name) ?: return@mapNotNull null
                try {
                    val header = VaultBlobCodec.decodeHeader(readPrefix(file, VaultBlobCodec.HEADER_SIZE))
                    VaultEntryInfo(
                        ref = ref,
                        kind = header.kind,
                        createdAt = header.createdAt,
                        updatedAt = header.updatedAt,
                        sizeBytes = file.length(),
                    )
                } catch (e: VaultException) {
                    DftbLog.w(TAG, "Skipping unreadable vault entry ${file.name}", e)
                    null
                }
            }.sortedBy { it.ref }
        }
    }

    override fun newRef(prefix: String): String {
        require(prefix.isNotBlank()) { "ref prefix must not be blank" }
        return "$prefix-${UUID.randomUUID()}"
    }

    // --- internals -------------------------------------------------------------------------------

    private fun fileFor(ref: String): File = File(vaultDir, VaultRefCodec.toFileName(ref))

    /**
     * Additional authenticated data for one entry: the ref followed by the cleartext header fields
     * (version, kind ordinal, timestamps) in the file's big-endian layout. Binding the header means
     * neither a swap between refs nor an edit of the on-disk header survives the GCM tag check on
     * the next load. Encrypt and decrypt must derive this from the same values, so decrypt feeds the
     * header read back off disk.
     */
    private fun aad(ref: String, kind: SecretKind, createdAt: Long, updatedAt: Long): ByteArray {
        val refBytes = ref.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(refBytes.size + AAD_HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(refBytes)
            .putShort(VaultBlobCodec.VERSION.toShort())
            .put(kind.ordinal.toByte())
            .putLong(createdAt)
            .putLong(updatedAt)
            .array()
    }

    /**
     * Refuses to proceed when the master key alias is gone but encrypted entries still exist on
     * disk. Without this, the first [store]/[load] would trigger [VaultCipher] to mint a brand-new
     * key, after which every existing entry would fail its GCM tag check and surface as a generic
     * [VaultException.Reason.INTEGRITY_FAILURE] forever. A genuine first run (no key and no entries)
     * is left untouched so the key can be generated normally.
     */
    private fun guardAgainstLostKey() {
        if (cipher.masterKeyExists()) return
        val hasEntries = vaultDir.listFiles()
            ?.any { it.isFile && it.name.endsWith(VaultRefCodec.FILE_EXTENSION) }
            ?: false
        if (hasEntries) {
            throw VaultException(
                "The vault master key alias is gone but encrypted entries remain on disk; they can never be decrypted on this device",
                reason = VaultException.Reason.KEY_LOST,
            )
        }
    }

    private fun ensureDirectory() {
        if (vaultDir.isDirectory) return
        if (!vaultDir.mkdirs() && !vaultDir.isDirectory) {
            throw VaultException("Cannot create vault directory ${vaultDir.path}", reason = VaultException.Reason.IO)
        }
    }

    /** Creation time of the entry currently on disk, or null when absent or unreadable (then treated as new). */
    private fun existingCreatedAt(file: File): Long? {
        if (!file.isFile) return null
        return try {
            VaultBlobCodec.decodeHeader(readPrefix(file, VaultBlobCodec.HEADER_SIZE)).createdAt
        } catch (e: VaultException) {
            DftbLog.w(TAG, "Existing entry ${file.name} has an unreadable header; it will be overwritten", e)
            null
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.path + VaultRefCodec.TEMP_SUFFIX)
        try {
            FileOutputStream(temp, false).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(target.parentFile)
        } catch (e: IOException) {
            temp.delete()
            throw VaultException("Failed to write vault entry ${target.name}", e, VaultException.Reason.IO)
        } catch (e: SecurityException) {
            temp.delete()
            throw VaultException("Filesystem denied writing vault entry ${target.name}", e, VaultException.Reason.IO)
        }
    }

    /**
     * Flushes the vault directory's own metadata after a rename so a freshly created entry's
     * directory record survives power loss, not just the renamed file's contents. Best-effort: the
     * entry is already atomically in place, so a platform that refuses to open a directory for
     * fsync only loses the cross-power-loss guarantee rather than failing the write.
     */
    private fun syncDirectory(dir: File?) {
        if (dir == null) return
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            DftbLog.w(TAG, "Could not fsync vault directory ${dir.name}; entry is written but its directory record may not survive power loss", e)
        }
    }

    private fun readFully(file: File): ByteArray = try {
        FileInputStream(file).use { it.readBytes() }
    } catch (e: IOException) {
        throw VaultException("Failed to read vault entry ${file.name}", e, VaultException.Reason.IO)
    }

    private fun readPrefix(file: File, length: Int): ByteArray = try {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n < 0) break
                read += n
            }
            if (read == length) buffer else buffer.copyOf(read)
        }
    } catch (e: IOException) {
        throw VaultException("Failed to read vault entry ${file.name}", e, VaultException.Reason.IO)
    }

    companion object {
        const val VAULT_DIR_NAME = "vault"
        private const val TAG = "Vault"

        /** Cleartext header bytes bound into the AAD: u16 version, u8 kind, i64 createdAt, i64 updatedAt. */
        private const val AAD_HEADER_SIZE = 2 + 1 + 8 + 8
    }
}
