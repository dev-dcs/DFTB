package ca.devdcs.backup.core.exec

import kotlinx.coroutines.flow.Flow

/**
 * Runs shell commands at one [ElevationTier]. The backup/restore engines (Phases 6–7) depend only
 * on this interface, so they are identical whether the device owner chose Standard, Shizuku or Root;
 * the implementation is selected at runtime by [CommandExecutorRouter] from the persisted operating
 * mode.
 *
 * Implementations must be safe to call from any dispatcher (they move work to IO themselves) and must
 * never block the main thread. A [NONE][ElevationTier.NONE] executor rejects every command (there is
 * no shell without elevation) but still answers [isAvailable]; callers in Standard mode use the
 * in-process file APIs instead of commands.
 */
interface CommandExecutor {

    /** The privilege level this executor runs at. Used by the router and for diagnostics. */
    val tier: ElevationTier

    /**
     * Whether this executor can currently run commands: the su shell is granted, or the Shizuku
     * binder is alive and permission held. Cheap and non-throwing; a full environment report is
     * [ca.devdcs.backup.core.environment.EnvironmentProbe]'s job.
     */
    suspend fun isAvailable(): Boolean

    /** Run [command] to completion and buffer its output. Never throws; failures become a non-success [CommandResult]. */
    suspend fun execute(command: Command): CommandResult

    /**
     * Run [command], emitting each stdout/stderr line as it arrives and a final [CommandEvent.Exit].
     * The flow is cold; collecting it launches the process and cancelling it kills the process.
     * Used by the pipeline engines so a multi-hour `tar`/`dd` reports live progress.
     */
    fun stream(command: Command): Flow<CommandEvent>
}
