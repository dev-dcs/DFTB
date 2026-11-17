package ca.devdcs.backup.ui.restore

import androidx.compose.ui.state.ToggleableState
import ca.devdcs.backup.core.restore.ConflictPolicy
import ca.devdcs.backup.core.restore.apps.AppVersionPolicy
import ca.devdcs.backup.core.restore.engine.RestoreUnitKind
import ca.devdcs.backup.core.restore.files.FileRestoreTarget
import ca.devdcs.backup.core.restore.files.TwrpRestoreMode
import ca.devdcs.backup.core.restore.partition.SlotChoice
import ca.devdcs.backup.ui.restore.RestoreTestData.unit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreWizardRulesTest {

    private val app = unit("apps:a", RestoreUnitKind.APP)
    private val app2 = unit("apps:b", RestoreUnitKind.APP)
    private val boot = unit("partitions:boot", RestoreUnitKind.PARTITION)
    private val docs = unit("files:docs", RestoreUnitKind.FILE_TREE)
    private val twrp = unit("nandroid:x", RestoreUnitKind.FILE_TREE, nandroid = true)
    private val all = listOf(app, app2, boot, docs, twrp)

    // --- selection -----------------------------------------------------------------------------

    @Test
    fun `unset selection means every unit, unknown ids are dropped`() {
        assertEquals(all.map { it.id }.toSet(), RestoreWizardRules.effectiveSelection(RestoreWizardInput(), all))
        val input = RestoreWizardInput(selected = setOf("apps:a", "gone"))
        assertEquals(setOf("apps:a"), RestoreWizardRules.effectiveSelection(input, all))
    }

    @Test
    fun `toggling a unit adds and removes it`() {
        var input = RestoreWizardInput(selected = emptySet())
        input = RestoreWizardRules.toggleUnit(input, all, "apps:a")
        assertEquals(setOf("apps:a"), input.selected)
        input = RestoreWizardRules.toggleUnit(input, all, "apps:a")
        assertEquals(emptySet<String>(), input.selected)
    }

    @Test
    fun `toggling a kind selects all of it, then clears it`() {
        var input = RestoreWizardInput(selected = setOf("apps:a", "files:docs"))
        assertEquals(ToggleableState.Indeterminate, RestoreWizardRules.kindState(input, all, RestoreUnitKind.APP))
        input = RestoreWizardRules.toggleKind(input, all, RestoreUnitKind.APP)
        assertEquals(setOf("apps:a", "apps:b", "files:docs"), input.selected)
        assertEquals(ToggleableState.On, RestoreWizardRules.kindState(input, all, RestoreUnitKind.APP))
        input = RestoreWizardRules.toggleKind(input, all, RestoreUnitKind.APP)
        assertEquals(setOf("files:docs"), input.selected)
        assertEquals(ToggleableState.Off, RestoreWizardRules.kindState(input, all, RestoreUnitKind.APP))
    }

    @Test
    fun `toggling a kind without units changes nothing`() {
        val input = RestoreWizardInput(selected = setOf("apps:a"))
        assertEquals(input, RestoreWizardRules.toggleKind(input, listOf(app), RestoreUnitKind.PARTITION))
    }

    // --- validation ----------------------------------------------------------------------------

    @Test
    fun `nothing selected is the only problem reported`() {
        assertEquals(listOf(WizardProblem.NOTHING_SELECTED), RestoreWizardRules.validate(RestoreWizardInput(selected = emptySet()), all))
    }

    @Test
    fun `folder target needs an absolute safe path`() {
        fun problems(path: String) = RestoreWizardRules.validate(
            RestoreWizardInput(selected = setOf(docs.id), fileTarget = FileTargetKind.FOLDER, folderPath = path),
            all,
        )
        assertEquals(listOf(WizardProblem.FOLDER_REQUIRED), problems("  "))
        assertEquals(listOf(WizardProblem.FOLDER_NOT_ABSOLUTE), problems("storage/x"))
        assertEquals(listOf(WizardProblem.FOLDER_TRAVERSAL), problems("/storage/../system"))
        assertEquals(listOf(WizardProblem.FOLDER_PROTECTED), problems("/system/app"))
        assertEquals(listOf(WizardProblem.FOLDER_PROTECTED), problems("/"))
        assertEquals(listOf(WizardProblem.FOLDER_IN_APP_DATA), problems("/data/data/com.x"))
        assertEquals(emptyList<WizardProblem>(), problems("/data/media/0/Restored"))
        assertEquals(emptyList<WizardProblem>(), problems("/storage/emulated/0/Restored/"))
    }

    @Test
    fun `app data warning does not block`() {
        val problems = RestoreWizardRules.folderProblems("/data/local/tmp/x")
        assertEquals(listOf(WizardProblem.FOLDER_IN_APP_DATA), problems)
        assertTrue(RestoreWizardRules.blocking(problems).isEmpty())
    }

    @Test
    fun `folder problems only matter when file units are selected`() {
        val input = RestoreWizardInput(selected = setOf(app.id), fileTarget = FileTargetKind.FOLDER, folderPath = "")
        assertEquals(emptyList<WizardProblem>(), RestoreWizardRules.validate(input, all))
    }

    @Test
    fun `saf target needs a tree`() {
        val input = RestoreWizardInput(selected = setOf(docs.id), fileTarget = FileTargetKind.SAF_TREE)
        assertEquals(listOf(WizardProblem.TREE_REQUIRED), RestoreWizardRules.validate(input, all))
        assertEquals(emptyList<WizardProblem>(), RestoreWizardRules.validate(input.copy(treeUri = "content://tree/1"), all))
    }

    @Test
    fun `extracting twrp volumes needs a chosen folder`() {
        val input = RestoreWizardInput(selected = setOf(twrp.id), twrpMode = TwrpRestoreMode.EXTRACT_VOLUMES)
        assertEquals(listOf(WizardProblem.TWRP_EXTRACT_NEEDS_FOLDER), RestoreWizardRules.validate(input, all))
        val ok = input.copy(fileTarget = FileTargetKind.FOLDER, folderPath = "/storage/emulated/0/TWRP-x")
        assertEquals(emptyList<WizardProblem>(), RestoreWizardRules.validate(ok, all))
        // Copying the folder (the default) works for the earlier build location.
        assertEquals(emptyList<WizardProblem>(), RestoreWizardRules.validate(input.copy(twrpMode = TwrpRestoreMode.COPY_FOLDER), all))
    }

    @Test
    fun `apps need an apk or data to restore`() {
        val base = RestoreWizardInput(selected = setOf(app.id))
        assertEquals(listOf(WizardProblem.APP_NOTHING_TO_RESTORE), RestoreWizardRules.validate(base.copy(restoreApks = false, restoreAppData = false), all))
        assertEquals(
            listOf(WizardProblem.APP_NOTHING_TO_RESTORE),
            RestoreWizardRules.validate(base.copy(versionPolicy = AppVersionPolicy.DATA_ONLY, restoreAppData = false), all),
        )
        assertEquals(emptyList<WizardProblem>(), RestoreWizardRules.validate(base, all))
    }

    @Test
    fun `downgrade without installing is a non-blocking notice`() {
        val input = RestoreWizardInput(selected = setOf(app.id), allowDowngrade = true, versionPolicy = AppVersionPolicy.DATA_ONLY)
        val problems = RestoreWizardRules.validate(input, all)
        assertEquals(listOf(WizardProblem.DOWNGRADE_IGNORED), problems)
        assertTrue(RestoreWizardRules.blocking(problems).isEmpty())
    }

    // --- steps ---------------------------------------------------------------------------------

    @Test
    fun `steps advance only when their problems allow it`() {
        assertEquals(RestoreStep.SELECT, RestoreWizardRules.next(RestoreStep.DETAILS, emptyList()))
        assertEquals(RestoreStep.SELECT, RestoreWizardRules.next(RestoreStep.SELECT, listOf(WizardProblem.NOTHING_SELECTED)))
        assertEquals(RestoreStep.OPTIONS, RestoreWizardRules.next(RestoreStep.SELECT, listOf(WizardProblem.TREE_REQUIRED)))
        assertEquals(RestoreStep.OPTIONS, RestoreWizardRules.next(RestoreStep.OPTIONS, listOf(WizardProblem.TREE_REQUIRED)))
        assertEquals(RestoreStep.PREFLIGHT, RestoreWizardRules.next(RestoreStep.OPTIONS, listOf(WizardProblem.DOWNGRADE_IGNORED)))
        assertEquals(RestoreStep.PREFLIGHT, RestoreWizardRules.next(RestoreStep.PREFLIGHT, emptyList()))
        assertEquals(RestoreStep.OPTIONS, RestoreWizardRules.previous(RestoreStep.PREFLIGHT))
        assertEquals(RestoreStep.SELECT, RestoreWizardRules.previous(RestoreStep.OPTIONS))
        assertEquals(RestoreStep.DETAILS, RestoreWizardRules.previous(RestoreStep.SELECT))
        assertEquals(RestoreStep.DETAILS, RestoreWizardRules.previous(RestoreStep.DETAILS))
    }

    // --- request building ----------------------------------------------------------------------

    @Test
    fun `options carry explicit partition gates and app choices`() {
        val input = RestoreWizardInput(
            conflictPolicy = ConflictPolicy.OVERWRITE,
            restoreMetadata = false,
            verifyBeforeRestore = false,
            ignoreInsufficientSpace = true,
            restoreApks = false,
            targetUserId = 10,
            versionPolicy = AppVersionPolicy.REINSTALL,
            allowDowngrade = true,
            restoreExternalData = false,
            restoreObb = false,
            restorePermissions = false,
            restoreCache = true,
            restoreInstaller = true,
            partition = PartitionOverrides(slotChoice = SlotChoice.INACTIVE, allowOtherDevice = true, allowCriticalIdentity = true),
        )
        val options = RestoreWizardRules.options(input)
        assertEquals(ConflictPolicy.OVERWRITE, options.conflictPolicy)
        assertFalse(options.restoreMetadata)
        assertFalse(options.verifyBeforeRestore)
        assertTrue(options.ignoreInsufficientSpace)
        assertFalse(options.restoreApks)
        assertTrue(options.restoreAppData)
        assertTrue(options.allowCrossDevice)
        val partition = options.partition!!
        assertEquals(SlotChoice.INACTIVE, partition.slotChoice)
        assertTrue(partition.allowOtherDevice)
        assertTrue(partition.allowCriticalIdentity)
        assertFalse(partition.allowCrossSlot)
        assertFalse(partition.allowOtherBuild)
        assertFalse(partition.allowSmallerImage)
        val app = options.app!!
        assertEquals(10, app.targetUserId)
        assertEquals(AppVersionPolicy.REINSTALL, app.versionPolicy)
        assertTrue(app.allowDowngrade)
        assertFalse(app.restoreExternalData)
        assertFalse(app.restoreObb)
        assertFalse(app.restoreRuntimePermissions)
        assertTrue(app.restoreCache)
        assertTrue(app.restoreInstallerAttribution)
        // Never reachable from the wizard: signature and protected-package overrides stay off.
        assertFalse(app.allowSignatureMismatch)
        assertFalse(app.allowProtectedPackages)
    }

    @Test
    fun `default options are the safe ones`() {
        val options = RestoreWizardRules.options(RestoreWizardInput())
        assertEquals(ConflictPolicy.SKIP, options.conflictPolicy)
        assertFalse(options.ignoreInsufficientSpace)
        assertFalse(options.allowCrossDevice)
        assertTrue(options.verifyBeforeRestore)
        assertFalse(options.partition!!.allowCriticalIdentity)
        assertEquals(SlotChoice.SAME_ROLE, options.partition!!.slotChoice)
    }

    @Test
    fun `file spec maps the chosen target`() {
        assertEquals(FileRestoreTarget.OriginalLocation, RestoreWizardRules.fileSpec(RestoreWizardInput()).target)
        val folder = RestoreWizardRules.fileSpec(RestoreWizardInput(fileTarget = FileTargetKind.FOLDER, folderPath = " /storage//x/ "))
        assertEquals(FileRestoreTarget.Folder("/storage/x"), folder.target)
        val tree = RestoreWizardRules.fileSpec(RestoreWizardInput(fileTarget = FileTargetKind.SAF_TREE, treeUri = "content://t", twrpMode = TwrpRestoreMode.EXTRACT_VOLUMES))
        assertEquals(FileRestoreTarget.SafTree("content://t"), tree.target)
        assertEquals(TwrpRestoreMode.EXTRACT_VOLUMES, tree.twrpMode)
    }

    // --- risky options -------------------------------------------------------------------------

    @Test
    fun `every risky option round-trips through the input`() {
        for (option in RiskyOption.entries) {
            val on = RestoreWizardInput().with(option, true)
            assertTrue(option.name, on.isOn(option))
            assertFalse(option.name, on.with(option, false).isOn(option))
            RiskyOption.entries.filter { it != option }.forEach { other -> assertFalse("$option leaked into $other", on.isOn(other)) }
        }
    }

    @Test
    fun `only identity and other-device overrides need a typed word`() {
        assertEquals(setOf(RiskyOption.OTHER_DEVICE, RiskyOption.CRITICAL_IDENTITY), RiskyOption.entries.filter { it.typedWord }.toSet())
    }

    @Test
    fun `active risky options are limited to what the selection uses`() {
        val input = RestoreWizardInput(selected = setOf(app.id), allowDowngrade = true, ignoreInsufficientSpace = true)
            .with(RiskyOption.CRITICAL_IDENTITY, true)
        assertEquals(listOf(RiskyOption.IGNORE_SPACE, RiskyOption.ALLOW_DOWNGRADE), RestoreWizardRules.activeRiskyOptions(input, all))
        val withPartition = input.copy(selected = setOf(app.id, boot.id))
        assertTrue(RiskyOption.CRITICAL_IDENTITY in RestoreWizardRules.activeRiskyOptions(withPartition, all))
    }

    // --- persistence ---------------------------------------------------------------------------

    @Test
    fun `input survives encode and decode`() {
        val input = RestoreWizardInput(
            step = RestoreStep.OPTIONS,
            selected = setOf("apps:a", "files:docs"),
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            fileTarget = FileTargetKind.SAF_TREE,
            treeUri = "content://tree/primary%3ARestore",
            treeGrantedHere = true,
            twrpMode = TwrpRestoreMode.EXTRACT_VOLUMES,
            targetUserId = 11,
            versionPolicy = AppVersionPolicy.DATA_ONLY,
            partition = PartitionOverrides(slotChoice = SlotChoice.RECORDED, allowCrossSlot = true),
        )
        assertEquals(input, RestoreWizardInput.decode(RestoreWizardInput.encode(input)))
    }

    @Test
    fun `corrupt or missing input starts a fresh wizard`() {
        assertEquals(RestoreWizardInput(), RestoreWizardInput.decode(null))
        assertEquals(RestoreWizardInput(), RestoreWizardInput.decode(""))
        assertEquals(RestoreWizardInput(), RestoreWizardInput.decode("{not json"))
        assertEquals(RestoreWizardInput(), RestoreWizardInput.decode("""{"step":"NO_SUCH_STEP"}"""))
    }
}
