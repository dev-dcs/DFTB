package ca.devdcs.backup.ui.settings

import ca.devdcs.backup.core.environment.CheckStatus
import ca.devdcs.backup.core.environment.DiagnosticCheck
import ca.devdcs.backup.core.environment.DiagnosticReport
import ca.devdcs.backup.core.environment.Readiness
import ca.devdcs.backup.core.mode.OperatingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeChangeRulesTest {

    private fun report(mode: OperatingMode, readiness: Readiness) = DiagnosticReport(
        mode = mode,
        readiness = readiness,
        checks = listOf(
            DiagnosticCheck(
                id = "probe",
                titleRes = 0,
                status = if (readiness == Readiness.UNAVAILABLE) CheckStatus.FAIL else CheckStatus.PASS,
                detail = null,
                hintRes = null,
                required = true,
                action = null,
            ),
        ),
        durationMillis = 5,
    )

    @Test
    fun readyOnlyWithAUsableReportForTheCandidateAndNoJobs() {
        assertEquals(
            ModeApplyVerdict.READY,
            ModeChangeRules.verdict(OperatingMode.STANDARD, OperatingMode.ROOT, report(OperatingMode.ROOT, Readiness.READY), jobsActive = false),
        )
        assertEquals(
            ModeApplyVerdict.READY,
            ModeChangeRules.verdict(OperatingMode.STANDARD, OperatingMode.SHIZUKU, report(OperatingMode.SHIZUKU, Readiness.DEGRADED), jobsActive = false),
        )
        // First configuration (no mode stored yet) is allowed too.
        assertEquals(
            ModeApplyVerdict.READY,
            ModeChangeRules.verdict(null, OperatingMode.STANDARD, report(OperatingMode.STANDARD, Readiness.READY), jobsActive = false),
        )
    }

    @Test
    fun blockingReasonsAreReportedInPriorityOrder() {
        val rootReady = report(OperatingMode.ROOT, Readiness.READY)
        assertEquals(ModeApplyVerdict.SAME_MODE, ModeChangeRules.verdict(OperatingMode.ROOT, OperatingMode.ROOT, rootReady, jobsActive = true))
        assertEquals(ModeApplyVerdict.JOBS_RUNNING, ModeChangeRules.verdict(OperatingMode.STANDARD, OperatingMode.ROOT, rootReady, jobsActive = true))
        assertEquals(ModeApplyVerdict.NO_DIAGNOSTICS, ModeChangeRules.verdict(OperatingMode.STANDARD, OperatingMode.ROOT, null, jobsActive = false))
        assertEquals(ModeApplyVerdict.NO_DIAGNOSTICS, ModeChangeRules.verdict(OperatingMode.STANDARD, null, null, jobsActive = false))
        assertEquals(
            ModeApplyVerdict.UNAVAILABLE,
            ModeChangeRules.verdict(OperatingMode.STANDARD, OperatingMode.ROOT, report(OperatingMode.ROOT, Readiness.UNAVAILABLE), jobsActive = false),
        )
    }

    @Test
    fun aReportForAnotherModeNeverUnlocksTheSwitch() {
        assertEquals(
            ModeApplyVerdict.NO_DIAGNOSTICS,
            ModeChangeRules.verdict(OperatingMode.STANDARD, OperatingMode.ROOT_MODULE, report(OperatingMode.ROOT, Readiness.READY), jobsActive = false),
        )
    }

    @Test
    fun everyChangeStatesThatItAppliesImmediately() {
        OperatingMode.entries.forEach { from ->
            OperatingMode.entries.filter { it != from }.forEach { to ->
                assertEquals(ModeChangeEffect.APPLIES_IMMEDIATELY, ModeChangeRules.effects(from, to, Readiness.READY).first())
            }
        }
    }

    @Test
    fun downgradeFromRootModuleListsEverythingThatIsLost() {
        val effects = ModeChangeRules.effects(OperatingMode.ROOT_MODULE, OperatingMode.STANDARD, Readiness.READY)
        assertEquals(
            listOf(
                ModeChangeEffect.APPLIES_IMMEDIATELY,
                ModeChangeEffect.LOSES_PARTITIONS,
                ModeChangeEffect.LOSES_APP_DATA,
                ModeChangeEffect.MODULE_LEFT_INSTALLED,
            ),
            effects,
        )
    }

    @Test
    fun rootToShizukuLosesPartitionsButKeepsAppData() {
        val effects = ModeChangeRules.effects(OperatingMode.ROOT, OperatingMode.SHIZUKU, Readiness.READY)
        assertTrue(ModeChangeEffect.LOSES_PARTITIONS in effects)
        assertFalse(ModeChangeEffect.LOSES_APP_DATA in effects)
        assertTrue(ModeChangeEffect.NEEDS_SHIZUKU_RUNNING in effects)
        assertFalse(ModeChangeEffect.ROOT_PROMPTS in effects)
    }

    @Test
    fun upgradeToRootModuleWarnsAboutPromptsAndTheModule() {
        val effects = ModeChangeRules.effects(OperatingMode.STANDARD, OperatingMode.ROOT_MODULE, Readiness.DEGRADED)
        assertEquals(
            listOf(
                ModeChangeEffect.APPLIES_IMMEDIATELY,
                ModeChangeEffect.ROOT_PROMPTS,
                ModeChangeEffect.MODULE_NEEDED,
                ModeChangeEffect.DEGRADED,
            ),
            effects,
        )
    }

    @Test
    fun rootToRootModuleNeedsNoNewPrompt() {
        val effects = ModeChangeRules.effects(OperatingMode.ROOT, OperatingMode.ROOT_MODULE, Readiness.READY)
        assertFalse(ModeChangeEffect.ROOT_PROMPTS in effects)
        assertTrue(ModeChangeEffect.MODULE_NEEDED in effects)
        assertFalse(ModeChangeEffect.LOSES_PARTITIONS in effects)
    }

    @Test
    fun firstConfigurationLosesNothing() {
        val effects = ModeChangeRules.effects(null, OperatingMode.ROOT, Readiness.READY)
        assertEquals(listOf(ModeChangeEffect.APPLIES_IMMEDIATELY, ModeChangeEffect.ROOT_PROMPTS), effects)
    }
}
