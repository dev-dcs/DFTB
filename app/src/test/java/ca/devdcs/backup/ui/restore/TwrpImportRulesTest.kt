package ca.devdcs.backup.ui.restore

import ca.devdcs.backup.core.restore.partition.SlotChoice
import ca.devdcs.backup.core.restore.twrp.TwrpCheckStatus
import ca.devdcs.backup.core.restore.twrp.TwrpFile
import ca.devdcs.backup.core.restore.twrp.TwrpFileCheck
import ca.devdcs.backup.core.restore.twrp.TwrpPartitionBackup
import ca.devdcs.backup.core.restore.twrp.TwrpPayloadFormat
import ca.devdcs.backup.core.restore.twrp.TwrpPayloadKind
import ca.devdcs.backup.core.restore.twrp.TwrpSidecar
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwrpImportRulesTest {

    private fun file(name: String, split: Int? = null, sidecar: Boolean = true) = TwrpFile(
        fileName = name,
        path = "TWRP/BACKUPS/serial/b/$name",
        sizeBytes = 100L,
        splitIndex = split,
        sidecar = if (sidecar) TwrpSidecar("$name.sha2", "TWRP/BACKUPS/serial/b/$name.sha2", ChecksumAlgorithm.SHA256) else null,
    )

    private val boot = TwrpPartitionBackup("boot", "emmc", TwrpPayloadKind.RAW_IMAGE, listOf(file("boot.emmc.win")), null)
    private val efs = TwrpPartitionBackup("efs", "emmc", TwrpPayloadKind.RAW_IMAGE, listOf(file("efs.emmc.win")), null)
    private val splitImage = TwrpPartitionBackup(
        "system_image",
        "emmc",
        TwrpPayloadKind.RAW_IMAGE,
        listOf(file("system_image.emmc.win000", 0), file("system_image.emmc.win001", 1)),
        null,
    )
    private val data = TwrpPartitionBackup("data", "f2fs", TwrpPayloadKind.FILESYSTEM_ARCHIVE, listOf(file("data.f2fs.win000", 0), file("data.f2fs.win001", 1, sidecar = false)), null)

    private fun restore(target: String = "") = TwrpActionDraft("b", "boot", TwrpAction.RESTORE_IMAGE, targetPartition = target)
    private fun extract(dest: String? = "d1", folder: String = "x/y") = TwrpActionDraft("b", "data", TwrpAction.EXTRACT_FILES, extractDestinationId = dest, extractFolder = folder)

    @Test
    fun `raw image restore is valid by default`() {
        assertEquals(emptyList<TwrpDraftProblem>(), TwrpImportRules.problems(restore(), boot, TwrpPayloadFormat.RAW))
        assertEquals(emptyList<TwrpDraftProblem>(), TwrpImportRules.problems(restore(), boot, null))
    }

    @Test
    fun `restore refuses archives, split images, encrypted payloads and bad names`() {
        assertEquals(listOf(TwrpDraftProblem.NOT_RAW_IMAGE), TwrpImportRules.problems(restore(), data.copy(parts = listOf(file("data.f2fs.win"))), TwrpPayloadFormat.TAR))
        assertEquals(listOf(TwrpDraftProblem.SPLIT_IMAGE), TwrpImportRules.problems(restore(), splitImage, TwrpPayloadFormat.RAW))
        assertTrue(TwrpDraftProblem.ENCRYPTED in TwrpImportRules.problems(restore(), boot, TwrpPayloadFormat.ENCRYPTED))
        assertEquals(listOf(TwrpDraftProblem.INVALID_PARTITION_NAME), TwrpImportRules.problems(restore("../boot"), boot, TwrpPayloadFormat.RAW))
        assertEquals(listOf(TwrpDraftProblem.INVALID_PARTITION_NAME), TwrpImportRules.problems(restore(".."), boot, TwrpPayloadFormat.RAW))
        assertEquals(emptyList<TwrpDraftProblem>(), TwrpImportRules.problems(restore("boot_b"), boot, TwrpPayloadFormat.RAW))
    }

    @Test
    fun `extraction needs an archive, a readable format, a destination and a safe folder`() {
        assertEquals(emptyList<TwrpDraftProblem>(), TwrpImportRules.problems(extract(), data, TwrpPayloadFormat.GZIP))
        assertEquals(listOf(TwrpDraftProblem.NOT_ARCHIVE), TwrpImportRules.problems(extract(), boot.copy(backupName = "data"), TwrpPayloadFormat.RAW))
        assertEquals(listOf(TwrpDraftProblem.UNKNOWN_FORMAT), TwrpImportRules.problems(extract(), data, TwrpPayloadFormat.UNKNOWN))
        assertEquals(listOf(TwrpDraftProblem.UNKNOWN_FORMAT), TwrpImportRules.problems(extract(), data, TwrpPayloadFormat.EMPTY))
        assertEquals(listOf(TwrpDraftProblem.TARGET_DESTINATION_REQUIRED), TwrpImportRules.problems(extract(dest = null), data, TwrpPayloadFormat.TAR))
        assertEquals(listOf(TwrpDraftProblem.FOLDER_TRAVERSAL), TwrpImportRules.problems(extract(folder = "a/../b"), data, TwrpPayloadFormat.TAR))
        assertTrue(TwrpDraftProblem.ENCRYPTED in TwrpImportRules.problems(extract(), data, TwrpPayloadFormat.ENCRYPTED))
    }

    @Test
    fun `target partition defaults to the image's partition`() {
        assertEquals("system", TwrpImportRules.targetPartition(restore(), splitImage))
        assertEquals("boot_a", TwrpImportRules.targetPartition(restore(" boot_a "), boot))
    }

    @Test
    fun `identity partitions are recognised`() {
        assertTrue(TwrpImportRules.isCriticalIdentity("efs"))
        assertTrue(TwrpImportRules.isCriticalIdentity("modemst1"))
        assertTrue(TwrpImportRules.isCriticalIdentity(efs.partitionName))
        assertFalse(TwrpImportRules.isCriticalIdentity("boot"))
    }

    @Test
    fun `actions follow the payload kind`() {
        assertEquals(listOf(TwrpAction.RESTORE_IMAGE), TwrpImportRules.actionsFor(boot))
        assertEquals(listOf(TwrpAction.EXTRACT_FILES), TwrpImportRules.actionsFor(data))
    }

    @Test
    fun `default extract folder is relative and safe`() {
        assertEquals("TWRP-extracted/2026-10-01--12-00-00/data", TwrpImportRules.defaultExtractFolder("2026-10-01--12-00-00", data))
        assertEquals("TWRP-extracted/_/data", TwrpImportRules.defaultExtractFolder("..", data))
        assertEquals("TWRP-extracted/a_b/data", TwrpImportRules.defaultExtractFolder("a/b", data))
        assertEquals("a/b", TwrpImportRules.normalizeFolder(" /a//b/ "))
    }

    @Test
    fun `sidecar checks are summarised and filtered per partition`() {
        val checks = listOf(
            TwrpFileCheck(boot.parts[0], TwrpCheckStatus.OK, ChecksumAlgorithm.SHA256, "a", "a"),
            TwrpFileCheck(data.parts[0], TwrpCheckStatus.MISMATCH, ChecksumAlgorithm.SHA256, "a", "b"),
            TwrpFileCheck(data.parts[1], TwrpCheckStatus.NO_SIDECAR, null, null, null),
            TwrpFileCheck(efs.parts[0], TwrpCheckStatus.READ_FAILED, ChecksumAlgorithm.SHA256, "a", null, "io"),
        )
        val summary = TwrpImportRules.summarize(checks)
        assertEquals(TwrpVerifySummary(total = 4, ok = 1, mismatched = 1, missingSidecar = 1, unreadable = 1), summary)
        assertTrue(summary.damaged)
        assertFalse(summary.allOk)
        assertEquals(2, TwrpImportRules.checksOf(checks, data).size)
        assertTrue(TwrpImportRules.summarize(checks.take(1)).allOk)
    }

    @Test
    fun `draft survives encode and decode, garbage decodes to nothing`() {
        val draft = restore("boot_b").copy(overrides = PartitionOverrides(slotChoice = SlotChoice.ACTIVE, allowOtherDevice = true))
        assertEquals(draft, TwrpActionDraft.decode(TwrpActionDraft.encode(draft)))
        assertNull(TwrpActionDraft.decode(null))
        assertNull(TwrpActionDraft.decode("[]"))
    }

    @Test
    fun `image task reboot warnings depend on bytes reaching the device`() {
        val base = TwrpTaskState("t", TwrpAction.RESTORE_IMAGE, "d", "b", "boot", "boot", startedAtMillis = 0L)
        assertFalse(base.writingDevice)
        assertTrue(base.copy(processedBytes = 10).writingDevice)
        assertFalse(base.copy(finished = true, ok = false).doNotReboot)
        assertTrue(base.copy(finished = true, ok = false, processedBytes = 10).doNotReboot)
        assertFalse(base.copy(finished = true, ok = true, processedBytes = 10).doNotReboot)
        val extract = base.copy(action = TwrpAction.EXTRACT_FILES, processedBytes = 10)
        assertFalse(extract.writingDevice)
        assertFalse(extract.copy(finished = true, ok = false).doNotReboot)
    }
}
