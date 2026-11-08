package ca.devdcs.backup.core.exec

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import ca.devdcs.backup.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the one [CommandExecutor] that matches the device owner's persisted [OperatingMode] and
 * routes every command through it. The backup/restore engines (Phases 6–7) depend on this router
 * rather than on any concrete executor, so switching modes in Settings re-targets all command
 * execution with no code change anywhere else.
 *
 * Each tier's executor contributes itself into a Hilt multibinding set (see
 * [ca.devdcs.backup.di.ExecModule]); the router indexes the set by [ElevationTier] and always has
 * at least the [ElevationTier.NONE] executor, so resolution can never return null. When the mode
 * maps to a tier whose executor was not compiled in (e.g. a Root-only build that excluded the
 * Shizuku impl), the router falls back to NONE and the caller gets a clean refusal instead of a
 * crash.
 */
@Singleton
class CommandExecutorRouter @Inject constructor(
    executors: Set<@JvmSuppressWildcards CommandExecutor>,
    private val preferences: AppPreferencesRepository,
    @ApplicationScope scope: CoroutineScope,
) {

    /** One executor per tier. Duplicate tiers would be a DI mistake; the last one registered wins. */
    private val byTier: Map<ElevationTier, CommandExecutor> = executors.associateBy { it.tier }

    /** Every registered executor, strongest privilege first, for [bestAvailable]. */
    private val rankedByPrivilege: List<CommandExecutor> =
        executors.sortedWith(ExecutorAvailability.byPrivilegeDescending)

    /**
     * The tier the app is currently executing at, derived from the persisted operating mode and
     * kept hot for the whole process so the UI can observe it. Standard mode (or an unset mode)
     * maps to [ElevationTier.NONE].
     */
    val activeTier: StateFlow<ElevationTier> = preferences.preferences
        .map { ElevationTier.forMode(it.operatingMode ?: OperatingMode.STANDARD) }
        .stateIn(scope, SharingStarted.Eagerly, ElevationTier.NONE)

    /**
     * The executor for the current operating mode, read fresh from preferences so it reflects a
     * mode change the instant it is persisted. Falls back to the NONE executor if the mapped tier
     * has no registered implementation.
     */
    suspend fun current(): CommandExecutor {
        val mode = preferences.preferences.first().operatingMode ?: OperatingMode.STANDARD
        val tier = ElevationTier.forMode(mode)
        val executor = byTier[tier]
        if (executor != null) return executor
        DftbLog.w(TAG, "No executor registered for tier $tier (mode $mode); falling back to NONE")
        return fallback()
    }

    /** The executor registered for [tier], or null if no implementation contributes that tier. */
    suspend fun executorFor(tier: ElevationTier): CommandExecutor? = byTier[tier]

    /**
     * The executor for [tier], or [CommandException] if absent. Use when a feature is meaningless
     * without a specific tier (e.g. a Tier-4 companion-module operation that only root can run).
     */
    suspend fun require(tier: ElevationTier): CommandExecutor =
        byTier[tier] ?: throw CommandException("No CommandExecutor registered for tier $tier")

    /** Run [command] through the current mode's executor. Never throws (delegates to the executor). */
    suspend fun execute(command: Command): CommandResult = current().execute(command)

    /**
     * Stream [command] through the current mode's executor. The returned flow is cold: the current
     * executor is resolved when collection starts, so a mode change before collection is honoured.
     */
    fun stream(command: Command): Flow<CommandEvent> = flow {
        emitAll(current().stream(command))
    }

    /**
     * The highest-privilege executor that reports itself available right now (ROOT > SHIZUKU >
     * NONE), independent of the selected mode. Used by onboarding/diagnostics to detect what the
     * device could do; NONE is always available, so this never returns null.
     */
    suspend fun bestAvailable(): CommandExecutor {
        for (executor in rankedByPrivilege) {
            if (executor.isAvailable()) return executor
        }
        return fallback()
    }

    private fun fallback(): CommandExecutor =
        byTier[ElevationTier.NONE]
            ?: throw CommandException("No NONE-tier CommandExecutor registered; ExecModule is misconfigured")

    private companion object {
        const val TAG = "ExecRouter"
    }
}
