package ca.devdcs.backup.core.storage.rclone

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.Secret
import ca.devdcs.backup.domain.security.VaultException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns DFTB's single rclone.conf.
 *
 * Why this design: the config holds OAuth tokens and (obscured, i.e. reversibly encoded) passwords,
 * so its durable copy lives only in the [CredentialVault] (ref [VAULT_REF], Keystore-encrypted).
 * rclone needs a plain file, so the text is materialized to `filesDir/rclone/rclone.conf` (0600,
 * inside the app's private 0700 dir) only while at least one rclone invocation holds a [Lease],
 * and deleted when the last lease closes. rclone rewrites that file when it refreshes an OAuth
 * token; on release the store reads it back and re-seals the new text, so refreshed (and, for
 * providers like Box, rotated) refresh tokens are never lost. A file left behind by a crash is
 * adopted the same way on the next acquire.
 *
 * rclone's own config encryption (`RCLONE_CONFIG_PASS`) was not used: it would add a second
 * password to manage and still need the vault to hold it, for no gain over the vault alone.
 */
@Singleton
class RcloneConfigStore @Inject constructor(
    @ApplicationContext context: Context,
    private val vault: CredentialVault,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** `filesDir/rclone`: config, per-run files and rclone's cache dir live below it. */
    val rcloneDir: File = File(context.filesDir, "rclone")

    /** Path rclone is pointed at with `--config` while a lease is held. */
    val configFile: File = File(rcloneDir, CONFIG_FILE_NAME)

    /** Per-invocation env/log/status files of the root runner. */
    val runDir: File = File(rcloneDir, "run")

    /** FIFOs used for root-runner streaming. */
    val fifoDir: File = File(context.cacheDir, "rclone-fifo")

    private val mutex = Mutex()
    private var leaseCount = 0
    private var materializedText: String? = null
    private var swept = false

    init {
        // Clean up after a process that died mid-run (plaintext config, env files with secrets, FIFOs).
        scope.launch(ioDispatcher) { mutex.withLock { sweepLocked() } }
    }

    /** The stored config text; `""` when none was ever saved. */
    suspend fun read(): String = mutex.withLock { readLocked() }

    /** Parse the stored config. */
    suspend fun document(): RcloneIniDocument = RcloneIni.parse(read())

    /** Validate [text] without saving it (for the raw editor's live feedback). */
    fun validate(text: String): RcloneIniDocument = RcloneIni.parse(text)

    /**
     * Validate and save [text]. The previous version is kept under [VAULT_REF_PREVIOUS] so
     * [restorePrevious] can undo a bad edit. Returns the parsed document (warnings included).
     * @throws StorageException MISCONFIGURED when the text has validation errors.
     */
    suspend fun write(text: String): RcloneIniDocument {
        val normalized = normalize(text)
        val document = RcloneIni.parse(normalized)
        if (!document.isValid) {
            val first = document.errors.first()
            throw StorageException(
                StorageException.Reason.MISCONFIGURED,
                "rclone.conf is invalid (${document.errors.size} error(s)); line ${first.line ?: "-"}: ${first.message}",
            )
        }
        mutex.withLock {
            sweepLocked()
            val previous = readLocked()
            if (previous.isNotEmpty() && previous != normalized) storeLocked(VAULT_REF_PREVIOUS, previous)
            storeLocked(VAULT_REF, normalized)
            if (leaseCount > 0) materializeLocked(normalized)
        }
        return document
    }

    /** Swap the current config with the saved previous version. False when there is none. */
    suspend fun restorePrevious(): Boolean = mutex.withLock {
        sweepLocked()
        val previous = (loadLocked(VAULT_REF_PREVIOUS) ?: return@withLock false)
        val current = readLocked()
        storeLocked(VAULT_REF, previous)
        if (current.isNotEmpty()) storeLocked(VAULT_REF_PREVIOUS, current)
        if (leaseCount > 0) materializeLocked(previous)
        true
    }

    /** Names of every remote, in file order. */
    suspend fun listRemotes(): List<String> = document().sections.map { it.name }

    /** Display view of remote [name] (secrets masked), or null when absent. */
    suspend fun remote(name: String): RcloneRemote? = document().section(name)?.let(RcloneIni::toRemote)

    /** Every remote with a type, masked for display. */
    suspend fun remotes(): List<RcloneRemote> = document().sections.mapNotNull(RcloneIni::toRemote)

    /** Add or replace one remote (e.g. from [RcloneConfigBuilder]) and save. */
    suspend fun upsertRemote(section: RcloneSection): RcloneIniDocument =
        write(RcloneIni.upsertSection(read(), section))

    /** Remove remote [name]; false when it did not exist. */
    suspend fun removeRemote(name: String): Boolean {
        val current = read()
        val updated = RcloneIni.removeSection(current, name)
        if (updated == current) return false
        write(updated)
        return true
    }

    /**
     * Materialize the config file for the duration of an rclone invocation. Callers MUST close the
     * lease; the file is removed (after token write-back) when the last lease closes.
     */
    suspend fun acquire(): Lease = mutex.withLock {
        sweepLocked()
        leaseCount++
        if (leaseCount == 1) {
            try {
                materializeLocked(readLocked())
            } catch (t: Throwable) {
                // Includes cancellation: never leave a plaintext config behind without a lease.
                leaseCount--
                withContext(NonCancellable + ioDispatcher) { configFile.delete() }
                materializedText = null
                throw t
            }
        }
        Lease(configFile.absolutePath)
    }

    /** Proof that the config file exists; [close] is idempotent and never throws. */
    inner class Lease internal constructor(val configPath: String) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            scope.launch(ioDispatcher) { release() }
        }
    }

    private suspend fun release() = mutex.withLock {
        leaseCount = (leaseCount - 1).coerceAtLeast(0)
        if (leaseCount > 0) return@withLock
        try {
            writeBackLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "rclone.conf write-back failed", e)
        } finally {
            if (configFile.exists() && !configFile.delete()) DftbLog.w(TAG, "Could not delete materialized rclone.conf")
            materializedText = null
        }
    }

    /** If rclone changed the file (token refresh), seal the new text. */
    private suspend fun writeBackLocked() {
        val written = withContext(ioDispatcher) { if (configFile.canRead()) configFile.readText() else null } ?: return
        val baseline = materializedText ?: return
        if (normalize(written) == normalize(baseline)) return
        val document = RcloneIni.parse(written)
        if (!document.isValid) {
            DftbLog.w(TAG, "Ignoring rclone-rewritten config: ${document.errors.size} validation error(s)")
            return
        }
        storeLocked(VAULT_REF, normalize(written))
        DftbLog.i(TAG, "rclone.conf updated by rclone (token refresh); re-sealed in vault")
    }

    /**
     * Once per process, before any lease or edit: adopt a config left by a process that died
     * mid-run (keeps tokens rclone refreshed), then delete it together with stale run files and
     * FIFOs. Running before the first [write] means a leftover can never revert a later user edit.
     */
    private suspend fun sweepLocked() {
        if (swept || leaseCount > 0) return
        swept = true
        try {
            adoptLeftoverLocked()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "Could not adopt leftover rclone.conf", e)
        }
        withContext(ioDispatcher) {
            if (configFile.exists() && !configFile.delete()) DftbLog.w(TAG, "Could not delete leftover rclone.conf")
            listOf(runDir, fifoDir).forEach { dir -> dir.listFiles()?.forEach { it.delete() } }
        }
    }

    /** A file present with no lease means a previous process died mid-run; keep its refreshed tokens. */
    private suspend fun adoptLeftoverLocked() {
        val leftover = withContext(ioDispatcher) { if (configFile.canRead()) configFile.readText() else null } ?: return
        val stored = readLocked()
        if (normalize(leftover) != normalize(stored) && stored.isNotEmpty()) {
            val document = RcloneIni.parse(leftover)
            val storedNames = RcloneIni.parse(stored).sections.map { it.name }.toSet()
            if (document.isValid && document.sections.map { it.name }.toSet() == storedNames) {
                storeLocked(VAULT_REF, normalize(leftover))
                DftbLog.i(TAG, "Adopted rclone.conf left by an interrupted run")
            }
        }
    }

    private suspend fun materializeLocked(text: String) = withContext(ioDispatcher) {
        try {
            RcloneFiles.ensurePrivateDir(rcloneDir)
            RcloneFiles.writePrivate(configFile, text)
            materializedText = text
        } catch (e: IOException) {
            throw StorageException(StorageException.Reason.PERMISSION, "Cannot write rclone.conf: ${e.message}", e)
        }
    }

    private suspend fun readLocked(): String = loadLocked(VAULT_REF) ?: ""

    private suspend fun loadLocked(ref: String): String? {
        val secret = try {
            vault.load(ref)
        } catch (e: VaultException) {
            throw StorageException(StorageException.Reason.MISCONFIGURED, "Credential vault cannot read the rclone config (${e.reason})", e)
        } ?: return null
        return (secret as? Secret.RcloneConfig)?.iniText
            ?: throw StorageException(StorageException.Reason.MISCONFIGURED, "Vault entry '$ref' is not an rclone config")
    }

    private suspend fun storeLocked(ref: String, text: String) {
        try {
            vault.store(ref, Secret.RcloneConfig(remoteName = ALL_REMOTES, iniText = text))
        } catch (e: VaultException) {
            throw StorageException(StorageException.Reason.MISCONFIGURED, "Credential vault cannot save the rclone config (${e.reason})", e)
        }
    }

    private fun normalize(text: String): String {
        val unix = text.replace("\r\n", "\n").trimEnd()
        return if (unix.isEmpty()) "" else unix + "\n"
    }

    companion object {
        private const val TAG = "RcloneConfig"
        const val CONFIG_FILE_NAME = "rclone.conf"

        /** Vault ref holding the whole config text. */
        const val VAULT_REF = "rclone.conf"

        /** Vault ref holding the version replaced by the last [write]. */
        const val VAULT_REF_PREVIOUS = "rclone.conf.previous"

        /** [Secret.RcloneConfig.remoteName] marker meaning "the whole file, every remote". */
        const val ALL_REMOTES = "*"
    }
}

/** Private-file helpers (0700 dirs, 0600 files written atomically) shared by the rclone runners. */
internal object RcloneFiles {
    private const val MODE_DIR = 448 // 0700
    private const val MODE_FILE = 384 // 0600

    fun ensurePrivateDir(dir: File) {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create ${dir.absolutePath}")
        chmod(dir, MODE_DIR)
    }

    /** Write [text] to a 0600 temp file in the same directory, fsync, then rename over [target]. */
    fun writePrivate(target: File, text: String) {
        val dir = target.parentFile ?: throw IOException("No parent for ${target.absolutePath}")
        ensurePrivateDir(dir)
        val tmp = File(dir, ".${target.name}.tmp")
        tmp.delete()
        if (!tmp.createNewFile()) throw IOException("Cannot create ${tmp.absolutePath}")
        chmod(tmp, MODE_FILE)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("Cannot move ${tmp.name} to ${target.name}")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private fun chmod(file: File, mode: Int) {
        try {
            Os.chmod(file.absolutePath, mode)
        } catch (e: ErrnoException) {
            throw IOException("chmod ${file.name} failed: ${e.message}", e)
        }
    }
}
