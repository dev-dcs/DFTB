package ca.devdcs.backup.ui.profiles

import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.domain.model.AppComponent
import ca.devdcs.backup.domain.model.AppTarget
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.FileFilter
import ca.devdcs.backup.domain.model.PartitionTarget
import ca.devdcs.backup.domain.model.PathTarget
import ca.devdcs.backup.domain.model.ScheduleFrequency
import ca.devdcs.backup.domain.model.SchedulePolicy
import ca.devdcs.backup.domain.model.TargetSelection
import ca.devdcs.backup.domain.model.TransferMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftValidatorTest {

    private val context = ValidationContext(
        otherProfileNames = setOf(DraftValidator.nameKey("Photos")),
        destinationTypes = mapOf("local" to DestinationType.LOCAL_INTERNAL, "nas" to DestinationType.SMB, "box" to DestinationType.SFTP),
        componentAvailable = AppComponent.entries.associateWith { it == AppComponent.APK || it == AppComponent.EXTERNAL_DATA },
        tier = ElevationTier.SHIZUKU,
    )

    /** A draft with nothing wrong (warnings aside). */
    private fun valid(): ProfileDraft = ProfileDraft(
        profileId = "p1",
        isNew = true,
        createdAt = 0L,
        name = "Documents",
        targets = TargetSelection(paths = listOf(PathTarget("/storage/emulated/0/Documents"))),
        destinationId = "local",
    )

    private fun problems(draft: ProfileDraft, ctx: ValidationContext = context) = DraftValidator.validate(draft, ctx)

    @Test
    fun `a complete draft has no problems`() {
        assertEquals(emptyList<DraftProblem>(), problems(valid()))
        assertNull(DraftValidator.firstInvalidStep(problems(valid())))
    }

    @Test
    fun `name is required, bounded and unique ignoring case and spacing`() {
        assertTrue(DraftProblem.NAME_BLANK in problems(valid().copy(name = "   ")))
        assertTrue(DraftProblem.NAME_TOO_LONG in problems(valid().copy(name = "x".repeat(DraftValidator.MAX_NAME_LENGTH + 1))))
        assertTrue(DraftProblem.NAME_DUPLICATE in problems(valid().copy(name = "  photos ")))
        assertTrue(DraftProblem.DESCRIPTION_TOO_LONG in problems(valid().copy(description = "d".repeat(DraftValidator.MAX_DESCRIPTION_LENGTH + 1))))
        assertEquals("my  photos".let { DraftValidator.nameKey(it) }, DraftValidator.nameKey(" My\tPhotos "))
    }

    @Test
    fun `targets must exist and be well formed`() {
        assertTrue(DraftProblem.NO_TARGETS in problems(valid().copy(targets = TargetSelection.EMPTY)))
        val noComponents = valid().copy(targets = TargetSelection(apps = listOf(AppTarget("a.b", emptySet()))))
        assertTrue(DraftProblem.APP_WITHOUT_COMPONENTS in problems(noComponents))
        val relative = valid().copy(targets = TargetSelection(paths = listOf(PathTarget("Documents"))))
        assertTrue(DraftProblem.PATH_INVALID in problems(relative))
        val escaping = valid().copy(targets = TargetSelection(paths = listOf(PathTarget("/sdcard/../data"))))
        assertTrue(DraftProblem.PATH_INVALID in problems(escaping))
        val duplicate = valid().copy(targets = TargetSelection(paths = listOf(PathTarget("/sdcard/DCIM"), PathTarget("/sdcard/DCIM/"))))
        assertTrue(DraftProblem.PATH_DUPLICATE in problems(duplicate))
        val badFilter = valid().copy(targets = TargetSelection(paths = listOf(PathTarget("/sdcard", filter = FileFilter(includeRegex = "(")))))
        assertTrue(DraftProblem.FILTER_INVALID in problems(badFilter))
    }

    @Test
    fun `saf locations are valid`() {
        assertTrue(DraftValidator.isValidLocation("content://com.android.externalstorage.documents/tree/primary%3ADCIM"))
        assertFalse(DraftValidator.isValidLocation("content://"))
        assertTrue(DraftValidator.isValidLocation("/"))
        assertFalse(DraftValidator.isValidLocation("./x"))
    }

    @Test
    fun `environment limits are warnings, not errors`() {
        val draft = valid().copy(
            targets = TargetSelection(
                partitions = listOf(PartitionTarget("boot")),
                apps = listOf(AppTarget("a.b", setOf(AppComponent.APK, AppComponent.INTERNAL_DATA))),
            ),
        )
        val found = problems(draft)
        assertTrue(DraftProblem.COMPONENTS_UNAVAILABLE in found)
        assertTrue(DraftProblem.PARTITIONS_NEED_ROOT in found)
        assertFalse(DraftValidator.hasErrors(found, WizardStep.TARGETS))
        // Unknown environment: nothing is claimed.
        val unknown = problems(draft, context.copy(componentAvailable = null, tier = null))
        assertFalse(DraftProblem.COMPONENTS_UNAVAILABLE in unknown)
        assertFalse(DraftProblem.PARTITIONS_NEED_ROOT in unknown)
        assertFalse(DraftProblem.PARTITIONS_NEED_ROOT in problems(draft, context.copy(tier = ElevationTier.ROOT)))
    }

    @Test
    fun `destination must be chosen and still exist`() {
        assertTrue(DraftProblem.NO_DESTINATION in problems(valid().copy(destinationId = null)))
        assertTrue(DraftProblem.DESTINATION_GONE in problems(valid().copy(destinationId = "deleted")))
        // Destinations not loaded yet: no false "gone".
        assertFalse(DraftProblem.DESTINATION_GONE in problems(valid().copy(destinationId = "deleted"), context.copy(destinationTypes = null)))
    }

    @Test
    fun `option conflicts`() {
        val legacy = valid().copy(unsupportedEncryption = "gpg")
        assertTrue(DraftProblem.ENCRYPTION_UNSUPPORTED in problems(legacy))
        val remote = valid().copy(destinationId = "box", pipeline = valid().pipeline.copy(transferMode = TransferMode.REMOTE_COMPRESS))
        assertTrue(DraftProblem.TRANSFER_UNSUPPORTED in problems(remote))
        val twrpNetwork = valid().copy(destinationId = "nas", pipeline = valid().pipeline.copy(twrpCompatible = true))
        val twrpProblems = problems(twrpNetwork)
        assertTrue(DraftProblem.TWRP_NOT_LOCAL in twrpProblems)
        assertTrue(DraftProblem.TWRP_WITHOUT_PARTITIONS in twrpProblems)
        assertFalse(DraftValidator.hasErrors(twrpProblems, WizardStep.OPTIONS))
    }

    @Test
    fun `a missing vault key is reported only for vault key profiles`() {
        val vault = valid().copy(keyChoice = KeyChoice.VAULT_KEY, existingKeyRef = "enckey-1")
        assertTrue(DraftProblem.VAULT_KEY_MISSING in problems(vault, context.copy(vaultKeyMissing = true)))
        assertFalse(DraftProblem.VAULT_KEY_MISSING in problems(vault, context))
        assertFalse(DraftProblem.VAULT_KEY_MISSING in problems(vault.copy(keyChoice = KeyChoice.NONE), context.copy(vaultKeyMissing = true)))
    }

    @Test
    fun `schedule rules apply only when scheduling is on`() {
        val weeklyNoDays = valid().copy(schedule = SchedulePolicy(frequency = ScheduleFrequency.WEEKLY, daysOfWeek = emptySet()))
        assertFalse(DraftProblem.SCHEDULE_NO_DAYS in problems(weeklyNoDays))
        assertTrue(DraftProblem.SCHEDULE_NO_DAYS in problems(weeklyNoDays.copy(scheduleEnabled = true)))

        val interval = valid().copy(scheduleEnabled = true, schedule = SchedulePolicy(frequency = ScheduleFrequency.INTERVAL))
        assertTrue(DraftProblem.INTERVAL_INVALID in problems(interval.copy(intervalText = "")))
        assertTrue(DraftProblem.INTERVAL_INVALID in problems(interval.copy(intervalText = "0")))
        assertTrue(DraftProblem.SCHEDULE_INTERVAL_TOO_SHORT in problems(interval.copy(intervalText = "10", intervalUnit = IntervalUnit.MINUTES)))
        assertFalse(DraftValidator.hasErrors(problems(interval.copy(intervalText = "1", intervalUnit = IntervalUnit.HOURS)), WizardStep.SCHEDULE))

        val battery = valid().copy(scheduleEnabled = true, schedule = SchedulePolicy(minBatteryPercent = 140))
        assertTrue(DraftProblem.SCHEDULE_BATTERY_INVALID in problems(battery))
        val time = valid().copy(scheduleEnabled = true, schedule = SchedulePolicy(hour = 25))
        assertTrue(DraftProblem.SCHEDULE_TIME_INVALID in problems(time))
    }

    @Test
    fun `retention limits must be positive whole numbers when enabled`() {
        assertTrue(DraftProblem.KEEP_LAST_INVALID in problems(valid().copy(keepLastEnabled = true, keepLastText = "0")))
        assertTrue(DraftProblem.MAX_AGE_INVALID in problems(valid().copy(maxAgeEnabled = true, maxAgeText = "x")))
        assertFalse(DraftProblem.KEEP_LAST_INVALID in problems(valid().copy(keepLastEnabled = false, keepLastText = "0")))
        assertEquals(emptyList<DraftProblem>(), problems(valid().copy(keepLastEnabled = true, keepLastText = "3", maxAgeEnabled = true, maxAgeText = "30")))
    }

    @Test
    fun `scheduled passphrase profiles get a warning`() {
        val draft = valid().copy(scheduleEnabled = true, keyChoice = KeyChoice.PASSPHRASE)
        val found = problems(draft)
        assertTrue(DraftProblem.PASSPHRASE_SCHEDULED in found)
        assertFalse(DraftValidator.hasErrors(found, WizardStep.SCHEDULE))
        // TWRP forces no encryption, so no prompt is ever needed.
        assertFalse(DraftProblem.PASSPHRASE_SCHEDULED in problems(draft.copy(pipeline = draft.pipeline.copy(twrpCompatible = true))))
    }

    @Test
    fun `first invalid step is the earliest step with an error`() {
        val draft = valid().copy(name = "", destinationId = null)
        val found = problems(draft)
        assertEquals(WizardStep.NAME, DraftValidator.firstInvalidStep(found))
        assertEquals(listOf(DraftProblem.NO_DESTINATION), DraftValidator.forStep(found, WizardStep.DESTINATION))
        val warningsOnly = listOf(DraftProblem.PARTITIONS_NEED_ROOT, DraftProblem.PASSPHRASE_SCHEDULED)
        assertNull(DraftValidator.firstInvalidStep(warningsOnly))
    }
}
