package ca.devdcs.backup.ui.onboarding

import androidx.annotation.StringRes
import ca.devdcs.backup.core.environment.DiagnosticReport
import ca.devdcs.backup.core.environment.Readiness
import ca.devdcs.backup.core.locale.AppLanguage
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.DestinationType

/** The six wizard pages, in order. Declaration order is the navigation order. */
enum class OnboardingStep {
    LANGUAGE,
    OVERVIEW,
    DISCLAIMER,
    LICENSES,
    MODE,
    BASELINE;

    val isFirst: Boolean get() = ordinal == 0
    val isLast: Boolean get() = ordinal == entries.lastIndex
    val next: OnboardingStep get() = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
    val previous: OnboardingStep get() = entries[(ordinal - 1).coerceAtLeast(0)]

    companion object {
        val COUNT: Int = entries.size
    }
}

/** Lifecycle of the environment diagnostics for the currently selected operating mode. */
sealed interface DiagnosticsState {
    data object Idle : DiagnosticsState
    data class Running(val mode: OperatingMode) : DiagnosticsState
    data class Done(val report: DiagnosticReport) : DiagnosticsState
    data class Failed(val mode: OperatingMode, val detail: String?) : DiagnosticsState
}

/**
 * What the wizard knows about the destination it created, enough to display it and to clean it up
 * if the user changes their mind. [treeUri] is only set for SAF folders so the persistable
 * permission can be released when the row is deleted.
 */
data class DestinationSummary(
    val id: String,
    val name: String,
    val type: DestinationType,
    val treeUri: String?,
)

/**
 * The entire wizard in one immutable value, so a rotation or a live language switch re-renders
 * from the same source of truth and nothing is held in composable-local state that matters.
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.LANGUAGE,
    val language: AppLanguage = AppLanguage.DEFAULT,
    /** Supported language matching the device's system locale, or null when none does. */
    val suggestedLanguage: AppLanguage? = null,
    val disclaimerAccepted: Boolean = false,
    val selectedMode: OperatingMode? = null,
    val diagnostics: DiagnosticsState = DiagnosticsState.Idle,
    val archiveFormat: ArchiveFormat = ArchiveFormat.DEFAULT,
    val compressionLevel: Int = DEFAULT_COMPRESSION_LEVEL,
    val checksumAlgorithm: ChecksumAlgorithm = ChecksumAlgorithm.DEFAULT,
    val destination: DestinationSummary? = null,
    val destinationBusy: Boolean = false,
    val notificationsGranted: Boolean = false,
    val batteryExempt: Boolean = false,
    val finishing: Boolean = false,
) {
    /** The report for [selectedMode], once diagnostics finished for exactly that mode. */
    val currentReport: DiagnosticReport?
        get() = (diagnostics as? DiagnosticsState.Done)?.report?.takeIf { it.mode == selectedMode }

    /** The selected mode is usable: diagnostics ran and did not come back UNAVAILABLE. */
    val modeIsUsable: Boolean
        get() = currentReport?.let { it.readiness != Readiness.UNAVAILABLE } ?: false

    val canGoBack: Boolean get() = !step.isFirst && !finishing

    val canGoNext: Boolean
        get() = when (step) {
            OnboardingStep.LANGUAGE, OnboardingStep.OVERVIEW, OnboardingStep.LICENSES -> true
            OnboardingStep.DISCLAIMER -> disclaimerAccepted
            OnboardingStep.MODE -> modeIsUsable
            OnboardingStep.BASELINE -> !finishing && !destinationBusy
        }

    companion object {
        const val DEFAULT_COMPRESSION_LEVEL = 3
    }
}

/** One-shot effects the screen must act on exactly once (navigation, snackbars). */
sealed interface OnboardingEvent {
    data object Finished : OnboardingEvent
    data class ShowMessage(@StringRes val messageRes: Int) : OnboardingEvent
}
