package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.CompressionCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ArtifactNamingTest {

    private val tarZst = ArtifactSpec(ArtifactLayout.ARCHIVE, ArchiveFormat.TAR_ZST, ArchiveFormat.TAR, CompressionCodec.ZSTD, 3)
    private val ddGz = ArtifactSpec(ArtifactLayout.RAW_STREAM, ArchiveFormat.DD_IMAGE, null, CompressionCodec.GZIP, 6)
    private val treeXz = ArtifactSpec(ArtifactLayout.FILE_TREE, ArchiveFormat.RAW_COPY, null, CompressionCodec.XZ, 6)

    @Test
    fun `job folder is profile name plus UTC timestamp`() {
        // 2026-10-19T08:05:09Z
        val millis = 1_792_397_109_000L
        assertEquals("Nightly_phone_20261019-080509", ArtifactNaming.jobDirName("Nightly phone", millis, ZoneOffset.UTC))
    }

    @Test
    fun `sanitize keeps safe characters and never yields hidden or empty names`() {
        assertEquals("a_b_c", ArtifactNaming.sanitizeSegment("a/b\\c"))
        assertEquals("etc_passwd", ArtifactNaming.sanitizeSegment("../etc/passwd"))
        assertEquals("backup", ArtifactNaming.sanitizeSegment("..."))
        assertEquals("backup", ArtifactNaming.sanitizeSegment("   "))
        assertEquals("Fotos_2024", ArtifactNaming.sanitizeSegment("Fotos  2024"))
        assertEquals(96, ArtifactNaming.sanitizeSegment("x".repeat(500)).length)
        assertFalse(ArtifactNaming.sanitizeSegment(".hidden").startsWith("."))
    }

    @Test
    fun `base names carry the kind`() {
        assertEquals("boot_a", ArtifactNaming.artifactBaseName(SourceKind.PARTITION, "boot_a"))
        assertEquals("app_com.example.app", ArtifactNaming.artifactBaseName(SourceKind.APP, "com.example.app"))
        assertEquals("files_storage_emulated_0_DCIM", ArtifactNaming.artifactBaseName(SourceKind.FILES, "/storage/emulated/0/DCIM"))
    }

    @Test
    fun `duplicate bases are numbered case-insensitively`() {
        assertEquals(listOf("a", "A-2", "a-3", "b"), ArtifactNaming.uniqueNames(listOf("a", "A", "a", "b")))
    }

    @Test
    fun `artifact names follow the layout`() {
        assertEquals("files_x.tar.zst", ArtifactNaming.artifactName("files_x", tarZst, encrypted = false))
        assertEquals("files_x.tar.zst.dftbenc", ArtifactNaming.artifactName("files_x", tarZst, encrypted = true))
        assertEquals("boot.img.gz", ArtifactNaming.artifactName("boot", ddGz, encrypted = false))
        assertEquals("files_x", ArtifactNaming.artifactName("files_x", treeXz, encrypted = true))
    }

    @Test
    fun `tree file paths get the codec suffix and per-segment masking`() {
        assertEquals("DCIM/a.jpg.xz", ArtifactNaming.treeFilePath("DCIM/a.jpg", treeXz, encrypted = false, mask = null))
        assertEquals("DCIM/a.jpg.xz.dftbenc", ArtifactNaming.treeFilePath("/DCIM//a.jpg", treeXz, encrypted = true, mask = null))
        assertEquals("[DCIM]/[a.jpg.xz]", ArtifactNaming.treeFilePath("DCIM/a.jpg", treeXz, encrypted = false) { "[$it]" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tree path rejects traversal`() {
        ArtifactNaming.treeFilePath("a/../b", treeXz, encrypted = false, mask = null)
    }

    @Test
    fun `sidecar part and manifest names`() {
        assertEquals("boot.img.sha256", ArtifactNaming.checksumSidecarName("boot.img", ChecksumAlgorithm.SHA256))
        assertEquals("boot.img.sha2", ArtifactNaming.checksumSidecarName("boot.img", ChecksumAlgorithm.SHA256, twrpStyle = true))
        assertEquals("x.blake3", ArtifactNaming.checksumSidecarName("x", ChecksumAlgorithm.BLAKE3, twrpStyle = true))
        assertEquals("d/x.part", ArtifactNaming.partPath("d/x"))
        assertEquals("dftb-manifest.json", ArtifactNaming.manifestName(false))
        assertEquals("dftb-manifest.json.dftbenc", ArtifactNaming.manifestName(true))
        assertEquals("a/b", ArtifactNaming.join("", "a", "", "b"))
        assertTrue(ArtifactNaming.timestamp(0L, ZoneOffset.UTC) == "19700101-000000")
    }
}
