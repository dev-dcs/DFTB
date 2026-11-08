package ca.devdcs.backup.core.storage.ssh

import android.content.Context
import ca.devdcs.backup.core.logging.DftbLog
import dagger.hilt.android.qualifiers.ApplicationContext
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** One trusted host key: which server (`host:port`), which key algorithm, which SHA-256 fingerprint. */
data class KnownHostEntry(val hostId: String, val keyType: String, val fingerprint: String)

/**
 * Pure (de)serialisation of DFTB's known-hosts file plus fingerprint maths. Separate from
 * [SshKnownHosts] so the format that guards against man-in-the-middle attacks is unit-tested.
 * Format: one `host:port keytype SHA256:base64` per line; `#` comments and blank lines ignored.
 */
internal object KnownHostsFormat {

    /** Stable identity of a server; IPv6 literals are bracketed so the port stays unambiguous. */
    fun hostId(host: String, port: Int): String {
        val h = host.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
        return if (':' in h) "[$h]:$port" else "$h:$port"
    }

    /**
     * Parse the file. A malformed line throws [IOException]: silently dropping it would make that
     * host look unknown and let trust-on-first-use pin an attacker's key.
     */
    fun parse(text: String): List<KnownHostEntry> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size != 3 || !parts[2].startsWith("SHA256:")) throw IOException("Corrupt known_hosts line")
            KnownHostEntry(parts[0], parts[1], parts[2])
        }
        .toList()

    fun format(entries: List<KnownHostEntry>): String = buildString {
        append("# DFTB trusted SSH host keys (trust on first use). Remove a line to re-trust a server.\n")
        entries.forEach { append(it.hostId).append(' ').append(it.keyType).append(' ').append(it.fingerprint).append('\n') }
    }

    /** OpenSSH-style `SHA256:<base64, no padding>` fingerprint of an SSH wire-format key blob. */
    fun fingerprint(keyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** Outcome of checking a presented key against the stored entries for its host. */
    enum class Verdict { MATCH, UNKNOWN_HOST, MISMATCH }

    /**
     * MATCH when the same type+fingerprint is stored; UNKNOWN_HOST when nothing is stored for the
     * host; otherwise MISMATCH (a changed key, or a key of a type we never saw for a known host —
     * the latter is treated as suspicious because [algorithmsFor] makes us ask for the known type).
     */
    fun verdict(entries: List<KnownHostEntry>, hostId: String, keyType: String, fingerprint: String): Verdict {
        val forHost = entries.filter { it.hostId == hostId }
        if (forHost.isEmpty()) return Verdict.UNKNOWN_HOST
        return if (forHost.any { it.keyType == keyType && it.fingerprint == fingerprint }) Verdict.MATCH else Verdict.MISMATCH
    }

    /** Host-key algorithm names to prefer for a stored key type (RSA keys sign with three). */
    fun algorithmsFor(keyType: String): List<String> =
        if (keyType == "ssh-rsa") listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa") else listOf(keyType)
}

/**
 * File-backed trust-on-first-use store of SSH host keys under the app's private files dir.
 *
 * Exists because a backup destination is contacted unattended: the first successful contact pins
 * the server's key, and every later contact must present the same key or the session is refused
 * with HOST_KEY — a changed key is never accepted automatically. The UI can call [forget] after the
 * device owner confirms that a server was legitimately re-keyed.
 */
@Singleton
class SshKnownHosts @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val file = File(File(context.filesDir, "ssh"), "dftb_known_hosts")
    private val lock = Any()
    private var cache: List<KnownHostEntry>? = null

    /** Every trusted key, for a settings screen. Throws [IOException] if the store is unreadable. */
    fun entries(): List<KnownHostEntry> = synchronized(lock) { loadLocked() }

    /** Drop every trusted key for [host]:[port]; the next connection re-pins (TOFU). */
    fun forget(host: String, port: Int): Boolean = synchronized(lock) {
        val id = KnownHostsFormat.hostId(host, port)
        val current = loadLocked()
        val kept = current.filterNot { it.hostId == id }
        if (kept.size == current.size) return false
        saveLocked(kept)
        true
    }

    internal fun knownTypes(hostId: String): List<String> =
        synchronized(lock) { loadLocked().filter { it.hostId == hostId }.map { it.keyType } }

    /** Check a presented key; pins it when the host is unknown. Blocking file I/O (sshj transport thread). */
    internal fun checkAndPin(hostId: String, keyType: String, fingerprint: String): KnownHostsFormat.Verdict =
        synchronized(lock) {
            val current = loadLocked()
            val verdict = KnownHostsFormat.verdict(current, hostId, keyType, fingerprint)
            if (verdict == KnownHostsFormat.Verdict.UNKNOWN_HOST) {
                saveLocked(current + KnownHostEntry(hostId, keyType, fingerprint))
                DftbLog.i(TAG, "Pinned new SSH host key for $hostId ($keyType $fingerprint)")
            }
            verdict
        }

    /** Fails closed: an unreadable or corrupt store throws, so no key is pinned over lost pins. */
    private fun loadLocked(): List<KnownHostEntry> {
        cache?.let { return it }
        val loaded = if (file.exists()) KnownHostsFormat.parse(file.readText()) else emptyList()
        cache = loaded
        return loaded
    }

    private fun saveLocked(entries: List<KnownHostEntry>) {
        val dir = file.parentFile ?: throw IOException("known_hosts has no parent directory")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(KnownHostsFormat.format(entries).toByteArray(Charsets.UTF_8))
            out.fd.sync() // durable before the rename replaces the old pins
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Cannot replace ${file.path}")
        }
        cache = entries
    }

    private companion object {
        const val TAG = "SshKnownHosts"
    }
}

/**
 * sshj [HostKeyVerifier] implementing trust-on-first-use against [SshKnownHosts]. Records the
 * presented fingerprint when it refuses a key so the backend can report exactly what changed.
 */
internal class TofuHostKeyVerifier(
    private val store: SshKnownHosts,
    private val hostId: String,
) : HostKeyVerifier {

    @Volatile
    var rejectedFingerprint: String? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val type = KeyType.fromKey(key).toString()
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val fingerprint = KnownHostsFormat.fingerprint(blob)
        val verdict = try {
            store.checkAndPin(hostId, type, fingerprint)
        } catch (e: IOException) {
            // Store unreadable/corrupt or pin not persistable: refuse (fail closed).
            DftbLog.e("TofuHostKeyVerifier", "Known-hosts store failed for $hostId", e)
            rejectedFingerprint = fingerprint
            return false
        }
        if (verdict == KnownHostsFormat.Verdict.MISMATCH) {
            DftbLog.w("TofuHostKeyVerifier", "Host key MISMATCH for $hostId: presented $type $fingerprint")
            rejectedFingerprint = fingerprint
            return false
        }
        return true
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = try {
        store.knownTypes(hostId).flatMap { KnownHostsFormat.algorithmsFor(it) }.distinct()
    } catch (e: IOException) {
        emptyList() // verify() hits the same failure and refuses the key
    }
}
