package ca.devdcs.backup.ui.settings

import androidx.annotation.StringRes
import ca.devdcs.backup.R
import ca.devdcs.backup.core.environment.DiagnosticReport
import ca.devdcs.backup.core.environment.Readiness
import ca.devdcs.backup.core.mode.OperatingMode

/**
 * One consequence of switching operating mode, spelled out in the confirm dialog. Changing mode
 * reroutes every privileged command immediately, so the user must see what stops working (and
 * what new requirement appears) before committing.
 */
enum class ModeChangeEffect(@StringRes val textRes: Int) {
    APPLIES_IMMEDIATELY(R.string.settings_mode_effect_immediate),
    LOSES_PARTITIONS(R.string.settings_mode_effect_loses_partitions),
    LOSES_APP_DATA(R.string.settings_mode_effect_loses_app_data),
    NEEDS_SHIZUKU_RUNNING(R.string.settings_mode_effect_needs_shizuku),
    ROOT_PROMPTS(R.string.settings_mode_effect_root_prompts),
    MODULE_NEEDED(R.string.settings_mode_effect_module_needed),
    MODULE_LEFT_INSTALLED(R.string.settings_mode_effect_module_left),
    DEGRADED(R.string.settings_mode_effect_degraded),
}

/** Whether the selected candidate mode can be committed right now, and if not, why. */
enum class ModeApplyVerdict {
    READY,
    SAME_MODE,
    /** Diagnostics for the candidate have not finished (or ran for a different mode). */
    NO_DIAGNOSTICS,
    /** A required check failed; the mode cannot work on this device as it is. */
    UNAVAILABLE,
    /** A backup or restore is running; switching tiers under it could break its commands. */
    JOBS_RUNNING,
}

/** Pure rules behind the mode screen's "Switch" button and its confirmation text. */
object ModeChangeRules {

    fun verdict(
        current: OperatingMode?,
        candidate: OperatingMode?,
        report: DiagnosticReport?,
        jobsActive: Boolean,
    ): ModeApplyVerdict = when {
        candidate == null -> ModeApplyVerdict.NO_DIAGNOSTICS
        candidate == current -> ModeApplyVerdict.SAME_MODE
        jobsActive -> ModeApplyVerdict.JOBS_RUNNING
        report == null || report.mode != candidate -> ModeApplyVerdict.NO_DIAGNOSTICS
        report.readiness == Readiness.UNAVAILABLE -> ModeApplyVerdict.UNAVAILABLE
        else -> ModeApplyVerdict.READY
    }

    /** What changes when going from [from] (null = never configured) to [to], in reading order. */
    fun effects(from: OperatingMode?, to: OperatingMode, readiness: Readiness?): List<ModeChangeEffect> = buildList {
        add(ModeChangeEffect.APPLIES_IMMEDIATELY)
        if (from != null && from.requiresRoot && !to.requiresRoot) add(ModeChangeEffect.LOSES_PARTITIONS)
        if (from != null && from != OperatingMode.STANDARD && to == OperatingMode.STANDARD) add(ModeChangeEffect.LOSES_APP_DATA)
        if (to == OperatingMode.SHIZUKU && from != OperatingMode.SHIZUKU) add(ModeChangeEffect.NEEDS_SHIZUKU_RUNNING)
        if (to.requiresRoot && (from == null || !from.requiresRoot)) add(ModeChangeEffect.ROOT_PROMPTS)
        if (to == OperatingMode.ROOT_MODULE && from != OperatingMode.ROOT_MODULE) add(ModeChangeEffect.MODULE_NEEDED)
        if (from == OperatingMode.ROOT_MODULE && to != OperatingMode.ROOT_MODULE) add(ModeChangeEffect.MODULE_LEFT_INSTALLED)
        if (readiness == Readiness.DEGRADED) add(ModeChangeEffect.DEGRADED)
    }
}
