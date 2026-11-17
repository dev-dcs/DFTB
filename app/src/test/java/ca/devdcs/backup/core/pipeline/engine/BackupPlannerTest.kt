package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.pipeline.SourceEstimate
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.CompressionCodec
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.TransferMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPlannerTest {

    @Test
    fun `source kind is read from the id prefix`() {
        assertEquals(SourceKind.PARTITION, SourceKind.fromSourceId("partition:boot_a"))
        assertEquals(SourceKind.APP, SourceKind.fromSourceId("app:com.x"))
        assertEquals(SourceKind.FILES, SourceKind.fromSourceId("files:/sdcard"))
        assertEquals(SourceKind.OTHER, SourceKind.fromSourceId("custom"))
    }

    @Test
    fun `tar formats imply their codec and use a plain tar writer`() {
        val spec = ArtifactSpecResolver.resolve(SourceKind.FILES, PipelineOptions(archiveFormat = ArchiveFormat.TAR_XZ, compressionLevel = 15))
        assertEquals(ArtifactLayout.ARCHIVE, spec.layout)
        assertEquals(ArchiveFormat.TAR_XZ, spec.format)
        assertEquals(ArchiveFormat.TAR, spec.writerFormat)
        assertEquals(CompressionCodec.XZ, spec.codec)
        assertEquals(9, spec.compressionLevel)
        assertFalse(spec.substituted)
    }

    @Test
    fun `zip and 7z compress internally`() {
        val zip = ArtifactSpecResolver.resolve(SourceKind.APP, PipelineOptions(archiveFormat = ArchiveFormat.ZIP, compressionCodec = CompressionCodec.ZSTD))
        assertEquals(CompressionCodec.NONE, zip.codec)
        assertEquals(ArchiveFormat.ZIP, zip.writerFormat)
        val sevenZ = ArtifactSpecResolver.resolve(SourceKind.APP, PipelineOptions(archiveFormat = ArchiveFormat.SEVEN_ZIP))
        assertTrue(sevenZ.needsLocalSpool)
    }

    @Test
    fun `partitions are always images compressed with the profile codec`() {
        val spec = ArtifactSpecResolver.resolve(
            SourceKind.PARTITION,
            PipelineOptions(archiveFormat = ArchiveFormat.TAR_ZST, compressionCodec = CompressionCodec.GZIP, compressionLevel = 12),
        )
        assertEquals(ArtifactLayout.RAW_STREAM, spec.layout)
        assertEquals(ArchiveFormat.DD_IMAGE, spec.format)
        assertEquals(CompressionCodec.GZIP, spec.codec)
        assertEquals(9, spec.compressionLevel)
        assertFalse(spec.substituted)
    }

    @Test
    fun `dd image for files falls back to tar with the codec and is flagged`() {
        val spec = ArtifactSpecResolver.resolve(SourceKind.FILES, PipelineOptions(archiveFormat = ArchiveFormat.DD_IMAGE, compressionCodec = CompressionCodec.BZIP2))
        assertEquals(ArchiveFormat.TAR_BZ2, spec.format)
        assertEquals(CompressionCodec.BZIP2, spec.codec)
        assertTrue(spec.substituted)
    }

    @Test
    fun `raw copy is a file tree`() {
        val spec = ArtifactSpecResolver.resolve(SourceKind.FILES, PipelineOptions(archiveFormat = ArchiveFormat.RAW_COPY, compressionCodec = CompressionCodec.NONE))
        assertEquals(ArtifactLayout.FILE_TREE, spec.layout)
        assertEquals(CompressionCodec.NONE, spec.codec)
    }

    @Test
    fun `transfer planner`() {
        assertEquals(TransferResolution.Direct, TransferPlanner.resolve(TransferMode.DIRECT_STREAM, DestinationType.SMB))
        assertEquals(TransferResolution.Direct, TransferPlanner.resolve(TransferMode.LOCAL_STAGING, DestinationType.USB_OTG))
        assertEquals(TransferResolution.Staged, TransferPlanner.resolve(TransferMode.LOCAL_STAGING, DestinationType.S3))
        val ssh = TransferPlanner.resolve(TransferMode.REMOTE_COMPRESS, DestinationType.SSH)
        assertTrue(ssh is TransferResolution.Unsupported && ssh.forSsh)
        val ftp = TransferPlanner.resolve(TransferMode.REMOTE_COMPRESS, DestinationType.FTP)
        assertTrue(ftp is TransferResolution.Unsupported && !ftp.forSsh)
    }

    private fun source(id: String, bytes: Long, exact: Boolean = true) =
        SourceDescriptor(id, SourceKind.fromSourceId(id), id.substringAfter(':'), SourceEstimate(10, bytes, exact))

    @Test
    fun `plan names artifacts inside the job folder and sums totals`() {
        val plan = BackupPlanner.build(
            options = PipelineOptions(archiveFormat = ArchiveFormat.TAR_ZST, compressionCodec = CompressionCodec.ZSTD),
            jobDirName = "P_20260101-000000",
            sources = listOf(source("partition:boot_a", 100), source("app:com.x", 50), source("app:com.x", 25)),
            transfer = TransferResolution.Direct,
            mask = null,
        )
        assertEquals(
            listOf("P_20260101-000000/boot_a.img.zst", "P_20260101-000000/app_com.x.tar.zst", "P_20260101-000000/app_com.x-2.tar.zst"),
            plan.artifacts.map { it.path },
        )
        assertEquals(175L, plan.totalBytes)
        assertEquals(30L, plan.totalItems)
        assertEquals(175L, plan.destinationRequiredBytes)
        assertEquals(0L, plan.stagingRequiredBytes)
        assertEquals("P_20260101-000000/dftb-manifest.json", plan.manifestPath)
        assertFalse(plan.encryptedManifest)
        assertTrue(plan.estimatesExact)
    }

    @Test
    fun `masking renames stored files and encrypts the manifest when encrypted`() {
        val plan = BackupPlanner.build(
            options = PipelineOptions(encryptionMode = EncryptionMode.AES_256_GCM, maskFilenames = true),
            jobDirName = "J",
            sources = listOf(source("files:/sdcard/DCIM", 10)),
            transfer = TransferResolution.Direct,
            mask = { "m-" + it.length },
        )
        val artifact = plan.artifacts.single()
        assertEquals("files_sdcard_DCIM.tar.zst.dftbenc", artifact.logicalName)
        assertEquals("m-" + artifact.logicalName.length, artifact.storedName)
        assertTrue(artifact.masked)
        assertTrue(plan.encryptedManifest)
        assertEquals("J/dftb-manifest.json.dftbenc", plan.manifestPath)
    }

    @Test
    fun `staging needs cover staged uploads 7z spools and inexact tar streams`() {
        val staged = BackupPlanner.build(PipelineOptions(), "J", listOf(source("app:a", 100), source("app:b", 300)), TransferResolution.Staged, null)
        assertEquals(300L, staged.stagingRequiredBytes)

        val inexact = BackupPlanner.build(PipelineOptions(), "J", listOf(source("app:a", 100, exact = false)), TransferResolution.Staged, null)
        assertEquals(200L, inexact.stagingRequiredBytes)
        assertFalse(inexact.estimatesExact)

        val sevenZ = BackupPlanner.build(
            PipelineOptions(archiveFormat = ArchiveFormat.SEVEN_ZIP),
            "J",
            listOf(source("app:a", 100)),
            TransferResolution.Direct,
            null,
        )
        assertEquals(100L, sevenZ.stagingRequiredBytes)

        val image = BackupPlanner.build(PipelineOptions(), "J", listOf(source("partition:boot", 100, exact = false)), TransferResolution.Direct, null)
        assertEquals(0L, image.stagingRequiredBytes)
    }

    @Test
    fun `saturating sum never overflows or goes negative`() {
        assertEquals(Long.MAX_VALUE, BackupPlanner.saturatingSum(listOf(Long.MAX_VALUE, 5)))
        assertEquals(5L, BackupPlanner.saturatingSum(listOf(-10, 5)))
    }
}
