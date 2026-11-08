package ca.devdcs.backup.data.security

import ca.devdcs.backup.domain.security.Secret
import ca.devdcs.backup.domain.security.SecretKind
import ca.devdcs.backup.domain.security.VaultException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileCredentialVaultTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var cipher: FakeVaultCipher

    private fun newVault(testDispatcher: kotlinx.coroutines.CoroutineDispatcher): FileCredentialVault {
        dir = File(temp.root, "vault")
        cipher = FakeVaultCipher()
        return FileCredentialVault(dir, cipher, testDispatcher)
    }

    @Test
    fun storeThenLoadReturnsEqualSecretForEveryKind() = runTest {
        val vault = newVault(UnconfinedTestDispatcher(testScheduler))
        val secrets = listOf(
            Secret.Password("u", "p"),
            Secret.SshKey("pem", null, "pub"),
            Secret.OAuthToken("a", "r", 1L, "Bearer", null),
            Secret.RcloneConfig("remote", "[remote]\ntype = s3\n"),
            Secret.EncryptionKey("k", "AES-256-GCM"),
            Secret.Generic("{}"),
        )
        for (secret in secrets) {
            val ref = vault.newRef(secret.kind.name.lowercase())
            vault.store(ref, secret)
            assertEquals(secret, vault.load(ref))
            assertTrue(vault.exists(ref))
        }
        assertEquals(secrets.size, vault.list().size)
    }

    @Test
    fun loadOfUnknownRefIsNullAndExistsIsFalse() = runTest {
        val vault = newVault(UnconfinedTestDispatcher(testScheduler))
        assertNull(vault.load("nope"))
        assertFalse(vault.exists("nope"))
        assertFalse(vault.delete("nope"))
        assertTrue(vault.list().isEmpty())
    }

    @Test
    fun fileOnDiskUsesHexNameAndFormatAndNeverContainsPlaintext() = runTest {
        val vault = newVault(UnconfinedTestDispatcher(testScheduler))
        vault.store("dest-1", Secret.Password("alice", "hunter2-plaintext"))
        val file = File(dir, VaultRefCodec.toFileName("dest-1"))
        assertTrue(file.isFile)
        assertEquals("646573742d31.dftbsec", file.name)
        val bytes = file.readBytes()
        assertEquals("DFTBSEC1", String(bytes, 0, 8, Charsets.US_ASCII))
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("hunter2-plaintext"))
        assertFalse(text.contains("alice"))
        val blob = VaultBlobCodec.decode(bytes)
        assertEquals(SecretKind.PASSWORD, blob.kind)
        assertEquals(12, blob.iv.size)
        assertFalse(File(dir, file.name + VaultRefCodec.TEMP_SUFFIX).exists())
    }

    @Test
    fun listRecoversRefsAndMetadataWithoutDecrypting() = runTest {
        val vault = newVault(UnconfinedTestDispatcher(testScheduler))
        vault.store("zeta", Secret.Generic("{}"))
        vault.store("alpha/β", Secret.EncryptionKey("k", "AES-256-GCM"))
        File(dir, "stray.txt").writeText("ignore me")
        File(dir, VaultRefCodec.toFileName("alpha/β") + VaultRefCodec.TEMP_SUFFIX).writeBytes(ByteArray(3))
        val decryptsBefore = cipher.decryptCalls

        val entries = vault.list()

        assertEquals(listOf("alpha/β", "zeta"), entries.map { it.ref })
        assertEquals(SecretKind.ENCRYPTION_KEY, entries[0].kind)
        assertEquals(SecretKind.GENERIC, entries[1].kind)
        assertEquals(File(dir, VaultRefCodec.toFileName("zeta")).length(), entries[1].sizeBytes)
        assertTrue(entries.all { it.createdAt > 0 && it.updatedAt >= it.createdAt })
        assertEquals(decryptsBefore, cipher.decryptCalls)
    }

    @Test
    fun replacingAnEntryKeepsCreatedAtAndBumpsUpdatedAt() = runTest {
        val vault = newVault(UnconfinedTestDispatcher(testScheduler))
        vault.store("r", Secret.Password(null, "one"))
        val first = vault.list().single()
        Thread.sleep(5)
        vault.store("r", Secret.Password(null, "two"))
        val second = vault.list().single()
        assertEquals(first.createdAt, second.createdAt)
        assertTrue(second.updatedAt > first.updatedAt)
        assertEquals(Secret.Password(null, "two"), vault.load("r"))
    }

    @Test
    fun deleteRemovesFileAndReportsIt() = runTest {
        val vault = newVault(UnconfinedTestDispatcher(testScheduler))
        vault.store("r", Secret.Generic("{}"))
        assertTrue(vault.delete("r"))
        assertFalse(vault.exists("r"))
        assertNull(vault.load("r"))
        assertFalse(vault.delete("r"))
    }

    @Test
    fun blobMovedToAnotherRefIsRejected() = runTest {
        val vault = newVault(UnconfinedTestDispatcher(testScheduler))
        vault.store("a", Secret.Generic("{}"))
        File(dir, VaultRefCodec.toFileName("a")).copyTo(File(dir, VaultRefCodec.toFileName("b")))
        assertExceptionReason(VaultException.Reason.INTEGRITY_FAILURE) { vault.load("b") }
    }

    @Test
    fun tamperedCiphertextIsRejected() = runTest {
        val vault = newVault(UnconfinedTestDispatcher(testScheduler))
        vault.store("a", Secret.Password("u", "p"))
        val file = File(dir, VaultRefCodec.toFileName("a"))
        val bytes = file.readBytes()
        bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)
        assertExceptionReason(VaultException.Reason.INTEGRITY_FAILURE) { vault.load("a") }
    }

    @Test
    fun corruptHeaderIsReportedOnLoadAndSkippedOnList() = runTest {
        val vault = newVault(UnconfinedTestDispatcher(testScheduler))
        vault.store("good", Secret.Generic("{}"))
        File(dir, VaultRefCodec.toFileName("bad")).writeBytes("garbage".toByteArray())
        assertExceptionReason(VaultException.Reason.CORRUPT_BLOB) { vault.load("bad") }
        assertEquals(listOf("good"), vault.list().map { it.ref })
    }

    @Test
    fun corruptExistingEntryIsOverwrittenAsNew() = runTest {
        val vault = newVault(UnconfinedTestDispatcher(testScheduler))
        dir.mkdirs()
        File(dir, VaultRefCodec.toFileName("r")).writeBytes(ByteArray(3))
        vault.store("r", Secret.Generic("{}"))
        assertEquals(Secret.Generic("{}"), vault.load("r"))
    }

    @Test
    fun ciphertextOnDiskIsWhatTheCipherProduced() = runTest {
        val vault = newVault(UnconfinedTestDispatcher(testScheduler))
        val secret = Secret.RcloneConfig("r", "[r]\n")
        vault.store("r", secret)
        val blob = VaultBlobCodec.decode(File(dir, VaultRefCodec.toFileName("r")).readBytes())
        // store() binds the cleartext header into the AAD alongside the ref, so a manual decrypt
        // must feed the same ref + header bytes (big-endian: u16 version, u8 kind, i64 createdAt, i64 updatedAt).
        val headerAad = java.nio.ByteBuffer.allocate(2 + 1 + 8 + 8)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
            .putShort(VaultBlobCodec.VERSION.toShort())
            .put(blob.kind.ordinal.toByte())
            .putLong(blob.createdAt)
            .putLong(blob.updatedAt)
            .array()
        val plaintext = FakeVaultCipher().decrypt(
            EncryptedBlob(blob.iv, blob.ciphertext),
            "r".toByteArray() + headerAad,
        )
        assertArrayEquals(SecretJson.encodeToBytes(secret), plaintext)
    }

    @Test
    fun newRefHasPrefixAndIsUnique() = runTest {
        val vault = newVault(UnconfinedTestDispatcher(testScheduler))
        val refs = (1..50).map { vault.newRef("dest") }
        assertTrue(refs.all { it.startsWith("dest-") && it.length == "dest-".length + 36 })
        assertEquals(50, refs.toSet().size)
    }

    @Test
    fun invalidRefIsRejectedBeforeTouchingDisk() = runTest {
        val vault = newVault(UnconfinedTestDispatcher(testScheduler))
        assertExceptionReason(VaultException.Reason.INVALID_REF) { vault.store("", Secret.Generic("{}")) }
        assertFalse(dir.exists())
    }

    private suspend fun assertExceptionReason(reason: VaultException.Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("expected VaultException($reason)")
        } catch (e: VaultException) {
            assertEquals(e.message, reason, e.reason)
        }
    }
}
