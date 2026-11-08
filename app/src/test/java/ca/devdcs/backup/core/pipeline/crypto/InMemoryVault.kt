package ca.devdcs.backup.core.pipeline.crypto

import ca.devdcs.backup.domain.security.CredentialVault
import ca.devdcs.backup.domain.security.Secret
import ca.devdcs.backup.domain.security.VaultEntryInfo
import java.util.UUID

/** Map-backed [CredentialVault] for JVM tests of components that store keys. */
class InMemoryVault : CredentialVault {
    val entries = linkedMapOf<String, Secret>()
    override suspend fun store(ref: String, secret: Secret) { entries[ref] = secret }
    override suspend fun load(ref: String): Secret? = entries[ref]
    override suspend fun delete(ref: String): Boolean = entries.remove(ref) != null
    override suspend fun exists(ref: String): Boolean = ref in entries
    override suspend fun list(): List<VaultEntryInfo> =
        entries.map { (k, v) -> VaultEntryInfo(k, v.kind, 0L, 0L, 0L) }.sortedBy { it.ref }
    override fun newRef(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}
