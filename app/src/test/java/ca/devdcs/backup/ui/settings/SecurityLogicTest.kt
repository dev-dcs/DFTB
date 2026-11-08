package ca.devdcs.backup.ui.settings

import ca.devdcs.backup.core.storage.ssh.KnownHostEntry
import ca.devdcs.backup.core.storage.ssh.KnownHostsFormat
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.security.SecretKind
import ca.devdcs.backup.domain.security.VaultEntryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityLogicTest {

    private fun profile(
        id: String,
        name: String,
        keyRef: String?,
        mode: EncryptionMode = EncryptionMode.AES_256_GCM,
        enabled: Boolean = true,
    ) = BackupProfile(
        id = id,
        name = name,
        pipeline = PipelineOptions(encryptionMode = mode, encryptionKeyRef = keyRef),
        createdAt = 1L,
        updatedAt = 1L,
        enabled = enabled,
    )

    private fun entry(ref: String, kind: SecretKind, createdAt: Long) =
        VaultEntryInfo(ref = ref, kind = kind, createdAt = createdAt, updatedAt = createdAt, sizeBytes = 100)

    // ---------------------------------------------------------------- key deletion guard

    @Test
    fun unreferencedKeyMayBeDeleted() {
        val profiles = listOf(profile("p1", "Photos", "enckey-other"), profile("p2", "Plain", null, EncryptionMode.NONE))
        assertEquals(KeyDeletionVerdict.Allowed, KeyDeletionGuard.check("enckey-a", profiles))
        assertEquals(KeyDeletionVerdict.Allowed, KeyDeletionGuard.check("enckey-a", emptyList()))
    }

    @Test
    fun anyReferenceBlocksDeletionIncludingDisabledAndUnencryptedProfiles() {
        val profiles = listOf(
            profile("p1", "photos", "enckey-a"),
            profile("p2", "Apps", "enckey-a", enabled = false),
            // Encryption switched off but the ref kept: turning it back on would need the key.
            profile("p3", "Documents", "enckey-a", mode = EncryptionMode.NONE),
            profile("p4", "Other", "enckey-b"),
        )
        val verdict = KeyDeletionGuard.check("enckey-a", profiles)
        assertTrue(verdict is KeyDeletionVerdict.Blocked)
        verdict as KeyDeletionVerdict.Blocked
        // Sorted case-insensitively by name so the message reads naturally.
        assertEquals(listOf("Apps", "Documents", "photos"), verdict.references.map { it.profileName })
        assertEquals(setOf("p1", "p2", "p3"), verdict.references.map { it.profileId }.toSet())
    }

    @Test
    fun keyRowsListOnlyEncryptionKeysNewestFirstWithTheirUsers() {
        val entries = listOf(
            entry("enckey-old", SecretKind.ENCRYPTION_KEY, createdAt = 10),
            entry("dest-1", SecretKind.PASSWORD, createdAt = 50),
            entry("enckey-new", SecretKind.ENCRYPTION_KEY, createdAt = 30),
            entry("rclone-1", SecretKind.RCLONE_CONFIG, createdAt = 40),
        )
        val rows = encryptionKeyRows(entries, listOf(profile("p1", "Photos", "enckey-old")))
        assertEquals(listOf("enckey-new", "enckey-old"), rows.map { it.ref })
        assertTrue(rows[0].deletable)
        assertFalse(rows[1].deletable)
        assertEquals(listOf("Photos"), rows[1].usedBy.map { it.profileName })
    }

    // ---------------------------------------------------------------- known hosts

    @Test
    fun parsesHostAndPort() {
        assertEquals(HostAndPort("nas.local", 22), KnownHostIds.parse("nas.local:22"))
        assertEquals(HostAndPort("192.168.1.10", 2222), KnownHostIds.parse("192.168.1.10:2222"))
        assertEquals(HostAndPort("fe80::1", 22), KnownHostIds.parse("[fe80::1]:22"))
        assertEquals(HostAndPort("::1", 65535), KnownHostIds.parse("[::1]:65535"))
    }

    @Test
    fun rejectsMalformedHostIds() {
        listOf(
            "", "nas.local", "nas.local:", ":22", "nas.local:abc", "nas.local:0", "nas.local:65536",
            "fe80::1:22", "[fe80::1:22", "[]:22", "nas local:22", "nas.local:123456",
        ).forEach { assertNull(it, KnownHostIds.parse(it)) }
    }

    @Test
    fun parseInvertsTheStoreFormat() {
        listOf("NAS.Local" to 22, "10.0.0.2" to 2022, "fe80::abcd" to 22, "[2001:db8::1]" to 8022).forEach { (host, port) ->
            val id = KnownHostsFormat.hostId(host, port)
            val parsed = KnownHostIds.parse(id)
            assertEquals(id, parsed?.let { KnownHostsFormat.hostId(it.host, it.port) })
        }
    }

    @Test
    fun groupsKeysPerServerSorted() {
        val entries = listOf(
            KnownHostEntry("nas.local:22", "ssh-ed25519", "SHA256:aaa"),
            KnownHostEntry("backup.example:2222", "ssh-rsa", "SHA256:bbb"),
            KnownHostEntry("nas.local:22", "ecdsa-sha2-nistp256", "SHA256:ccc"),
            KnownHostEntry("broken-entry", "ssh-rsa", "SHA256:ddd"),
        )
        val groups = KnownHostIds.group(entries)
        assertEquals(listOf("backup.example:2222", "broken-entry", "nas.local:22"), groups.map { it.hostId })
        val nas = groups.single { it.hostId == "nas.local:22" }
        assertEquals(listOf("ssh-ed25519", "ecdsa-sha2-nistp256"), nas.keys.map { it.keyType })
        assertEquals(HostAndPort("nas.local", 22), nas.endpoint)
        assertNull(groups.single { it.hostId == "broken-entry" }.endpoint)
    }
}
