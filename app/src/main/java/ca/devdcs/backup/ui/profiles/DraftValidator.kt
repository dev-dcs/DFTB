package ca.devdcs.backup.ui.profiles

import ca.devdcs.backup.core.discovery.files.validate
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.pipeline.engine.TransferPlanner
import ca.devdcs.backup.domain.model.AppComponent
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.ScheduleFrequency
import ca.devdcs.backup.domain.model.SchedulePolicy

/** Whether a problem stops the user from leaving a step / saving, or is only worth knowing. */
enum class ProblemSeverity { ERROR, WARNING }

/**
 * Everything the wizard can find wrong with a draft, tagged with the step that fixes it. Machine
 * readable on purpose: the UI maps each one to a localized message (ProfileLabels).
 */
enum class DraftProblem(val step: WizardStep, val severity: ProblemSeverity) {
    NAME_BLANK(WizardStep.NAME, ProblemSeverity.ERROR),
    NAME_TOO_LONG(WizardStep.NAME, ProblemSeverity.ERROR),
    NAME_DUPLICATE(WizardStep.NAME, ProblemSeverity.ERROR),
    DESCRIPTION_TOO_LONG(WizardStep.NAME, ProblemSeverity.ERROR),

    NO_TARGETS(WizardStep.TARGETS, ProblemSeverity.ERROR),
    APP_WITHOUT_COMPONENTS(WizardStep.TARGETS, ProblemSeverity.ERROR),
    PATH_INVALID(WizardStep.TARGETS, ProblemSeverity.ERROR),
    PATH_DUPLICATE(WizardStep.TARGETS, ProblemSeverity.ERROR),
    FILTER_INVALID(WizardStep.TARGETS, ProblemSeverity.ERROR),
    /** Some selected app components cannot be captured in the current mode (they will be skipped). */
    COMPONENTS_UNAVAILABLE(WizardStep.TARGETS, ProblemSeverity.WARNING),
    /** Partitions are only readable in Root mode. */
    PARTITIONS_NEED_ROOT(WizardStep.TARGETS, ProblemSeverity.WARNING),

    NO_DESTINATION(WizardStep.DESTINATION, ProblemSeverity.ERROR),
    DESTINATION_GONE(WizardStep.DESTINATION, ProblemSeverity.ERROR),

    ENCRYPTION_UNSUPPORTED(WizardStep.OPTIONS, ProblemSeverity.ERROR),
    TRANSFER_UNSUPPORTED(WizardStep.OPTIONS, ProblemSeverity.ERROR),
    /** The profile's vault key is gone; saving creates a new one (older backups stay unreadable). */
    VAULT_KEY_MISSING(WizardStep.OPTIONS, ProblemSeverity.WARNING),
    /** TWRP/OrangeFox only see device storage and USB. */
    TWRP_NOT_LOCAL(WizardStep.OPTIONS, ProblemSeverity.WARNING),
    /** TWRP compatibility changes only how partitions are stored. */
    TWRP_WITHOUT_PARTITIONS(WizardStep.OPTIONS, ProblemSeverity.WARNING),

    INTERVAL_INVALID(WizardStep.SCHEDULE, ProblemSeverity.ERROR),
    SCHEDULE_TIME_INVALID(WizardStep.SCHEDULE, ProblemSeverity.ERROR),
    SCHEDULE_NO_DAYS(WizardStep.SCHEDULE, ProblemSeverity.ERROR),
    SCHEDULE_INVALID_DAY(WizardStep.SCHEDULE, ProblemSeverity.ERROR),
    SCHEDULE_INTERVAL_TOO_SHORT(WizardStep.SCHEDULE, ProblemSeverity.ERROR),
    SCHEDULE_BATTERY_INVALID(WizardStep.SCHEDULE, ProblemSeverity.ERROR),
    KEEP_LAST_INVALID(WizardStep.SCHEDULE, ProblemSeverity.ERROR),
    MAX_AGE_INVALID(WizardStep.SCHEDULE, ProblemSeverity.ERROR),
    /** Passphrase profiles cannot run unattended: every scheduled run waits for the user. */
    PASSPHRASE_SCHEDULED(WizardStep.SCHEDULE, ProblemSeverity.WARNING),
    ;

    val isError: Boolean get() = severity == ProblemSeverity.ERROR
}

/**
 * Facts outside the draft that validation depends on. Null maps mean "not loaded yet": the rules
 * that need them are skipped rather than reporting a false problem.
 */
data class ValidationContext(
    /** Names of the other profiles, already passed through [DraftValidator.nameKey]. */
    val otherProfileNames: Set<String> = emptySet(),
    val destinationTypes: Map<String, DestinationType>? = null,
    val componentAvailable: Map<AppComponent, Boolean>? = null,
    val tier: ElevationTier? = null,
    /** True when the draft's existing vault key ref no longer exists in the vault. */
    val vaultKeyMissing: Boolean = false,
)

/**
 * Pure per-step validation of a [ProfileDraft]. Exists so "can the user go on / save?" is one
 * tested function: steps block on [ProblemSeverity.ERROR], warnings are shown but never block.
 */
object DraftValidator {

    const val MAX_NAME_LENGTH = 80
    const val MAX_DESCRIPTION_LENGTH = 500

    /** Case- and whitespace-insensitive identity of a profile name. */
    fun nameKey(name: String): String = name.trim().replace(WHITESPACE, " ").lowercase()

    fun validate(draft: ProfileDraft, context: ValidationContext): List<DraftProblem> = buildList {
        addAll(nameProblems(draft, context))
        addAll(targetProblems(draft, context))
        addAll(destinationProblems(draft, context))
        addAll(optionProblems(draft, context))
        addAll(scheduleProblems(draft))
    }

    fun forStep(problems: List<DraftProblem>, step: WizardStep): List<DraftProblem> = problems.filter { it.step == step }

    fun hasErrors(problems: List<DraftProblem>, step: WizardStep): Boolean = problems.any { it.step == step && it.isError }

    /** First step holding an error, or null when the draft can be saved. */
    fun firstInvalidStep(problems: List<DraftProblem>): WizardStep? =
        problems.filter { it.isError }.minByOrNull { it.step.ordinal }?.step

    /** Absolute filesystem path without `..` segments, or a SAF content URI. */
    fun isValidLocation(location: String): Boolean {
        val trimmed = location.trim()
        if (trimmed.startsWith("content://")) return trimmed.length > "content://".length
        if (!trimmed.startsWith("/")) return false
        return trimmed.split('/').none { it == ".." || it == "." }
    }

    private fun nameProblems(draft: ProfileDraft, context: ValidationContext): List<DraftProblem> = buildList {
        val name = draft.name.trim()
        when {
            name.isEmpty() -> add(DraftProblem.NAME_BLANK)
            name.length > MAX_NAME_LENGTH -> add(DraftProblem.NAME_TOO_LONG)
            nameKey(name) in context.otherProfileNames -> add(DraftProblem.NAME_DUPLICATE)
        }
        if (draft.description.trim().length > MAX_DESCRIPTION_LENGTH) add(DraftProblem.DESCRIPTION_TOO_LONG)
    }

    private fun targetProblems(draft: ProfileDraft, context: ValidationContext): List<DraftProblem> = buildList {
        val targets = draft.targets
        if (targets.isEmpty) add(DraftProblem.NO_TARGETS)
        if (targets.apps.any { it.components.isEmpty() }) add(DraftProblem.APP_WITHOUT_COMPONENTS)
        if (targets.paths.any { !isValidLocation(it.location) }) add(DraftProblem.PATH_INVALID)
        val locations = targets.paths.map { TargetEdits.normalizeLocation(it.location) }
        if (locations.size != locations.toSet().size) add(DraftProblem.PATH_DUPLICATE)
        if (targets.paths.any { path -> path.filter?.validate()?.isNotEmpty() == true }) add(DraftProblem.FILTER_INVALID)
        val availability = context.componentAvailable
        if (availability != null && targets.apps.any { app -> app.components.any { availability[it] == false } }) {
            add(DraftProblem.COMPONENTS_UNAVAILABLE)
        }
        val tier = context.tier
        if (tier != null && tier != ElevationTier.ROOT && targets.partitions.isNotEmpty()) add(DraftProblem.PARTITIONS_NEED_ROOT)
    }

    private fun destinationProblems(draft: ProfileDraft, context: ValidationContext): List<DraftProblem> = buildList {
        val id = draft.destinationId
        val known = context.destinationTypes
        when {
            id == null -> add(DraftProblem.NO_DESTINATION)
            known != null && id !in known -> add(DraftProblem.DESTINATION_GONE)
        }
    }

    private fun optionProblems(draft: ProfileDraft, context: ValidationContext): List<DraftProblem> = buildList {
        val pipeline = PipelineRules.normalize(draft.pipeline, draft.keyChoice)
        if (draft.unsupportedEncryption != null) add(DraftProblem.ENCRYPTION_UNSUPPORTED)
        val destinationType = draft.destinationId?.let { context.destinationTypes?.get(it) }
        if (PipelineRules.transferBlock(pipeline.transferMode, destinationType) != null) add(DraftProblem.TRANSFER_UNSUPPORTED)
        val choice = PipelineRules.effectiveKeyChoice(draft.keyChoice, pipeline)
        if (choice == KeyChoice.VAULT_KEY && draft.existingKeyRef != null && context.vaultKeyMissing) add(DraftProblem.VAULT_KEY_MISSING)
        if (pipeline.twrpCompatible) {
            if (destinationType != null && !TransferPlanner.isLocal(destinationType)) add(DraftProblem.TWRP_NOT_LOCAL)
            if (draft.targets.partitions.isEmpty()) add(DraftProblem.TWRP_WITHOUT_PARTITIONS)
        }
    }

    private fun scheduleProblems(draft: ProfileDraft): List<DraftProblem> = buildList {
        if (draft.keepLastEnabled && draft.keepLastValue == null) add(DraftProblem.KEEP_LAST_INVALID)
        if (draft.maxAgeEnabled && draft.maxAgeValue == null) add(DraftProblem.MAX_AGE_INVALID)
        val schedule = draft.effectiveSchedule() ?: return@buildList
        if (schedule.frequency == ScheduleFrequency.INTERVAL && draft.intervalMinutes == null) {
            add(DraftProblem.INTERVAL_INVALID)
        } else {
            when (schedule.problem) {
                SchedulePolicy.Problem.TIME_OUT_OF_RANGE -> add(DraftProblem.SCHEDULE_TIME_INVALID)
                SchedulePolicy.Problem.NO_DAYS_SELECTED -> add(DraftProblem.SCHEDULE_NO_DAYS)
                SchedulePolicy.Problem.INVALID_DAY -> add(DraftProblem.SCHEDULE_INVALID_DAY)
                SchedulePolicy.Problem.INTERVAL_TOO_SHORT -> add(DraftProblem.SCHEDULE_INTERVAL_TOO_SHORT)
                SchedulePolicy.Problem.BATTERY_OUT_OF_RANGE -> add(DraftProblem.SCHEDULE_BATTERY_INVALID)
                null -> Unit
            }
        }
        val choice = PipelineRules.effectiveKeyChoice(draft.keyChoice, draft.pipeline)
        if (choice == KeyChoice.PASSPHRASE) add(DraftProblem.PASSPHRASE_SCHEDULED)
    }

    private val WHITESPACE = Regex("\\s+")
}
