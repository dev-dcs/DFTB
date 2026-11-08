package ca.devdcs.backup.core.restore.catalog

import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.checksum.DefaultChecksumEngine
import ca.devdcs.backup.core.pipeline.crypto.CipherIntegrityException
import ca.devdcs.backup.core.pipeline.engine.BackupManifest
import ca.devdcs.backup.core.pipeline.engine.FixedClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class DefaultBackupCatalogTest {

    private val io = Dispatchers.Unconfined
    private val store = TreeStore()
    private val crypto = XorRestoreCrypto()
    private val archives = RestoreFixtures.archiveFactory(io)
    private val catalog = DefaultBackupCatalog(TreeRegistry(store), crypto, archives, archives, DefaultChecksumEngine(io), FixedClock(), io)
    private val dest = RestoreFixtures.destination
    private val jobDir = "Phone_20261019-080509"

    private fun tarOf(vararg files: Pair<String, String>): ByteArray =
        RestoreFixtures.tar(archives, files.map { RestoreFixtures.file(it.first, it.second) })

    private fun putArtifact(name: String, bytes: ByteArray) {
        store.put("$jobDir/$name", bytes)
        store.put("$jobDir/$name.sha256", RestoreFixtures.sidecar(name, bytes))
    }

    @Test
    fun openMergesEveryManifestOfAResumedJob() = runTest {
        val a = tarOf("files/DCIM/a.jpg" to "AAA")
        val b = tarOf("files/DCIM/b.jpg" to "BBBB")
        putArtifact("files_a.tar", a)
        putArtifact("files_b.tar", b)
        val first = RestoreFixtures.manifest(listOf(RestoreFixtures.tarArtifact("files:a", "files_a.tar", a, mapOf("files/DCIM/a.jpg" to "AAA"))), finished = 10)
        val second = RestoreFixtures.manifest(listOf(RestoreFixtures.tarArtifact("files:b", "files_b.tar", b, mapOf("files/DCIM/b.jpg" to "BBBB"))), finished = 20)
        store.put("$jobDir/dftb-manifest.json", BackupManifest.encode(first))
        store.put("$jobDir/dftb-manifest.20.json", BackupManifest.encode(second))
        store.put("$jobDir/dftb-manifest.30.json.part", "garbage")

        catalog.open(dest, jobDir, null).use { backup ->
            assertEquals(listOf("files:a", "files:b"), backup.manifest.artifacts.map { it.sourceId })
            assertEquals("$jobDir/dftb-manifest.json", backup.summary.manifestPath)
            assertTrue(backup.summary.manifestReadable)
            assertFalse(backup.summary.encrypted)
            assertEquals((a.size + b.size).toLong(), backup.summary.totalBytes)
        }
    }

    @Test
    fun decodedStreamMatchesAndVerifiesAtEof() = runTest {
        val plain = tarOf("files/x/a.txt" to "hello")
        putArtifact("files_x.tar", plain)
        val artifact = RestoreFixtures.tarArtifact("files:x", "files_x.tar", plain, mapOf("files/x/a.txt" to "hello"))
        store.put("$jobDir/dftb-manifest.json", BackupManifest.encode(RestoreFixtures.manifest(listOf(artifact))))
        catalog.open(dest, jobDir, null).use { backup ->
            val bytes = catalog.openDecoded(backup, backup.manifest.artifacts.single()).use { it.readBytes() }
            assertArrayEquals(plain, bytes)
        }
    }

    @Test
    fun corruptedStoredBytesFailAtEndOfStream() = runTest {
        val plain = tarOf("files/x/a.txt" to "hello")
        val artifact = RestoreFixtures.tarArtifact("files:x", "files_x.tar", plain, mapOf("files/x/a.txt" to "hello"))
        val corrupted = plain.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        putArtifact("files_x.tar", corrupted)
        store.put("$jobDir/dftb-manifest.json", BackupManifest.encode(RestoreFixtures.manifest(listOf(artifact))))
        catalog.open(dest, jobDir, null).use { backup ->
            val stream = catalog.openDecoded(backup, backup.manifest.artifacts.single())
            try {
                stream.readBytes()
                fail("Expected a digest mismatch")
            } catch (e: CatalogException) {
                assertEquals(CatalogException.Reason.DIGEST_MISMATCH, e.reason)
            } finally {
                stream.close()
            }
        }
    }

    @Test
    fun truncatedStoredBytesFailWithSizeMismatch() = runTest {
        val plain = tarOf("files/x/a.txt" to "hello")
        val artifact = RestoreFixtures.tarArtifact("files:x", "files_x.tar", plain, mapOf("files/x/a.txt" to "hello"))
        putArtifact("files_x.tar", plain.copyOf(plain.size - 512))
        store.put("$jobDir/dftb-manifest.json", BackupManifest.encode(RestoreFixtures.manifest(listOf(artifact))))
        catalog.open(dest, jobDir, null).use { backup ->
            val stream = catalog.openDecoded(backup, backup.manifest.artifacts.single()) as DecodedArtifactStream
            try {
                stream.finishAndVerify()
                fail("Expected a size mismatch")
            } catch (e: CatalogException) {
                assertEquals(CatalogException.Reason.SIZE_MISMATCH, e.reason)
            } finally {
                stream.close()
            }
        }
    }

    @Test
    fun encryptedManifestNeedsTheKeyAndUnmasksNames() = runTest {
        val plain = tarOf("files/x/a.txt" to "hello")
        val stored = XorRestoreCrypto.encrypt(plain)
        val masked = HexNames.mask("files_x.tar")
        putArtifact(masked, stored)
        val artifact = RestoreFixtures.tarArtifact("files:x", masked, stored, mapOf("files/x/a.txt" to "hello"), encrypted = true, name = null)
            .copy(masked = true)
        val manifest = RestoreFixtures.manifest(listOf(artifact), masking = mapOf("scheme" to "hex"))
        store.put("$jobDir/dftb-manifest.json.dftbenc", XorRestoreCrypto.encrypt(BackupManifest.encode(manifest).toByteArray()))

        val summary = catalog.list(dest).single()
        assertFalse(summary.manifestReadable)
        assertTrue(summary.encrypted)
        assertEquals("Phone", summary.profileName)

        try {
            catalog.open(dest, jobDir, null)
            fail("Expected KEY_REQUIRED")
        } catch (e: CatalogException) {
            assertEquals(CatalogException.Reason.KEY_REQUIRED, e.reason)
        }
        try {
            catalog.open(dest, jobDir, CipherKeySpec.Passphrase("wrong".toCharArray()))
            fail("Expected a wrong-key failure")
        } catch (e: CipherIntegrityException) {
            assertEquals(CipherIntegrityException.Reason.WRONG_KEY, e.reason)
        }

        val key = CipherKeySpec.Passphrase("secret".toCharArray())
        catalog.open(dest, jobDir, key).use { backup ->
            assertEquals("files_x.tar", backup.manifest.artifacts.single().name)
            assertArrayEquals(plain, catalog.openDecoded(backup, backup.manifest.artifacts.single()).use { it.readBytes() })
        }
        // The caller's passphrase is untouched; the catalog wiped only its own copy.
        assertEquals("secret", String(key.chars))
        assertTrue(crypto.closed >= 1)
    }

    @Test
    fun quickAndDeepVerifyReportCorruptionAndSidecars() = runTest {
        val good = tarOf("files/x/a.txt" to "hello")
        val bad = tarOf("files/y/b.txt" to "world")
        putArtifact("files_x.tar", good)
        putArtifact("files_y.tar", bad)
        // Corrupt y's block padding (tar ignores it) after its sidecar was written, so only the
        // stored-bytes digests can notice.
        val at = indexOf(bad, "world".toByteArray()) + 100
        store.put("$jobDir/files_y.tar", bad.copyOf().also { it[at] = 0x7f })
        val manifest = RestoreFixtures.manifest(
            listOf(
                RestoreFixtures.tarArtifact("files:x", "files_x.tar", good, mapOf("files/x/a.txt" to "hello")),
                RestoreFixtures.tarArtifact("files:y", "files_y.tar", bad, mapOf("files/y/b.txt" to "world")),
            ),
        )
        store.put("$jobDir/dftb-manifest.json", BackupManifest.encode(manifest))
        catalog.open(dest, jobDir, null).use { backup ->
            for (deep in listOf(false, true)) {
                val checks = catalog.verify(backup, deep)
                assertTrue(checks[0].ok)
                assertNull(checks[0].problem)
                assertFalse(checks[1].ok)
                assertEquals(CatalogException.Reason.DIGEST_MISMATCH, ArtifactProblems.parse(checks[1].problem!!).first)
            }
        }
    }

    @Test
    fun deepVerifyCatchesAnItemDigestMismatch() = runTest {
        val plain = tarOf("files/x/a.txt" to "hello")
        putArtifact("files_x.tar", plain)
        val artifact = RestoreFixtures.tarArtifact("files:x", "files_x.tar", plain, mapOf("files/x/a.txt" to "HELLO"))
        store.put("$jobDir/dftb-manifest.json", BackupManifest.encode(RestoreFixtures.manifest(listOf(artifact))))
        catalog.open(dest, jobDir, null).use { backup ->
            assertTrue(catalog.verify(backup, deep = false).single().ok)
            val deep = catalog.verify(backup, deep = true).single()
            assertFalse(deep.ok)
            assertEquals(CatalogException.Reason.ITEM_DIGEST_MISMATCH, ArtifactProblems.parse(deep.problem!!).first)
        }
    }

    @Test
    fun missingSidecarIsReported() = runTest {
        val plain = tarOf("files/x/a.txt" to "hello")
        store.put("$jobDir/files_x.tar", plain)
        val artifact = RestoreFixtures.tarArtifact("files:x", "files_x.tar", plain, mapOf("files/x/a.txt" to "hello"))
        store.put("$jobDir/dftb-manifest.json", BackupManifest.encode(RestoreFixtures.manifest(listOf(artifact))))
        catalog.open(dest, jobDir, null).use { backup ->
            val check = catalog.verify(backup, deep = false).single()
            assertFalse(check.ok)
            assertEquals(CatalogException.Reason.SIDECAR_MISMATCH, ArtifactProblems.parse(check.problem!!).first)
        }
    }

    @Test
    fun folderWithoutManifestIsNotABackup() = runTest {
        store.put("random/file.txt", "x")
        assertTrue(catalog.list(dest).isEmpty())
        try {
            catalog.open(dest, "random", null)
            fail("Expected NO_MANIFEST")
        } catch (e: IOException) {
            assertEquals(CatalogException.Reason.NO_MANIFEST, (e as CatalogException).reason)
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        throw AssertionError("needle not found")
    }
}
