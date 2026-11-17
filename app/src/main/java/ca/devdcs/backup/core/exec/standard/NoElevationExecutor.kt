package ca.devdcs.backup.core.exec.standard

import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandEvent
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandResult
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [ElevationTier.NONE] executor used in Standard mode, where the app has no elevated shell at
 * all. It exists so [ca.devdcs.backup.core.exec.CommandExecutorRouter] always resolves to a
 * non-null [CommandExecutor]: Standard-mode callers that reach a command path by mistake get a
 * clear, non-crashing refusal instead of a NullPointerException or a swallowed failure.
 *
 * Standard mode performs all of its work through in-process, app-context file APIs
 * (SAF / MediaStore / app-private dirs), never shell commands, so every [execute]/[stream] here is
 * intentionally a refusal rather than an attempt to spawn a process.
 */
@Singleton
class NoElevationExecutor @Inject constructor() : CommandExecutor {

    override val tier: ElevationTier = ElevationTier.NONE

    /** Always true: "no shell" is itself an available, well-defined state the router can rely on. */
    override suspend fun isAvailable(): Boolean = true

    override suspend fun execute(command: Command): CommandResult {
        DftbLog.d(TAG, "Refusing command in Standard mode (no shell): ${command.displayString()}")
        return CommandResult.notLaunched(REFUSAL)
    }

    override fun stream(command: Command): Flow<CommandEvent> {
        DftbLog.d(TAG, "Refusing streamed command in Standard mode (no shell): ${command.displayString()}")
        return flowOf(CommandEvent.Exit(CommandResult.EXIT_NOT_LAUNCHED, durationMillis = 0))
    }

    private companion object {
        const val TAG = "NoElevationExec"
        const val REFUSAL = "No elevated shell in Standard mode; use in-process file APIs"
    }
}
