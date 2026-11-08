package ca.devdcs.backup.data.security

import ca.devdcs.backup.domain.security.Secret
import ca.devdcs.backup.domain.security.SecretKind
import ca.devdcs.backup.domain.security.VaultException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecretJsonTest {

    private val all: List<Secret> = listOf(
        Secret.Password("alice", "s3cr3t"),
        Secret.Password(null, "anon"),
        Secret.SshKey("-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----", "pw", "ssh-ed25519 AAAA alice"),
        Secret.OAuthToken("acc-xyz-1", "rfr-xyz-2", 1_800_000_000_000L, "Bearer", """{"scope":"drive"}"""),
        Secret.OAuthToken("acc-xyz-3", null, null, null, null),
        Secret.RcloneConfig("gdrive", "[gdrive]\ntype = drive\ntoken = {...}\n"),
        Secret.EncryptionKey("AAAA", "AES-256-GCM"),
        Secret.Generic("""{"anything":true}"""),
    )

    @Test
    fun everyVariantRoundTrips() {
        for (secret in all) {
            val bytes = SecretJson.encodeToBytes(secret)
            assertEquals(secret, SecretJson.decodeFromBytes(bytes))
        }
    }

    @Test
    fun discriminatorsAreStableSerialNames() {
        val expected = mapOf(
            SecretKind.PASSWORD to "password",
            SecretKind.SSH_KEY to "ssh_key",
            SecretKind.OAUTH_TOKEN to "oauth_token",
            SecretKind.RCLONE_CONFIG to "rclone_config",
            SecretKind.ENCRYPTION_KEY to "encryption_key",
            SecretKind.GENERIC to "generic",
        )
        for (secret in all) {
            val json = String(SecretJson.encodeToBytes(secret), Charsets.UTF_8)
            assertTrue(json, json.contains("\"type\":\"${expected.getValue(secret.kind)}\""))
            assertFalse("kind must not be serialised: $json", json.contains("\"kind\""))
        }
    }

    @Test
    fun kindMatchesVariant() {
        assertEquals(SecretKind.PASSWORD, Secret.Password(null, "x").kind)
        assertEquals(SecretKind.SSH_KEY, Secret.SshKey("k", null, null).kind)
        assertEquals(SecretKind.OAUTH_TOKEN, Secret.OAuthToken("a", null, null, null, null).kind)
        assertEquals(SecretKind.RCLONE_CONFIG, Secret.RcloneConfig("r", "").kind)
        assertEquals(SecretKind.ENCRYPTION_KEY, Secret.EncryptionKey("k", "alg").kind)
        assertEquals(SecretKind.GENERIC, Secret.Generic("{}").kind)
    }

    @Test
    fun toStringNeverLeaksSecretMaterial() {
        val leaky = listOf("s3cr3t", "anon", "BEGIN OPENSSH", "pw", "acc-xyz", "rfr-xyz", "token = {", "AAAA", "anything", """{"scope"""")
        for (secret in all) {
            val text = secret.toString()
            for (fragment in leaky) assertFalse("$text leaks '$fragment'", text.contains(fragment))
        }
    }

    @Test
    fun unknownDiscriminatorIsCorruptBlob() {
        try {
            SecretJson.decodeFromBytes("""{"type":"carrier_pigeon","note":"x"}""".toByteArray())
            fail("expected VaultException")
        } catch (e: VaultException) {
            assertEquals(VaultException.Reason.CORRUPT_BLOB, e.reason)
        }
    }

    @Test
    fun malformedJsonIsCorruptBlob() {
        try {
            SecretJson.decodeFromBytes("not json".toByteArray())
            fail("expected VaultException")
        } catch (e: VaultException) {
            assertEquals(VaultException.Reason.CORRUPT_BLOB, e.reason)
        }
    }

    @Test
    fun unknownFieldsAreIgnoredForForwardCompatibility() {
        val decoded = SecretJson.decodeFromBytes("""{"type":"password","username":"u","password":"p","futureField":1}""".toByteArray())
        assertEquals(Secret.Password("u", "p"), decoded)
    }
}
