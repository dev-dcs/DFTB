package ca.devdcs.backup.core.restore.catalog

import ca.devdcs.backup.core.pipeline.engine.ManifestEncryption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManifestMergerTest {

    private fun artifact(id: String, size: Int, encrypted: Boolean = false) =
        RestoreFixtures.tarArtifact(id, "$id.tar", ByteArray(size), mapOf("files/x/$id" to "x"), encrypted = encrypted)

    @Test
    fun recognizesManifestNamesButNotParts() {
        assertTrue(ManifestMerger.isManifestName("dftb-manifest.json"))
        assertTrue(ManifestMerger.isManifestName("dftb-manifest.json.dftbenc"))
        assertTrue(ManifestMerger.isManifestName("dftb-manifest.1792397109000.json"))
        assertTrue(ManifestMerger.isManifestName("dftb-manifest-run2.json.dftbenc"))
        assertFalse(ManifestMerger.isManifestName("dftb-manifest.json.part"))
        assertFalse(ManifestMerger.isManifestName("dftb-manifest.json.dftbenc.part"))
        assertFalse(ManifestMerger.isManifestName("other.json"))
        assertFalse(ManifestMerger.isManifestName("dftb-manifest.jsonx"))
        assertTrue(ManifestMerger.isEncryptedName("dftb-manifest.json.dftbenc"))
    }

    @Test
    fun resumedRunsAreMergedLatestWinsInFirstSeenOrder() {
        val first = RestoreFixtures.manifest(listOf(artifact("files:a", 1), artifact("files:b", 2)), created = 100, finished = 200)
        val second = RestoreFixtures.manifest(listOf(artifact("files:b", 22), artifact("files:c", 3)), created = 300, finished = 400)
        // Given out of order on purpose: chronology comes from the manifests, not listing order.
        val merged = ManifestMerger.merge(
            listOf(ManifestRun("j/dftb-manifest.2.json", false, second), ManifestRun("j/dftb-manifest.json", false, first)),
        )
        assertEquals(listOf("files:a", "files:b", "files:c"), merged.artifacts.map { it.sourceId })
        assertEquals(22L, merged.artifacts.first { it.sourceId == "files:b" }.sizeBytes)
        assertEquals(100L, merged.createdAtMillis)
        assertEquals(400L, merged.finishedAtMillis)
    }

    @Test
    fun runsOfDifferentProfilesAreRejected() {
        val a = RestoreFixtures.manifest(listOf(artifact("files:a", 1)), profile = RestoreFixtures.profile(id = "p1"))
        val b = RestoreFixtures.manifest(listOf(artifact("files:b", 1)), profile = RestoreFixtures.profile(id = "p2"), finished = 5_000)
        assertInconsistent(listOf(ManifestRun("1", false, a), ManifestRun("2", false, b)))
    }

    @Test
    fun mixedManifestEncryptionIsRejected() {
        val a = RestoreFixtures.manifest(listOf(artifact("files:a", 1)))
        val b = RestoreFixtures.manifest(listOf(artifact("files:b", 1)), finished = 5_000)
        assertInconsistent(listOf(ManifestRun("1", false, a), ManifestRun("2", true, b)))
    }

    @Test
    fun differentMaskingParametersAreRejected() {
        val a = RestoreFixtures.manifest(listOf(artifact("files:a", 1)), masking = mapOf("scheme" to "hex", "salt" to "AA"))
        val b = RestoreFixtures.manifest(listOf(artifact("files:b", 1)), masking = mapOf("scheme" to "hex", "salt" to "BB"), finished = 5_000)
        assertInconsistent(listOf(ManifestRun("1", true, a), ManifestRun("2", true, b)))
    }

    @Test
    fun mixedArtifactEncryptionIsRejected() {
        val a = RestoreFixtures.manifest(listOf(artifact("files:a", 1, encrypted = true)))
        val b = RestoreFixtures.manifest(listOf(artifact("files:b", 1, encrypted = false)), finished = 5_000)
        assertInconsistent(listOf(ManifestRun("1", false, a), ManifestRun("2", false, b)))
    }

    @Test
    fun differentEncryptionSchemesAreRejectedEvenInOneRun() {
        val plain = artifact("files:a", 1, encrypted = true)
        val other = artifact("files:b", 1).copy(encryption = ManifestEncryption("other-scheme", emptyMap()))
        assertInconsistent(listOf(ManifestRun("1", false, RestoreFixtures.manifest(listOf(plain, other)))))
    }

    @Test
    fun nandroidArtifactsDoNotCountForEncryptionConsistency() {
        val encrypted = artifact("files:a", 1, encrypted = true)
        val twrp = artifact("nandroid:x", 1).copy(kind = "nandroid")
        val merged = ManifestMerger.merge(listOf(ManifestRun("1", false, RestoreFixtures.manifest(listOf(encrypted, twrp)))))
        assertEquals(2, merged.artifacts.size)
    }

    private fun assertInconsistent(runs: List<ManifestRun>) {
        try {
            ManifestMerger.merge(runs)
            fail("Expected INCONSISTENT_RUNS")
        } catch (e: CatalogException) {
            assertEquals(CatalogException.Reason.INCONSISTENT_RUNS, e.reason)
        }
    }

    @Test
    fun carriedForwardArtifactsDedupeAndPartialFollowsTheNewestRun() {
        val a = artifact("files:a", 1)
        val interrupted = RestoreFixtures.manifest(listOf(a), finished = 10).copy(partial = true)
        // The resumed run repeats the earlier artifact verbatim and adds the rest.
        val resumed = RestoreFixtures.manifest(listOf(a, artifact("files:b", 2)), finished = 20)
        val merged = ManifestMerger.merge(listOf(ManifestRun("1", false, interrupted), ManifestRun("2", false, resumed)))
        assertEquals(listOf("files:a", "files:b"), merged.artifacts.map { it.sourceId })
        assertFalse(merged.partial)

        val stillPartial = ManifestMerger.merge(
            listOf(ManifestRun("1", false, resumed), ManifestRun("2", false, interrupted.copy(finishedAtMillis = 30))),
        )
        assertTrue(stillPartial.partial)
    }
}
