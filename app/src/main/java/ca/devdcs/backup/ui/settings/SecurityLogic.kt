package ca.devdcs.backup.ui.settings

import ca.devdcs.backup.core.storage.ssh.KnownHostEntry
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.security.SecretKind
import ca.devdcs.backup.domain.security.VaultEntryInfo

/** A profile that still points at a vault key, shown so the user knows what to edit first. */
data class KeyReference(val profileId: String, val profileName: String)

/** Outcome of asking whether a vault encryption key may be deleted. */
sealed interface KeyDeletionVerdict {
    data object Allowed : KeyDeletionVerdict

    /** Deletion refused: these profiles reference the key and their next run would fail. */
    data class Blocked(val references: List<KeyReference>) : KeyDeletionVerdict
}

/**
 * Refuses to delete an encryption key any backup profile still references. Exists because a
 * deleted key makes every artifact encrypted with it permanently unreadable, and a profile left
 * pointing at a missing key would fail every scheduled run. Any reference blocks — even from a
 * disabled profile or one whose encryption is currently off — since re-enabling it would need the
 * key again.
 */
object KeyDeletionGuard {

    fun references(ref: String, profiles: List<BackupProfile>): List<KeyReference> =
        profiles
            .filter { it.pipeline.encryptionKeyRef == ref }
            .map { KeyReference(it.id, it.name) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.profileName })

    fun check(ref: String, profiles: List<BackupProfile>): KeyDeletionVerdict {
        val references = references(ref, profiles)
        return if (references.isEmpty()) KeyDeletionVerdict.Allowed else KeyDeletionVerdict.Blocked(references)
    }
}

/** One vault encryption key as the security screen lists it (the key itself is never loaded). */
data class EncryptionKeyRow(
    val ref: String,
    val createdAt: Long,
    val updatedAt: Long,
    val usedBy: List<KeyReference>,
) {
    val deletable: Boolean get() = usedBy.isEmpty()
}

/** Builds the key list from vault metadata: encryption keys only, newest first, with their users. */
fun encryptionKeyRows(entries: List<VaultEntryInfo>, profiles: List<BackupProfile>): List<EncryptionKeyRow> =
    entries
        .filter { it.kind == SecretKind.ENCRYPTION_KEY }
        .sortedWith(compareByDescending<VaultEntryInfo> { it.createdAt }.thenBy { it.ref })
        .map { entry ->
            EncryptionKeyRow(
                ref = entry.ref,
                createdAt = entry.createdAt,
                updatedAt = entry.updatedAt,
                usedBy = KeyDeletionGuard.references(entry.ref, profiles),
            )
        }

/** A server identity (`host` + `port`) recovered from a known-hosts host id. */
data class HostAndPort(val host: String, val port: Int)

/** Every pinned key of one server; forgetting is per server, so the UI groups by it. */
data class KnownHostGroup(
    val hostId: String,
    val endpoint: HostAndPort?,
    val keys: List<KnownHostEntry>,
)

/**
 * Inverse of the known-hosts host-id format (`host:port`, IPv6 literals as `[addr]:port`).
 * Needed because [ca.devdcs.backup.core.storage.ssh.SshKnownHosts.forget] takes host and port,
 * while the stored entries only carry the combined id.
 */
object KnownHostIds {

    fun parse(hostId: String): HostAndPort? {
        val trimmed = hostId.trim()
        val separator = trimmed.lastIndexOf(':')
        if (separator <= 0 || separator == trimmed.lastIndex) return null
        val portText = trimmed.substring(separator + 1)
        if (!portText.all { it in '0'..'9' } || portText.length > 5) return null
        val port = portText.toInt()
        if (port !in 1..65_535) return null
        val hostPart = trimmed.substring(0, separator)
        val host = if (hostPart.startsWith("[")) {
            if (!hostPart.endsWith("]") || hostPart.length < 3) return null
            hostPart.substring(1, hostPart.length - 1)
        } else {
            // An unbracketed colon would be an IPv6 literal the format always brackets: malformed.
            if (':' in hostPart) return null
            hostPart
        }
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        return HostAndPort(host, port)
    }

    /** Groups entries per server, sorted by host id, keeping each server's keys in file order. */
    fun group(entries: List<KnownHostEntry>): List<KnownHostGroup> =
        entries
            .groupBy { it.hostId }
            .map { (hostId, keys) -> KnownHostGroup(hostId, parse(hostId), keys) }
            .sortedBy { it.hostId }
}
