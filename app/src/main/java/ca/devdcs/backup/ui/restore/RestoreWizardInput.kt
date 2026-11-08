package ca.devdcs.backup.ui.restore

import androidx.compose.ui.state.ToggleableState
import ca.devdcs.backup.core.restore.ConflictPolicy
import ca.devdcs.backup.core.restore.apps.AppRestoreOptions
import ca.devdcs.backup.core.restore.apps.AppVersionPolicy
import ca.devdcs.backup.core.restore.engine.RestoreOptions
import ca.devdcs.backup.core.restore.engine.RestoreUnitKind
import ca.devdcs.backup.core.restore.files.FileRestoreSpec
import ca.devdcs.backup.core.restore.files.FileRestoreTarget
import ca.devdcs.backup.core.restore.files.TwrpRestoreMode
import ca.devdcs.backup.core.restore.partition.PartitionRestoreOptions
import ca.devdcs.backup.core.restore.partition.SlotChoice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the restore screen is: the backup overview or one step of the restore wizard. */
@Serializable
enum class RestoreStep { DETAILS, SELECT, OPTIONS, PREFLIGHT }

/** Where file units are written. */
@Serializable
enum class FileTargetKind { ORIGINAL, FOLDER, SAF_TREE }

/**
 * Restore choices that need their own explicit confirmation before they can be switched on. Exists
 * so the wizard and the TWRP import gate every dangerous override the same way; [typedWord] ones
 * additionally require typing a confirmation word because a mistake can permanently damage the
 * device (identity data, another device's images).
 */
enum class RiskyOption(val typedWord: Boolean, val partition: Boolean) {
    IGNORE_SPACE(typedWord = false, partition = false),
    ALLOW_DOWNGRADE(typedWord = false, partition = false),
    CROSS_SLOT(typedWord = false, partition = true),
    OTHER_DEVICE(typedWord = true, partition = true),
    OTHER_BUILD(typedWord = false, partition = true),
    SMALLER_IMAGE(typedWord = false, partition = true),
    CRITICAL_IDENTITY(typedWord = true, partition = true),
}

/**
 * Slot choice + per-gate partition overrides, persisted with wizard input. Every flag defaults to
 * the safe value. Exists separately so DFTB partition restores and TWRP image restores share it.
 */
@Serializable
data class PartitionOverrides(
    val slotChoice: SlotChoice = SlotChoice.SAME_ROLE,
    val allowCrossSlot: Boolean = false,
    val allowOtherDevice: Boolean = false,
    val allowOtherBuild: Boolean = false,
    val allowSmallerImage: Boolean = false,
    val allowCriticalIdentity: Boolean = false,
) {
    fun isOn(option: RiskyOption): Boolean = when (option) {
        RiskyOption.CROSS_SLOT -> allowCrossSlot
        RiskyOption.OTHER_DEVICE -> allowOtherDevice
        RiskyOption.OTHER_BUILD -> allowOtherBuild
        RiskyOption.SMALLER_IMAGE -> allowSmallerImage
        RiskyOption.CRITICAL_IDENTITY -> allowCriticalIdentity
        RiskyOption.IGNORE_SPACE, RiskyOption.ALLOW_DOWNGRADE -> false
    }

    fun with(option: RiskyOption, on: Boolean): PartitionOverrides = when (option) {
        RiskyOption.CROSS_SLOT -> copy(allowCrossSlot = on)
        RiskyOption.OTHER_DEVICE -> copy(allowOtherDevice = on)
        RiskyOption.OTHER_BUILD -> copy(allowOtherBuild = on)
        RiskyOption.SMALLER_IMAGE -> copy(allowSmallerImage = on)
        RiskyOption.CRITICAL_IDENTITY -> copy(allowCriticalIdentity = on)
        RiskyOption.IGNORE_SPACE, RiskyOption.ALLOW_DOWNGRADE -> this
    }

    val anyOverride: Boolean get() = allowCrossSlot || allowOtherDevice || allowOtherBuild || allowSmallerImage || allowCriticalIdentity

    fun toOptions(): PartitionRestoreOptions = PartitionRestoreOptions(
        slotChoice = slotChoice,
        allowCrossSlot = allowCrossSlot,
        allowOtherDevice = allowOtherDevice,
        allowOtherBuild = allowOtherBuild,
        allowSmallerImage = allowSmallerImage,
        allowCriticalIdentity = allowCriticalIdentity,
    )
}

/**
 * Everything the user chose in the restore wizard. Serializable so it lives in SavedStateHandle
 * and survives process death; holds no secret (the key stays in the ViewModel's memory only).
 * [selected] is null until the backup was opened once (then it defaults to every unit).
 */
@Serializable
data class RestoreWizardInput(
    val step: RestoreStep = RestoreStep.DETAILS,
    val selected: Set<String>? = null,
    // General
    val conflictPolicy: ConflictPolicy = ConflictPolicy.SKIP,
    val restoreMetadata: Boolean = true,
    val verifyBeforeRestore: Boolean = true,
    val ignoreInsufficientSpace: Boolean = false,
    // Files
    val fileTarget: FileTargetKind = FileTargetKind.ORIGINAL,
    val folderPath: String = "",
    val treeUri: String? = null,
    /** True when [treeUri]'s persisted grant was taken by this wizard (and must be released after). */
    val treeGrantedHere: Boolean = false,
    val twrpMode: TwrpRestoreMode = TwrpRestoreMode.COPY_FOLDER,
    // Apps
    val restoreApks: Boolean = true,
    val restoreAppData: Boolean = true,
    val targetUserId: Int? = null,
    val versionPolicy: AppVersionPolicy = AppVersionPolicy.AUTO,
    val allowDowngrade: Boolean = false,
    val restoreExternalData: Boolean = true,
    val restoreObb: Boolean = true,
    val restorePermissions: Boolean = true,
    val restoreCache: Boolean = false,
    val restoreInstaller: Boolean = false,
    // Partitions
    val partition: PartitionOverrides = PartitionOverrides(),
) {
    fun isOn(option: RiskyOption): Boolean = when (option) {
        RiskyOption.IGNORE_SPACE -> ignoreInsufficientSpace
        RiskyOption.ALLOW_DOWNGRADE -> allowDowngrade
        else -> partition.isOn(option)
    }

    fun with(option: RiskyOption, on: Boolean): RestoreWizardInput = when (option) {
        RiskyOption.IGNORE_SPACE -> copy(ignoreInsufficientSpace = on)
        RiskyOption.ALLOW_DOWNGRADE -> copy(allowDowngrade = on)
        else -> copy(partition = partition.with(option, on))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun encode(input: RestoreWizardInput): String = json.encodeToString(serializer(), input)

        /** Lenient decode: a corrupt or foreign value starts a fresh wizard instead of crashing. */
        fun decode(text: String?): RestoreWizardInput = if (text.isNullOrBlank()) {
            RestoreWizardInput()
        } else {
            try {
                json.decodeFromString(serializer(), text)
            } catch (e: IllegalArgumentException) {
                RestoreWizardInput()
            }
        }
    }
}

/** Why the wizard cannot continue ([blocking]) or what the user should know before it does. */
enum class WizardProblem(val blocking: Boolean) {
    NOTHING_SELECTED(true),
    FOLDER_REQUIRED(true),
    FOLDER_NOT_ABSOLUTE(true),
    FOLDER_TRAVERSAL(true),
    FOLDER_PROTECTED(true),
    TREE_REQUIRED(true),
    TWRP_EXTRACT_NEEDS_FOLDER(true),
    APP_NOTHING_TO_RESTORE(true),
    /** Downgrade is meaningless when only data is restored. */
    DOWNGRADE_IGNORED(false),
    /** Writing into /data outside shared storage can break installed apps. */
    FOLDER_IN_APP_DATA(false),
}

/** Pure selection, validation and request-building rules of the restore wizard. */
object RestoreWizardRules {

    /** Live system trees a file restore must never be pointed at. */
    private val PROTECTED_ROOTS = listOf("/proc", "/sys", "/dev", "/apex", "/system", "/system_ext", "/vendor", "/product", "/odm", "/metadata")
    private val SHARED_DATA_ROOTS = listOf("/data/media")

    /** Selection with unknown ids dropped; null (never chosen) means every unit. */
    fun effectiveSelection(input: RestoreWizardInput, units: List<RestoreUnitInfo>): Set<String> {
        val ids = units.map { it.id }.toSet()
        return input.selected?.intersect(ids) ?: ids
    }

    fun toggleUnit(input: RestoreWizardInput, units: List<RestoreUnitInfo>, unitId: String): RestoreWizardInput {
        val current = effectiveSelection(input, units)
        return input.copy(selected = if (unitId in current) current - unitId else current + unitId)
    }

    /** Selects every unit of [kind] unless all are selected already, in which case it clears them. */
    fun toggleKind(input: RestoreWizardInput, units: List<RestoreUnitInfo>, kind: RestoreUnitKind): RestoreWizardInput {
        val current = effectiveSelection(input, units)
        val ofKind = units.filter { it.kind == kind }.map { it.id }.toSet()
        if (ofKind.isEmpty()) return input
        val next = if (current.containsAll(ofKind)) current - ofKind else current + ofKind
        return input.copy(selected = next)
    }

    fun kindState(input: RestoreWizardInput, units: List<RestoreUnitInfo>, kind: RestoreUnitKind): ToggleableState {
        val current = effectiveSelection(input, units)
        val ofKind = units.filter { it.kind == kind }.map { it.id }
        val chosen = ofKind.count { it in current }
        return when {
            ofKind.isEmpty() || chosen == 0 -> ToggleableState.Off
            chosen == ofKind.size -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
    }

    fun selectedUnits(input: RestoreWizardInput, units: List<RestoreUnitInfo>): List<RestoreUnitInfo> {
        val ids = effectiveSelection(input, units)
        return units.filter { it.id in ids }
    }

    /** Problems of [input] for [units]; blocking ones prevent leaving the step they belong to. */
    fun validate(input: RestoreWizardInput, units: List<RestoreUnitInfo>): List<WizardProblem> {
        val selected = selectedUnits(input, units)
        if (selected.isEmpty()) return listOf(WizardProblem.NOTHING_SELECTED)
        val problems = ArrayList<WizardProblem>()
        val files = selected.filter { it.kind == RestoreUnitKind.FILE_TREE }
        if (files.isNotEmpty()) {
            when (input.fileTarget) {
                FileTargetKind.ORIGINAL -> {
                    if (input.twrpMode == TwrpRestoreMode.EXTRACT_VOLUMES && files.any { it.nandroid }) {
                        problems += WizardProblem.TWRP_EXTRACT_NEEDS_FOLDER
                    }
                }
                FileTargetKind.FOLDER -> folderProblems(input.folderPath).let(problems::addAll)
                FileTargetKind.SAF_TREE -> if (input.treeUri.isNullOrBlank()) problems += WizardProblem.TREE_REQUIRED
            }
        }
        if (selected.any { it.kind == RestoreUnitKind.APP }) {
            val apkPossible = input.restoreApks && input.versionPolicy != AppVersionPolicy.DATA_ONLY
            if (!apkPossible && !input.restoreAppData) problems += WizardProblem.APP_NOTHING_TO_RESTORE
            if (input.allowDowngrade && !apkPossible) problems += WizardProblem.DOWNGRADE_IGNORED
        }
        return problems
    }

    /** Checks an absolute restore folder; empty when it is acceptable. */
    fun folderProblems(rawPath: String): List<WizardProblem> {
        val path = rawPath.trim()
        if (path.isEmpty()) return listOf(WizardProblem.FOLDER_REQUIRED)
        if (!path.startsWith("/") || '\u0000' in path) return listOf(WizardProblem.FOLDER_NOT_ABSOLUTE)
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == ".." || it == "." }) return listOf(WizardProblem.FOLDER_TRAVERSAL)
        val normalized = "/" + segments.joinToString("/")
        if (normalized == "/" || PROTECTED_ROOTS.any { normalized == it || normalized.startsWith("$it/") }) {
            return listOf(WizardProblem.FOLDER_PROTECTED)
        }
        val inData = normalized == "/data" || normalized.startsWith("/data/")
        val shared = SHARED_DATA_ROOTS.any { normalized == it || normalized.startsWith("$it/") }
        return if (inData && !shared) listOf(WizardProblem.FOLDER_IN_APP_DATA) else emptyList()
    }

    fun blocking(problems: List<WizardProblem>): List<WizardProblem> = problems.filter { it.blocking }

    /** "/a//b/" -> "/a/b"; used for the request so the engine sees one canonical folder. */
    fun normalizeFolder(rawPath: String): String = "/" + rawPath.trim().split('/').filter { it.isNotEmpty() }.joinToString("/")

    fun fileSpec(input: RestoreWizardInput): FileRestoreSpec {
        val target = when (input.fileTarget) {
            FileTargetKind.ORIGINAL -> FileRestoreTarget.OriginalLocation
            FileTargetKind.FOLDER -> FileRestoreTarget.Folder(normalizeFolder(input.folderPath))
            FileTargetKind.SAF_TREE -> input.treeUri?.takeIf { it.isNotBlank() }?.let { FileRestoreTarget.SafTree(it) }
                ?: FileRestoreTarget.OriginalLocation
        }
        return FileRestoreSpec(target = target, twrpMode = input.twrpMode)
    }

    fun appOptions(input: RestoreWizardInput): AppRestoreOptions = AppRestoreOptions(
        targetUserId = input.targetUserId,
        versionPolicy = input.versionPolicy,
        allowDowngrade = input.allowDowngrade,
        restoreRuntimePermissions = input.restorePermissions,
        restoreExternalData = input.restoreExternalData,
        restoreObb = input.restoreObb,
        restoreCache = input.restoreCache,
        restoreInstallerAttribution = input.restoreInstaller,
    )

    /**
     * Engine options. Partition gates are always passed explicitly (never derived from the coarse
     * allowCrossDevice switch), so each override is exactly what the user confirmed.
     */
    fun options(input: RestoreWizardInput): RestoreOptions = RestoreOptions(
        conflictPolicy = input.conflictPolicy,
        restoreMetadata = input.restoreMetadata,
        ignoreInsufficientSpace = input.ignoreInsufficientSpace,
        allowCrossDevice = input.partition.allowOtherDevice,
        verifyBeforeRestore = input.verifyBeforeRestore,
        restoreApks = input.restoreApks,
        restoreAppData = input.restoreAppData,
        partition = input.partition.toOptions(),
        app = appOptions(input),
    )

    /** Risky options that are on but irrelevant to the current selection are not worth a warning. */
    fun activeRiskyOptions(input: RestoreWizardInput, units: List<RestoreUnitInfo>): List<RiskyOption> {
        val kinds = selectedUnits(input, units).map { it.kind }.toSet()
        return RiskyOption.entries.filter { option ->
            input.isOn(option) && when {
                option.partition -> RestoreUnitKind.PARTITION in kinds
                option == RiskyOption.ALLOW_DOWNGRADE -> RestoreUnitKind.APP in kinds
                else -> true
            }
        }
    }

    /** The step before [step] in the wizard (DETAILS has none). */
    fun previous(step: RestoreStep): RestoreStep = when (step) {
        RestoreStep.DETAILS, RestoreStep.SELECT -> RestoreStep.DETAILS
        RestoreStep.OPTIONS -> RestoreStep.SELECT
        RestoreStep.PREFLIGHT -> RestoreStep.OPTIONS
    }

    /** The step after [step] when [problems] allow it, else [step] itself. */
    fun next(step: RestoreStep, problems: List<WizardProblem>): RestoreStep = when (step) {
        RestoreStep.DETAILS -> RestoreStep.SELECT
        RestoreStep.SELECT -> if (WizardProblem.NOTHING_SELECTED in problems) step else RestoreStep.OPTIONS
        RestoreStep.OPTIONS -> if (blocking(problems).isEmpty()) RestoreStep.PREFLIGHT else step
        RestoreStep.PREFLIGHT -> step
    }
}

/**
 * Typed-confirmation gate for the most dangerous overrides. Whitespace is collapsed and case is
 * ignored so the check tests intent, not typing precision; an empty expected word never passes.
 */
object ConfirmationWord {
    private val SPACES = Regex("\\s+")

    fun normalize(text: String): String = text.trim().replace(SPACES, " ")

    fun accepted(typed: String, expected: String): Boolean {
        val want = normalize(expected)
        if (want.isEmpty()) return false
        return normalize(typed).equals(want, ignoreCase = true)
    }
}
