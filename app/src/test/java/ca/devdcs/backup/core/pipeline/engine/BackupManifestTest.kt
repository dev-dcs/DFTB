package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.pipeline.EncryptionHeader
import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.CompressionCodec
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.domain.model.PipelineOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BackupManifestTest {

    private val profile = BackupProfile(
        id = "p1",
        name = "Phone",
        destinationId = "d1",
        pipeline = PipelineOptions(encryptionMode = EncryptionMode.AES_256_GCM, encryptionKeyRef = "enckey-secret-ref"),
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun manifest() = BackupManifest(
        jobId = "job",
        createdAtMillis = 10L,
        finishedAtMillis = 20L,
        app = ManifestApp("ca.devdcs.backup", "0.5.0", 500L),
        device = ManifestDevice("Google", "Pixel", "husky", "google/husky/...", 36, "_a"),
        profile = ManifestProfile.of(profile, DestinationType.SFTP),
        checksumAlgorithm = ChecksumAlgorithm.SHA256,
        artifacts = listOf(
            ManifestArtifact(
                sourceId = "app:com.x",
                kind = "app",
                name = "app_com.x.tar.zst.dftbenc",
                storedName = "app_com.x.tar.zst.dftbenc",
                masked = false,
                layout = "archive",
                format = ArchiveFormat.TAR_ZST,
                codec = CompressionCodec.ZSTD,
                compressionLevel = 3,
                sizeBytes = 123L,
                sourceBytes = 456L,
                digests = ManifestText.digestMap(mapOf(ChecksumAlgorithm.SHA256 to "ab".repeat(32))),
                encryption = ManifestEncryption.of(EncryptionHeader("dftb-aesgcm-v1", mapOf("kdf" to "vault"))),
                checksumFile = "app_com.x.tar.zst.dftbenc.sha256",
                verified = true,
                items = listOf(
                    ManifestItem("data", ManifestItem.ItemType.DIRECTORY, metadata = ManifestMetadata(mode = 493, uid = 10123)),
                    ManifestItem("data/a.db", ManifestItem.ItemType.FILE, sizeBytes = 4, digests = mapOf("sha256" to "cd".repeat(32))),
                ),
            ),
        ),
        masking = mapOf("scheme" to "dftb-mask-v1"),
    )

    @Test
    fun `round trips through json`() {
        val original = manifest()
        val decoded = BackupManifest.decode(BackupManifest.encode(original))
        assertEquals(original, decoded)
        assertEquals(BackupManifest.SCHEMA_VERSION, decoded.schemaVersion)
    }

    @Test
    fun `json uses storage keys and never carries the key reference`() {
        val text = BackupManifest.encode(manifest())
        assertTrue(text.contains("\"format\": \"tar_zst\""))
        assertTrue(text.contains("\"checksumAlgorithm\": \"sha256\""))
        assertTrue(text.contains("\"destinationType\": \"sftp\""))
        assertTrue(text.contains("\"type\": \"directory\""))
        assertFalse(text.contains("enckey-secret-ref"))
        assertTrue(text.contains("\"hadEncryptionKeyRef\": true"))
    }

    @Test
    fun `unknown fields from newer versions are ignored`() {
        val text = BackupManifest.encode(manifest()).replaceFirst("{", "{\"futureField\": 1,")
        assertEquals("job", BackupManifest.decode(text).jobId)
    }

    @Test
    fun `metadata is omitted when nothing is known`() {
        assertNull(ManifestMetadata.of(EntryMetadata()))
        assertEquals(ManifestMetadata(uid = 1), ManifestMetadata.of(EntryMetadata(uid = 1)))
    }

    @Test
    fun `item helpers`() {
        val file = SourceItem.File("a", 7L) { ByteArrayInputStream(ByteArray(7)) }
        val stream = SourceItem.Stream("b", expectedSizeBytes = null, sizeIsExact = false) { ByteArrayInputStream(ByteArray(0)) }
        assertEquals(ManifestItem.ItemType.FILE, ManifestText.itemType(file))
        assertEquals(7L, ManifestText.declaredSize(file))
        assertNull(ManifestText.declaredSize(stream))
        assertEquals(ManifestItem.ItemType.SYMLINK, ManifestText.itemType(SourceItem.Symlink("c", "t")))
        assertEquals(
            listOf("md5", "sha256"),
            ManifestText.digestMap(mapOf(ChecksumAlgorithm.SHA256 to "x", ChecksumAlgorithm.MD5 to "y")).keys.toList(),
        )
    }
}
