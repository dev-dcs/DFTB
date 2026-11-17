package ca.devdcs.backup.ui.settings

import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineDefaultsRulesTest {

    private fun defaults(
        format: ArchiveFormat = ArchiveFormat.TAR_ZST,
        level: Int = 3,
        checksum: ChecksumAlgorithm = ChecksumAlgorithm.SHA256,
        destinationId: String? = null,
    ) = PipelineDefaults(format, level, checksum, destinationId)

    @Test
    fun onlyArchiveContainersAreSelectable() {
        assertEquals(ArchiveFormat.entries.filter { it.isArchive }, PipelineDefaultsRules.selectableFormats)
        assertFalse(PipelineDefaultsRules.isSelectable(ArchiveFormat.RAW_COPY))
        assertFalse(PipelineDefaultsRules.isSelectable(ArchiveFormat.DD_IMAGE))
        assertTrue(PipelineDefaultsRules.isSelectable(ArchiveFormat.ZIP))
    }

    @Test
    fun levelRangeFollowsTheFormat() {
        assertEquals(0..19, PipelineDefaultsRules.levelRange(ArchiveFormat.TAR_ZST))
        assertEquals(0..9, PipelineDefaultsRules.levelRange(ArchiveFormat.TAR_GZ))
        assertEquals(0..0, PipelineDefaultsRules.levelRange(ArchiveFormat.TAR))
        assertFalse(PipelineDefaultsRules.levelApplies(ArchiveFormat.TAR))
    }

    @Test
    fun switchingToANarrowerScaleClampsTheLevel() {
        val next = PipelineDefaultsRules.withFormat(defaults(level = 19), ArchiveFormat.TAR_GZ)
        assertEquals(ArchiveFormat.TAR_GZ, next.archiveFormat)
        assertEquals(9, next.compressionLevel)
    }

    @Test
    fun switchingToALevelFreeFormatKeepsTheLevelForLater() {
        val tar = PipelineDefaultsRules.withFormat(defaults(level = 7), ArchiveFormat.TAR)
        assertEquals(7, tar.compressionLevel)
        val back = PipelineDefaultsRules.withFormat(tar, ArchiveFormat.TAR_XZ)
        assertEquals(7, back.compressionLevel)
    }

    @Test
    fun nonArchiveFormatsAreRefused() {
        val current = defaults()
        assertSame(current, PipelineDefaultsRules.withFormat(current, ArchiveFormat.DD_IMAGE))
    }

    @Test
    fun withLevelClampsAndIgnoresLevelFreeFormats() {
        assertEquals(9, PipelineDefaultsRules.withLevel(defaults(format = ArchiveFormat.ZIP), 42).compressionLevel)
        assertEquals(0, PipelineDefaultsRules.withLevel(defaults(format = ArchiveFormat.ZIP), -5).compressionLevel)
        val tar = defaults(format = ArchiveFormat.TAR, level = 3)
        assertSame(tar, PipelineDefaultsRules.withLevel(tar, 9))
    }

    @Test
    fun validCombinationHasNoIssues() {
        assertEquals(emptySet<DefaultsIssue>(), PipelineDefaultsRules.issues(defaults(destinationId = "d1"), setOf("d1", "d2")))
        assertEquals(emptySet<DefaultsIssue>(), PipelineDefaultsRules.issues(defaults(), emptySet()))
    }

    @Test
    fun everyInvalidCombinationIsReported() {
        assertEquals(setOf(DefaultsIssue.FORMAT_NOT_SELECTABLE), PipelineDefaultsRules.issues(defaults(format = ArchiveFormat.RAW_COPY), null))
        assertEquals(setOf(DefaultsIssue.LEVEL_OUT_OF_RANGE), PipelineDefaultsRules.issues(defaults(format = ArchiveFormat.TAR_BZ2, level = 15), null))
        assertEquals(setOf(DefaultsIssue.DESTINATION_MISSING), PipelineDefaultsRules.issues(defaults(destinationId = "gone"), setOf("d1")))
    }

    @Test
    fun unknownDestinationListIsNotReportedAsMissing() {
        assertEquals(emptySet<DefaultsIssue>(), PipelineDefaultsRules.issues(defaults(destinationId = "d1"), null))
    }

    @Test
    fun levelOfALevelFreeFormatIsNeverAnIssue() {
        assertEquals(emptySet<DefaultsIssue>(), PipelineDefaultsRules.issues(defaults(format = ArchiveFormat.TAR, level = 15), null))
    }

    @Test
    fun normalizeProducesTheNearestValidCombination() {
        val broken = defaults(format = ArchiveFormat.DD_IMAGE, level = 19, destinationId = "gone")
        val fixed = PipelineDefaultsRules.normalize(broken, setOf("d1"))
        assertEquals(ArchiveFormat.DEFAULT, fixed.archiveFormat)
        assertEquals(19, fixed.compressionLevel) // TAR_ZST accepts 19
        assertEquals(null, fixed.destinationId)
        assertEquals(emptySet<DefaultsIssue>(), PipelineDefaultsRules.issues(fixed, setOf("d1")))

        val gz = PipelineDefaultsRules.normalize(defaults(format = ArchiveFormat.TAR_GZ, level = 12, destinationId = "d1"), setOf("d1"))
        assertEquals(9, gz.compressionLevel)
        assertEquals("d1", gz.destinationId)
    }

    @Test
    fun onlySevenZipNeedsFullLocalStaging() {
        assertTrue(PipelineDefaultsRules.needsFullLocalStaging(ArchiveFormat.SEVEN_ZIP))
        ArchiveFormat.entries.filter { it != ArchiveFormat.SEVEN_ZIP }.forEach {
            assertFalse(it.name, PipelineDefaultsRules.needsFullLocalStaging(it))
        }
    }
}
